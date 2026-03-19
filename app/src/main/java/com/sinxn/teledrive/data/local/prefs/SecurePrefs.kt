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
    fun clearUserId()

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

    /** Store the preferred storage channel ID (null means default/Saved Messages) */
    fun setStorageChannelId(chatId: Long?)

    /** Get the preferred storage channel ID */
    fun getStorageChannelId(): Long?

    /** Store the preferred storage channel Name */
    fun setStorageChannelName(name: String?)

    /** Get the preferred storage channel Name */
    fun getStorageChannelName(): String?

    /** Whether to require authentication (password or biometric) on app launch */
    fun isAuthOnLaunchEnabled(): Boolean
    fun setAuthOnLaunch(enabled: Boolean)

    /** Whether to use system biometric/device credential for unlock */
    fun isBiometricUnlockEnabled(): Boolean
    fun setBiometricUnlock(enabled: Boolean)
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
        private const val KEY_STORAGE_CHANNEL_ID = "key_storage_channel_id"
        private const val KEY_STORAGE_CHANNEL_NAME = "key_storage_channel_name"
        private const val KEY_AUTH_ON_LAUNCH = "key_auth_on_launch"
        private const val KEY_BIOMETRIC_UNLOCK = "key_biometric_unlock"
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

    override fun clearUserId() {
        sharedPreferences.edit()
            .remove(KEY_USER_ID)
            .apply()
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

    override fun setStorageChannelId(chatId: Long?) {
        val editor = sharedPreferences.edit()
        if (chatId != null) {
            editor.putLong(KEY_STORAGE_CHANNEL_ID, chatId)
        } else {
            editor.remove(KEY_STORAGE_CHANNEL_ID)
        }
        editor.apply()
    }

    override fun getStorageChannelId(): Long? {
        return if (sharedPreferences.contains(KEY_STORAGE_CHANNEL_ID)) {
            sharedPreferences.getLong(KEY_STORAGE_CHANNEL_ID, 0L)
        } else {
            null
        }
    }

    override fun setStorageChannelName(name: String?) {
        val editor = sharedPreferences.edit()
        if (name != null) {
            editor.putString(KEY_STORAGE_CHANNEL_NAME, name)
        } else {
            editor.remove(KEY_STORAGE_CHANNEL_NAME)
        }
        editor.apply()
    }

    override fun getStorageChannelName(): String? {
        return sharedPreferences.getString(KEY_STORAGE_CHANNEL_NAME, null)
    }

    override fun isAuthOnLaunchEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_AUTH_ON_LAUNCH, false)
    }

    override fun setAuthOnLaunch(enabled: Boolean) {
        sharedPreferences.edit()
            .putBoolean(KEY_AUTH_ON_LAUNCH, enabled)
            .apply()
    }

    override fun isBiometricUnlockEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_BIOMETRIC_UNLOCK, false)
    }

    override fun setBiometricUnlock(enabled: Boolean) {
        sharedPreferences.edit()
            .putBoolean(KEY_BIOMETRIC_UNLOCK, enabled)
            .apply()
    }
}
