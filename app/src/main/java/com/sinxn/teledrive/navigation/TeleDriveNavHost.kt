package com.sinxn.teledrive.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.sinxn.teledrive.ui.screens.home.HomeScreen
import com.sinxn.teledrive.ui.screens.login.LoginScreen
import com.sinxn.teledrive.ui.screens.settings.SettingsScreen
import com.sinxn.teledrive.ui.screens.folder.FolderViewScreen

@Composable
fun TeleDriveNavHost(
    navController: NavHostController,
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
                    navController.navigate(Route.Home) {
                        popUpTo(Route.Login) { inclusive = true }
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
