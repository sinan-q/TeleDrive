package com.sinxn.teledrive

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.sinxn.teledrive.data.crypto.BiometricPasswordStore
import com.sinxn.teledrive.data.crypto.EncryptionManager
import com.sinxn.teledrive.data.local.prefs.SecurePrefs
import com.sinxn.teledrive.data.local.prefs.SetupStep
import com.sinxn.teledrive.data.telegram.AuthManager
import com.sinxn.teledrive.navigation.Route
import com.sinxn.teledrive.navigation.TeleDriveNavHost
import com.sinxn.teledrive.ui.theme.TeleDriveTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var securePrefs: SecurePrefs

    @Inject
    lateinit var authManager: AuthManager

    @Inject
    lateinit var encryptionManager: EncryptionManager

    @Inject
    lateinit var biometricPasswordStore: BiometricPasswordStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Ensure TDLib client is created and listening for updates regardless of starting screen
        authManager.startClient()
        
        enableEdgeToEdge()
        setContent {
            TeleDriveTheme {
                val navController = rememberNavController()

                val hasSession = securePrefs.hasSession()
                val hasMasterPassword = encryptionManager.isPasswordSetUp()
                val authOnLaunch = securePrefs.isAuthOnLaunchEnabled()
                val setupStep = securePrefs.getSetupStep()

                val startDest: Route
                val isFirstTimeSetup: Boolean

                when {
                    // First install or logged out: no session yet -> Login flow
                    !hasSession || setupStep == SetupStep.LOGIN -> {
                        startDest = Route.Login
                        isFirstTimeSetup = true
                    }
                    // Setup in progress: any incomplete step -> Setup Wizard
                    setupStep == SetupStep.STORAGE_SELECTION ||
                    setupStep == SetupStep.MASTER_PASSWORD ||
                    setupStep == SetupStep.SYNC_PROGRESS ||
                    setupStep == SetupStep.SECURITY_PREFERENCES -> {
                        startDest = Route.SetupWizard
                        isFirstTimeSetup = true
                    }
                    // Setup completed: has master password + auth disabled -> auto-unlock, go to Home
                    hasMasterPassword && !authOnLaunch -> {
                        val storedSecret = securePrefs.getAutoUnlockSecret()
                        val didUnlock = if (storedSecret != null && !encryptionManager.isUnlocked) {
                            val decryptedPassword = biometricPasswordStore.decryptForAutoUnlock(storedSecret)
                            decryptedPassword != null && encryptionManager.unlockWithPassword(decryptedPassword)
                        } else {
                            encryptionManager.isUnlocked
                        }

                        if (didUnlock) {
                            startDest = Route.Home
                            isFirstTimeSetup = false
                        } else {
                            securePrefs.clearAutoUnlockSecret()
                            startDest = Route.LockScreen
                            isFirstTimeSetup = false
                        }
                    }
                    // Setup completed: has master password + auth enabled -> show LockScreen
                    hasMasterPassword -> {
                        startDest = Route.LockScreen
                        isFirstTimeSetup = false
                    }
                    // Fallback
                    else -> {
                        startDest = Route.SetupWizard
                        isFirstTimeSetup = true
                    }
                }

                TeleDriveNavHost(
                    navController = navController,
                    securePrefs = securePrefs,
                    isFirstTimeSetup = isFirstTimeSetup,
                    startDestination = startDest
                )
            }
        }
    }
}
