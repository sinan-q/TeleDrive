package com.sinxn.teledrive.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a file or virtual folder stored in Telegram.
 * Mapped from the original Prisma `files` model.
 *
 * - type = "file" for actual files, "folder" for virtual folders
 * - parentId enables hierarchical folder structure (self-referencing)
 * - messageId / fileId link to the Telegram message/file for TDLib operations
 * - deletedAt enables soft-delete
 */
@Entity(
    tableName = "files",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("user_id"),
        Index("parent_id"),
        Index("message_id")
    ]
)
data class FileEntity(
    @PrimaryKey
    val id: String,

    val name: String,

    /** "file" or "folder" */
    val type: String = "file",

    @ColumnInfo(name = "mime_type")
    val mimeType: String? = null,

    /** Identifies if this file is a chunk of a larger file (e.g. video.mp4.part1) */
    @ColumnInfo(name = "is_multipart")
    val isMultipart: Boolean = false,

    /** The chunk index (e.g. 1 for part1) */
    @ColumnInfo(name = "part_index")
    val partIndex: Int? = null,

    /** Total number of parts (if known) */
    @ColumnInfo(name = "total_parts")
    val totalParts: Int? = null,

    /** The root filename without the .partX extension (e.g. video.mp4) */
    @ColumnInfo(name = "original_file_name")
    val originalFileName: String? = null,

    /** File size in bytes */
    val size: Long? = null,

    /** Telegram message ID — links this file to a TG message */
    @ColumnInfo(name = "message_id")
    val messageId: String? = null,

    /** Telegram file ID — used for downloading via TDLib */
    @ColumnInfo(name = "file_id")
    val fileId: String? = null,

    /** Parent folder ID (null = root level) */
    @ColumnInfo(name = "parent_id")
    val parentId: String? = null,

    /** Owner user ID */
    @ColumnInfo(name = "user_id")
    val userId: String,

    /** Upload progress (0.0 to 1.0) */
    @ColumnInfo(name = "upload_progress")
    val uploadProgress: Float? = null,

    @ColumnInfo(name = "uploaded_at")
    val uploadedAt: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    /** Soft-delete timestamp (null = not deleted) */
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,

    /** The Telegram Chat ID where this file is stored */
    @ColumnInfo(name = "chat_id")
    val chatId: Long? = null
)
