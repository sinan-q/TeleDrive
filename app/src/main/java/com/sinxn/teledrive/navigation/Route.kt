package com.sinxn.teledrive.navigation

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes for the app.
 * Using kotlinx.serialization for compile-time type safety with Navigation Compose.
 */
@Keep
sealed interface Route {

    @Keep
    @Serializable
    data object Login : Route

    @Keep
    @Serializable
    data object StorageSelection : Route

    @Keep
    @Serializable
    data object SetupWizard : Route

    @Keep
    @Serializable
    data object LockScreen : Route

    @Keep
    @Serializable
    data object Home : Route

    @Keep
    @Serializable
    data object Settings : Route

    @Keep
    @Serializable
    data object Backup : Route

    @Keep
    @Serializable
    data class FolderView(val folderId: String) : Route
}
