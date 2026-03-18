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
    data object StorageSelection : Route

    @Serializable
    data object MasterPassword : Route

    @Serializable
    data object Home : Route

    @Serializable
    data object Settings : Route

    @Serializable
    data class FolderView(val folderId: String) : Route
}
