package com.sinxn.teledrive.data.repository

import android.content.Context
import android.util.Log
import com.sinxn.teledrive.data.backup.BackupManager
import com.sinxn.teledrive.data.crypto.FileCryptor
import com.sinxn.teledrive.data.telegram.TelegramClient
import com.sinxn.teledrive.data.local.prefs.SecurePrefs
import com.sinxn.teledrive.data.local.prefs.SetupStep
import dagger.hilt.android.qualifiers.ApplicationContext
import org.drinkless.tdlib.TdApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetadataManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telegramClient: TelegramClient,
    private val fileCryptor: FileCryptor,
    private val backupManager: BackupManager,
    private val securePrefs: SecurePrefs
) {

    companion object {
        const val METADATA_CAPTION = "#teledrive_vault_metadata"
    }

    /**
     * Initializes the vault by creating the very first message containing the encrypted metadata.
     * Call this when a user selects a new empty chat for storage.
     * 
     * @return The message ID of the newly created metadata message.
     */
    suspend fun initializeMetadataMessage(chatId: Long): Long {
        val jsonPayload = backupManager.generateBackupJson()
        val encryptedBytes = fileCryptor.encryptBytes(jsonPayload.toByteArray(Charsets.UTF_8))
        
        val tempFile = File(context.cacheDir, "metadata_init.enc")
        tempFile.writeBytes(encryptedBytes)
        
        val inputFile = TdApi.InputFileLocal(tempFile.absolutePath)
        val content = TdApi.InputMessageDocument(
            inputFile,
            null,
            false,
            TdApi.FormattedText(METADATA_CAPTION, emptyArray())
        )
        
        val result = telegramClient.send(TdApi.SendMessage(chatId, null, null, null, null, content))
        tempFile.delete()
        
        return if (result is TdApi.Message) {
            Log.d("MetadataManager", "Initialized metadata at message ID: ${result.id}")
            result.id
        } else {
            error("Failed to initialize metadata message: ${result.javaClass.simpleName}")
        }
    }

    /**
     * Updates an existing metadata message with the latest database state.
     * Uses EditMessageMedia to avoid spamming the chat history.
     */
    suspend fun updateMetadataMessage(chatId: Long, messageId: Long) {
        val jsonPayload = backupManager.generateBackupJson()
        val encryptedBytes = fileCryptor.encryptBytes(jsonPayload.toByteArray(Charsets.UTF_8))
        
        val tempFile = File(context.cacheDir, "metadata_update_${System.currentTimeMillis()}.enc")
        tempFile.writeBytes(encryptedBytes)
        
        val inputFile = TdApi.InputFileLocal(tempFile.absolutePath)
        val content = TdApi.InputMessageDocument(
            inputFile,
            null,
            false,
            TdApi.FormattedText(METADATA_CAPTION, emptyArray())
        )
        
        val result = telegramClient.send(TdApi.EditMessageMedia(chatId, messageId, null, content))
        tempFile.delete()
        
        if (result is TdApi.Error) {
            if (result.code == 420) {
                // FLOOD_WAIT: parse wait time from error message and retry once
                val waitSeconds = result.message
                    .substringAfter("FLOOD_WAIT_", "5")
                    .toIntOrNull() ?: 5
                Log.w("MetadataManager", "FLOOD_WAIT_$waitSeconds for message $messageId, retrying after delay...")
                delay((waitSeconds * 1000L) + 500L)

                // Re-generate with latest DB state (may have changed during wait)
                val retryJson = backupManager.generateBackupJson()
                val retryEncrypted = fileCryptor.encryptBytes(retryJson.toByteArray(Charsets.UTF_8))
                val retryFile = File(context.cacheDir, "metadata_retry_${System.currentTimeMillis()}.enc")
                retryFile.writeBytes(retryEncrypted)

                val retryContent = TdApi.InputMessageDocument(
                    TdApi.InputFileLocal(retryFile.absolutePath),
                    null,
                    false,
                    TdApi.FormattedText(METADATA_CAPTION, emptyArray())
                )

                val retryResult = telegramClient.send(TdApi.EditMessageMedia(chatId, messageId, null, retryContent))
                retryFile.delete()

                if (retryResult is TdApi.Error) {
                    Log.e("MetadataManager", "Retry failed for metadata message $messageId: ${retryResult.message}")
                } else {
                    Log.d("MetadataManager", "Successfully updated metadata message $messageId after FLOOD_WAIT retry")
                }
            } else {
                Log.e("MetadataManager", "Failed to update metadata message $messageId: ${result.message}")
            }
        } else {
            Log.d("MetadataManager", "Successfully updated metadata message $messageId")
        }
    }

    /**
     * Searches for the TeleDrive vault metadata message in the specified chat using SearchChatMessages.
     * Guaranteed to locate the metadata document regardless of chat history length or message offset.
     */
    suspend fun findMetadataMessage(chatId: Long): TdApi.Message? {
        val result = telegramClient.send(
            TdApi.SearchChatMessages(
                chatId,
                null,
                METADATA_CAPTION,
                null,
                0L,
                0,
                1,
                TdApi.SearchMessagesFilterDocument()
            )
        )
        if (result is TdApi.FoundChatMessages) {
            val msg = result.messages.firstOrNull()
            if (msg != null && msg.content is TdApi.MessageDocument) {
                val doc = msg.content as TdApi.MessageDocument
                if (doc.caption.text == METADATA_CAPTION) {
                    return msg
                }
            }
        }
        return null
    }

    @Volatile
    private var cachedEncryptedBytes: ByteArray? = null

    @Volatile
    private var cachedMetadataMessageId: Long? = null

    @Volatile
    private var cachedDecryptedJson: String? = null

    fun clearCache() {
        cachedEncryptedBytes = null
        cachedMetadataMessageId = null
        cachedDecryptedJson = null
    }

    fun getCachedDecryptedJson(): String? = cachedDecryptedJson

    fun getCachedMetadataMessageId(): Long? = cachedMetadataMessageId

    fun consumeCachedDecryptedJson(): String? {
        val payload = cachedDecryptedJson
        cachedDecryptedJson = null
        return payload
    }

    suspend fun validateStorageChat(chatId: Long): StorageValidation {
        val chatObj = telegramClient.send(TdApi.GetChat(chatId))
        if (chatObj is TdApi.Chat) {
            val canWrite = chatObj.permissions?.canSendDocuments == true || chatObj.permissions?.canSendBasicMessages == true
            if (!canWrite) {
                return StorageValidation.Invalid
            }
        }

        // 1. Check if the chat contains an existing TeleDrive vault metadata message
        val metadataMsg = findMetadataMessage(chatId)
        if (metadataMsg != null) {
            cachedMetadataMessageId = metadataMsg.id
            val doc = metadataMsg.content as? TdApi.MessageDocument
            val fileId = doc?.document?.document?.id
            if (fileId != null) {
                val tdFile = downloadMetadataFileInternal(fileId)
                if (tdFile != null && tdFile.local.path.isNotEmpty()) {
                    cachedEncryptedBytes = runCatching { File(tdFile.local.path).readBytes() }.getOrNull()
                }
            }
            return StorageValidation.ExistingVault
        }

        // 2. If no metadata message exists, check if chat has any non-service user messages
        val historyResult = telegramClient.send(
            TdApi.GetChatHistory(chatId, 0L, 0, 100, false)
        )
        val hasUserMessages = historyResult is TdApi.Messages && historyResult.messages.any { msg ->
            msg.content is TdApi.MessageText ||
            msg.content is TdApi.MessageDocument ||
            msg.content is TdApi.MessagePhoto ||
            msg.content is TdApi.MessageVideo ||
            msg.content is TdApi.MessageAudio ||
            msg.content is TdApi.MessageVoiceNote ||
            msg.content is TdApi.MessageSticker ||
            msg.content is TdApi.MessageAnimation
        }

        return if (!hasUserMessages) {
            StorageValidation.Empty
        } else {
            StorageValidation.Invalid
        }
    }

    suspend fun hasExistingVault(chatId: Long): Boolean {
        return validateStorageChat(chatId) is StorageValidation.ExistingVault
    }

    /**
     * Internal helper to initiate TDLib file download and suspend until completion.
     */
    private suspend fun downloadMetadataFileInternal(fileId: Int): TdApi.File? {
        val fileObj = telegramClient.send(TdApi.DownloadFile(fileId, 32, 0, 0, true)) as? TdApi.File ?: return null
        if (fileObj.local.isDownloadingCompleted && fileObj.local.path.isNotEmpty()) {
            return fileObj
        }
        return withTimeoutOrNull(15000L) {
            telegramClient.updates
                .filterIsInstance<TdApi.UpdateFile>()
                .firstOrNull { it.file.id == fileId && it.file.local.isDownloadingCompleted && it.file.local.path.isNotEmpty() }
                ?.file
        }
    }

    suspend fun verifyVaultPassword(chatId: Long, candidatePassword: String): Boolean {
        return try {
            val fileBytes = cachedEncryptedBytes ?: run {
                val firstMessage = findMetadataMessage(chatId) ?: return false
                val content = firstMessage.content as? TdApi.MessageDocument ?: return false
                val fileId = content.document.document.id

                val tdFile = downloadMetadataFileInternal(fileId) ?: return false
                if (tdFile.local.path.isEmpty()) return false

                val bytes = File(tdFile.local.path).readBytes()
                cachedEncryptedBytes = bytes
                cachedMetadataMessageId = firstMessage.id
                bytes
            }

            val decryptedBytes = fileCryptor.decryptBytesWithPassword(fileBytes, candidatePassword)
            val jsonString = String(decryptedBytes, Charsets.UTF_8)
            val isValid = jsonString.contains("TELEDRIVE_VAULT_V1")
            if (isValid) {
                cachedDecryptedJson = jsonString
            }
            isValid
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Imports the decrypted metadata payload into the Room Database
     */
    suspend fun importMetadata(jsonPayload: String) {
        backupManager.importFromBackupJson(jsonPayload)
    }

    /**
     * Synchronously downloads the metadata document and attempts to decrypt it.
     * Throws an error (or AEADBadTagException) if decryption fails.
     */
    suspend fun downloadMetadataFileBlocking(fileId: Int): String {
        val tdFile = downloadMetadataFileInternal(fileId)
        if (tdFile != null && tdFile.local.path.isNotEmpty()) {
            val fileBytes = File(tdFile.local.path).readBytes()
            val decrypted = fileCryptor.decryptBytes(fileBytes)
            return String(decrypted, Charsets.UTF_8)
        } else {
            error("Failed to download metadata file (fileId: $fileId)")
        }
    }

    /**
     * Checks if the vault metadata message exists on Telegram using the stored message ID.
     */
    suspend fun checkStoredMetadataMessage(): CheckStoredMetadataResult {
        val chatId = securePrefs.getStorageChannelId()
        val messageId = securePrefs.getMetadataMessageId()

        if (chatId == null || messageId == null) {
            if (securePrefs.getSetupStep() == SetupStep.COMPLETED) {
                Log.w("MetadataManager", "Setup is completed but storage channel ID or metadata message ID is missing.")
                return CheckStoredMetadataResult.NotFound(messageId, "No stored metadata message ID found")
            }
            Log.d("MetadataManager", "No stored metadata message ID to check (setup not complete).")
            return CheckStoredMetadataResult.SkippedNotConfigured
        }

        Log.d("MetadataManager", "Checking stored metadata message (chatId: $chatId, messageId: $messageId)...")

        return try {
            val result = telegramClient.send(TdApi.GetMessage(chatId, messageId))
            if (result is TdApi.Message) {
                val doc = result.content as? TdApi.MessageDocument
                if (doc != null && doc.caption.text == METADATA_CAPTION) {
                    Log.d("MetadataManager", "Metadata message found successfully (ID: $messageId).")
                    CheckStoredMetadataResult.Found
                } else {
                    Log.w("MetadataManager", "Message $messageId found but content or caption is invalid.")
                    CheckStoredMetadataResult.NotFound(messageId, "Message content is not vault metadata")
                }
            } else {
                val errorMsg = (result as? TdApi.Error)?.message ?: "Message not found"
                Log.w("MetadataManager", "Metadata message $messageId not found: $errorMsg")
                CheckStoredMetadataResult.NotFound(messageId, errorMsg)
            }
        } catch (e: Exception) {
            Log.e("MetadataManager", "Exception checking stored metadata message $messageId", e)
            CheckStoredMetadataResult.NotFound(messageId, e.message ?: "Unknown error")
        }
    }
}

sealed interface StorageValidation {
    data object Empty : StorageValidation
    data object ExistingVault : StorageValidation
    data object Invalid : StorageValidation
}

sealed interface CheckStoredMetadataResult {
    data object Found : CheckStoredMetadataResult
    data class NotFound(val messageId: Long?, val reason: String) : CheckStoredMetadataResult
    data object SkippedNotConfigured : CheckStoredMetadataResult
}
