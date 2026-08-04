package com.sinxn.teledrive.data.repository

import android.content.Context
import android.util.Log
import com.sinxn.teledrive.data.backup.BackupManager
import com.sinxn.teledrive.data.crypto.FileCryptor
import com.sinxn.teledrive.data.telegram.TelegramClient
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
    private val backupManager: BackupManager
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

    suspend fun fetchFirstMessage(chatId: Long): TdApi.Message? {
        val result = telegramClient.send(
            TdApi.GetChatHistory(
                chatId,
                0L,
                -99, // Negative offset to fetch oldest messages
                1,
                false
            )
        )
        return if (result is TdApi.Messages) {
            result.messages.firstOrNull()
        } else {
            null
        }
    }

    suspend fun validateStorageChat(chatId: Long): StorageValidation {
        val chatObj = telegramClient.send(TdApi.GetChat(chatId))
        if (chatObj is TdApi.Chat) {
            val canWrite = chatObj.permissions?.canSendDocuments == true || chatObj.permissions?.canSendBasicMessages == true
            if (!canWrite) {
                return StorageValidation.Invalid
            }
        }

        val firstMessage = fetchFirstMessage(chatId) ?: return StorageValidation.Empty
        val content = firstMessage.content
        return if (content is TdApi.MessageDocument && content.caption.text == METADATA_CAPTION) {
            StorageValidation.ExistingVault
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
            val firstMessage = fetchFirstMessage(chatId) ?: return false
            val content = firstMessage.content as? TdApi.MessageDocument ?: return false
            val fileId = content.document.document.id

            val tdFile = downloadMetadataFileInternal(fileId) ?: return false
            if (tdFile.local.path.isEmpty()) return false

            val fileBytes = File(tdFile.local.path).readBytes()
            val decryptedBytes = fileCryptor.decryptBytesWithPassword(fileBytes, candidatePassword)
            val jsonString = String(decryptedBytes, Charsets.UTF_8)
            jsonString.contains("TELEDRIVE_VAULT_V1")
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
}

sealed interface StorageValidation {
    data object Empty : StorageValidation
    data object ExistingVault : StorageValidation
    data object Invalid : StorageValidation
}
