package com.sinxn.teledrive.data.local.prefs

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface defining secure local storage operations.
 */
interface SecurePrefs {
    fun setHasSession(hasSession: Boolean)
    fun hasSession(): Boolean
    
    fun setUserId(userId: String)
    fun getUserId(): String?
}

/**
 * Implementation of [SecurePrefs] using AndroidX Security Crypto [EncryptedSharedPreferences].
 */
@Singleton
class SecurePrefsImpl @Inject constructor(
    @ApplicationContext context: Context
) : SecurePrefs {

    companion object {
        private const val PREFS_FILE_NAME = "teledrive_secure_prefs"
        private const val KEY_HAS_SESSION = "key_has_session"
        private const val KEY_USER_ID = "key_user_id"
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    override fun setHasSession(hasSession: Boolean) {
        sharedPreferences.edit()
            .putBoolean(KEY_HAS_SESSION, hasSession)
            .apply()
    }

    override fun hasSession(): Boolean {
        return sharedPreferences.getBoolean(KEY_HAS_SESSION, false)
    }

    override fun setUserId(userId: String) {
        sharedPreferences.edit()
            .putString(KEY_USER_ID, userId)
            .apply()
    }

    override fun getUserId(): String? {
        return sharedPreferences.getString(KEY_USER_ID, null)
    }
}
