package com.sinxn.teledrive.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.sinxn.teledrive.data.local.prefs.SecurePrefs
import com.sinxn.teledrive.data.local.prefs.SetupStep
import com.sinxn.teledrive.ui.screens.home.HomeScreen
import com.sinxn.teledrive.ui.screens.login.LoginScreen
import com.sinxn.teledrive.ui.screens.lock.LockScreen
import com.sinxn.teledrive.ui.screens.settings.SettingsScreen
import com.sinxn.teledrive.ui.screens.folder.FolderViewScreen
import com.sinxn.teledrive.ui.screens.backup.BackupScreen
import com.sinxn.teledrive.ui.screens.storageselection.StorageSelectionScreen
import com.sinxn.teledrive.ui.screens.setupwizard.SetupWizardScreen

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
                    securePrefs.setSetupStep(SetupStep.STORAGE_SELECTION)
                    navController.navigate(Route.SetupWizard) {
                        popUpTo(Route.Login) { inclusive = true }
                    }
                }
            )
        }

        composable<Route.SetupWizard> {
            SetupWizardScreen(
                onSetupComplete = {
                    navController.navigate(Route.Home) {
                        popUpTo<Route.SetupWizard> { inclusive = true }
                    }
                }
            )
        }

        composable<Route.LockScreen> {
            LockScreen(
                onUnlocked = {
                    navController.navigate(Route.Home) {
                        popUpTo<Route.LockScreen> { inclusive = true }
                    }
                }
            )
        }

        // Standalone StorageSelection for Settings → Change Storage flow
        composable<Route.StorageSelection> {
            StorageSelectionScreen(
                onContinue = { isSyncMode ->
                    // After changing storage from settings, go back through the wizard
                    navController.navigate(Route.SetupWizard) {
                        popUpTo<Route.StorageSelection> { inclusive = true }
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
                    securePrefs.setSetupStep(SetupStep.STORAGE_SELECTION)
                    navController.navigate(Route.StorageSelection)
                },
                onNavigateToBackup = {
                    navController.navigate(Route.Backup)
                },
                onLogout = {
                    navController.navigate(Route.Login) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
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
