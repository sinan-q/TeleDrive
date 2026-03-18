package com.sinxn.teledrive.data.crypto

import android.util.Base64
import com.sinxn.teledrive.data.local.prefs.SecurePrefs
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages password-based key derivation and verification for client-side encryption.
 *
 * ## KeePass-Style Architecture
 *
 * The raw master password is held in memory for the session duration; it is never
 * persisted to disk. Each `.enc` file is self-contained: it carries its own random
 * 16-byte salt which, combined with the in-memory password, derives a unique per-file
 * AES-256 key via PBKDF2-HMAC-SHA256 (100,000 iterations).
 *
 * A local verification hash (password + random verification salt → PBKDF2) is stored
 * in SecurePrefs purely to validate the password at app launch. This local hash is
 * **not** used for file encryption — files can be decrypted on any device or with
 * external tools using only the password.
 */
@Singleton
class EncryptionManager @Inject constructor(
    private val securePrefs: SecurePrefs
) {
    companion object {
        private const val PBKDF2_ITERATIONS = 100_000
        private const val KEY_LENGTH_BITS = 256
        private const val SALT_LENGTH_BYTES = 16
        private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    }

    /** The session-only raw password. Null until the user unlocks. */
    @Volatile
    private var _sessionPassword: String? = null

    /** Public read-only accessor. Non-null means the vault is unlocked. */
    val isUnlocked: Boolean get() = _sessionPassword != null

    /**
     * Get the raw session password. Throws if not unlocked.
     * Used by [FileCryptor] to derive per-file AES keys.
     */
    fun getSessionPassword(): String {
        return _sessionPassword
            ?: throw IllegalStateException("Vault is locked. Call unlockWithPassword() first.")
    }

    /**
     * Checks if a master password has been set up previously.
     */
    fun isPasswordSetUp(): Boolean = securePrefs.hasMasterPassword()

    /**
     * First-time setup: stores a verification hash + salt in SecurePrefs
     * and keeps the raw password in RAM for the session.
     */
    fun setupPassword(password: String) {
        val salt = generateSalt()
        val verificationHash = deriveVerificationHash(password, salt)

        securePrefs.setMasterPasswordHash(
            hash = Base64.encodeToString(verificationHash, Base64.NO_WRAP),
            salt = Base64.encodeToString(salt, Base64.NO_WRAP)
        )

        _sessionPassword = password
    }

    /**
     * Subsequent unlocks: derives a verification hash from [password] using the
     * stored salt and checks it against the stored hash.
     *
     * @return true if the password is correct and the vault is now unlocked.
     */
    fun unlockWithPassword(password: String): Boolean {
        val saltB64 = securePrefs.getMasterPasswordSalt() ?: return false
        val storedHashB64 = securePrefs.getMasterPasswordHash() ?: return false

        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val verificationHash = deriveVerificationHash(password, salt)
        val expectedHash = Base64.decode(storedHashB64, Base64.NO_WRAP)

        return if (verificationHash.contentEquals(expectedHash)) {
            _sessionPassword = password
            true
        } else {
            false
        }
    }

    /**
     * Sync mode: sets the session password directly without local verification.
     * Used when the user is syncing to a new device and there is no local verification
     * hash to compare against. The password will be verified implicitly when the first
     * `.enc` file is decrypted (AES-GCM will throw AEADBadTagException if wrong).
     *
     * Also creates a new local verification hash so future unlocks work normally.
     */
    fun setSyncPassword(password: String) {
        // Store the password for this session
        _sessionPassword = password

        // Create fresh local verification data so subsequent opens work normally
        val salt = generateSalt()
        val verificationHash = deriveVerificationHash(password, salt)
        securePrefs.setMasterPasswordHash(
            hash = Base64.encodeToString(verificationHash, Base64.NO_WRAP),
            salt = Base64.encodeToString(salt, Base64.NO_WRAP)
        )
    }

    /**
     * Lock the vault (clear the in-memory password).
     */
    fun lock() {
        _sessionPassword = null
    }

    // ── Private helpers ──────────────────────────────────────────────

    private fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH_BYTES)
        SecureRandom().nextBytes(salt)
        return salt
    }

    /**
     * Derives a verification hash from the password and salt using PBKDF2.
     * This hash is stored locally purely to verify the password at app launch.
     */
    private fun deriveVerificationHash(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        return factory.generateSecret(spec).encoded
    }
}
