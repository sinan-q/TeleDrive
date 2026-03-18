package com.sinxn.teledrive.data.repository

import android.content.Context
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Track file IDs that we explicitly asked TDLib to download this session */
    private val pendingDownloads = mutableSetOf<Int>()

    /** Maps a pending upload's raw file name back to its target folder's parentId */
    private val pendingUploadParents = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    override val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    /**
     * Maps an encrypted TG filename (e.g. "report.pdf.enc") back to its original metadata
     * so we can display the clean name and mime type in Room.
     */
    data class UploadMeta(val originalName: String, val mimeType: String?)
    private val pendingUploadMeta = java.util.concurrent.ConcurrentHashMap<String, UploadMeta>()

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

                        // Download logic - export only if we explicitly requested this download
                        if (file.local.isDownloadingCompleted && file.local.path.isNotEmpty()
                            && pendingDownloads.remove(file.id)) {
                            try {
                                val fileEntity = fileDao.getFileByFileId(file.id.toString())
                                if (fileEntity != null) {
                                    val downloadedFile = java.io.File(file.local.path)
                                    val exportName = fileEntity.name
                                    val exportMimeType = fileEntity.mimeType ?: "application/octet-stream"

                                    // Check if the TDLib file is encrypted
                                    if (downloadedFile.name.endsWith(".enc")) {
                                        // Decrypt to a temp file, then export the plaintext
                                        val decryptedFile = java.io.File(context.cacheDir, "dec_$exportName")
                                        try {
                                            fileCryptor.decryptFile(downloadedFile, decryptedFile)
                                            com.sinxn.teledrive.utils.FileUtils.exportToDownloads(
                                                context, decryptedFile, exportName, exportMimeType
                                            )
                                        } finally {
                                            decryptedFile.delete() // Always clean up temp decrypted file
                                        }
                                    } else {
                                        // Legacy unencrypted file — export directly
                                        com.sinxn.teledrive.utils.FileUtils.exportToDownloads(
                                            context, downloadedFile, exportName, exportMimeType
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("FileRepositoryImpl", "Failed to export/decrypt downloaded file", e)
                            }
                        }
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
                                val meta = pendingUploadMeta.remove(entity.localName())
                                val cleanEntity = if (meta != null) {
                                    entity.copy(
                                        name = meta.originalName,
                                        mimeType = meta.mimeType
                                    )
                                } else entity // MessageMapper already strips .enc suffix

                                // Re-attach parent folder mapping using the ORIGINAL name
                                val parentId = pendingUploadParents.remove(cleanEntity.name)
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
                        val parentId = oldEntity?.parentId ?: pendingUploadParents.remove(oldEntity?.name ?: "")
                        
                        // Create the final entity with the permanent ID
                        val newEntity = update.message.toFileEntity(activeUserId, parentId)
                        if (newEntity != null) {
                            // If it's an encrypted file, try matching original meta to get clean name
                            val meta = pendingUploadMeta.remove(newEntity.localName())
                            val cleanEntity = if (meta != null) {
                                newEntity.copy(name = meta.originalName, mimeType = meta.mimeType)
                            } else newEntity

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

    override suspend fun uploadFile(filePath: String, userId: String, parentId: String?) {
        try {
            val originalFile = java.io.File(filePath)
            val originalName = originalFile.name

            // Track the destination folder using the ORIGINAL filename
            if (parentId != null) {
                pendingUploadParents[originalName] = parentId
            }

            // ── Encrypt the file ──────────────────────────────────────
            val encFile = java.io.File(context.cacheDir, "$originalName.enc")
            fileCryptor.encryptFile(originalFile, encFile)
            Log.d("FileRepositoryImpl", "Encrypted $originalName -> ${encFile.name} (${encFile.length()} bytes)")

            // Map the encrypted TG name back to the original clean name
            val mimeType = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(originalFile.extension)
            pendingUploadMeta[encFile.name] = UploadMeta(originalName, mimeType)

            // Get the target chat ID (configured channel or Saved Messages)
            val configuredChatId = securePrefs.getStorageChannelId()
            val savedMessagesChatId = if (configuredChatId != null) {
                configuredChatId
            } else {
                val chat = telegramClient.send(
                    TdApi.CreatePrivateChat(userId.toLong(), true)
                ) as TdApi.Chat
                chat.id
            }

            // Create InputFileLocal pointing to the ENCRYPTED file
            val inputFile = TdApi.InputFileLocal(encFile.absolutePath)
            
            val inputMessageContent = TdApi.InputMessageDocument(
                inputFile,
                null,           // thumbnail
                true,           // disableContentTypeDetection
                null            // caption
            )

            val sendMessage = TdApi.SendMessage(
                savedMessagesChatId,
                null,
                null,
                null,
                null,
                inputMessageContent
            )

            telegramClient.sendAsync(sendMessage) { result ->
                if (result is TdApi.Error) {
                    pendingUploadParents.remove(originalName)
                    pendingUploadMeta.remove(encFile.name)
                    encFile.delete()
                    Log.e("FileRepositoryImpl", "Failed to queue upload: ${result.message}")
                } else {
                    Log.d("FileRepositoryImpl", "Encrypted upload queued to TDLib!")
                    // Clean up temp encrypted file after TDLib copies it to its internal buffer
                    encFile.deleteOnExit()
                }
            }
        } catch (e: Exception) {
            Log.e("FileRepositoryImpl", "Error initiating encrypted file upload", e)
            throw e
        }
    }

    override suspend fun downloadFile(file: FileEntity) {
        val fileIdStr = file.fileId ?: return
        val fileId = fileIdStr.toIntOrNull() ?: return
        
        // Track this as an intentional download
        pendingDownloads.add(fileId)
        
        // Priority 32 (default), offset/limit 0 (entire file), synchronous false
        val downloadRequest = TdApi.DownloadFile(fileId, 32, 0, 0, false)
        telegramClient.sendAsync(downloadRequest) { result ->
             if (result is TdApi.Error) {
                 pendingDownloads.remove(fileId)
                 Log.e("FileRepositoryImpl", "Failed to start download: ${result.message}")
             } else {
                 Log.d("FileRepositoryImpl", "Download queued to TDLib!")
             }
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
}
