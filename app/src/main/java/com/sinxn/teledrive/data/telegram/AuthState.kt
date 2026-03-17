package com.sinxn.teledrive.data.telegram

/**
 * Sealed class representing all TDLib authorization states the UI needs to react to.
 */
sealed class AuthState {
    /** Initial state — TDLib client not yet started. */
    data object Idle : AuthState()

    /** TDLib is waiting for the user's phone number. */
    data object WaitPhoneNumber : AuthState()

    /** TDLib is waiting for the authentication code (SMS or Telegram message). */
    data class WaitCode(
        val codeType: String = "SMS" // Human-readable description of code delivery
    ) : AuthState()

    /** TDLib is waiting for the 2FA password. */
    data class WaitPassword(
        val passwordHint: String = "",
        val hasRecoveryEmail: Boolean = false
    ) : AuthState()

    /** TDLib is waiting for the password recovery code sent to the email. */
    data class WaitPasswordRecoveryCode(
        val emailPattern: String
    ) : AuthState()

    /** User is fully authenticated. */
    data object Ready : AuthState()

    /** TDLib is logging out. */
    data object LoggingOut : AuthState()

    /** TDLib client is closed. */
    data object Closed : AuthState()

    /** Something is loading (sending phone, code, etc.). */
    data object Loading : AuthState()

    /** An error occurred. */
    data class Error(val message: String) : AuthState()
}
