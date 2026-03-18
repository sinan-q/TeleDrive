package com.sinxn.teledrive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.sinxn.teledrive.data.local.prefs.SecurePrefs
import com.sinxn.teledrive.data.telegram.AuthManager
import com.sinxn.teledrive.navigation.Route
import com.sinxn.teledrive.navigation.TeleDriveNavHost
import com.sinxn.teledrive.ui.theme.TeleDriveTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var securePrefs: SecurePrefs

    @Inject
    lateinit var authManager: AuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Ensure TDLib client is created and listening for updates regardless of starting screen
        authManager.startClient()
        
        enableEdgeToEdge()
        setContent {
            TeleDriveTheme {
                val navController = rememberNavController()
                val startDest = if (securePrefs.hasSession()) Route.MasterPassword else Route.Login

                TeleDriveNavHost(
                    navController = navController,
                    startDestination = startDest
                )
            }
        }
    }
}