package com.sinxn.teledrive.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.sinxn.teledrive.data.local.entity.FileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(file: FileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFiles(files: List<FileEntity>)

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

    /** Soft-delete a file by setting deleted_at timestamp */
    @Query("UPDATE files SET deleted_at = :timestamp WHERE id = :id")
    suspend fun softDeleteFile(id: String, timestamp: Long = System.currentTimeMillis())

    /** Update the upload progress of an active file (0.0 to 1.0) */
    @Query("UPDATE files SET upload_progress = :progress WHERE file_id = :fileId")
    suspend fun updateUploadProgress(fileId: String, progress: Float)

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

    /** Hard-delete all files for a user (used during logout) */
    @Query("DELETE FROM files WHERE user_id = :userId")
    suspend fun deleteAllFilesForUser(userId: String)
}
