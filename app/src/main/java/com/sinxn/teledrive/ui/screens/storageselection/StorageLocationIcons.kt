package com.sinxn.teledrive.ui.screens.storageselection

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Groups
import androidx.compose.ui.graphics.vector.ImageVector

fun StorageLocationKind.icon(): ImageVector = when (this) {
    StorageLocationKind.SavedMessages -> Icons.Default.Bookmark
    StorageLocationKind.Group -> Icons.Default.Groups
    StorageLocationKind.Channel -> Icons.Default.Campaign
}

fun StorageLocationKind.label(): String = when (this) {
    StorageLocationKind.SavedMessages -> "Your private cloud"
    StorageLocationKind.Group -> "Group/Chat"
    StorageLocationKind.Channel -> "Channel"
}
