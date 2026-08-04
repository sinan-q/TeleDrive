package com.sinxn.teledrive.data.repository

import android.net.Uri
import com.sinxn.teledrive.data.local.entity.FileEntity
import com.sinxn.teledrive.data.model.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for file/folder data operations.
 * Abstracts Room DAO + future TDLib remote data source.
 */
interface FileRepository {

    val downloadEvents: SharedFlow<DownloadEvent>

    /** Observe files within a folder (null parentId = root). */
    fun getFilesByParent(parentId: String?, userId: String): Flow<List<FileEntity>>

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

    /** Observe local cached file/folder counts for a user. */
    fun observeFileFolderCounts(userId: String): Flow<FileFolderSummary>
    
    /**
     * Uploads a file selected from the device to a specified chat (e.g. Saved Messages).
     */
    suspend fun uploadFile(uri: Uri, userId: String, parentId: String? = null): String

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

    /**
     * Downloads and imports existing metadata into Room database.
     */
    suspend fun syncExistingFiles(userId: String)

    /**
     * Performs Step 5 read-only reconciliation scanning Telegram chat history
     * to cross-verify local database against Telegram storage.
     */
    suspend fun crossVerifyVault(userId: String)

    /**
     * Sends the initial empty encrypted metadata payload to Telegram to
     * establish the vault marker (#teledrive_vault_metadata).
     * Must be called once when a new vault is created (non-sync setup).
     */
    suspend fun initializeVault(userId: String)

    /**
     * Observable flow of the current sync status.
     */
    val syncStatus: StateFlow<SyncStatus>
}

sealed interface DownloadEvent {
    data class Progress(val fileId: String, val progress: Float, val message: String? = null) : DownloadEvent
    data class Status(val fileId: String?, val fileName: String, val message: String) : DownloadEvent
    data class Success(val fileId: String?, val fileName: String) : DownloadEvent
    data class Failure(val fileId: String?, val fileName: String, val reason: String) : DownloadEvent
}

data class FileFolderSummary(
    val fileCount: Int = 0,
    val folderCount: Int = 0
)
