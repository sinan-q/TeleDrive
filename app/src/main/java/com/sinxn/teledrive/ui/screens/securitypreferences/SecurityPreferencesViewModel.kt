package com.sinxn.teledrive.ui.screens.securitypreferences

import androidx.lifecycle.ViewModel
import com.sinxn.teledrive.data.crypto.BiometricPasswordStore
import com.sinxn.teledrive.data.crypto.EncryptionManager
import com.sinxn.teledrive.data.local.prefs.SecurePrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.crypto.Cipher
import javax.inject.Inject

@HiltViewModel
class SecurityPreferencesViewModel @Inject constructor(
    private val securePrefs: SecurePrefs,
    private val encryptionManager: EncryptionManager,
    private val biometricPasswordStore: BiometricPasswordStore
) : ViewModel() {

    private val _authOnLaunch = MutableStateFlow(securePrefs.isAuthOnLaunchEnabled())
    val authOnLaunch: StateFlow<Boolean> = _authOnLaunch.asStateFlow()

    private val _biometricUnlock = MutableStateFlow(
        securePrefs.isBiometricUnlockEnabled() && securePrefs.getBiometricUnlockSecret() != null
    )
    val biometricUnlock: StateFlow<Boolean> = _biometricUnlock.asStateFlow()

    fun setAuthOnLaunch(enabled: Boolean) {
        securePrefs.setAuthOnLaunch(enabled)
        _authOnLaunch.value = enabled
        if (!enabled) {
            disableBiometricUnlock()
        }
    }

    fun prepareBiometricEnrollmentCipher(): Cipher? {
        if (!encryptionManager.isUnlocked) return null
        return try {
            biometricPasswordStore.createBiometricEncryptCipher()
        } catch (_: Exception) {
            null
        }
    }

    fun enableBiometricUnlock(cipher: Cipher): Boolean {
        return try {
            val encryptedSecret = biometricPasswordStore.encryptForBiometric(
                encryptionManager.getSessionPassword(),
                cipher
            )
            securePrefs.setBiometricUnlockSecret(encryptedSecret)
            securePrefs.setBiometricUnlock(true)
            _biometricUnlock.value = true
            true
        } catch (_: Exception) {
            false
        }
    }

    fun disableBiometricUnlock() {
        securePrefs.clearBiometricUnlockSecret()
        securePrefs.setBiometricUnlock(false)
        _biometricUnlock.value = false
    }
}
