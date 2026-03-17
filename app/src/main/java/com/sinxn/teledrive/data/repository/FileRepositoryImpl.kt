package com.sinxn.teledrive.data.repository

import com.sinxn.teledrive.data.local.dao.FileDao
import com.sinxn.teledrive.data.local.entity.FileEntity
import com.sinxn.teledrive.data.telegram.TelegramClient
import com.sinxn.teledrive.data.telegram.mapper.toFileEntity
import kotlinx.coroutines.flow.Flow
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
    private val fileDao: FileDao,
    private val telegramClient: TelegramClient
) : FileRepository {

    override fun getFilesByParent(parentId: String?, userId: String): Flow<List<FileEntity>> =
        fileDao.getFilesByParent(parentId, userId)

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

    override suspend fun deleteFile(id: String) =
        fileDao.softDeleteFile(id)

    override fun searchFiles(query: String, userId: String): Flow<List<FileEntity>> =
        fileDao.searchFiles(query, userId)

    override suspend fun getStorageUsed(userId: String): Long =
        fileDao.getStorageUsed(userId)
}
