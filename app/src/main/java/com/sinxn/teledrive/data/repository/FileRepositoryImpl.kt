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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.sinxn.teledrive.data.model.SyncStatus
import org.drinkless.tdlib.TdApi
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
    private val fileCryptor: FileCryptor
) : FileRepository {

    companion object {
        private const val MULTIPART_CHUNK_SIZE_BYTES = 64L * 1024L * 1024L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Track file IDs that we explicitly asked TDLib to download this session */
    private val pendingDownloads = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

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

    init {
        scope.launch {
            telegramClient.updates.collect { update ->
                when (update) {
                    is TdApi.UpdateFile -> {
                        val file = update.file
                        
                        // Upload logic
                        if (file.remote.isUploadingActive && file.expectedSize > 0) {
                            val progress = file.remote.uploadedSize.toFloat() / file.expectedSize.toFloat()
                            fileDao.updateUploadProgress(file.id.toString(), progress)
                        } else if (file.remote.isUploadingCompleted) {
                            fileDao.updateUploadProgress(file.id.toString(), 1.0f)
                        }

                        handleCompletedDownload(file)
                    }
                    is TdApi.UpdateNewMessage -> {
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
                        }
                    }
                }
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

    override suspend fun saveFile(file: FileEntity) =
        fileDao.insertFile(file)

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
        try {
            val pickedFile = com.sinxn.teledrive.utils.FileUtils.resolvePickedFileMetadata(context, uri)
            val originalName = pickedFile.displayName
            val targetChatId = resolveTargetChatId(userId)
            val totalParts = pickedFile.sizeBytes
                ?.takeIf { it > MULTIPART_CHUNK_SIZE_BYTES }
                ?.let { ((it + MULTIPART_CHUNK_SIZE_BYTES - 1) / MULTIPART_CHUNK_SIZE_BYTES).toInt() }
                ?: 1

            context.contentResolver.openInputStream(uri)?.use { sourceStream ->
                if (totalParts == 1) {
                    uploadEncryptedPart(
                        sourceStream = sourceStream,
                        userId = userId,
                        chatId = targetChatId,
                        encryptedFileName = "$originalName.enc",
                        storedDisplayName = originalName,
                        mimeType = pickedFile.mimeType,
                        parentId = parentId
                    )
                } else {
                    for (partIndex in 1..totalParts) {
                        val chunkName = "$originalName.part$partIndex"
                        val bytesRead = uploadEncryptedPart(
                            sourceStream = LimitedInputStream(sourceStream, MULTIPART_CHUNK_SIZE_BYTES),
                            userId = userId,
                            chatId = targetChatId,
                            encryptedFileName = "$chunkName.enc",
                            storedDisplayName = chunkName,
                            mimeType = pickedFile.mimeType,
                            parentId = parentId
                        )
                        if (bytesRead <= 0L) {
                            break
                        }
                    }
                }
            } ?: error("Could not open selected file")
            return originalName
        } catch (e: Exception) {
            Log.e("FileRepositoryImpl", "Error initiating encrypted file upload", e)
            throw e
        }
    }

    override suspend fun downloadFile(file: FileEntity) {
        if (file.isMultipart && !file.originalFileName.isNullOrBlank()) {
            downloadMultipartFile(file)
            return
        }

        val fileIdStr = file.fileId
        if (fileIdStr.isNullOrBlank()) {
            _downloadEvents.emit(DownloadEvent.Failure(file.name, "Missing Telegram file ID"))
            return
        }
        val fileId = fileIdStr.toIntOrNull()
        if (fileId == null) {
            _downloadEvents.emit(DownloadEvent.Failure(file.name, "Invalid Telegram file ID"))
            return
        }
        
        // Track this as an intentional download
        pendingDownloads.add(fileId)
        
        // Priority 32 (default), offset/limit 0 (entire file), synchronous false
        val downloadRequest = TdApi.DownloadFile(fileId, 32, 0, 0, false)
        telegramClient.sendAsync(downloadRequest) { result ->
             if (result is TdApi.Error) {
                 pendingDownloads.remove(fileId)
                 Log.e("FileRepositoryImpl", "Failed to start download: ${result.message}")
                 _downloadEvents.tryEmit(
                     DownloadEvent.Failure(file.name, result.message ?: "Failed to start download")
                 )
             } else if (result is TdApi.File) {
                 Log.d("FileRepositoryImpl", "Download request returned file state for ${file.name}")
                 handleCompletedDownload(result)
             } else {
                 Log.d("FileRepositoryImpl", "Download queued to TDLib!")
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

        scope.launch {
            val fileEntity = fileDao.getFileByFileId(file.id.toString())
            if (fileEntity == null) {
                Log.e("FileRepositoryImpl", "Downloaded file ${file.id} is not present in Room")
                _downloadEvents.emit(
                    DownloadEvent.Failure("Unknown file", "Downloaded file metadata was not found")
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
                        fileCryptor.decryptFile(downloadedFile, decryptedFile)
                        com.sinxn.teledrive.utils.FileUtils.exportToDownloads(
                            context, decryptedFile, exportName, exportMimeType
                        )
                    } finally {
                        decryptedFile.delete()
                    }
                } else {
                    com.sinxn.teledrive.utils.FileUtils.exportToDownloads(
                        context, downloadedFile, exportName, exportMimeType
                    )
                }

                if (exported) {
                    _downloadEvents.emit(DownloadEvent.Success(exportName))
                } else {
                    _downloadEvents.emit(
                        DownloadEvent.Failure(exportName, "Could not write file to Downloads/TeleDrive")
                    )
                }
            } catch (e: Exception) {
                Log.e("FileRepositoryImpl", "Failed to export/decrypt downloaded file", e)
                _downloadEvents.emit(
                    DownloadEvent.Failure(fileEntity.name, e.message ?: "Failed to process downloaded file")
                )
            }
        }
    }

    private suspend fun downloadMultipartFile(file: FileEntity) {
        val originalName = file.originalFileName ?: file.name
        val chunks = fileDao.getMultipartFiles(file.userId, originalName)
        if (chunks.isEmpty()) {
            _downloadEvents.emit(DownloadEvent.Failure(originalName, "No file chunks found"))
            return
        }

        val assembledFile = java.io.File(context.cacheDir, "assembled_${java.util.UUID.randomUUID()}_$originalName")
        try {
            java.io.FileOutputStream(assembledFile).use { assembledOutput ->
                for (chunk in chunks) {
                    val fileId = chunk.fileId?.toIntOrNull()
                    if (fileId == null) {
                        _downloadEvents.emit(DownloadEvent.Failure(originalName, "Missing Telegram file ID for a chunk"))
                        return
                    }

                    val result = telegramClient.send(TdApi.DownloadFile(fileId, 32, 0, 0, true))
                    val tdFile = result as? TdApi.File
                        ?: throw IllegalStateException("Chunk download failed for $originalName")

                    val downloadedFile = java.io.File(tdFile.local.path)
                    val decryptedChunk = java.io.File(context.cacheDir, "dec_${java.util.UUID.randomUUID()}_${chunk.name}")
                    try {
                        fileCryptor.decryptFile(downloadedFile, decryptedChunk)
                        decryptedChunk.inputStream().use { it.copyTo(assembledOutput) }
                    } finally {
                        decryptedChunk.delete()
                    }
                }
            }

            val exportMimeType = file.mimeType ?: "application/octet-stream"
            val exported = com.sinxn.teledrive.utils.FileUtils.exportToDownloads(
                context,
                assembledFile,
                originalName,
                exportMimeType
            )
            if (exported) {
                _downloadEvents.emit(DownloadEvent.Success(originalName))
            } else {
                _downloadEvents.emit(
                    DownloadEvent.Failure(originalName, "Could not write file to Downloads/TeleDrive")
                )
            }
        } catch (e: Exception) {
            Log.e("FileRepositoryImpl", "Failed to download multipart file", e)
            _downloadEvents.emit(
                DownloadEvent.Failure(originalName, e.message ?: "Failed to download multipart file")
            )
        } finally {
            assembledFile.delete()
        }
    }

    override suspend fun renameFile(id: String, newName: String) =
        fileDao.renameFile(id, newName)

    override suspend fun moveFile(id: String, newParentId: String?) =
        fileDao.moveFile(id, newParentId)

    override suspend fun getFolders(userId: String): List<FileEntity> =
        fileDao.getFoldersByUser(userId)

    override suspend fun syncExistingFiles(userId: String) {
        try {
            var currentMetrics = com.sinxn.teledrive.data.model.SyncMetrics()
            _syncStatus.value = SyncStatus.Syncing(currentMetrics)
            
            val configuredChatId = securePrefs.getStorageChannelId()
            val targetChatId = if (configuredChatId != null) {
                configuredChatId
            } else {
                val chat = telegramClient.send(
                    TdApi.CreatePrivateChat(userId.toLong(), true)
                ) as TdApi.Chat
                chat.id
            }

            Log.d("FileRepositoryImpl", "Starting existing files sync for chat $targetChatId")
            
            // Ensure chat is opened so TDLib syncs it from server
            telegramClient.send(TdApi.OpenChat(targetChatId))

            var lastMsgId = 0L
            
            // Loop to fetch history in batches of 100
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
                
                // Track metrics
                val scannedBatch = messages.size
                val docsInBatch = messages.filter { it.content is TdApi.MessageDocument }
                val filenamesInBatch = docsInBatch.mapNotNull { 
                    (it.content as TdApi.MessageDocument).document.fileName.takeIf { name -> name.isNotBlank() }
                }
                val entitiesToInsert = messages.mapNotNull { it.toFileEntity(userId) }
                
                currentMetrics = currentMetrics.copy(
                    messagesScanned = currentMetrics.messagesScanned + scannedBatch,
                    totalDocumentsFound = currentMetrics.totalDocumentsFound + docsInBatch.size,
                    encFilesMatched = currentMetrics.encFilesMatched + entitiesToInsert.size,
                    foundFilenames = currentMetrics.foundFilenames + filenamesInBatch
                )
                
                if (entitiesToInsert.isNotEmpty()) {
                    fileDao.insertFiles(entitiesToInsert)
                    _syncStatus.value = SyncStatus.Syncing(currentMetrics)
                }
                
                lastMsgId = messages.last().id
                if (messages.size < 100) break
            }
            
            Log.d("FileRepositoryImpl", "Sync completed. Metrics: $currentMetrics")
            _syncStatus.value = SyncStatus.Completed(currentMetrics)
        } catch (e: Exception) {
            Log.e("FileRepositoryImpl", "Error syncing existing files", e)
            _syncStatus.value = SyncStatus.Error(e.message ?: "Unknown error during sync")
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

    private suspend fun uploadEncryptedPart(
        sourceStream: java.io.InputStream,
        userId: String,
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

    private class LimitedInputStream(
        private val upstream: java.io.InputStream,
        private val maxBytes: Long
    ) : java.io.InputStream() {
        private var bytesRead = 0L

        override fun read(): Int {
            if (bytesRead >= maxBytes) return -1
            val value = upstream.read()
            if (value != -1) bytesRead++
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (bytesRead >= maxBytes) return -1
            val allowed = minOf(length.toLong(), maxBytes - bytesRead).toInt()
            val read = upstream.read(buffer, offset, allowed)
            if (read > 0) bytesRead += read.toLong()
            return read
        }
    }
}
