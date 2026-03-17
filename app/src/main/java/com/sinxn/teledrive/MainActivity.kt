package com.sinxn.teledrive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.sinxn.teledrive.navigation.TeleDriveNavHost
import com.sinxn.teledrive.ui.theme.TeleDriveTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TeleDriveTheme {
                val navController = rememberNavController()
                TeleDriveNavHost(navController = navController)
            }
        }
    }
}