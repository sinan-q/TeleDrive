package com.sinxn.teledrive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.sinxn.teledrive.data.local.prefs.SecurePrefs
import com.sinxn.teledrive.navigation.Route
import com.sinxn.teledrive.navigation.TeleDriveNavHost
import com.sinxn.teledrive.ui.theme.TeleDriveTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var securePrefs: SecurePrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TeleDriveTheme {
                val navController = rememberNavController()
                val startDest = if (securePrefs.hasSession()) Route.Home else Route.Login

                TeleDriveNavHost(
                    navController = navController,
                    startDestination = startDest
                )
            }
        }
    }
}