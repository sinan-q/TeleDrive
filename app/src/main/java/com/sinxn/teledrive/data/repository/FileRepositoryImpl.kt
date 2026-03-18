package com.sinxn.teledrive.data.repository

import android.content.Context
import android.util.Log
import com.sinxn.teledrive.data.local.dao.FileDao
import com.sinxn.teledrive.data.local.entity.FileEntity
import com.sinxn.teledrive.data.local.prefs.SecurePrefs
import com.sinxn.teledrive.data.telegram.TelegramClient
import com.sinxn.teledrive.data.telegram.mapper.toFileEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    private val securePrefs: SecurePrefs
) : FileRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Track file IDs that we explicitly asked TDLib to download this session */
    private val pendingDownloads = mutableSetOf<Int>()

    /** Maps a pending upload's raw file name back to its target folder's parentId */
    private val pendingUploadParents = java.util.concurrent.ConcurrentHashMap<String, String>()

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
                                    com.sinxn.teledrive.utils.FileUtils.exportToDownloads(
                                        context, 
                                        downloadedFile, 
                                        fileEntity.name, 
                                        fileEntity.mimeType ?: "application/octet-stream"
                                    )
                                }
                            } catch (e: Exception) {
                                Log.e("FileRepositoryImpl", "Failed to export downloaded file", e)
                            }
                        }
                    }
                    is TdApi.UpdateNewMessage -> {
                        val activeUserId = securePrefs.getUserId()
                        if (activeUserId != null) {
                            val entity = update.message.toFileEntity(activeUserId)
                            if (entity != null) {
                                // If we initiated this upload from a specific folder, re-attach its parent mapping
                                val parentId = pendingUploadParents.remove(entity.name)
                                val entityToInsert = if (parentId != null) {
                                    entity.copy(parentId = parentId)
                                } else entity

                                // Auto-insert new finished uploads or remote syncs into Room directly.
                                // Room's IGNORE strategy ensures we don't clobber existing ones.
                                fileDao.insertFile(entityToInsert)
                            }
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

    override suspend fun getSavedMessagesHistory(): List<TdApi.Message> {
        // 1. Get current user ID
        val me = telegramClient.send(TdApi.GetMe()) as TdApi.User
        val myUserId = me.id

        // 2. Chat ID for Saved Messages is essentially a private chat with yourself
        val chat = telegramClient.send(TdApi.CreatePrivateChat(myUserId, true)) as TdApi.Chat
        val savedMessagesChatId = chat.id

        // 3. Fetch history
        // fromMessageId = 0 (get from the end), offset = 0, limit = 100
        val historyParams = TdApi.GetChatHistory(savedMessagesChatId, 0, 0, 100, false)
        val messagesResult = telegramClient.send(historyParams) as TdApi.Messages

        return messagesResult.messages.toList()
    }

    override suspend fun syncSavedMessages(userId: String) {
        val messages = getSavedMessagesHistory()
        val fileEntities = messages.mapNotNull { it.toFileEntity(userId) }
        fileDao.insertFiles(fileEntities)
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
                val chat = telegramClient.send(
                    TdApi.CreatePrivateChat(file.userId.toLong(), true)
                ) as TdApi.Chat
                telegramClient.send(
                    TdApi.DeleteMessages(chat.id, longArrayOf(msgId), true)
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
            if (parentId != null) {
                val fileName = java.io.File(filePath).name
                pendingUploadParents[fileName] = parentId
            }

            // Get our own ID to figure out the private chat ID (Saved Messages)
            val chat = telegramClient.send(
                TdApi.CreatePrivateChat(userId.toLong(), true)
            ) as TdApi.Chat
            val savedMessagesChatId = chat.id

            // Create InputFileLocal pointing to the physical file on device
            val inputFile = TdApi.InputFileLocal(filePath)
            
            // Wrap it in an InputMessageDocument to upload as a generic file
            val inputMessageContent = TdApi.InputMessageDocument(
                inputFile,
                null,           // thumbnail
                true,           // disableContentTypeDetection
                null            // caption
            )

            // Fire the SendMessage request 
            val sendMessage = TdApi.SendMessage(
                savedMessagesChatId,
                null,           // messageThreadId / replyToMessageId equivalent
                null,           // replyTo
                null,           // options
                null,           // replyMarkup
                inputMessageContent
            )

            // SendAsync delegates the blocking chunk/upload work to TDLib's internal workers
            telegramClient.sendAsync(sendMessage) { result ->
                if (result is TdApi.Error) {
                    if (parentId != null) {
                        val fileName = java.io.File(filePath).name
                        pendingUploadParents.remove(fileName)
                    }
                    Log.e("FileRepositoryImpl", "Failed to queue upload: ${result.message}")
                } else {
                    Log.d("FileRepositoryImpl", "Upload successfully queued to TDLib!")
                }
            }
        } catch (e: Exception) {
            Log.e("FileRepositoryImpl", "Error initiating file upload", e)
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
}
