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
            val fileName = content.document.fileName.trim().takeIf { it.isNotBlank() } ?: "Unknown Document"
            
            // Only accept encrypted files uploaded by TeleDrive
            if (!fileName.endsWith(".enc", ignoreCase = true)) {
                return null
            }
            
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
