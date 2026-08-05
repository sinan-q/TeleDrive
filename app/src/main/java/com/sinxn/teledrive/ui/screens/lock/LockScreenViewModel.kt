package com.sinxn.teledrive.ui.screens.lock

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

sealed interface LockScreenUiState {
    data object Idle : LockScreenUiState
    data object Unlocked : LockScreenUiState
    data class Error(val message: String) : LockScreenUiState
}

@HiltViewModel
class LockScreenViewModel @Inject constructor(
    private val encryptionManager: EncryptionManager,
    private val biometricPasswordStore: BiometricPasswordStore,
    private val securePrefs: SecurePrefs
) : ViewModel() {

    private val _uiState = MutableStateFlow<LockScreenUiState>(LockScreenUiState.Idle)
    val uiState: StateFlow<LockScreenUiState> = _uiState.asStateFlow()

    private val _showBiometricPrompt = MutableStateFlow(
        securePrefs.getBiometricUnlockSecret() != null && securePrefs.isBiometricUnlockEnabled()
    )
    val showBiometricPrompt: StateFlow<Boolean> = _showBiometricPrompt.asStateFlow()

    fun unlockWithPassword(password: String) {
        if (password.isBlank()) {
            _uiState.value = LockScreenUiState.Error("Password cannot be empty")
            return
        }

        try {
            val success = encryptionManager.unlockWithPassword(password)
            if (success) {
                persistAutoUnlockSecret(password)
                _uiState.value = LockScreenUiState.Unlocked
            } else {
                _uiState.value = LockScreenUiState.Error("Incorrect password")
            }
        } catch (e: Exception) {
            _uiState.value = LockScreenUiState.Error("Decryption error: ${e.message}")
        }
    }

    fun prepareBiometricUnlockCipher(): Cipher? {
        val payload = securePrefs.getBiometricUnlockSecret() ?: return null
        return try {
            biometricPasswordStore.createBiometricDecryptCipher(payload)
        } catch (e: Exception) {
            securePrefs.clearBiometricUnlockSecret()
            securePrefs.setBiometricUnlock(false)
            fallbackToPassword()
            null
        }
    }

    fun unlockWithBiometric(cipher: Cipher?) {
        val payload = securePrefs.getBiometricUnlockSecret()
        if (payload == null || cipher == null) {
            fallbackToPassword()
            return
        }

        try {
            val password = biometricPasswordStore.decryptFromBiometric(payload, cipher)
            unlockWithPassword(password)
        } catch (e: Exception) {
            if (biometricPasswordStore.isBiometricKeyInvalidated(e)) {
                securePrefs.clearBiometricUnlockSecret()
                securePrefs.setBiometricUnlock(false)
            }
            _uiState.value = LockScreenUiState.Error("Biometric unlock is no longer available. Use your password instead.")
            fallbackToPassword()
        }
    }

    fun fallbackToPassword() {
        _showBiometricPrompt.value = false
    }

    fun clearError() {
        _uiState.value = LockScreenUiState.Idle
    }

    private fun persistAutoUnlockSecret(password: String) {
        val encryptedSecret = biometricPasswordStore.encryptForAutoUnlock(password)
        securePrefs.setAutoUnlockSecret(encryptedSecret)
    }
}
