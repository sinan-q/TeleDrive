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
    val kind: StorageLocationKind
)

fun TdApi.Chat.toStorageLocationOption(): StorageLocationOption {
    val kind = when (val chatType = type) {
        is TdApi.ChatTypeSupergroup -> {
            if (chatType.isChannel) StorageLocationKind.Channel else StorageLocationKind.Group
        }
        else -> StorageLocationKind.Group
    }

    return StorageLocationOption(
        id = id,
        title = title,
        kind = kind
    )
}
