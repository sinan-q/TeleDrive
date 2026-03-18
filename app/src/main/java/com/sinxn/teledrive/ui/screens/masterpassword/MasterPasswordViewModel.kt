package com.sinxn.teledrive.ui.screens.masterpassword

import androidx.lifecycle.ViewModel
import com.sinxn.teledrive.data.crypto.EncryptionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

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
    private val encryptionManager: EncryptionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<MasterPasswordUiState>(MasterPasswordUiState.Idle)
    val uiState: StateFlow<MasterPasswordUiState> = _uiState.asStateFlow()

    /** True when this is the user's first time (setup mode), false when unlocking */
    val isSetupMode: Boolean get() = !encryptionManager.isPasswordSetUp()

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
            encryptionManager.setupPassword(password)
            _uiState.value = MasterPasswordUiState.Unlocked
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
}
