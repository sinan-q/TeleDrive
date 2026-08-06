package com.sinxn.teledrive.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.sinxn.teledrive.data.local.AppDatabase
import com.sinxn.teledrive.data.local.dao.FileDao
import com.sinxn.teledrive.data.local.dao.UserDao
import com.sinxn.teledrive.data.local.entity.FileEntity
import com.sinxn.teledrive.data.local.entity.UserEntity
import com.sinxn.teledrive.data.local.prefs.SecurePrefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManager @Inject constructor(
    private val appDatabase: AppDatabase,
    private val userDao: UserDao,
    private val fileDao: FileDao,
    private val securePrefs: SecurePrefs
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    suspend fun generateBackupJson(exportedAt: Long = System.currentTimeMillis()): String {
        val backup = BackupDocument(
            magicHeader = "TELEDRIVE_VAULT_V1",
            schemaVersion = 1,
            exportedAt = exportedAt,
            users = userDao.getAllUsers().map(UserEntity::toBackup),
            files = fileDao.getAllFiles().map(FileEntity::toBackup),
            preferences = BackupPreferences(
                storageChannelId = securePrefs.getStorageChannelId(),
                storageChannelName = securePrefs.getStorageChannelName(),
                authOnLaunch = securePrefs.isAuthOnLaunchEnabled()
            )
        )
        return json.encodeToString(backup)
    }

    suspend fun exportBackup(context: Context, uri: Uri) {
        val jsonPayload = generateBackupJson()
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
            writer.write(jsonPayload)
        } ?: error("Could not open output stream")
    }

    suspend fun importBackup(context: Context, uri: Uri) {
        val payload = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("Could not read backup file")
        importFromBackupJson(payload)
    }

    suspend fun importFromBackupJson(payload: String) {
        val backup = json.decodeFromString<BackupDocument>(payload)
        require(backup.magicHeader == "TELEDRIVE_VAULT_V1") { "Invalid TeleDrive vault payload signature" }

        appDatabase.withTransaction {
            fileDao.deleteAllFiles()
            userDao.deleteAllUsers()

            if (backup.users.isNotEmpty()) {
                userDao.upsertUsers(backup.users.map(BackupUser::toEntity))
            }
            if (backup.files.isNotEmpty()) {
                fileDao.insertFiles(backup.files.map(BackupFile::toEntity))
            }
        }

        securePrefs.setStorageChannelId(backup.preferences.storageChannelId)
        securePrefs.setStorageChannelName(backup.preferences.storageChannelName)
        securePrefs.setAuthOnLaunch(backup.preferences.authOnLaunch)
        securePrefs.setBiometricUnlock(false)
        securePrefs.clearBiometricUnlockSecret()
        securePrefs.clearAutoUnlockSecret()

        val restoredUserId = backup.users.singleOrNull()?.id
        if (restoredUserId != null) {
            securePrefs.setUserId(restoredUserId)
        } else {
            securePrefs.clearUserId()
        }
    }
}

@Serializable
data class BackupDocument(
    val magicHeader: String = "TELEDRIVE_VAULT_V1",
    val schemaVersion: Int,
    val exportedAt: Long,
    val users: List<BackupUser>,
    val files: List<BackupFile>,
    val preferences: BackupPreferences
)

@Serializable
data class BackupPreferences(
    val storageChannelId: Long?,
    val storageChannelName: String?,
    val authOnLaunch: Boolean
)

@Serializable
data class BackupUser(
    val id: String,
    val tgId: String,
    val username: String,
    val name: String?,
    val phone: String?,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class BackupFile(
    val id: String,
    val name: String,
    val type: String,
    val mimeType: String?,
    val isMultipart: Boolean,
    val partIndex: Int?,
    val totalParts: Int?,
    val originalFileName: String?,
    val size: Long?,
    val messageId: String?,
    val fileId: String?,
    val parentId: String?,
    val userId: String,
    val uploadProgress: Float?,
    val uploadedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val chatId: Long?
)

private fun UserEntity.toBackup() = BackupUser(
    id = id,
    tgId = tgId,
    username = username,
    name = name,
    phone = phone,
    createdAt = createdAt,
    updatedAt = updatedAt
)

private fun BackupUser.toEntity() = UserEntity(
    id = id,
    tgId = tgId,
    username = username,
    name = name,
    phone = phone,
    createdAt = createdAt,
    updatedAt = updatedAt
)

private fun FileEntity.toBackup() = BackupFile(
    id = id,
    name = name,
    type = type,
    mimeType = mimeType,
    isMultipart = isMultipart,
    partIndex = partIndex,
    totalParts = totalParts,
    originalFileName = originalFileName,
    size = size,
    messageId = messageId,
    fileId = fileId,
    parentId = parentId,
    userId = userId,
    uploadProgress = uploadProgress,
    uploadedAt = uploadedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    chatId = chatId
)

private fun BackupFile.toEntity() = FileEntity(
    id = id,
    name = name,
    type = type,
    mimeType = mimeType,
    isMultipart = isMultipart,
    partIndex = partIndex,
    totalParts = totalParts,
    originalFileName = originalFileName,
    size = size,
    messageId = messageId,
    fileId = fileId,
    parentId = parentId,
    userId = userId,
    uploadProgress = uploadProgress,
    uploadedAt = uploadedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    chatId = chatId
)
