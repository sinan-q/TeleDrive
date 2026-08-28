package com.sinxn.teledrive.data.telegram.mapper

import com.sinxn.teledrive.data.local.entity.FileEntity
import com.sinxn.teledrive.data.repository.MetadataManager
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

    return when (val content = this.content) {
        is TdApi.MessageDocument -> {
            // Ignore vault metadata document messages
            if (content.caption?.text == MetadataManager.METADATA_CAPTION) {
                return null
            }

            val rawFileName = content.document.fileName.trim().takeIf { it.isNotBlank() } ?: "Unknown Document"
            
            // Exclude metadata files or non-encrypted files
            if (rawFileName.startsWith("metadata_") && rawFileName.endsWith(".enc", ignoreCase = true)) {
                return null
            }

            // Only accept encrypted files uploaded by TeleDrive
            if (!rawFileName.endsWith(".enc", ignoreCase = true)) {
                return null
            }
            
            val cleanName = if (rawFileName.endsWith(".enc", ignoreCase = true)) {
                rawFileName.dropLast(4)
            } else rawFileName

            val matchResult = partRegex.find(cleanName)
            val isMultipart: Boolean
            val partIndex: Int?
            val userFacingName: String

            if (matchResult != null) {
                isMultipart = true
                userFacingName = matchResult.groupValues[1]
                partIndex = matchResult.groupValues[2].toIntOrNull()
            } else {
                isMultipart = false
                userFacingName = cleanName
                partIndex = null
            }
            
            FileEntity(
                id = entityId,
                name = userFacingName,
                type = "file",
                mimeType = content.document.mimeType,
                isMultipart = isMultipart,
                partIndex = partIndex,
                originalFileName = rawFileName,
                size = content.document.document.expectedSize.toLong(),
                messageId = this.id.toString(),
                fileId = content.document.document.id.toString(),
                parentId = parentId,
                userId = userId,
                uploadedAt = createdAtMs,
                createdAt = createdAtMs,
                updatedAt = createdAtMs,
                chatId = this.chatId
            )
        }
        // Ignore all other content types as TeleDrive uploads everything as encrypted documents
        is TdApi.MessagePhoto -> null
        is TdApi.MessageVideo -> null
        is TdApi.MessageAudio -> null
        is TdApi.MessageVoiceNote -> null
        else -> null // Unsupported message type (e.g., text, stickers)
    }
}
