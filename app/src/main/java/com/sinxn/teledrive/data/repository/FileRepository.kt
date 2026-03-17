package com.sinxn.teledrive.data.repository

import com.sinxn.teledrive.data.local.entity.FileEntity
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for file/folder data operations.
 * Abstracts Room DAO + future TDLib remote data source.
 */
interface FileRepository {

    /** Observe files within a folder (null parentId = root). */
    fun getFilesByParent(parentId: String?, userId: String): Flow<List<FileEntity>>

    /**
     * Fetches the recent history of the "Saved Messages" chat directly from TDLib.
     */
    suspend fun getSavedMessagesHistory(): List<org.drinkless.tdlib.TdApi.Message>

    /** Get a single file by its ID. */
    suspend fun getFileById(id: String): FileEntity?

    /** Get a file by its Telegram message ID. */
    suspend fun getFileByMessageId(messageId: String): FileEntity?

    /** Insert or update a single file. */
    suspend fun saveFile(file: FileEntity)

    /** Batch insert/update files (e.g., after syncing from Telegram). */
    suspend fun saveFiles(files: List<FileEntity>)

    /** Update an existing file entity. */
    suspend fun updateFile(file: FileEntity)

    /** Soft-delete a file (sets deletedAt timestamp). */
    suspend fun deleteFile(id: String)

    /** Search files by name. */
    fun searchFiles(query: String, userId: String): Flow<List<FileEntity>>

    /** Get total storage used by a user (sum of file sizes in bytes). */
    suspend fun getStorageUsed(userId: String): Long
}
