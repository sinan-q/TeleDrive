package com.sinxn.teledrive.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.toRoute
import com.sinxn.teledrive.data.local.prefs.SecurePrefs
import com.sinxn.teledrive.ui.screens.home.HomeScreen
import com.sinxn.teledrive.ui.screens.login.LoginScreen
import com.sinxn.teledrive.ui.screens.masterpassword.MasterPasswordScreen
import com.sinxn.teledrive.ui.screens.settings.SettingsScreen
import com.sinxn.teledrive.ui.screens.folder.FolderViewScreen
import com.sinxn.teledrive.ui.screens.backup.BackupScreen
import com.sinxn.teledrive.ui.screens.storageselection.StorageSelectionScreen
import com.sinxn.teledrive.ui.screens.securitypreferences.SecurityPreferencesScreen
import com.sinxn.teledrive.ui.screens.sync.SyncProgressScreen

@Composable
fun TeleDriveNavHost(
    navController: NavHostController,
    securePrefs: SecurePrefs,
    isFirstTimeSetup: Boolean,
    modifier: Modifier = Modifier,
    startDestination: Route = Route.Login
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable<Route.Login> {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Route.StorageSelection()) {
                        popUpTo(Route.Login) { inclusive = true }
                    }
                }
            )
        }

        composable<Route.StorageSelection> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.StorageSelection>()
            StorageSelectionScreen(
                isFromSettings = route.isFromSettings,
                onContinue = { isSyncMode ->
                    if (route.isFromSettings) {
                        if (isSyncMode) {
                            navController.navigate(Route.SyncProgress(isFromSettings = true)) {
                                popUpTo<Route.StorageSelection> { inclusive = true }
                            }
                        } else {
                            navController.popBackStack()
                        }
                    } else {
                        navController.navigate(Route.MasterPassword(isSyncMode = isSyncMode)) {
                            popUpTo<Route.StorageSelection> { inclusive = true }
                        }
                    }
                }
            )
        }

        composable<Route.MasterPassword> { backStackEntry ->
            val masterPasswordRoute = backStackEntry.toRoute<Route.MasterPassword>()
            MasterPasswordScreen(
                isSyncMode = masterPasswordRoute.isSyncMode,
                onUnlocked = {
                    if (isFirstTimeSetup) {
                        if (masterPasswordRoute.isSyncMode) {
                            navController.navigate(Route.SyncProgress(isFromSettings = false)) {
                                popUpTo<Route.MasterPassword> { inclusive = true }
                            }
                        } else {
                            navController.navigate(Route.SecurityPreferences) {
                                popUpTo<Route.MasterPassword> { inclusive = true }
                            }
                        }
                    } else {
                        navController.navigate(Route.Home) {
                            popUpTo<Route.MasterPassword> { inclusive = true }
                        }
                    }
                }
            )
        }

        composable<Route.SecurityPreferences> {
            SecurityPreferencesScreen(
                onContinue = {
                    navController.navigate(Route.Home) {
                        popUpTo(Route.SecurityPreferences) { inclusive = true }
                    }
                }
            )
        }

        composable<Route.SyncProgress> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.SyncProgress>()
            SyncProgressScreen(
                onContinue = {
                    if (route.isFromSettings) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(Route.SecurityPreferences) {
                            popUpTo<Route.SyncProgress> { inclusive = true }
                        }
                    }
                }
            )
        }

        composable<Route.Home> {
            HomeScreen(
                onNavigateToFolder = { folderId ->
                    navController.navigate(Route.FolderView(folderId))
                },
                onNavigateToSettings = {
                    navController.navigate(Route.Settings)
                }
            )
        }

        composable<Route.Settings> {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToStorageSelection = {
                    navController.navigate(Route.StorageSelection(isFromSettings = true))
                },
                onNavigateToBackup = {
                    navController.navigate(Route.Backup)
                }
            )
        }

        composable<Route.Backup> {
            BackupScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable<Route.FolderView> { backStackEntry ->
            val folderView = backStackEntry.toRoute<Route.FolderView>()
            FolderViewScreen(
                folderId = folderView.folderId,
                onNavigateToFolder = { childFolderId ->
                    navController.navigate(Route.FolderView(childFolderId))
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
