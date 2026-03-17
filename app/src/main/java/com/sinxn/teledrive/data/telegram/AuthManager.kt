package com.sinxn.teledrive.data.telegram

import android.content.Context
import android.util.Log
import com.sinxn.teledrive.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages TDLib authentication lifecycle.
 *
 * Listens for [TdApi.UpdateAuthorizationState] updates from TDLib and
 * drives the auth flow by sending the correct responses (parameters,
 * phone number, code, password). Exposes [authState] as a StateFlow
 * for the ViewModel/UI.
 */
@Singleton
class AuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telegramClient: TelegramClient
) {
    companion object {
        private const val TAG = "AuthManager"
    }

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /**
     * Starts the TDLib client and begins listening for auth state updates.
     * Call this once from the Application or ViewModel.
     */
    fun startClient() {
        telegramClient.create { update ->
            when (update) {
                is TdApi.UpdateAuthorizationState -> handleAuthState(update.authorizationState)
                else -> { /* Other updates handled elsewhere in later sprints */ }
            }
        }
    }

    private fun handleAuthState(state: TdApi.AuthorizationState) {
        Log.d(TAG, "Auth state changed: ${state::class.simpleName}")

        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                sendTdlibParameters()
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                _authState.value = AuthState.WaitPhoneNumber
            }
            is TdApi.AuthorizationStateWaitCode -> {
                val codeType = when (state.codeInfo.type) {
                    is TdApi.AuthenticationCodeTypeTelegramMessage -> "Telegram"
                    is TdApi.AuthenticationCodeTypeSms -> "SMS"
                    is TdApi.AuthenticationCodeTypeFlashCall -> "Flash Call"
                    is TdApi.AuthenticationCodeTypeMissedCall -> "Missed Call"
                    is TdApi.AuthenticationCodeTypeFragment -> "Fragment"
                    else -> "Code"
                }
                _authState.value = AuthState.WaitCode(codeType)
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                _authState.value = AuthState.WaitPassword(state.passwordHint, state.hasRecoveryEmailAddress)
            }
            is TdApi.AuthorizationStateReady -> {
                _authState.value = AuthState.Ready
            }
            is TdApi.AuthorizationStateLoggingOut -> {
                _authState.value = AuthState.LoggingOut
            }
            is TdApi.AuthorizationStateClosed -> {
                _authState.value = AuthState.Closed
            }
            is TdApi.AuthorizationStateClosing -> {
                // Transitional state, no action needed
            }
            else -> {
                Log.w(TAG, "Unhandled auth state: ${state::class.simpleName}")
            }
        }
    }

    private fun sendTdlibParameters() {
        val params = TdApi.SetTdlibParameters().apply {
            databaseDirectory = context.filesDir.absolutePath + "/tdlib"
            useMessageDatabase = true
            useSecretChats = false
            apiId = BuildConfig.TELEGRAM_API_ID
            apiHash = BuildConfig.TELEGRAM_API_HASH
            systemLanguageCode = "en"
            deviceModel = android.os.Build.MODEL
            systemVersion = android.os.Build.VERSION.RELEASE
            applicationVersion = BuildConfig.VERSION_NAME
        }

        telegramClient.sendAsync(params) { result ->
            if (result is TdApi.Error) {
                Log.e(TAG, "SetTdlibParameters failed: ${result.code} ${result.message}")
                _authState.value = AuthState.Error("TDLib init failed: ${result.message}")
            }
        }
    }

    /**
     * Send the user's phone number to TDLib.
     */
    fun sendPhoneNumber(phoneNumber: String) {
        _authState.value = AuthState.Loading
        val settings = TdApi.PhoneNumberAuthenticationSettings().apply {
            allowFlashCall = false
            allowMissedCall = false
            isCurrentPhoneNumber = false
        }
        telegramClient.sendAsync(
            TdApi.SetAuthenticationPhoneNumber(phoneNumber, settings)
        ) { result ->
            if (result is TdApi.Error) {
                Log.e(TAG, "SetAuthenticationPhoneNumber failed: ${result.message}")
                _authState.value = AuthState.Error(result.message)
            }
            // On success, TDLib sends UpdateAuthorizationState → WaitCode automatically
        }
    }

    /**
     * Send the OTP code to TDLib.
     */
    fun sendCode(code: String) {
        _authState.value = AuthState.Loading
        telegramClient.sendAsync(
            TdApi.CheckAuthenticationCode(code)
        ) { result ->
            if (result is TdApi.Error) {
                Log.e(TAG, "CheckAuthenticationCode failed: ${result.message}")
                _authState.value = AuthState.Error(result.message)
            }
            // On success, TDLib sends UpdateAuthorizationState → Ready (or WaitPassword)
        }
    }

    /**
     * Send the 2FA password to TDLib.
     */
    fun sendPassword(password: String) {
        _authState.value = AuthState.Loading
        telegramClient.sendAsync(
            TdApi.CheckAuthenticationPassword(password)
        ) { result ->
            if (result is TdApi.Error) {
                Log.e(TAG, "CheckAuthenticationPassword failed: ${result.message}")
                // Restore WaitPassword state so the user can try again
                val prevState = authState.value
                val hint = if (prevState is AuthState.WaitPassword) prevState.passwordHint else ""
                val hasEmail = if (prevState is AuthState.WaitPassword) prevState.hasRecoveryEmail else false
                
                // Set error temporarily, then revert to input state so UI shows error and input
                _authState.value = AuthState.Error(result.message)
                _authState.value = AuthState.WaitPassword(hint, hasEmail)
            }
        }
    }

    /**
     * Request a password recovery code via email.
     */
    fun requestPasswordRecovery() {
        _authState.value = AuthState.Loading
        telegramClient.sendAsync(
            TdApi.RequestAuthenticationPasswordRecovery()
        ) { result ->
            if (result is TdApi.Error) {
                Log.e(TAG, "RequestAuthenticationPasswordRecovery failed: ${result.message}")
                _authState.value = AuthState.Error(result.message)
            } else if (result is TdApi.EmailAddressAuthenticationCodeInfo) {
                // TDLib successfully sent the code
                _authState.value = AuthState.WaitPasswordRecoveryCode(result.emailAddressPattern)
            }
        }
    }

    /**
     * Submit the password recovery code sent to the user's email.
     */
    fun recoverPassword(recoveryCode: String) {
        _authState.value = AuthState.Loading
        // Providing empty newPassword enables them to login without 2FA,
        // or they can set it up again later.
        telegramClient.sendAsync(
            TdApi.RecoverAuthenticationPassword(recoveryCode, "", "")
        ) { result ->
            if (result is TdApi.Error) {
                Log.e(TAG, "RecoverAuthenticationPassword failed: ${result.message}")
                _authState.value = AuthState.Error(result.message)
            }
        }
    }

    /**
     * Logout the current user.
     */
    fun logout() {
        telegramClient.sendAsync(TdApi.LogOut()) { result ->
            if (result is TdApi.Error) {
                Log.e(TAG, "LogOut failed: ${result.message}")
            }
        }
    }
}
