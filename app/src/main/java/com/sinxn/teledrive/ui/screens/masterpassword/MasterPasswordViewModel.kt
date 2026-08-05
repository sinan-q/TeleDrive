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
    private val metadataManager: com.sinxn.teledrive.data.repository.MetadataManager,
    private val telegramClient: com.sinxn.teledrive.data.telegram.TelegramClient,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** True when the user chose "Sync existing files" in Storage Selection */
    val isSyncMode: Boolean = savedStateHandle.get<Boolean>("isSyncMode") ?: securePrefs.isSyncMode()

    private val _uiState = MutableStateFlow<MasterPasswordUiState>(MasterPasswordUiState.Idle)
    val uiState: StateFlow<MasterPasswordUiState> = _uiState.asStateFlow()

    private val _showBiometricPrompt = MutableStateFlow(
        securePrefs.getBiometricUnlockSecret() != null && securePrefs.isBiometricUnlockEnabled()
    )
    val showBiometricPrompt: StateFlow<Boolean> = _showBiometricPrompt.asStateFlow()

    /** True when in setup mode or re-configuring vault storage */
    val isSetupMode: Boolean
        get() = !encryptionManager.isPasswordSetUp() ||
                securePrefs.getSetupStep() == com.sinxn.teledrive.data.local.prefs.SetupStep.MASTER_PASSWORD ||
                securePrefs.getSetupStep() == com.sinxn.teledrive.data.local.prefs.SetupStep.STORAGE_SELECTION

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
        if (!isSyncMode && password != confirmPassword) {
            _uiState.value = MasterPasswordUiState.Error("Passwords do not match")
            return
        }

        _uiState.value = MasterPasswordUiState.Deriving
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                if (isSyncMode) {
                    val configuredChatId = securePrefs.getStorageChannelId()
                    val targetChatId = if (configuredChatId != null) {
                        configuredChatId
                    } else {
                        val activeUserId = securePrefs.getUserId()
                        if (activeUserId != null) {
                            val chat = telegramClient.send(
                                org.drinkless.tdlib.TdApi.CreatePrivateChat(activeUserId.toLong(), true)
                            ) as? org.drinkless.tdlib.TdApi.Chat
                            chat?.id
                        } else null
                    }

                    if (targetChatId == null) {
                        _uiState.value = MasterPasswordUiState.Error("Could not resolve storage chat")
                        return@launch
                    }

                    val isValidPassword = metadataManager.verifyVaultPassword(targetChatId, password)
                    if (!isValidPassword) {
                        _uiState.value = MasterPasswordUiState.Error("Incorrect master password for this vault. Please try again.")
                        return@launch
                    }

                    encryptionManager.setSyncPassword(password)
                } else {
                    encryptionManager.setupPassword(password)
                }

                persistAutoUnlockSecret(password)
                if (isSetupMode) {
                    if (isSyncMode) {
                        securePrefs.setSetupStep(com.sinxn.teledrive.data.local.prefs.SetupStep.SYNC_PROGRESS)
                    } else {
                        securePrefs.setSetupStep(com.sinxn.teledrive.data.local.prefs.SetupStep.SECURITY_PREFERENCES)
                    }
                }
                _uiState.value = MasterPasswordUiState.Unlocked

                if (!isSyncMode) {
                    securePrefs.getUserId()?.let { userId ->
                        fileRepository.initializeVault(userId)
                    }
                }
            } catch (e: Exception) {
                _uiState.value = MasterPasswordUiState.Error("Failed to set up encryption: ${e.message}")
            }
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
