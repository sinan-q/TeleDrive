package com.sinxn.teledrive

import android.app.Application
import android.util.Log
import com.sinxn.teledrive.data.crypto.BiometricPasswordStore
import com.sinxn.teledrive.data.crypto.EncryptionManager
import com.sinxn.teledrive.data.local.prefs.SecurePrefs
import com.sinxn.teledrive.data.telegram.AuthManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TeleDriveApp : Application() {

    @Inject
    lateinit var securePrefs: SecurePrefs

    @Inject
    lateinit var authManager: AuthManager

    @Inject
    lateinit var encryptionManager: EncryptionManager

    @Inject
    lateinit var biometricPasswordStore: BiometricPasswordStore

    override fun onCreate() {
        super.onCreate()

        // Start TDLib client on app launch if a user session exists
        // (Ensures TDLib is alive even when initialized by background components like DocumentsProvider)
        if (securePrefs.hasSession()) {
            Log.d("TeleDriveApp", "User session found. Initializing TDLib client...")
            authManager.startClient()
        }

        // Attempt vault auto-unlock if configured and enabled
        if (encryptionManager.isPasswordSetUp() && !securePrefs.isAuthOnLaunchEnabled()) {
            val storedSecret = securePrefs.getAutoUnlockSecret()
            if (storedSecret != null && !encryptionManager.isUnlocked) {
                try {
                    val decryptedPassword = biometricPasswordStore.decryptForAutoUnlock(storedSecret)
                    if (decryptedPassword != null) {
                        val unlocked = encryptionManager.unlockWithPassword(decryptedPassword)
                        Log.d("TeleDriveApp", "Vault auto-unlock attempted. Result: $unlocked")
                    }
                } catch (e: Exception) {
                    Log.w("TeleDriveApp", "Auto-unlock failed during app start", e)
                }
            }
        }
    }
}
