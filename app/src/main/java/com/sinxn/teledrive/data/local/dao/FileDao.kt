package com.sinxn.teledrive.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.sinxn.teledrive.data.local.entity.FileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FileDao {

    data class FileFolderCounts(
        val fileCount: Int,
        val folderCount: Int
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFile(file: FileEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFiles(files: List<FileEntity>)

    @Query("SELECT * FROM files ORDER BY created_at ASC")
    suspend fun getAllFiles(): List<FileEntity>

    @Update
    suspend fun updateFile(file: FileEntity)

    /** Get all non-deleted files in a folder (null parentId = root level) */
    @Query("""
        SELECT * FROM files 
        WHERE parent_id IS :parentId 
          AND deleted_at IS NULL 
          AND user_id = :userId
        ORDER BY type DESC, name ASC
    """)
    fun getFilesByParent(parentId: String?, userId: String): Flow<List<FileEntity>>

    @Query("SELECT * FROM files WHERE id = :id AND deleted_at IS NULL")
    suspend fun getFileById(id: String): FileEntity?

    @Query("SELECT * FROM files WHERE message_id = :messageId AND deleted_at IS NULL LIMIT 1")
    suspend fun getFileByMessageId(messageId: String): FileEntity?

    @Query("SELECT * FROM files WHERE file_id = :fileId AND deleted_at IS NULL LIMIT 1")
    suspend fun getFileByFileId(fileId: String): FileEntity?

    @Query("""
        SELECT * FROM files
        WHERE user_id = :userId
          AND original_file_name = :originalFileName
          AND deleted_at IS NULL
        ORDER BY part_index ASC, created_at ASC
    """)
    suspend fun getMultipartFiles(userId: String, originalFileName: String): List<FileEntity>

    /** Soft-delete a file by setting deleted_at timestamp */
    @Query("UPDATE files SET deleted_at = :timestamp WHERE id = :id")
    suspend fun softDeleteFile(id: String, timestamp: Long = System.currentTimeMillis())

    /** Hard-delete a file permanently from the database */
    @Query("DELETE FROM files WHERE id = :id")
    suspend fun hardDeleteFile(id: String)

    /** Clean up any stale saf_enc_ artifact files from database */
    @Query("DELETE FROM files WHERE name LIKE 'saf_enc_%' OR id LIKE 'saf_enc_%'")
    suspend fun cleanupStaleSafArtifacts()

    /** Update the transfer progress of an active file (0.0 to 1.0) */
    @Query("UPDATE files SET upload_progress = :progress WHERE file_id = :fileId")
    suspend fun updateTransferProgress(fileId: String, progress: Float)

    @Query("UPDATE files SET upload_progress = :progress WHERE id = :id")
    suspend fun updateTransferProgressById(id: String, progress: Float)

    /** Rename a file or folder */
    @Query("UPDATE files SET name = :newName, updated_at = :timestamp WHERE id = :id")
    suspend fun renameFile(id: String, newName: String, timestamp: Long = System.currentTimeMillis())

    /** Move a file or folder to a different parent */
    @Query("UPDATE files SET parent_id = :newParentId, updated_at = :timestamp WHERE id = :id")
    suspend fun moveFile(id: String, newParentId: String?, timestamp: Long = System.currentTimeMillis())

    /** Get all folders for a user (for move-to picker) */
    @Query("SELECT * FROM files WHERE type = 'folder' AND deleted_at IS NULL AND user_id = :userId ORDER BY name ASC")
    suspend fun getFoldersByUser(userId: String): List<FileEntity>

    /** Search files by name (case-insensitive) */
    @Query("""
        SELECT * FROM files 
        WHERE name LIKE '%' || :query || '%' 
          AND deleted_at IS NULL 
          AND user_id = :userId
        ORDER BY name ASC
    """)
    fun searchFiles(query: String, userId: String): Flow<List<FileEntity>>

    /** Get total storage used by a user (sum of file sizes) */
    @Query("SELECT COALESCE(SUM(size), 0) FROM files WHERE user_id = :userId AND deleted_at IS NULL AND type = 'file'")
    suspend fun getStorageUsed(userId: String): Long

    @Transaction
    @Query("""
        SELECT
            SUM(CASE WHEN type = 'file' THEN 1 ELSE 0 END) AS fileCount,
            SUM(CASE WHEN type = 'folder' THEN 1 ELSE 0 END) AS folderCount
        FROM files
        WHERE user_id = :userId AND deleted_at IS NULL
    """)
    fun observeFileFolderCounts(userId: String): Flow<FileFolderCounts?>

    /** Hard-delete all files for a user (used during logout) */
    @Query("DELETE FROM files WHERE user_id = :userId")
    suspend fun deleteAllFilesForUser(userId: String)

    @Query("DELETE FROM files")
    suspend fun deleteAllFiles()

    /** Update file content fields after a replacement (preserves name, parentId, createdAt). */
    @Query("""
        UPDATE files 
        SET message_id = :messageId, 
            file_id = :fileId, 
            size = :size, 
            mime_type = :mimeType, 
            chat_id = :chatId, 
            updated_at = :updatedAt, 
            upload_progress = NULL,
            is_multipart = :isMultipart,
            part_index = :partIndex,
            total_parts = :totalParts,
            original_file_name = :originalFileName
        WHERE id = :id
    """)
    suspend fun updateFileContent(
        id: String,
        messageId: String?,
        fileId: String?,
        size: Long?,
        mimeType: String?,
        chatId: Long?,
        updatedAt: Long,
        isMultipart: Boolean,
        partIndex: Int?,
        totalParts: Int?,
        originalFileName: String?
    )

    // ── Snapshot queries for DocumentsProvider (non-Flow) ───────────

    /** One-shot list of children in a folder (null parentId = root). Used by SAF. */
    @Query("""
        SELECT * FROM files 
        WHERE parent_id IS :parentId 
          AND deleted_at IS NULL 
          AND user_id = :userId
        ORDER BY type DESC, name ASC
    """)
    suspend fun getFilesByParentSnapshot(parentId: String?, userId: String): List<FileEntity>

    /** One-shot search by name. Used by SAF querySearchDocuments. */
    @Query("""
        SELECT * FROM files 
        WHERE name LIKE '%' || :query || '%' 
          AND deleted_at IS NULL 
          AND user_id = :userId
        ORDER BY name ASC
    """)
    suspend fun searchFilesSnapshot(query: String, userId: String): List<FileEntity>
}
