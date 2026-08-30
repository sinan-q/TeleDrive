package com.sinxn.teledrive.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.sinxn.teledrive.data.local.dao.FileDao
import com.sinxn.teledrive.data.local.entity.FileEntity
import com.sinxn.teledrive.data.local.prefs.SecurePrefs
import com.sinxn.teledrive.data.crypto.EncryptionManager
import com.sinxn.teledrive.data.crypto.FileCryptor
import com.sinxn.teledrive.data.telegram.AuthManager
import com.sinxn.teledrive.data.telegram.AuthState
import com.sinxn.teledrive.data.telegram.TelegramClient
import com.sinxn.teledrive.data.telegram.TdLibException
import com.sinxn.teledrive.data.telegram.mapper.toFileEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
    private val authManager: AuthManager,
    private val securePrefs: SecurePrefs,
    private val encryptionManager: EncryptionManager,
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

    /** Maps a pending SAF upload's encrypted filename to the initial placeholder documentId. */
    private val pendingUploadSafIds =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<String>>()

    /** Dynamic alias map resolving temporary SAF documentIds to permanent FileEntity IDs */
    private val safIdAliases = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun resolveSafAlias(safId: String): String? = safIdAliases[safId]

    private fun notifySafChange(safId: String?, permanentId: String?, parentId: String?) {
        try {
            val authority = "${context.packageName}.documents"
            val cr = context.contentResolver
            if (safId != null) {
                cr.notifyChange(DocumentsContract.buildDocumentUri(authority, safId), null)
            }
            if (permanentId != null && permanentId != safId) {
                cr.notifyChange(DocumentsContract.buildDocumentUri(authority, permanentId), null)
            }
            val targetParent = if (parentId.isNullOrEmpty()) "teledrive_root" else parentId
            cr.notifyChange(DocumentsContract.buildChildDocumentsUri(authority, targetParent), null)
        } catch (e: Exception) {
            Log.w("FileRepositoryImpl", "Failed to dispatch SAF notification", e)
        }
    }

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

    /** Maps a pending upload's encrypted filename to temporary directory for deterministic cleanup */
    private val pendingUploadTempDirs =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<java.io.File>>()

    init {
        scope.launch {
            // Clean up any stale saf_enc_ artifact files and leftover upload directories from prior sessions
            try {
                fileDao.cleanupStaleSafArtifacts()
                context.cacheDir.listFiles()?.forEach { file ->
                    if (file.isDirectory && (file.name.startsWith("upload_") || file.name.startsWith("saf_upload_") || file.name.startsWith("replace_"))) {
                        file.deleteRecursively()
                    }
                }
            } catch (e: Exception) {
                Log.w("FileRepositoryImpl", "Could not clean stale artifacts or upload temp directories", e)
            }
        }

        scope.launch {
            telegramClient.updates.collect { update ->
                when (update) {
                    is TdApi.UpdateFile -> {
                        val file = update.file
                        
                        // Upload progress
                        if (file.remote.isUploadingActive && file.expectedSize > 0) {
                            val progress = (file.remote.uploadedSize.toFloat() / file.expectedSize.toFloat()).coerceIn(0f, 1f)
                            var entityId = pendingDownloadEntityIds[file.id]
                            if (entityId == null) {
                                entityId = fileDao.getFileByFileId(file.id.toString())?.id
                                if (entityId != null) {
                                    pendingDownloadEntityIds[file.id] = entityId
                                }
                            }
                            if (entityId != null) {
                                _downloadEvents.tryEmit(DownloadEvent.Progress(entityId, progress, "Uploading to Telegram..."))
                            }
                        } else if (file.remote.isUploadingCompleted) {
                            val entityId = pendingDownloadEntityIds.remove(file.id)
                            if (entityId != null) {
                                _downloadEvents.tryEmit(DownloadEvent.Progress(entityId, 1.0f, "Finalizing..."))
                            }
                        }

                        // Download progress
                        if (pendingDownloads.contains(file.id)) {
                            val totalBytes = maxOf(file.expectedSize, file.size)
                            val downloaded = file.local.downloadedPrefixSize
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
                                    val safId = dequeuePendingSafId(entity.localName())

                                    val entityToInsert = if (parentId != null) {
                                        cleanEntity.copy(parentId = parentId)
                                    } else cleanEntity

                                    // If this upload originated from a SAF createDocument placeholder, delete the placeholder and alias it
                                    if (safId != null) {
                                        fileDao.hardDeleteFile(safId)
                                        safIdAliases[safId] = entityToInsert.id
                                    }

                                    entityToInsert.fileId?.toIntOrNull()?.let { fileIdInt ->
                                        pendingDownloadEntityIds[fileIdInt] = entityToInsert.id
                                        _downloadEvents.tryEmit(DownloadEvent.Progress(entityToInsert.id, 0.01f, "Uploading to Telegram..."))
                                    }

                                    fileDao.insertFile(entityToInsert)
                                    if (safId != null) {
                                        notifySafChange(safId, entityToInsert.id, entityToInsert.parentId)
                                    }
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
                        _downloadEvents.tryEmit(DownloadEvent.Progress(oldId, 1.0f, "Finalizing..."))
                        
                        // Fetch the temporary record to preserve its folder context (parentId)
                        val oldEntity = fileDao.getFileById(oldId)
                        val uploadKey = update.message.toFileEntity(activeUserId)?.localName()
                        val parentId = oldEntity?.parentId ?: uploadKey?.let(::dequeuePendingParent)
                        val safId = uploadKey?.let(::dequeuePendingSafId)
                        if (uploadKey != null) {
                            cleanupPendingTempDir(uploadKey)
                        }
                        
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

                            val notifiedSafIds = mutableListOf<String>()
                            if (safId != null) {
                                fileDao.hardDeleteFile(safId)
                                safIdAliases[safId] = cleanEntity.id
                                notifiedSafIds.add(safId)
                            }
                            // Update existing aliases targeting old temporary message ID
                            safIdAliases.entries.forEach { entry ->
                                if (entry.value == oldId) {
                                    safIdAliases[entry.key] = cleanEntity.id
                                    notifiedSafIds.add(entry.key)
                                }
                            }

                            // Delete the temporary record and insert the final one
                            fileDao.hardDeleteFile(oldId)
                            fileDao.insertFile(cleanEntity)
                            saveMetadataRemote(activeUserId)

                            if (notifiedSafIds.isNotEmpty()) {
                                notifiedSafIds.forEach { aliasedSafId ->
                                    notifySafChange(aliasedSafId, cleanEntity.id, cleanEntity.parentId)
                                }
                            }
                        }
                    }
                    is TdApi.UpdateMessageSendFailed -> {
                        val activeUserId = securePrefs.getUserId() ?: return@collect
                        val oldId = "msg_${update.oldMessageId}"
                        val oldEntity = fileDao.getFileById(oldId)

                        val uploadKey = update.message?.toFileEntity(activeUserId)?.localName()
                        if (uploadKey != null) {
                            dequeuePendingParent(uploadKey)
                            dequeueUploadMeta(uploadKey)
                            dequeuePendingSafId(uploadKey)
                            cleanupPendingTempDir(uploadKey)
                        }

                        // Delete the temporary record from Room
                        fileDao.hardDeleteFile(oldId)

                        val displayName = oldEntity?.name
                            ?: update.message?.toFileEntity(activeUserId)?.name
                            ?: "File"
                        val errorMsg = update.error?.message?.ifBlank { null }
                            ?: "Upload failed (code: ${update.error?.code})"

                        Log.e("FileRepositoryImpl", "Upload failed for '$displayName' (oldMsgId=${update.oldMessageId}): $errorMsg")
                        _downloadEvents.tryEmit(DownloadEvent.Failure(oldId, displayName, errorMsg))
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
            // Group by user-facing name (if multipart) OR id (if normal file/folder)
            val aggregatedFiles = fileList.groupBy { file ->
                if (file.isMultipart) file.name else file.id
            }.map { (key, group) ->
                if (group.size == 1) {
                    group.first()
                } else {
                    // Aggregate multiple chunks into one conceptual file
                    val firstChunk = group.minByOrNull { it.partIndex ?: Int.MAX_VALUE } ?: group.first()
                    val totalSize = group.filter { it.size != null }.sumOf { it.size!! }
                    firstChunk.copy(size = totalSize)
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
                        val chat = telegramClient.sendWithTimeout(
                            TdApi.CreatePrivateChat(file.userId.toLong(), true)
                        ) as TdApi.Chat
                        chat.id
                    }
                }
                
                telegramClient.sendWithTimeout(
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
                ensureReadyForTransfer()
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
            ensureReadyForTransfer()
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
            telegramClient.sendWithTimeout(TdApi.OpenChat(chatId))
            resolvedFile.messageId?.toLongOrNull()?.let { messageId ->
                telegramClient.sendAsync(TdApi.OpenMessageContent(chatId, messageId))
            }

            pendingDownloads.add(fileId)
            pendingDownloadEntityIds[fileId] = resolvedFile.id
            _downloadEvents.emit(DownloadEvent.Status(resolvedFile.id, resolvedFile.name, "Checking cache..."))
            _downloadEvents.emit(DownloadEvent.Progress(resolvedFile.id, 0.01f, "Checking cache..."))

            val downloadResult = downloadTelegramFileWithPolling(fileId)
            val tdFile = downloadResult.getOrElse { error ->
                pendingDownloads.remove(fileId)
                pendingDownloadEntityIds.remove(fileId)
                val errorMsg = error.message?.takeIf { it.isNotBlank() } ?: "Download timed out or failed to start"
                Log.e("FileRepositoryImpl", "Download failed for ${resolvedFile.name}: $errorMsg", error)
                _downloadEvents.emit(
                    DownloadEvent.Failure(resolvedFile.id, resolvedFile.name, errorMsg)
                )
                return@withContext
            }

            _downloadEvents.emit(
                DownloadEvent.Status(resolvedFile.id, resolvedFile.name, "Preparing cached file...")
            )
            handleCompletedDownload(tdFile)
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
        ensureReadyForTransfer()
        val displayName = file.name
        val chunks = fileDao.getMultipartFiles(file.userId, displayName)
        if (chunks.isEmpty()) {
            _downloadEvents.emit(DownloadEvent.Failure(file.id, displayName, "No file chunks found"))
            return
        }

        val assembledFile = java.io.File(context.cacheDir, "assembled_${java.util.UUID.randomUUID()}_$displayName")
        try {
            _downloadEvents.emit(DownloadEvent.Status(file.id, displayName, "Downloading from Telegram..."))
            java.io.FileOutputStream(assembledFile).use { assembledOutput ->
                for (chunk in chunks) {
                    val resolvedChunk = resolveLatestDownloadableFile(chunk) ?: chunk
                    val fileId = resolvedChunk.fileId?.toIntOrNull()
                    if (fileId == null) {
                        _downloadEvents.emit(DownloadEvent.Failure(file.id, displayName, "Missing Telegram file ID for a chunk"))
                        return
                    }

                    val downloadResult = downloadTelegramFileWithPolling(fileId)
                    val tdFile = downloadResult.getOrElse { error ->
                        val errorMsg = error.message?.takeIf { it.isNotBlank() } ?: "Chunk download failed"
                        Log.e("FileRepositoryImpl", "Chunk download failed for $displayName (part ${resolvedChunk.partIndex}): $errorMsg", error)
                        _downloadEvents.emit(DownloadEvent.Failure(file.id, displayName, "Chunk download failed: $errorMsg"))
                        throw IllegalStateException("Chunk download failed for $displayName: $errorMsg", error)
                    }

                    val downloadedFile = java.io.File(tdFile.local.path)
                    val decryptedChunk = java.io.File(context.cacheDir, "dec_${java.util.UUID.randomUUID()}_${resolvedChunk.id}")
                    try {
                        _downloadEvents.emit(DownloadEvent.Status(file.id, displayName, "Decrypting..."))
                        fileCryptor.decryptFile(downloadedFile, decryptedChunk)
                        decryptedChunk.inputStream().use { it.copyTo(assembledOutput) }
                    } finally {
                        decryptedChunk.delete()
                    }
                }
            }

            val exportMimeType = file.mimeType ?: "application/octet-stream"
            _downloadEvents.emit(DownloadEvent.Status(file.id, displayName, "Saving to Downloads..."))
            val exported = com.sinxn.teledrive.utils.FileUtils.exportToDownloads(
                context,
                assembledFile,
                displayName,
                exportMimeType
            )
            if (exported) {
                _downloadEvents.emit(DownloadEvent.Success(file.id, displayName))
            } else {
                _downloadEvents.emit(
                    DownloadEvent.Failure(file.id, displayName, "Could not write file to Downloads/TeleDrive")
                )
            }
        } catch (e: Exception) {
            Log.e("FileRepositoryImpl", "Failed to download multipart file", e)
            _downloadEvents.emit(
                DownloadEvent.Failure(file.id, displayName, e.message ?: "Failed to download multipart file")
            )
        } finally {
            assembledFile.delete()
        }
    }

    override suspend fun renameFile(id: String, newName: String) {
        val target = fileDao.getFileById(id) ?: return
        if (target.isMultipart) {
            fileDao.renameMultipartFiles(target.userId, target.name, newName)
        } else {
            fileDao.renameFile(id, newName)
        }
        securePrefs.getUserId()?.let { saveMetadataRemote(it) }
    }

    override suspend fun moveFile(id: String, newParentId: String?) {
        fileDao.moveFile(id, newParentId)
        securePrefs.getUserId()?.let { saveMetadataRemote(it) }
    }

    override suspend fun getFolders(userId: String): List<FileEntity> =
        fileDao.getFoldersByUser(userId)

    override suspend fun replaceFile(existingFile: FileEntity, newContentUri: Uri) {
        withContext(Dispatchers.IO) {
            ensureReadyForTransfer()
            val targetChatId = resolveTargetChatId(existingFile.userId)

            _downloadEvents.emit(DownloadEvent.Status(existingFile.id, existingFile.name, "Encrypting replacement..."))

            // Multipart files: delete all old chunks then re-upload
            if (existingFile.isMultipart) {
                replaceMultipartFile(
                    existingFile = existingFile,
                    sourceStreamProvider = { context.contentResolver.openInputStream(newContentUri) },
                    chatId = targetChatId
                )
                return@withContext
            }

            val messageId = existingFile.messageId?.toLongOrNull()
                ?: error("Cannot replace file without a Telegram message ID")

            // 1. Encrypt the new content
            val tempDir = java.io.File(context.cacheDir, "replace_${java.util.UUID.randomUUID()}").apply { mkdirs() }
            val encFileName = "${existingFile.name}.enc"
            val encFile = java.io.File(tempDir, encFileName)

            try {
                context.contentResolver.openInputStream(newContentUri)?.use { stream ->
                    fileCryptor.encryptStream(stream, encFile)
                } ?: error("Could not open replacement file")

                // 2. EditMessageMedia — swap the document in-place
                val inputFile = TdApi.InputFileLocal(encFile.absolutePath)
                val inputContent = TdApi.InputMessageDocument(inputFile, null, true, null)

                _downloadEvents.emit(DownloadEvent.Status(existingFile.id, existingFile.name, "Uploading replacement..."))

                val result = try {
                    telegramClient.sendWithTimeout(
                        TdApi.EditMessageMedia(targetChatId, messageId, null, inputContent),
                        300_000L
                    )
                } catch (e: Exception) {
                    Log.e("FileRepositoryImpl", "EditMessageMedia failed: ${e.message}", e)
                    _downloadEvents.emit(
                        DownloadEvent.Failure(existingFile.id, existingFile.name, "Replace failed: ${e.message}")
                    )
                    throw e
                }

                // 3. Handle result
                if (result is TdApi.Message) {
                    val doc = (result.content as? TdApi.MessageDocument)?.document
                    if (doc != null) {
                        fileDao.updateFileContent(
                            id = existingFile.id,
                            messageId = result.id.toString(),
                            fileId = doc.document.id.toString(),
                            size = doc.document.expectedSize,
                            mimeType = existingFile.mimeType,  // preserve original mime type
                            chatId = result.chatId,
                            updatedAt = System.currentTimeMillis(),
                            isMultipart = false,
                            partIndex = null,
                            totalParts = null,
                            originalFileName = null
                        )
                    }
                    saveMetadataRemote(existingFile.userId)
                    _downloadEvents.emit(DownloadEvent.Success(existingFile.id, existingFile.name))
                    Log.d("FileRepositoryImpl", "Replaced file '${existingFile.name}' via EditMessageMedia (msg $messageId)")
                } else {
                    val err = "Unexpected response from TDLib when replacing file: $result"
                    Log.e("FileRepositoryImpl", err)
                    _downloadEvents.emit(
                        DownloadEvent.Failure(existingFile.id, existingFile.name, err)
                    )
                    error(err)
                }
            } finally {
                encFile.delete()
                tempDir.delete()
            }
        }
    }

    /**
     * Handles replacement for multipart (chunked) files.
     * Uploads the new content first, confirms Telegram upload success,
     * and only then deletes old chunk messages and database records.
     */
    private suspend fun replaceMultipartFile(
        existingFile: FileEntity,
        sourceStreamProvider: () -> java.io.InputStream?,
        chatId: Long
    ) {
        val displayName = existingFile.name
        val chunks = fileDao.getMultipartFiles(existingFile.userId, displayName)
        val allOldChunks = if (chunks.isNotEmpty()) chunks else listOf(existingFile)

        val tempDir = java.io.File(context.cacheDir, "replace_mp_${java.util.UUID.randomUUID()}").apply {
            mkdirs()
        }
        val encFileName = "${existingFile.name}.enc"
        val encFile = java.io.File(tempDir, encFileName)

        try {
            _downloadEvents.emit(DownloadEvent.Status(existingFile.id, existingFile.name, "Encrypting replacement..."))

            val bytesRead = sourceStreamProvider()?.use { sourceStream ->
                fileCryptor.encryptStream(sourceStream, encFile)
            } ?: error("Could not open replacement file")

            if (bytesRead <= 0L) {
                error("Replacement file is empty or could not be read")
            }

            if (existingFile.parentId != null) {
                enqueuePendingParent(encFile.name, existingFile.parentId)
            }
            enqueueUploadMeta(encFile.name, UploadMeta(existingFile.name, existingFile.mimeType))

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

            _downloadEvents.emit(DownloadEvent.Status(existingFile.id, existingFile.name, "Uploading replacement..."))

            val sendResultObj = telegramClient.sendWithTimeout(sendMessage, 300_000L)
            val tempMessage = sendResultObj as? TdApi.Message
                ?: error("Failed to initiate replacement upload: unexpected response $sendResultObj")

            val tempMsgId = tempMessage.id
            val updateResult = telegramClient.updates.filter { update ->
                (update is TdApi.UpdateMessageSendSucceeded && update.oldMessageId == tempMsgId) ||
                (update is TdApi.UpdateMessageSendFailed && update.oldMessageId == tempMsgId)
            }.first()

            when (updateResult) {
                is TdApi.UpdateMessageSendSucceeded -> {
                    val newEntityId = "msg_${updateResult.message.id}"
                    allOldChunks.forEach { chunk ->
                        safIdAliases[chunk.id] = newEntityId
                    }
                    safIdAliases[existingFile.id] = newEntityId

                    // 1. Delete all old chunk messages from Telegram now that replacement is confirmed
                    val messageIds = allOldChunks.mapNotNull { it.messageId?.toLongOrNull() }.toLongArray()
                    if (messageIds.isNotEmpty()) {
                        try {
                            telegramClient.sendWithTimeout(TdApi.DeleteMessages(chatId, messageIds, true))
                            Log.d("FileRepositoryImpl", "Deleted ${messageIds.size} old chunk messages for '$displayName'")
                        } catch (e: Exception) {
                            Log.e("FileRepositoryImpl", "Failed to delete old chunk messages after replacement", e)
                        }
                    }

                    // 2. Hard-delete old chunk records from Room
                    for (chunk in allOldChunks) {
                        fileDao.hardDeleteFile(chunk.id)
                    }

                    saveMetadataRemote(existingFile.userId)
                    _downloadEvents.emit(DownloadEvent.Success(existingFile.id, existingFile.name))
                    Log.d("FileRepositoryImpl", "Successfully replaced multipart file '$displayName' (deleted ${messageIds.size} old chunks)")
                }
                is TdApi.UpdateMessageSendFailed -> {
                    dequeuePendingParent(encFile.name)
                    dequeueUploadMeta(encFile.name)
                    val errorMsg = updateResult.error?.message?.ifBlank { null }
                        ?: "Upload failed (code: ${updateResult.error?.code})"
                    Log.e("FileRepositoryImpl", "Replacement upload failed: $errorMsg")
                    _downloadEvents.emit(
                        DownloadEvent.Failure(existingFile.id, existingFile.name, "Replace failed: $errorMsg")
                    )
                }
                else -> {
                    dequeuePendingParent(encFile.name)
                    dequeueUploadMeta(encFile.name)
                    _downloadEvents.emit(
                        DownloadEvent.Failure(existingFile.id, existingFile.name, "Replace failed: unexpected update")
                    )
                }
            }
        } catch (e: Exception) {
            dequeuePendingParent(encFile.name)
            dequeueUploadMeta(encFile.name)
            Log.e("FileRepositoryImpl", "Failed to replace multipart file '$displayName'", e)
            _downloadEvents.emit(
                DownloadEvent.Failure(existingFile.id, existingFile.name, "Replace failed: ${e.message ?: "Unknown error"}")
            )
            throw e
        } finally {
            encFile.delete()
            tempDir.delete()
        }
    }

    override suspend fun initializeVault(userId: String) {
        metadataMutex.withLock {
            try {
                fileDao.deleteAllFiles()
                val chatId = resolveTargetChatId(userId)
                val (newMsgId, exportedAt) = metadataManager.initializeMetadataMessage(chatId)
                securePrefs.setMetadataMessageId(newMsgId)
                securePrefs.setLastSyncedExportedAt(exportedAt)
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
            telegramClient.sendWithTimeout(TdApi.OpenChat(targetChatId))

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
                val searchResult = telegramClient.sendWithTimeout(
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
        return this.originalFileName ?: if (this.name.endsWith(".enc")) this.name else "${this.name}.enc"
    }

    private fun FileEntity.cleanExportName(): String {
        val suffix = ".enc"
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

    private fun enqueuePendingSafId(fileName: String, safId: String) {
        pendingUploadSafIds
            .getOrPut(fileName) { java.util.concurrent.ConcurrentLinkedQueue() }
            .add(safId)
    }

    private fun dequeuePendingSafId(fileName: String): String? {
        val queue = pendingUploadSafIds[fileName] ?: return null
        val value = queue.poll()
        if (queue.isEmpty()) {
            pendingUploadSafIds.remove(fileName, queue)
        }
        return value
    }

    private fun enqueuePendingTempDir(fileName: String, tempDir: java.io.File) {
        pendingUploadTempDirs
            .getOrPut(fileName) { java.util.concurrent.ConcurrentLinkedQueue() }
            .add(tempDir)
    }

    private fun cleanupPendingTempDir(fileName: String) {
        val queue = pendingUploadTempDirs[fileName] ?: return
        while (true) {
            val dir = queue.poll() ?: break
            try {
                if (dir.isDirectory) {
                    dir.deleteRecursively()
                } else {
                    dir.delete()
                    dir.parentFile?.let { parent ->
                        if (parent.name.startsWith("upload_") || parent.name.startsWith("saf_upload_") || parent.name.startsWith("replace_")) {
                            parent.delete()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("FileRepositoryImpl", "Could not delete upload temp dir: ${dir.absolutePath}", e)
            }
        }
        if (queue.isEmpty()) {
            pendingUploadTempDirs.remove(fileName, queue)
        }
    }

    override suspend fun uploadSafFile(
        tempFile: java.io.File,
        displayName: String,
        mimeType: String?,
        parentId: String?,
        userId: String,
        placeholderId: String?
    ): String {
        return withContext(Dispatchers.IO) {
            ensureReadyForTransfer()
            val targetChatId = resolveTargetChatId(userId)
            val cleanName = displayName.trim().takeIf { it.isNotBlank() } ?: "Untitled"
            val encFileName = if (cleanName.endsWith(".enc")) cleanName else "$cleanName.enc"

            val tempDir = java.io.File(context.cacheDir, "saf_upload_${java.util.UUID.randomUUID()}").apply { mkdirs() }
            val encFile = java.io.File(tempDir, encFileName)

            try {
                tempFile.inputStream().use { stream ->
                    fileCryptor.encryptStream(stream, encFile)
                }

                if (encFile.length() <= 0L) {
                    encFile.delete()
                    tempDir.delete()
                    return@withContext cleanName
                }

                Log.d("FileRepositoryImpl", "SAF Encrypted $cleanName -> ${encFile.name} (${encFile.length()} bytes)")

                if (parentId != null) {
                    enqueuePendingParent(encFile.name, parentId)
                }
                enqueueUploadMeta(encFile.name, UploadMeta(cleanName, mimeType))
                if (placeholderId != null) {
                    enqueuePendingSafId(encFile.name, placeholderId)
                }
                enqueuePendingTempDir(encFile.name, tempDir)

                val inputFile = TdApi.InputFileLocal(encFile.absolutePath)
                val inputMessageContent = TdApi.InputMessageDocument(
                    inputFile,
                    null,
                    true,
                    null
                )

                val sendMessage = TdApi.SendMessage(
                    targetChatId,
                    null,
                    null,
                    null,
                    null,
                    inputMessageContent
                )

                try {
                    val sendResult = telegramClient.sendWithTimeout(sendMessage, 30_000L)
                    if (sendResult !is TdApi.Message) {
                        error("Unexpected response from TDLib when sending SAF upload: $sendResult")
                    }
                } catch (e: Exception) {
                    dequeuePendingParent(encFile.name)
                    dequeueUploadMeta(encFile.name)
                    dequeuePendingSafId(encFile.name)
                    cleanupPendingTempDir(encFile.name)
                    encFile.delete()
                    tempDir.deleteRecursively()
                    Log.e("FileRepositoryImpl", "Failed to queue SAF upload: ${e.message}", e)
                    _downloadEvents.tryEmit(
                        DownloadEvent.Failure(placeholderId, cleanName, e.message ?: "Failed to queue upload")
                    )
                    throw e
                }
                cleanName
            } catch (e: Exception) {
                Log.e("FileRepositoryImpl", "Error initiating SAF upload", e)
                throw e
            } finally {
                tempFile.delete()
            }
        }
    }

    override suspend fun replaceSafFile(existingFile: FileEntity, tempFile: java.io.File) {
        withContext(Dispatchers.IO) {
            try {
                ensureReadyForTransfer()
                val targetChatId = resolveTargetChatId(existingFile.userId)

                // Multipart files: delete all old chunks then re-upload
                if (existingFile.isMultipart) {
                    replaceMultipartFile(
                        existingFile = existingFile,
                        sourceStreamProvider = { tempFile.inputStream() },
                        chatId = targetChatId
                    )
                    return@withContext
                }

                val messageId = existingFile.messageId?.toLongOrNull()
                    ?: error("Cannot replace file without a Telegram message ID")

                val tempDir = java.io.File(context.cacheDir, "replace_${java.util.UUID.randomUUID()}").apply { mkdirs() }
                val encFileName = "${existingFile.name}.enc"
                val encFile = java.io.File(tempDir, encFileName)

                try {
                    tempFile.inputStream().use { stream ->
                        fileCryptor.encryptStream(stream, encFile)
                    }

                    val inputFile = TdApi.InputFileLocal(encFile.absolutePath)
                    val inputContent = TdApi.InputMessageDocument(inputFile, null, true, null)

                    val result = try {
                        telegramClient.sendWithTimeout(
                            TdApi.EditMessageMedia(targetChatId, messageId, null, inputContent),
                            300_000L
                        )
                    } catch (e: Exception) {
                        Log.e("FileRepositoryImpl", "EditMessageMedia failed for SAF file '${existingFile.name}': ${e.message}", e)
                        throw e
                    }

                    if (result is TdApi.Message) {
                        val doc = (result.content as? TdApi.MessageDocument)?.document
                        if (doc != null) {
                            fileDao.updateFileContent(
                                id = existingFile.id,
                                messageId = result.id.toString(),
                                fileId = doc.document.id.toString(),
                                size = doc.document.expectedSize,
                                mimeType = existingFile.mimeType,
                                chatId = result.chatId,
                                updatedAt = System.currentTimeMillis(),
                                isMultipart = false,
                                partIndex = null,
                                totalParts = null,
                                originalFileName = null
                            )
                        }
                        saveMetadataRemote(existingFile.userId)
                        Log.d("FileRepositoryImpl", "Replaced SAF file '${existingFile.name}' via EditMessageMedia (msg $messageId)")
                    } else {
                        error("Unexpected response from TDLib when replacing SAF file: $result")
                    }
                } finally {
                    encFile.delete()
                    tempDir.delete()
                }
            } finally {
                tempFile.delete()
            }
        }
    }

    private suspend fun resolveTargetChatId(userId: String): Long {
        val configuredChatId = securePrefs.getStorageChannelId()
        return if (configuredChatId != null) {
            configuredChatId
        } else {
            val uid = userId.toLongOrNull() ?: error("Invalid user ID: $userId")
            val result = runCatching {
                telegramClient.sendWithTimeout(TdApi.CreatePrivateChat(uid, true))
            }.getOrNull() as? TdApi.Chat ?: error("Could not create private chat for user $userId")
            result.id
        }
    }

    private suspend fun ensureReadyForTransfer() {
        if (!encryptionManager.isUnlocked) {
            error("Vault is locked. Please unlock first.")
        }

        if (!authManager.isStarted) {
            authManager.startClient()
        }

        if (authManager.authState.value is AuthState.Ready) {
            return
        }

        val state = withTimeoutOrNull(15_000L) {
            authManager.authState
                .filter { it is AuthState.Ready || it is AuthState.Error || it is AuthState.WaitPhoneNumber }
                .first()
        }

        when (state) {
            is AuthState.Ready -> return
            is AuthState.Error -> error("Telegram is not ready: ${state.message}")
            is AuthState.WaitPhoneNumber -> error("Telegram session expired. Please log in again.")
            else -> error("Telegram is not ready. Please try again.")
        }
    }

    private suspend fun downloadTelegramFileWithPolling(
        fileId: Int,
        timeoutMillis: Long = 60_000L
    ): Result<TdApi.File> {
        val initialFile = runCatching {
            telegramClient.send(TdApi.DownloadFile(fileId, 32, 0, 0, false)) as? TdApi.File
                ?: error("DownloadFile returned unexpected response")
        }.getOrElse { e ->
            Log.e("FileRepositoryImpl", "Failed to start download for $fileId", e)
            return Result.failure(e)
        }

        if (initialFile.local.isDownloadingCompleted && initialFile.local.path.isNotEmpty()) {
            return Result.success(initialFile)
        }

        var lastError: Throwable? = null
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            val stateResult = runCatching {
                telegramClient.send(TdApi.GetFile(fileId)) as? TdApi.File
                    ?: error("GetFile returned unexpected response")
            }

            stateResult.fold(
                onSuccess = { fileState ->
                    if (fileState.local.isDownloadingCompleted && fileState.local.path.isNotEmpty()) {
                        return Result.success(fileState)
                    }
                    if (!fileState.local.canBeDownloaded && !fileState.local.isDownloadingActive && !fileState.local.isDownloadingCompleted) {
                        val errMsg = "Telegram reported file cannot be downloaded"
                        Log.e("FileRepositoryImpl", errMsg)
                        return Result.failure(IllegalStateException(errMsg))
                    }
                },
                onFailure = { err ->
                    lastError = err
                    Log.w("FileRepositoryImpl", "GetFile failed during polling for $fileId: ${err.message}")
                    if (err is TdLibException && (err.code == 400 || err.code == 401 || err.code == 403 || err.code == 404)) {
                        return Result.failure(err)
                    }
                }
            )

            delay(500L)
        }

        val timeoutMsg = lastError?.message ?: "Download timed out after ${timeoutMillis / 1000}s"
        return Result.failure(lastError ?: IllegalStateException(timeoutMsg))
    }

    private suspend fun resolveLatestDownloadableFile(file: FileEntity): FileEntity? {
        val messageId = file.messageId?.toLongOrNull() ?: return file
        val chatId = when {
            file.chatId != null && file.chatId != 0L -> file.chatId
            securePrefs.getStorageChannelId() != null -> securePrefs.getStorageChannelId()
            else -> {
                val uid = file.userId.toLongOrNull() ?: return file
                val result = runCatching {
                    telegramClient.sendWithTimeout(TdApi.CreatePrivateChat(uid, true))
                }.getOrNull() as? TdApi.Chat ?: return file
                result.id
            }
        } ?: return file

        val message = runCatching {
            telegramClient.sendWithTimeout(TdApi.GetMessage(chatId, messageId))
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
        enqueuePendingTempDir(encFile.name, tempDir)

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

        try {
            val sendResult = telegramClient.sendWithTimeout(sendMessage, 30_000L)
            if (sendResult !is TdApi.Message) {
                error("Unexpected response from TDLib when sending encrypted container: $sendResult")
            }
        } catch (e: Exception) {
            dequeuePendingParent(encFile.name)
            dequeueUploadMeta(encFile.name)
            cleanupPendingTempDir(encFile.name)
            encFile.delete()
            tempDir.deleteRecursively()
            Log.e("FileRepositoryImpl", "Failed to queue upload container: ${e.message}", e)
            _downloadEvents.tryEmit(
                DownloadEvent.Failure(null, storedDisplayName, e.message ?: "Failed to queue upload")
            )
            throw e
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
                    val exportedAt = metadataManager.updateMetadataMessage(chatId, messageId)
                    securePrefs.setLastSyncedExportedAt(exportedAt)
                } else {
                    val (newMsgId, exportedAt) = metadataManager.initializeMetadataMessage(chatId)
                    securePrefs.setMetadataMessageId(newMsgId)
                    securePrefs.setLastSyncedExportedAt(exportedAt)
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
