package com.sinxn.teledrive.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes for the app.
 * Using kotlinx.serialization for compile-time type safety with Navigation Compose.
 */
sealed interface Route {

    @Serializable
    data object Login : Route

    @Serializable
    data class StorageSelection(val isFromSettings: Boolean = false) : Route

    @Serializable
    data class MasterPassword(val isSyncMode: Boolean = false) : Route

    @Serializable
    data object SecurityPreferences : Route

    @Serializable
    data object Home : Route

    @Serializable
    data object Settings : Route

    @Serializable
    data object Backup : Route

    @Serializable
    data class SyncProgress(val isFromSettings: Boolean = false) : Route

    @Serializable
    data class FolderView(val folderId: String) : Route
}
