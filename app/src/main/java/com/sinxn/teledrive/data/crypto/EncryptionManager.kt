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
 * Uses PBKDF2WithHmacSHA256 to derive a 256-bit AES key from the user's master password.
 * The derived key is held in RAM only for the duration of the session; it is never persisted.
 *
 * Binary format for encrypted files:
 *   [16 bytes PBKDF2 Salt] + [12 bytes GCM IV] + [Encrypted Bytes] + [16 bytes GCM Auth Tag]
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

    /** The session-only derived key bytes. Null until the user unlocks. */
    @Volatile
    private var _derivedKeyBytes: ByteArray? = null

    /** Public read-only accessor. Non-null means the vault is unlocked. */
    val isUnlocked: Boolean get() = _derivedKeyBytes != null

    /** Get the derived key bytes. Throws if not unlocked. */
    fun getDerivedKey(): ByteArray {
        return _derivedKeyBytes
            ?: throw IllegalStateException("Vault is locked. Call unlockWithPassword() first.")
    }

    /**
     * Checks if a master password has been set up previously.
     */
    fun isPasswordSetUp(): Boolean = securePrefs.hasMasterPassword()

    /**
     * First-time setup: derives a key from [password], stores a verification hash + salt
     * in SecurePrefs, and keeps the derived key in RAM for the session.
     */
    fun setupPassword(password: String) {
        val salt = generateSalt()
        val keyBytes = deriveKey(password, salt)

        // Store a verification hash (hash the derived key itself) so we can verify future logins
        val verificationHash = hashForVerification(keyBytes)

        securePrefs.setMasterPasswordHash(
            hash = Base64.encodeToString(verificationHash, Base64.NO_WRAP),
            salt = Base64.encodeToString(salt, Base64.NO_WRAP)
        )

        _derivedKeyBytes = keyBytes
    }

    /**
     * Subsequent unlocks: derives the key from [password] using the stored salt,
     * then verifies it matches the stored hash.
     *
     * @return true if the password is correct and the vault is now unlocked.
     */
    fun unlockWithPassword(password: String): Boolean {
        val saltB64 = securePrefs.getMasterPasswordSalt() ?: return false
        val storedHashB64 = securePrefs.getMasterPasswordHash() ?: return false

        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val keyBytes = deriveKey(password, salt)

        val verificationHash = hashForVerification(keyBytes)
        val expectedHash = Base64.decode(storedHashB64, Base64.NO_WRAP)

        return if (verificationHash.contentEquals(expectedHash)) {
            _derivedKeyBytes = keyBytes
            true
        } else {
            false
        }
    }

    /**
     * Lock the vault (clear the in-memory key).
     */
    fun lock() {
        _derivedKeyBytes?.fill(0)
        _derivedKeyBytes = null
    }

    // ── Private helpers ──────────────────────────────────────────────

    private fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH_BYTES)
        SecureRandom().nextBytes(salt)
        return salt
    }

    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        return factory.generateSecret(spec).encoded
    }

    /**
     * Produces a secondary hash of the derived key bytes for password verification purposes.
     * We re-derive once more with a fixed "verify" salt so the stored hash cannot be used
     * to reconstruct the actual encryption key.
     */
    private fun hashForVerification(keyBytes: ByteArray): ByteArray {
        val verifySalt = "teledrive_verify".toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(
            Base64.encodeToString(keyBytes, Base64.NO_WRAP).toCharArray(),
            verifySalt,
            1, // single iteration — just a hash, not key stretching
            256
        )
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        return factory.generateSecret(spec).encoded
    }
}
