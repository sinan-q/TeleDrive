package com.sinxn.teledrive.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.sinxn.teledrive.data.local.dao.FileDao
import com.sinxn.teledrive.data.local.entity.FileEntity
import com.sinxn.teledrive.data.local.prefs.SecurePrefs
import com.sinxn.teledrive.data.crypto.FileCryptor
import com.sinxn.teledrive.data.telegram.TelegramClient
import com.sinxn.teledrive.data.telegram.mapper.toFileEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.sinxn.teledrive.data.model.SyncStatus
import org.drinkless.tdlib.TdApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default implementation of [FileRepository] backed by Room [FileDao].
 * In later sprints, this will also coordinate with TDLib for remote operations
 * (upload, download, sync).
 */
@Singleton
class FileRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileDao: FileDao,
    private val telegramClient: TelegramClient,
    private val securePrefs: SecurePrefs,
    private val fileCryptor: FileCryptor,
    private val metadataManager: MetadataManager
) : FileRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Track file IDs that we explicitly asked TDLib to download this session */
    private val pendingDownloads = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
    private val pendingDownloadEntityIds = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /** Maps a pending upload's encrypted filename to queued parent folder IDs. */
    private val pendingUploadParents =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<String>>()

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    override val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _downloadEvents = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 16)
    override val downloadEvents: SharedFlow<DownloadEvent> = _downloadEvents

    /**
     * Maps an encrypted TG filename (e.g. "report.pdf.enc") back to its original metadata
     * so we can display the clean name and mime type in Room.
     */
    data class UploadMeta(val originalName: String, val mimeType: String?)
    private val pendingUploadMeta =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<UploadMeta>>()

    private val metadataMutex = Mutex()

    /** Debounced channel for metadata save requests to avoid Telegram FLOOD_WAIT */
    private val metadataSaveRequest = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        scope.launch {
            telegramClient.updates.collect { update ->
                when (update) {
                    is TdApi.UpdateFile -> {
                        val file = update.file
                        
                        // Upload progress
                        if (file.remote.isUploadingActive && file.expectedSize > 0) {
                            val progress = file.remote.uploadedSize.toFloat() / file.expectedSize.toFloat()
                            fileDao.updateTransferProgress(file.id.toString(), progress)
                        } else if (file.remote.isUploadingCompleted) {
                            fileDao.updateTransferProgress(file.id.toString(), 1.0f)
                        }

                        // Download progress
                        if (pendingDownloads.contains(file.id)) {
                            val totalBytes = maxOf(file.expectedSize.toLong(), file.size.toLong())
                            val downloaded = file.local.downloadedPrefixSize.toLong()
                            val progress = when {
                                totalBytes > 0L -> (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                file.local.isDownloadingActive || downloaded > 0L -> 0.01f
                                else -> 0f
                            }
                            val phaseMessage = when {
                                file.local.isDownloadingCompleted -> "Preparing cached file..."
                                file.local.isDownloadingActive || downloaded > 0L -> "Downloading from Telegram..."
                                else -> "Preparing download..."
                            }
                            fileDao.updateTransferProgress(file.id.toString(), progress)
                            pendingDownloadEntityIds[file.id]?.let { entityId ->
                                _downloadEvents.tryEmit(DownloadEvent.Progress(entityId, progress, phaseMessage))
                            }
                        }

                        handleCompletedDownload(file)
                    }
                    is TdApi.UpdateNewMessage -> {
                        val caption = (update.message.content as? TdApi.MessageDocument)?.caption?.text
                        if (caption == MetadataManager.METADATA_CAPTION) {
                            return@collect
                        }

                        val activeUserId = securePrefs.getUserId()
                        if (activeUserId != null) {
                            val configuredChatId = securePrefs.getStorageChannelId()
                            val targetChatId = if (configuredChatId != null) {
                                configuredChatId
                            } else {
                                val chat = telegramClient.send(
                                    TdApi.CreatePrivateChat(activeUserId.toLong(), true)
                                ) as TdApi.Chat
                                chat.id
                            }
                            
                            if (update.message.chatId == targetChatId) {
                                val entity = update.message.toFileEntity(activeUserId)
                                if (entity != null) {
                                    // Strip .enc extension and restore original metadata if this was our upload
                                    val meta = dequeueUploadMeta(entity.localName())
                                    val cleanEntity = if (meta != null) {
                                        entity.copy(
                                            name = meta.originalName,
                                            mimeType = meta.mimeType
                                        )
                                    } else entity // MessageMapper already strips .enc suffix

                                    // Re-attach parent folder mapping using the ORIGINAL name
                                    val parentId = dequeuePendingParent(entity.localName())
                                    val entityToInsert = if (parentId != null) {
                                        cleanEntity.copy(parentId = parentId)
                                    } else cleanEntity

                                    fileDao.insertFile(entityToInsert)
                                }
                            }
                        }
                    }
                    is TdApi.UpdateMessageSendSucceeded -> {
                        val activeUserId = securePrefs.getUserId() ?: return@collect

                        // Check if the sent message is the vault metadata message
                        val caption = (update.message.content as? TdApi.MessageDocument)?.caption?.text
                        if (caption == MetadataManager.METADATA_CAPTION || update.oldMessageId == securePrefs.getMetadataMessageId()) {
                            securePrefs.setMetadataMessageId(update.message.id)
                            metadataManager.clearCache()
                            Log.d("FileRepositoryImpl", "Updated metadata message ID in securePrefs to permanent server ID: ${update.message.id}")
                            return@collect
                        }

                        val oldId = "msg_${update.oldMessageId}"
                        
                        // Fetch the temporary record to preserve its folder context (parentId)
                        val oldEntity = fileDao.getFileById(oldId)
                        val uploadKey = update.message.toFileEntity(activeUserId)?.localName()
                        val parentId = oldEntity?.parentId ?: uploadKey?.let(::dequeuePendingParent)
                        
                        // Create the final entity with the permanent ID
                        val newEntity = update.message.toFileEntity(activeUserId, parentId)
                        if (newEntity != null) {
                            // If it's an encrypted file, try matching original meta to get clean name
                            val meta = dequeueUploadMeta(newEntity.localName())
                            val cleanEntity = when {
                                meta != null -> {
                                    newEntity.copy(name = meta.originalName, mimeType = meta.mimeType)
                                }
                                oldEntity != null -> {
                                    newEntity.copy(
                                        name = oldEntity.name,
                                        mimeType = oldEntity.mimeType ?: newEntity.mimeType,
                                        originalFileName = oldEntity.originalFileName ?: newEntity.originalFileName,
                                        isMultipart = oldEntity.isMultipart,
                                        partIndex = oldEntity.partIndex ?: newEntity.partIndex,
                                        totalParts = oldEntity.totalParts ?: newEntity.totalParts
                                    )
                                }
                                else -> newEntity
                            }

                            // Delete the temporary record and insert the final one
                            fileDao.hardDeleteFile(oldId)
                            fileDao.insertFile(cleanEntity)
                            saveMetadataRemote(activeUserId)
                        }
                    }
                }
            }
        }
    }

    init {
        // Debounced metadata sync: coalesces rapid save requests and waits 2s
        // after the last change before uploading to Telegram.
        @OptIn(FlowPreview::class)
        scope.launch {
            metadataSaveRequest
                .debounce(2_000L)
                .collect { userId ->
                    performMetadataSave(userId)
                }
        }
    }

    override fun getFilesByParent(parentId: String?, userId: String): Flow<List<FileEntity>> =
        fileDao.getFilesByParent(parentId, userId).map { fileList ->
            // Aggregate multipart chunks (e.g. video.mp4.part1, video.mp4.part2)
            // Group by originalFileName (if multipart) OR id (if normal file/folder)
            val aggregatedFiles = fileList.groupBy { file ->
                if (file.isMultipart && file.originalFileName != null) file.originalFileName else file.id
            }.map { (key, group) ->
                if (group.size == 1) {
                    // Normal file or a multipart file with only 1 chunk found
                    group.first()
                } else {
                    // Aggregate multiple chunks into one conceptual file
                    val firstChunk = group.minByOrNull { it.partIndex ?: Int.MAX_VALUE } ?: group.first()
                    val totalSize = group.filter { it.size != null }.sumOf { it.size!! }
                    
                    // Return the base file but with the combined total size and clean name
                    firstChunk.copy(
                        name = firstChunk.originalFileName ?: firstChunk.name,
                        size = totalSize
                    )
                }
            }
            aggregatedFiles
        }

    override suspend fun getFileById(id: String): FileEntity? =
        fileDao.getFileById(id)

    override suspend fun getFileByMessageId(messageId: String): FileEntity? =
        fileDao.getFileByMessageId(messageId)

    override suspend fun saveFile(file: FileEntity) {
        fileDao.insertFile(file)
        saveMetadataRemote(file.userId)
    }

    override suspend fun saveFiles(files: List<FileEntity>) =
        fileDao.insertFiles(files)

    override suspend fun updateFile(file: FileEntity) =
        fileDao.updateFile(file)

    override suspend fun deleteFile(file: FileEntity) {
        // If file has a Telegram message, delete it from Saved Messages first
        val msgId = file.messageId?.toLongOrNull()
        if (msgId != null) {
            try {
                // Use the chatId stored with the file, OR fall back to current storage config
                val targetChatId = if (file.chatId != null && file.chatId != 0L) {
                    file.chatId
                } else {
                    val configuredChatId = securePrefs.getStorageChannelId()
                    if (configuredChatId != null) {
                        configuredChatId
                    } else {
                        val chat = telegramClient.send(
                            TdApi.CreatePrivateChat(file.userId.toLong(), true)
                        ) as TdApi.Chat
                        chat.id
                    }
                }
                
                telegramClient.send(
                    TdApi.DeleteMessages(targetChatId, longArrayOf(msgId), true)
                )
                Log.d("FileRepositoryImpl", "Deleted TG message ${file.messageId}")
            } catch (e: Exception) {
                Log.e("FileRepositoryImpl", "Failed to delete TG message, proceeding with local delete", e)
            }
        }
        // Hard-delete from Room regardless
        fileDao.hardDeleteFile(file.id)
        saveMetadataRemote(file.userId)
    }

    override fun searchFiles(query: String, userId: String): Flow<List<FileEntity>> =
        fileDao.searchFiles(query, userId)

    override suspend fun getStorageUsed(userId: String): Long =
        fileDao.getStorageUsed(userId)

    override fun observeFileFolderCounts(userId: String): Flow<FileFolderSummary> =
        fileDao.observeFileFolderCounts(userId)
            .filterNotNull()
            .map { counts ->
                FileFolderSummary(
                    fileCount = counts.fileCount,
                    folderCount = counts.folderCount
                )
            }

    override suspend fun uploadFile(uri: Uri, userId: String, parentId: String?): String {
        return withContext(Dispatchers.IO) {
            try {
                val pickedFile = com.sinxn.teledrive.utils.FileUtils.resolvePickedFileMetadata(context, uri)
                val originalName = pickedFile.displayName
                val targetChatId = resolveTargetChatId(userId)
                _downloadEvents.emit(DownloadEvent.Status(null, originalName, "Encrypting \"$originalName\"..."))

                context.contentResolver.openInputStream(uri)?.use { sourceStream ->
                    uploadEncryptedContainer(
                        sourceStream = sourceStream,
                        chatId = targetChatId,
                        encryptedFileName = "$originalName.enc",
                        storedDisplayName = originalName,
                        mimeType = pickedFile.mimeType,
                        parentId = parentId
                    )
                } ?: error("Could not open selected file")
                originalName
            } catch (e: Exception) {
                Log.e("FileRepositoryImpl", "Error initiating encrypted file upload", e)
                throw e
            }
        }
    }

    override suspend fun downloadFile(file: FileEntity) {
        if (file.isMultipart && !file.originalFileName.isNullOrBlank()) {
            downloadMultipartFile(file)
            return
        }

        withContext(Dispatchers.IO) {
            val resolvedFile = resolveLatestDownloadableFile(file) ?: file
            val fileIdStr = resolvedFile.fileId
            if (fileIdStr.isNullOrBlank()) {
                _downloadEvents.emit(DownloadEvent.Failure(file.id, file.name, "Missing Telegram file ID"))
                return@withContext
            }
            val fileId = fileIdStr.toIntOrNull()
            if (fileId == null) {
                _downloadEvents.emit(DownloadEvent.Failure(file.id, file.name, "Invalid Telegram file ID"))
                return@withContext
            }

            val chatId = resolvedFile.chatId
                ?: securePrefs.getStorageChannelId()
                ?: resolveTargetChatId(resolvedFile.userId)
            telegramClient.send(TdApi.OpenChat(chatId))
            resolvedFile.messageId?.toLongOrNull()?.let { messageId ->
                telegramClient.sendAsync(TdApi.OpenMessageContent(chatId, messageId))
            }

            pendingDownloads.add(fileId)
            pendingDownloadEntityIds[fileId] = resolvedFile.id
            fileDao.updateTransferProgressById(resolvedFile.id, 0.01f)
            _downloadEvents.emit(DownloadEvent.Status(resolvedFile.id, resolvedFile.name, "Checking cache..."))
            _downloadEvents.emit(DownloadEvent.Progress(resolvedFile.id, 0.01f, "Checking cache..."))

            telegramClient.sendAsync(TdApi.DownloadFile(fileId, 32, 0, 0, false)) { result ->
                if (result is TdApi.Error) {
                    pendingDownloads.remove(fileId)
                    pendingDownloadEntityIds.remove(fileId)
                    scope.launch {
                        fileDao.updateTransferProgressById(resolvedFile.id, 1f)
                    }
                    Log.e("FileRepositoryImpl", "Failed to start download: ${result.message}")
                    _downloadEvents.tryEmit(
                        DownloadEvent.Failure(resolvedFile.id, resolvedFile.name, result.message ?: "Failed to start download")
                    )
                } else if (result is TdApi.File && result.local.isDownloadingCompleted && result.local.path.isNotEmpty()) {
                    _downloadEvents.tryEmit(
                        DownloadEvent.Status(resolvedFile.id, resolvedFile.name, "Preparing cached file...")
                    )
                    handleCompletedDownload(result)
                } else {
                    Log.d("FileRepositoryImpl", "Download queued for ${resolvedFile.name}")
                    _downloadEvents.tryEmit(
                        DownloadEvent.Status(resolvedFile.id, resolvedFile.name, "Downloading from Telegram...")
                    )
                }
            }
        }
    }

    private fun handleCompletedDownload(file: TdApi.File) {
        if (!file.local.isDownloadingCompleted || file.local.path.isEmpty()) {
            return
        }
        if (!pendingDownloads.remove(file.id)) {
            return
        }
        val entityId = pendingDownloadEntityIds.remove(file.id)

        scope.launch {
            fileDao.updateTransferProgress(file.id.toString(), 1.0f)
            entityId?.let { _downloadEvents.emit(DownloadEvent.Progress(it, 1.0f, "Finalizing...")) }
            val fileEntity = fileDao.getFileByFileId(file.id.toString())
            if (fileEntity == null) {
                Log.e("FileRepositoryImpl", "Downloaded file ${file.id} is not present in Room")
                _downloadEvents.emit(
                    DownloadEvent.Failure(entityId, "Unknown file", "Downloaded file metadata was not found")
                )
                return@launch
            }

            try {
                val downloadedFile = java.io.File(file.local.path)
                val exportName = fileEntity.cleanExportName()
                val exportMimeType = fileEntity.mimeType ?: "application/octet-stream"

                val exported = if (downloadedFile.name.endsWith(".enc")) {
                    val decryptedFile = java.io.File(context.cacheDir, "dec_$exportName")
                    try {
                        _downloadEvents.emit(DownloadEvent.Status(fileEntity.id, exportName, "Decrypting..."))
                        fileCryptor.decryptFile(downloadedFile, decryptedFile)
                        _downloadEvents.emit(DownloadEvent.Status(fileEntity.id, exportName, "Saving to Downloads..."))
                        com.sinxn.teledrive.utils.FileUtils.exportToDownloads(
                            context, decryptedFile, exportName, exportMimeType
                        )
                    } finally {
                        decryptedFile.delete()
                    }
                } else {
                    _downloadEvents.emit(DownloadEvent.Status(fileEntity.id, exportName, "Saving to Downloads..."))
                    com.sinxn.teledrive.utils.FileUtils.exportToDownloads(
                        context, downloadedFile, exportName, exportMimeType
                    )
                }

                if (exported) {
                    _downloadEvents.emit(DownloadEvent.Success(fileEntity.id, exportName))
                } else {
                    _downloadEvents.emit(
                        DownloadEvent.Failure(fileEntity.id, exportName, "Could not write file to Downloads/TeleDrive")
                    )
                }
            } catch (e: Exception) {
                Log.e("FileRepositoryImpl", "Failed to export/decrypt downloaded file", e)
                _downloadEvents.emit(
                    DownloadEvent.Failure(fileEntity.id, fileEntity.name, e.message ?: "Failed to process downloaded file")
                )
            }
        }
    }

    private suspend fun downloadMultipartFile(file: FileEntity) {
        val originalName = file.originalFileName ?: file.name
        val chunks = fileDao.getMultipartFiles(file.userId, originalName)
        if (chunks.isEmpty()) {
            _downloadEvents.emit(DownloadEvent.Failure(file.id, originalName, "No file chunks found"))
            return
        }

        val assembledFile = java.io.File(context.cacheDir, "assembled_${java.util.UUID.randomUUID()}_$originalName")
        try {
            _downloadEvents.emit(DownloadEvent.Status(file.id, originalName, "Downloading from Telegram..."))
            java.io.FileOutputStream(assembledFile).use { assembledOutput ->
                for (chunk in chunks) {
                    val resolvedChunk = resolveLatestDownloadableFile(chunk) ?: chunk
                    val fileId = resolvedChunk.fileId?.toIntOrNull()
                    if (fileId == null) {
                        _downloadEvents.emit(DownloadEvent.Failure(file.id, originalName, "Missing Telegram file ID for a chunk"))
                        return
                    }

                    val result = telegramClient.send(TdApi.DownloadFile(fileId, 32, 0, 0, true))
                    val tdFile = result as? TdApi.File
                        ?: throw IllegalStateException("Chunk download failed for $originalName")

                    val downloadedFile = java.io.File(tdFile.local.path)
                    val decryptedChunk = java.io.File(context.cacheDir, "dec_${java.util.UUID.randomUUID()}_${resolvedChunk.name}")
                    try {
                        _downloadEvents.emit(DownloadEvent.Status(file.id, originalName, "Decrypting..."))
                        fileCryptor.decryptFile(downloadedFile, decryptedChunk)
                        decryptedChunk.inputStream().use { it.copyTo(assembledOutput) }
                    } finally {
                        decryptedChunk.delete()
                    }
                }
            }

            chunks.forEach { chunk ->
                chunk.fileId?.let { id ->
                    fileDao.updateTransferProgress(id, 1.0f)
                }
            }

            val exportMimeType = file.mimeType ?: "application/octet-stream"
            _downloadEvents.emit(DownloadEvent.Status(file.id, originalName, "Saving to Downloads..."))
            val exported = com.sinxn.teledrive.utils.FileUtils.exportToDownloads(
                context,
                assembledFile,
                originalName,
                exportMimeType
            )
            if (exported) {
                _downloadEvents.emit(DownloadEvent.Success(file.id, originalName))
            } else {
                _downloadEvents.emit(
                    DownloadEvent.Failure(file.id, originalName, "Could not write file to Downloads/TeleDrive")
                )
            }
        } catch (e: Exception) {
            Log.e("FileRepositoryImpl", "Failed to download multipart file", e)
            _downloadEvents.emit(
                DownloadEvent.Failure(file.id, originalName, e.message ?: "Failed to download multipart file")
            )
        } finally {
            assembledFile.delete()
        }
    }

    override suspend fun renameFile(id: String, newName: String) {
        fileDao.renameFile(id, newName)
        securePrefs.getUserId()?.let { saveMetadataRemote(it) }
    }

    override suspend fun moveFile(id: String, newParentId: String?) {
        fileDao.moveFile(id, newParentId)
        securePrefs.getUserId()?.let { saveMetadataRemote(it) }
    }

    override suspend fun getFolders(userId: String): List<FileEntity> =
        fileDao.getFoldersByUser(userId)

    override suspend fun initializeVault(userId: String) {
        metadataMutex.withLock {
            try {
                fileDao.deleteAllFiles()
                val chatId = resolveTargetChatId(userId)
                val newMsgId = metadataManager.initializeMetadataMessage(chatId)
                securePrefs.setMetadataMessageId(newMsgId)
                Log.d("FileRepositoryImpl", "Vault initialized with metadata message ID: $newMsgId")
            } catch (e: Exception) {
                Log.e("FileRepositoryImpl", "Failed to initialize vault metadata", e)
            }
        }
    }

    override suspend fun syncExistingFiles(userId: String) {
        try {
            var currentMetrics = com.sinxn.teledrive.data.model.SyncMetrics()
            _syncStatus.value = SyncStatus.Syncing(currentMetrics)
            
            val targetChatId = securePrefs.getStorageChannelId()
            if (targetChatId == null) {
                _syncStatus.value = SyncStatus.Error("No storage location selected. Please configure a storage location in setup.")
                return
            }

            Log.d("FileRepositoryImpl", "Starting metadata sync for chat $targetChatId")
            telegramClient.send(TdApi.OpenChat(targetChatId))

            val cachedJson = metadataManager.consumeCachedDecryptedJson()
            if (cachedJson != null) {
                Log.d("FileRepositoryImpl", "Using cached decrypted metadata payload. Loading database.")
                metadataManager.importMetadata(cachedJson)
            } else {
                Log.d("FileRepositoryImpl", "No cached metadata. Finding and decrypting remote metadata message...")
                val metadataMsg = metadataManager.findMetadataMessage(targetChatId)

                if (metadataMsg == null) {
                    _syncStatus.value = SyncStatus.Error("No existing TeleDrive vault metadata found in this chat.")
                    return
                }

                val content = metadataMsg.content
                if (content !is TdApi.MessageDocument || content.caption.text != MetadataManager.METADATA_CAPTION) {
                    _syncStatus.value = SyncStatus.Error("This chat is not a valid TeleDrive vault (Metadata missing or invalid).")
                    return
                }

                // Valid vault, attempt download & decryption
                Log.d("FileRepositoryImpl", "Found metadata message. Downloading and decrypting...")
                val fileId = content.document.document.id
                securePrefs.setMetadataMessageId(metadataMsg.id)
                
                // This blocks until downloaded and throws if password wrong (AEADBadTagException)
                val jsonPayload = metadataManager.downloadMetadataFileBlocking(fileId)
                
                Log.d("FileRepositoryImpl", "Decryption successful. Loading database.")
                metadataManager.importMetadata(jsonPayload)
            }

            // Calculate files & folders imported from metadata
            val allFiles = fileDao.getAllFiles()
            val fileCount = allFiles.count { it.type != "folder" }
            val folderCount = allFiles.count { it.type == "folder" }

            currentMetrics = currentMetrics.copy(
                filesImported = fileCount,
                foldersImported = folderCount
            )

            Log.d("FileRepositoryImpl", "Metadata import complete: $fileCount files, $folderCount folders.")
            _syncStatus.value = SyncStatus.MetadataImported(currentMetrics)

        } catch (e: Exception) {
            Log.e("FileRepositoryImpl", "Error syncing metadata", e)
            _syncStatus.value = SyncStatus.Error(e.message ?: "Unknown error during sync")
        }
    }

    override suspend fun crossVerifyVault(userId: String) {
        try {
            val targetChatId = securePrefs.getStorageChannelId()
            if (targetChatId == null) {
                _syncStatus.value = SyncStatus.Error("No storage location selected.")
                return
            }

            val currentStatus = _syncStatus.value
            val initialMetrics = when (currentStatus) {
                is SyncStatus.MetadataImported -> currentStatus.metrics
                is SyncStatus.Syncing -> currentStatus.metrics
                is SyncStatus.Completed -> currentStatus.metrics
                else -> com.sinxn.teledrive.data.model.SyncMetrics()
            }

            var currentMetrics = initialMetrics.copy(messagesScanned = 0)
            _syncStatus.value = SyncStatus.Reconciling(currentMetrics)

            var lastMsgId = 0L
            val additionalFiles = mutableListOf<String>()
            val metadataMessageId = securePrefs.getMetadataMessageId() ?: metadataManager.getCachedMetadataMessageId()

            val allFiles = fileDao.getAllFiles()
            val pendingDbMessageIds = allFiles.mapNotNull { it.messageId }.toMutableSet()

            while (true) {
                val searchResult = telegramClient.send(
                    TdApi.SearchChatMessages(targetChatId, null, "", null, lastMsgId, 0, 100, TdApi.SearchMessagesFilterDocument())
                )

                val messages = when (searchResult) {
                    is TdApi.Messages -> searchResult.messages
                    is TdApi.FoundChatMessages -> searchResult.messages
                    else -> break
                }

                if (messages.isEmpty()) break

                val scannedBatch = messages.size
                currentMetrics = currentMetrics.copy(messagesScanned = currentMetrics.messagesScanned + scannedBatch)
                _syncStatus.value = SyncStatus.Reconciling(currentMetrics)

                val docsInBatch = messages.filter { it.content is TdApi.MessageDocument }
                for (msg in docsInBatch) {
                    if (msg.id != metadataMessageId) {
                        val msgIdStr = msg.id.toString()
                        pendingDbMessageIds.remove(msgIdStr)
                        val exists = fileDao.getFileByMessageId(msgIdStr) != null
                        if (!exists) {
                            val fileName = (msg.content as TdApi.MessageDocument).document.fileName
                            additionalFiles.add(fileName)
                        }
                    }
                }

                lastMsgId = messages.last().id
                if (messages.size < 100) break
            }

            val deletedFiles = allFiles
                .filter { pendingDbMessageIds.contains(it.messageId) }
                .map { it.name }

            currentMetrics = currentMetrics.copy(
                additionalFiles = additionalFiles,
                deletedFiles = deletedFiles
            )

            Log.d("FileRepositoryImpl", "Cross verification completed. Additional orphaned files in chat: ${additionalFiles.size}, Deleted from TG: ${deletedFiles.size}")
            _syncStatus.value = SyncStatus.Completed(currentMetrics)

        } catch (e: Exception) {
            Log.e("FileRepositoryImpl", "Error during cross verification", e)
            _syncStatus.value = SyncStatus.Error(e.message ?: "Unknown error during cross verification")
        }
    }

    // ── Helper Extensions ──────────────────────────────────────────

    /**
     * Extracts the raw filename as it appears in Telegram from a FileEntity.
     * This is useful for looking up metadata in the pendingUploadMeta map.
     */
    private fun FileEntity.localName(): String {
        return if (this.name.endsWith(".enc")) this.name else "${this.name}.enc"
    }

    private fun FileEntity.cleanExportName(): String {
        val suffix = ".enc"
        if (!this.originalFileName.isNullOrBlank()) {
            return this.originalFileName
        }
        return if (this.name.length >= suffix.length && this.name.lowercase().endsWith(suffix)) {
            this.name.substring(0, this.name.length - suffix.length)
        } else {
            this.name
        }
    }

    private fun enqueuePendingParent(fileName: String, parentId: String) {
        pendingUploadParents
            .getOrPut(fileName) { java.util.concurrent.ConcurrentLinkedQueue() }
            .add(parentId)
    }

    private fun dequeuePendingParent(fileName: String): String? {
        val queue = pendingUploadParents[fileName] ?: return null
        val value = queue.poll()
        if (queue.isEmpty()) {
            pendingUploadParents.remove(fileName, queue)
        }
        return value
    }

    private fun enqueueUploadMeta(fileName: String, meta: UploadMeta) {
        pendingUploadMeta
            .getOrPut(fileName) { java.util.concurrent.ConcurrentLinkedQueue() }
            .add(meta)
    }

    private fun dequeueUploadMeta(fileName: String): UploadMeta? {
        val queue = pendingUploadMeta[fileName] ?: return null
        val value = queue.poll()
        if (queue.isEmpty()) {
            pendingUploadMeta.remove(fileName, queue)
        }
        return value
    }

    private suspend fun resolveTargetChatId(userId: String): Long {
        val configuredChatId = securePrefs.getStorageChannelId()
        return if (configuredChatId != null) {
            configuredChatId
        } else {
            val chat = telegramClient.send(
                TdApi.CreatePrivateChat(userId.toLong(), true)
            ) as TdApi.Chat
            chat.id
        }
    }

    private suspend fun resolveLatestDownloadableFile(file: FileEntity): FileEntity? {
        val messageId = file.messageId?.toLongOrNull() ?: return file
        val chatId = when {
            file.chatId != null && file.chatId != 0L -> file.chatId
            securePrefs.getStorageChannelId() != null -> securePrefs.getStorageChannelId()
            else -> {
                val chat = telegramClient.send(
                    TdApi.CreatePrivateChat(file.userId.toLong(), true)
                ) as TdApi.Chat
                chat.id
            }
        } ?: return file

        val message = runCatching {
            telegramClient.send(TdApi.GetMessage(chatId, messageId))
        }.getOrNull() as? TdApi.Message ?: return file

        val refreshed = message.toFileEntity(file.userId, file.parentId) ?: return file
        val merged = refreshed.copy(
            id = file.id,
            name = file.name,
            mimeType = file.mimeType ?: refreshed.mimeType,
            isMultipart = file.isMultipart || refreshed.isMultipart,
            partIndex = file.partIndex ?: refreshed.partIndex,
            totalParts = file.totalParts ?: refreshed.totalParts,
            originalFileName = file.originalFileName ?: refreshed.originalFileName,
            parentId = file.parentId ?: refreshed.parentId,
            createdAt = file.createdAt,
            updatedAt = System.currentTimeMillis(),
            uploadedAt = file.uploadedAt ?: refreshed.uploadedAt
        )

        fileDao.updateFile(merged)
        return merged
    }

    private suspend fun uploadEncryptedContainer(
        sourceStream: java.io.InputStream,
        chatId: Long,
        encryptedFileName: String,
        storedDisplayName: String,
        mimeType: String?,
        parentId: String?
    ): Long {
        val tempDir = java.io.File(context.cacheDir, "upload_${java.util.UUID.randomUUID()}").apply {
            mkdirs()
        }
        val encFile = java.io.File(tempDir, encryptedFileName)
        val bytesRead = fileCryptor.encryptStream(sourceStream, encFile)
        if (bytesRead <= 0L) {
            encFile.delete()
            tempDir.delete()
            return 0L
        }

        Log.d("FileRepositoryImpl", "Encrypted $storedDisplayName -> ${encFile.name} (${encFile.length()} bytes)")

        if (parentId != null) {
            enqueuePendingParent(encFile.name, parentId)
        }
        enqueueUploadMeta(encFile.name, UploadMeta(storedDisplayName, mimeType))

        val inputFile = TdApi.InputFileLocal(encFile.absolutePath)
        val inputMessageContent = TdApi.InputMessageDocument(
            inputFile,
            null,
            true,
            null
        )

        val sendMessage = TdApi.SendMessage(
            chatId,
            null,
            null,
            null,
            null,
            inputMessageContent
        )

        telegramClient.sendAsync(sendMessage) { result ->
            if (result is TdApi.Error) {
                dequeuePendingParent(encFile.name)
                dequeueUploadMeta(encFile.name)
                encFile.delete()
                tempDir.delete()
                Log.e("FileRepositoryImpl", "Failed to queue upload part: ${result.message}")
            } else {
                encFile.deleteOnExit()
                tempDir.deleteOnExit()
            }
        }
        return bytesRead
    }

    /**
     * Performs the actual metadata save to Telegram.
     * Called by the debounced collector, NOT directly by callers.
     */
    private suspend fun performMetadataSave(userId: String) {
        metadataMutex.withLock {
            try {
                val chatId = resolveTargetChatId(userId)
                var messageId = securePrefs.getMetadataMessageId()
                
                if (messageId == null || messageId == 0L) {
                    val metadataMsg = metadataManager.findMetadataMessage(chatId)
                    if (metadataMsg != null && metadataMsg.content is TdApi.MessageDocument && 
                        (metadataMsg.content as TdApi.MessageDocument).caption.text == MetadataManager.METADATA_CAPTION) {
                        messageId = metadataMsg.id
                        securePrefs.setMetadataMessageId(messageId)
                    }
                }
                
                if (messageId != null && messageId != 0L) {
                    metadataManager.updateMetadataMessage(chatId, messageId)
                } else {
                    val newMsgId = metadataManager.initializeMetadataMessage(chatId)
                    securePrefs.setMetadataMessageId(newMsgId)
                }
            } catch (e: Exception) {
                Log.e("FileRepositoryImpl", "Failed to update remote metadata", e)
            }
        }
    }

    /**
     * Requests a debounced metadata save. Multiple rapid calls are coalesced
     * into a single Telegram API call 2 seconds after the last request.
     */
    private fun saveMetadataRemote(userId: String) {
        metadataSaveRequest.tryEmit(userId)
    }
}
