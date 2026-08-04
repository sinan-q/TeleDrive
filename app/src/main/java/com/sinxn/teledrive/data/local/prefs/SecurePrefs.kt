package com.sinxn.teledrive.data.local.prefs

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.edit

enum class SetupStep {
    LOGIN,
    STORAGE_SELECTION,
    MASTER_PASSWORD,
    SYNC_PROGRESS,
    SECURITY_PREFERENCES,
    COMPLETED
}

/**
 * Interface defining secure local storage operations.
 */
interface SecurePrefs {
    fun setHasSession(hasSession: Boolean)
    fun hasSession(): Boolean
    
    fun setUserId(userId: String)
    fun getUserId(): String?
    fun clearUserId()

    /** Tracks the current step in the onboarding/setup flow */
    fun setSetupStep(step: SetupStep)
    fun getSetupStep(): SetupStep

    /** Tracks whether the user is restoring/syncing an existing vault */
    fun setSyncMode(isSyncMode: Boolean)
    fun isSyncMode(): Boolean

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

    /** Store the master password encrypted with a non-biometric Android Keystore key for silent auto-unlock */
    fun setAutoUnlockSecret(secret: String)

    /** Retrieve the encrypted payload used for silent auto-unlock */
    fun getAutoUnlockSecret(): String?

    /** Clear the silent auto-unlock payload */
    fun clearAutoUnlockSecret()

    /** Store the master password encrypted with a biometric-gated Android Keystore key */
    fun setBiometricUnlockSecret(secret: String)

    /** Retrieve the encrypted payload used for biometric unlock */
    fun getBiometricUnlockSecret(): String?

    /** Clear the biometric unlock payload */
    fun clearBiometricUnlockSecret()

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

    /** Store the message ID of the metadata message */
    fun setMetadataMessageId(messageId: Long?)

    /** Get the message ID of the metadata message */
    fun getMetadataMessageId(): Long?
}

/**
 * Implementation of [SecurePrefs] using AndroidX Security Crypto [EncryptedSharedPreferences].
 */
@Singleton
class SecurePrefsImpl @Inject constructor(
    @ApplicationContext context: Context
) : SecurePrefs {

    companion object {
        private const val TAG = "SecurePrefs"
        private const val PREFS_FILE_NAME = "teledrive_secure_prefs"
        private const val KEY_KEYSET_PREFS = "${PREFS_FILE_NAME}__androidx_security_crypto_encrypted_prefs_key_keyset__"
        private const val VALUE_KEYSET_PREFS = "${PREFS_FILE_NAME}__androidx_security_crypto_encrypted_prefs_value_keyset__"
        private const val KEY_HAS_SESSION = "key_has_session"
        private const val KEY_USER_ID = "key_user_id"
        private const val KEY_SETUP_STEP = "key_setup_step"
        private const val KEY_IS_SYNC_MODE = "key_is_sync_mode"
        private const val KEY_MASTER_PW_HASH = "key_master_pw_hash"
        private const val KEY_MASTER_PW_SALT = "key_master_pw_salt"
        private const val KEY_AUTO_UNLOCK_SECRET = "key_auto_unlock_secret"
        private const val KEY_BIOMETRIC_UNLOCK_SECRET = "key_biometric_unlock_secret"
        private const val KEY_STORAGE_CHANNEL_ID = "key_storage_channel_id"
        private const val KEY_STORAGE_CHANNEL_NAME = "key_storage_channel_name"
        private const val KEY_AUTH_ON_LAUNCH = "key_auth_on_launch"
        private const val KEY_BIOMETRIC_UNLOCK = "key_biometric_unlock"
        private const val KEY_METADATA_MESSAGE_ID = "key_metadata_message_id"
    }

    private val sharedPreferences = createEncryptedPrefs(context)

    override fun setHasSession(hasSession: Boolean) {
        sharedPreferences.edit {
            putBoolean(KEY_HAS_SESSION, hasSession)
        }
    }

    override fun hasSession(): Boolean {
        return sharedPreferences.getBoolean(KEY_HAS_SESSION, false)
    }

    override fun setUserId(userId: String) {
        sharedPreferences.edit {
            putString(KEY_USER_ID, userId)
        }
    }

    override fun getUserId(): String? {
        return sharedPreferences.getString(KEY_USER_ID, null)
    }

    override fun clearUserId() {
        sharedPreferences.edit {
            remove(KEY_USER_ID)
        }
    }

    override fun setSetupStep(step: SetupStep) {
        sharedPreferences.edit {
            putString(KEY_SETUP_STEP, step.name)
        }
    }

    override fun getSetupStep(): SetupStep {
        val stepName = sharedPreferences.getString(KEY_SETUP_STEP, null)
        return stepName?.let {
            try {
                SetupStep.valueOf(it)
            } catch (e: IllegalArgumentException) {
                SetupStep.LOGIN
            }
        } ?: if (hasSession() && hasMasterPassword()) {
            SetupStep.COMPLETED
        } else if (hasSession()) {
            SetupStep.STORAGE_SELECTION
        } else {
            SetupStep.LOGIN
        }
    }

    override fun setSyncMode(isSyncMode: Boolean) {
        sharedPreferences.edit {
            putBoolean(KEY_IS_SYNC_MODE, isSyncMode)
        }
    }

    override fun isSyncMode(): Boolean {
        return sharedPreferences.getBoolean(KEY_IS_SYNC_MODE, false)
    }

    override fun hasMasterPassword(): Boolean {
        return sharedPreferences.getString(KEY_MASTER_PW_HASH, null) != null
    }

    override fun setMasterPasswordHash(hash: String, salt: String) {
        sharedPreferences.edit {
            putString(KEY_MASTER_PW_HASH, hash)
                .putString(KEY_MASTER_PW_SALT, salt)
        }
    }

    override fun getMasterPasswordHash(): String? {
        return sharedPreferences.getString(KEY_MASTER_PW_HASH, null)
    }

    override fun getMasterPasswordSalt(): String? {
        return sharedPreferences.getString(KEY_MASTER_PW_SALT, null)
    }

    override fun clearMasterPassword() {
        sharedPreferences.edit {
            remove(KEY_MASTER_PW_HASH)
                .remove(KEY_MASTER_PW_SALT)
                .remove(KEY_AUTO_UNLOCK_SECRET)
                .remove(KEY_BIOMETRIC_UNLOCK_SECRET)
                .remove(KEY_SETUP_STEP)
                .remove(KEY_IS_SYNC_MODE)
        }
    }

    override fun setAutoUnlockSecret(secret: String) {
        sharedPreferences.edit {
            putString(KEY_AUTO_UNLOCK_SECRET, secret)
        }
    }

    override fun getAutoUnlockSecret(): String? {
        return sharedPreferences.getString(KEY_AUTO_UNLOCK_SECRET, null)
    }

    override fun clearAutoUnlockSecret() {
        sharedPreferences.edit()
            .remove(KEY_AUTO_UNLOCK_SECRET)
            .apply()
    }

    override fun setBiometricUnlockSecret(secret: String) {
        sharedPreferences.edit()
            .putString(KEY_BIOMETRIC_UNLOCK_SECRET, secret)
            .apply()
    }

    override fun getBiometricUnlockSecret(): String? {
        return sharedPreferences.getString(KEY_BIOMETRIC_UNLOCK_SECRET, null)
    }

    override fun clearBiometricUnlockSecret() {
        sharedPreferences.edit()
            .remove(KEY_BIOMETRIC_UNLOCK_SECRET)
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

    override fun setMetadataMessageId(messageId: Long?) {
        val editor = sharedPreferences.edit()
        if (messageId != null) {
            editor.putLong(KEY_METADATA_MESSAGE_ID, messageId)
        } else {
            editor.remove(KEY_METADATA_MESSAGE_ID)
        }
        editor.apply()
    }

    override fun getMetadataMessageId(): Long? {
        return if (sharedPreferences.contains(KEY_METADATA_MESSAGE_ID)) {
            sharedPreferences.getLong(KEY_METADATA_MESSAGE_ID, 0L)
        } else {
            null
        }
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return runCatching { buildEncryptedPrefs(context) }
            .getOrElse { firstError ->
                Log.w(TAG, "Encrypted prefs could not be opened. Clearing corrupted secure state.", firstError)
                context.deleteSharedPreferences(PREFS_FILE_NAME)
                context.deleteSharedPreferences(KEY_KEYSET_PREFS)
                context.deleteSharedPreferences(VALUE_KEYSET_PREFS)
                buildEncryptedPrefs(context)
            }
    }

    private fun buildEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
