package com.sinxn.teledrive.ui.screens.storageselection

import org.drinkless.tdlib.TdApi

enum class StorageLocationKind {
    SavedMessages,
    Group,
    Channel
}

data class StorageLocationOption(
    val id: Long,
    val title: String,
    val kind: StorageLocationKind,
    val photoFileId: Int? = null,
    val photoPath: String? = null,
    val hasWritePermission: Boolean = true
)

fun TdApi.Chat.toStorageLocationOption(): StorageLocationOption {
    val kind = when (val chatType = type) {
        is TdApi.ChatTypeSupergroup -> {
            if (chatType.isChannel) StorageLocationKind.Channel else StorageLocationKind.Group
        }
        else -> StorageLocationKind.Group
    }

    val canWrite = permissions?.canSendDocuments == true || permissions?.canSendBasicMessages == true

    return StorageLocationOption(
        id = id,
        title = title,
        kind = kind,
        photoFileId = photo?.small?.id,
        photoPath = photo?.small?.local
            ?.takeIf { it.isDownloadingCompleted && it.path.isNotEmpty() }
            ?.path,
        hasWritePermission = canWrite
    )
}
