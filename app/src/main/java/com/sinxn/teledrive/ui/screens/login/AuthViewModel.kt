package com.sinxn.teledrive.ui.screens.login

import androidx.lifecycle.ViewModel
import com.sinxn.teledrive.data.telegram.AuthManager
import com.sinxn.teledrive.data.telegram.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * ViewModel for the Login screen.
 * Delegates to [AuthManager] for TDLib auth operations and exposes
 * the auth state for the Compose UI.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authManager: AuthManager
) : ViewModel() {

    val authState: StateFlow<AuthState> = authManager.authState

    fun sendPhoneNumber(phoneNumber: String) {
        authManager.sendPhoneNumber(phoneNumber)
    }

    fun requestQrCodeLogin() {
        authManager.requestQrCodeLogin()
    }

    fun sendCode(code: String) {
        authManager.sendCode(code)
    }

    fun sendPassword(password: String) {
        authManager.sendPassword(password)
    }

    fun requestPasswordRecovery() {
        authManager.requestPasswordRecovery()
    }

    fun recoverPassword(recoveryCode: String) {
        authManager.recoverPassword(recoveryCode)
    }

    fun logout() {
        authManager.logout()
    }
}
