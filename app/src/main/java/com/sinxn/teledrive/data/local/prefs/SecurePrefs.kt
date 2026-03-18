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

    /** Returns true if the user has set a master encryption password */
    fun hasMasterPassword(): Boolean

    /** Store a verification hash + salt for the master password */
    fun setMasterPasswordHash(hash: String, salt: String)

    /** Get the stored password verification hash */
    fun getMasterPasswordHash(): String?

    /** Get the stored PBKDF2 salt (Base64-encoded 16 bytes) */
    fun getMasterPasswordSalt(): String?

    /** Clear the master password (used during logout / reset) */
    fun clearMasterPassword()

    /** Store the plaintext master password for Biometric unlock */
    fun setBiometricPassword(password: String)

    /** Retrieve the plaintext master password for Biometric unlock */
    fun getBiometricPassword(): String?

    /** Clear the biometric password */
    fun clearBiometricPassword()
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
        private const val KEY_MASTER_PW_HASH = "key_master_pw_hash"
        private const val KEY_MASTER_PW_SALT = "key_master_pw_salt"
        private const val KEY_BIOMETRIC_PW = "key_biometric_pw"
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

    override fun hasMasterPassword(): Boolean {
        return sharedPreferences.getString(KEY_MASTER_PW_HASH, null) != null
    }

    override fun setMasterPasswordHash(hash: String, salt: String) {
        sharedPreferences.edit()
            .putString(KEY_MASTER_PW_HASH, hash)
            .putString(KEY_MASTER_PW_SALT, salt)
            .apply()
    }

    override fun getMasterPasswordHash(): String? {
        return sharedPreferences.getString(KEY_MASTER_PW_HASH, null)
    }

    override fun getMasterPasswordSalt(): String? {
        return sharedPreferences.getString(KEY_MASTER_PW_SALT, null)
    }

    override fun clearMasterPassword() {
        sharedPreferences.edit()
            .remove(KEY_MASTER_PW_HASH)
            .remove(KEY_MASTER_PW_SALT)
            .remove(KEY_BIOMETRIC_PW)
            .apply()
    }

    override fun setBiometricPassword(password: String) {
        sharedPreferences.edit()
            .putString(KEY_BIOMETRIC_PW, password)
            .apply()
    }

    override fun getBiometricPassword(): String? {
        return sharedPreferences.getString(KEY_BIOMETRIC_PW, null)
    }

    override fun clearBiometricPassword() {
        sharedPreferences.edit()
            .remove(KEY_BIOMETRIC_PW)
            .apply()
    }
}
