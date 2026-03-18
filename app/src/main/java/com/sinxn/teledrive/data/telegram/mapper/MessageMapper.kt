package com.sinxn.teledrive.data.telegram.mapper

import com.sinxn.teledrive.data.local.entity.FileEntity
import org.drinkless.tdlib.TdApi

/**
 * Maps a Telegram [TdApi.Message] to our internal Room [FileEntity].
 * Returns null if the message does not contain supported file content.
 */
fun TdApi.Message.toFileEntity(userId: String, parentId: String? = null): FileEntity? {
    // Generate a deterministic unique ID based on the message ID
    val entityId = "msg_${this.id}"
    val createdAtMs = this.date * 1000L

    // Part aggregation logic
    val partRegex = Regex("""^(.*)\.part(\d+)$""", RegexOption.IGNORE_CASE)
    var isMultipart = false
    var partIndex: Int? = null
    var originalFileName: String? = null

    // Helper to evaluate parts
    fun parseMultipart(fileName: String) {
        val cleanName = if (fileName.endsWith(".enc")) {
            fileName.removeSuffix(".enc")
        } else fileName

        val matchResult = partRegex.find(cleanName)
        if (matchResult != null) {
            isMultipart = true
            originalFileName = matchResult.groupValues[1]
            partIndex = matchResult.groupValues[2].toIntOrNull()
        } else {
            originalFileName = cleanName
        }
    }

    return when (val content = this.content) {
        is TdApi.MessageDocument -> {
            val fileName = content.document.fileName.takeIf { it.isNotBlank() } ?: "Unknown Document"
            parseMultipart(fileName)
            
            FileEntity(
                id = entityId,
                name = fileName,
                type = "file",
                mimeType = content.document.mimeType,
                isMultipart = isMultipart,
                partIndex = partIndex,
                originalFileName = originalFileName,
                size = content.document.document.expectedSize.toLong(),
                messageId = this.id.toString(),
                fileId = content.document.document.id.toString(),
                parentId = parentId,
                userId = userId,
                uploadedAt = createdAtMs,
                createdAt = createdAtMs,
                updatedAt = createdAtMs
            )
        }
        is TdApi.MessagePhoto -> {
            // Pick the largest photo size for metadata
            val largestPhoto = content.photo.sizes.maxByOrNull { it.photo.expectedSize }
            FileEntity(
                id = entityId,
                name = "Photo_${this.date}.jpg",
                type = "file",
                mimeType = "image/jpeg",
                size = largestPhoto?.photo?.expectedSize?.toLong() ?: 0L,
                messageId = this.id.toString(),
                fileId = largestPhoto?.photo?.id?.toString(),
                parentId = parentId,
                userId = userId,
                uploadedAt = createdAtMs,
                createdAt = createdAtMs,
                updatedAt = createdAtMs
            )
        }
        is TdApi.MessageVideo -> {
            val fileName = content.video.fileName.takeIf { it.isNotBlank() } ?: "Video_${this.date}.mp4"
            parseMultipart(fileName)
            
            FileEntity(
                id = entityId,
                name = fileName,
                type = "file",
                mimeType = content.video.mimeType,
                isMultipart = isMultipart,
                partIndex = partIndex,
                originalFileName = originalFileName,
                size = content.video.video.expectedSize.toLong(),
                messageId = this.id.toString(),
                fileId = content.video.video.id.toString(),
                parentId = parentId,
                userId = userId,
                uploadedAt = createdAtMs,
                createdAt = createdAtMs,
                updatedAt = createdAtMs
            )
        }
        is TdApi.MessageAudio -> {
            val title = content.audio.title.takeIf { it.isNotBlank() }
            val performer = content.audio.performer.takeIf { it.isNotBlank() }
            val fileName = if (title != null && performer != null) "$performer - $title"
            else title ?: content.audio.fileName.takeIf { it.isNotBlank() } ?: "Audio_${this.date}.mp3"

            parseMultipart(fileName)

            FileEntity(
                id = entityId,
                name = fileName,
                type = "file",
                mimeType = content.audio.mimeType,
                isMultipart = isMultipart,
                partIndex = partIndex,
                originalFileName = originalFileName,
                size = content.audio.audio.expectedSize.toLong(),
                messageId = this.id.toString(),
                fileId = content.audio.audio.id.toString(),
                parentId = parentId,
                userId = userId,
                uploadedAt = createdAtMs,
                createdAt = createdAtMs,
                updatedAt = createdAtMs
            )
        }
        is TdApi.MessageVoiceNote -> {
            FileEntity(
                id = entityId,
                name = "Voice_Note_${this.date}.ogg",
                type = "file",
                mimeType = content.voiceNote.mimeType,
                size = content.voiceNote.voice.expectedSize.toLong(),
                messageId = this.id.toString(),
                fileId = content.voiceNote.voice.id.toString(),
                parentId = parentId,
                userId = userId,
                uploadedAt = createdAtMs,
                createdAt = createdAtMs,
                updatedAt = createdAtMs
            )
        }
        else -> null // Unsupported message type (e.g., text, stickers)
    }
}
