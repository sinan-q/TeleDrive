package com.sinxn.teledrive.ui.screens.masterpassword

import androidx.lifecycle.ViewModel
import javax.crypto.Cipher
import com.sinxn.teledrive.data.crypto.BiometricPasswordStore
import com.sinxn.teledrive.data.crypto.EncryptionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import com.sinxn.teledrive.data.local.prefs.SecurePrefs
import androidx.lifecycle.SavedStateHandle
import com.sinxn.teledrive.data.repository.FileRepository
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

sealed interface MasterPasswordUiState {
    /** Waiting for user input */
    data object Idle : MasterPasswordUiState

    /** Password is being derived (PBKDF2 takes ~200ms) */
    data object Deriving : MasterPasswordUiState

    /** Password accepted, vault unlocked */
    data object Unlocked : MasterPasswordUiState

    /** Incorrect password */
    data class Error(val message: String) : MasterPasswordUiState
}

@HiltViewModel
class MasterPasswordViewModel @Inject constructor(
    private val biometricPasswordStore: BiometricPasswordStore,
    private val encryptionManager: EncryptionManager,
    private val securePrefs: SecurePrefs,
    private val fileRepository: FileRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** True when the user chose "Sync existing files" in Storage Selection */
    val isSyncMode: Boolean = savedStateHandle.get<Boolean>("isSyncMode") ?: false

    private val _uiState = MutableStateFlow<MasterPasswordUiState>(MasterPasswordUiState.Idle)
    val uiState: StateFlow<MasterPasswordUiState> = _uiState.asStateFlow()

    private val _showBiometricPrompt = MutableStateFlow(
        securePrefs.getBiometricUnlockSecret() != null && securePrefs.isBiometricUnlockEnabled()
    )
    val showBiometricPrompt: StateFlow<Boolean> = _showBiometricPrompt.asStateFlow()

    /** True when this is the user's first time (setup mode), false when unlocking */
    val isSetupMode: Boolean get() = !encryptionManager.isPasswordSetUp()

    /** True when in sync mode (first time but syncing existing files) */
    val showSyncWarning: Boolean get() = isSyncMode && isSetupMode

    /**
     * First-time setup: set a new master password.
     */
    fun createPassword(password: String, confirmPassword: String) {
        if (password.length < 4) {
            _uiState.value = MasterPasswordUiState.Error("Password must be at least 4 characters")
            return
        }
        if (password != confirmPassword) {
            _uiState.value = MasterPasswordUiState.Error("Passwords do not match")
            return
        }

        _uiState.value = MasterPasswordUiState.Deriving
        try {
            if (isSyncMode) {
                // Sync mode: set the password without local verification check
                // (password will be verified implicitly when first file is decrypted)
                encryptionManager.setSyncPassword(password)
            } else {
                encryptionManager.setupPassword(password)
            }
            persistAutoUnlockSecret(password)
            _uiState.value = MasterPasswordUiState.Unlocked

            // For new vaults (non-sync), immediately upload the initial empty metadata
            // to Telegram so other devices can detect the vault exists.
            if (!isSyncMode) {
                viewModelScope.launch {
                    securePrefs.getUserId()?.let { userId ->
                        fileRepository.initializeVault(userId)
                    }
                }
            }
        } catch (e: Exception) {
            _uiState.value = MasterPasswordUiState.Error("Failed to set up encryption: ${e.message}")
        }
    }

    /**
     * Subsequent unlock: verify password.
     */
    fun unlockWithPassword(password: String) {
        if (password.isBlank()) {
            _uiState.value = MasterPasswordUiState.Error("Password cannot be empty")
            return
        }

        _uiState.value = MasterPasswordUiState.Deriving
        try {
            val success = encryptionManager.unlockWithPassword(password)
            if (success) {
                persistAutoUnlockSecret(password)
                _uiState.value = MasterPasswordUiState.Unlocked
            } else {
                _uiState.value = MasterPasswordUiState.Error("Incorrect password")
            }
        } catch (e: Exception) {
            _uiState.value = MasterPasswordUiState.Error("Decryption error: ${e.message}")
        }
    }

    fun clearError() {
        _uiState.value = MasterPasswordUiState.Idle
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
            _uiState.value = MasterPasswordUiState.Error("Biometric unlock is no longer available. Use your password instead.")
            fallbackToPassword()
        }
    }

    fun fallbackToPassword() {
        _showBiometricPrompt.value = false
    }

    private fun persistAutoUnlockSecret(password: String) {
        val encryptedSecret = biometricPasswordStore.encryptForAutoUnlock(password)
        securePrefs.setAutoUnlockSecret(encryptedSecret)
    }
}
