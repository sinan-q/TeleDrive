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

    /**
     * Fetches Saved Messages from TDLib, parses them, and saves them to the Room database.
     */
    suspend fun syncSavedMessages(userId: String)

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

    /** Delete a file: removes the TG message (if applicable) and hard-deletes from Room. */
    suspend fun deleteFile(file: FileEntity)

    /** Search files by name. */
    fun searchFiles(query: String, userId: String): Flow<List<FileEntity>>

    /** Get total storage used by a user (sum of file sizes in bytes). */
    suspend fun getStorageUsed(userId: String): Long
    
    /**
     * Uploads a physical file on the device to a specified chat (e.g. Saved Messages).
     */
    suspend fun uploadFile(filePath: String, userId: String, parentId: String? = null)

    /**
     * Triggers TDLib to download the file into its internal cache.
     * Export handled via event listening.
     */
    suspend fun downloadFile(file: FileEntity)

    /** Rename a file or folder (virtual metadata only). */
    suspend fun renameFile(id: String, newName: String)

    /** Move a file or folder to a different parent folder. */
    suspend fun moveFile(id: String, newParentId: String?)

    /** Get all available folders for the move-to picker. */
    suspend fun getFolders(userId: String): List<FileEntity>
}
