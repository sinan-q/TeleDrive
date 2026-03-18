package com.sinxn.teledrive.data.crypto

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles AES-256-GCM file encryption and decryption using streaming APIs.
 *
 * ## Binary Format (.enc files)
 *
 * ```
 * ┌──────────────┬──────────────┬───────────────────────────────────┐
 * │ 16-byte Salt │ 12-byte IV   │ AES-GCM Ciphertext + 16-byte Tag │
 * └──────────────┴──────────────┴───────────────────────────────────┘
 * ```
 *
 * - **Salt**: Used with PBKDF2 to derive the AES key from the master password.
 *             Stored per-file so each `.enc` file is independently decryptable.
 * - **IV (Nonce)**: 12-byte random nonce for GCM. Must be unique per encryption.
 * - **Ciphertext + Tag**: Standard AES-GCM output. The 16-byte authentication tag
 *                          is appended automatically by the JCE GCM implementation.
 *
 * ## External Decryption (Python Example)
 *
 * ```python
 * from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
 * from cryptography.hazmat.primitives.hashes import SHA256
 * from cryptography.hazmat.primitives.ciphers.aead import AESGCM
 *
 * with open("file.enc", "rb") as f:
 *     salt = f.read(16)
 *     iv   = f.read(12)
 *     ct   = f.read()
 *
 * kdf = PBKDF2HMAC(algorithm=SHA256(), length=32, salt=salt, iterations=100000)
 * key = kdf.derive(password.encode())
 * plaintext = AESGCM(key).decrypt(iv, ct, None)
 * ```
 */
@Singleton
class FileCryptor @Inject constructor(
    private val encryptionManager: EncryptionManager
) {
    companion object {
        private const val AES_ALGORITHM = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val SALT_LENGTH_BYTES = 16
        private const val PBKDF2_ITERATIONS = 100_000
        private const val KEY_LENGTH_BITS = 256
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"

        /** Size of the fixed header prepended to every .enc file */
        const val HEADER_SIZE = SALT_LENGTH_BYTES + GCM_IV_LENGTH_BYTES  // 28 bytes
    }

    /**
     * Encrypts [inputFile] and writes the result to [outputFile].
     *
     * The master password's derived key is used, but a **fresh per-file salt** is generated
     * so that each encrypted file is independently decryptable with just the password.
     *
     * @return the output file (for chaining)
     */
    fun encryptFile(inputFile: File, outputFile: File): File {
        val masterPassword = encryptionManager.getDerivedKey()

        // Generate fresh per-file salt and IV
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { random.nextBytes(it) }

        // Derive a per-file AES key from the master key + per-file salt
        val perFileKey = derivePerFileKey(masterPassword, salt)
        val secretKey = SecretKeySpec(perFileKey, "AES")

        val cipher = Cipher.getInstance(AES_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

        FileOutputStream(outputFile).use { fos ->
            // Write the fixed header: Salt + IV
            fos.write(salt)
            fos.write(iv)

            // Stream the plaintext through the cipher into the output
            CipherOutputStream(fos, cipher).use { cos ->
                FileInputStream(inputFile).use { fis ->
                    fis.copyTo(cos, bufferSize = 8192)
                }
            }
        }

        return outputFile
    }

    /**
     * Decrypts [inputFile] (a .enc file) and writes the plaintext to [outputFile].
     *
     * Reads the 28-byte header (salt + IV) from the encrypted file, derives the AES key
     * using the master password, and streams the decrypted content out.
     *
     * @return the output file (for chaining)
     * @throws javax.crypto.AEADBadTagException if the password is wrong or data was tampered with
     */
    fun decryptFile(inputFile: File, outputFile: File): File {
        val masterPassword = encryptionManager.getDerivedKey()

        FileInputStream(inputFile).use { fis ->
            // Read the fixed header
            val salt = ByteArray(SALT_LENGTH_BYTES)
            val iv = ByteArray(GCM_IV_LENGTH_BYTES)
            fis.readExactly(salt)
            fis.readExactly(iv)

            // Derive the per-file AES key
            val perFileKey = derivePerFileKey(masterPassword, salt)
            val secretKey = SecretKeySpec(perFileKey, "AES")

            val cipher = Cipher.getInstance(AES_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

            // Stream the ciphertext through the cipher into the output
            FileOutputStream(outputFile).use { fos ->
                CipherInputStream(fis, cipher).use { cis ->
                    cis.copyTo(fos, bufferSize = 8192)
                }
            }
        }

        return outputFile
    }

    /**
     * Encrypts raw bytes in memory (useful for small metadata payloads).
     *
     * @return ByteArray in the format: [16B salt][12B iv][ciphertext + tag]
     */
    fun encryptBytes(plaintext: ByteArray): ByteArray {
        val masterPassword = encryptionManager.getDerivedKey()

        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { random.nextBytes(it) }

        val perFileKey = derivePerFileKey(masterPassword, salt)
        val secretKey = SecretKeySpec(perFileKey, "AES")

        val cipher = Cipher.getInstance(AES_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

        val ciphertext = cipher.doFinal(plaintext)
        return salt + iv + ciphertext
    }

    /**
     * Decrypts a ByteArray that was produced by [encryptBytes].
     *
     * @return the original plaintext bytes
     */
    fun decryptBytes(encrypted: ByteArray): ByteArray {
        val masterPassword = encryptionManager.getDerivedKey()

        val salt = encrypted.copyOfRange(0, SALT_LENGTH_BYTES)
        val iv = encrypted.copyOfRange(SALT_LENGTH_BYTES, HEADER_SIZE)
        val ciphertext = encrypted.copyOfRange(HEADER_SIZE, encrypted.size)

        val perFileKey = derivePerFileKey(masterPassword, salt)
        val secretKey = SecretKeySpec(perFileKey, "AES")

        val cipher = Cipher.getInstance(AES_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

        return cipher.doFinal(ciphertext)
    }

    // ── Private helpers ──────────────────────────────────────────────

    /**
     * Derives a per-file AES-256 key from the master key bytes and the per-file salt
     * using PBKDF2. This ensures each file has a unique encryption key even though
     * the master password is the same.
     */
    private fun derivePerFileKey(masterKeyBytes: ByteArray, salt: ByteArray): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(
            android.util.Base64.encodeToString(masterKeyBytes, android.util.Base64.NO_WRAP).toCharArray(),
            salt,
            PBKDF2_ITERATIONS,
            KEY_LENGTH_BITS
        )
        val factory = javax.crypto.SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        return factory.generateSecret(spec).encoded
    }

    /**
     * Extension to read exactly N bytes from an InputStream, throwing if EOF is reached early.
     */
    private fun InputStream.readExactly(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val bytesRead = read(buffer, offset, buffer.size - offset)
            if (bytesRead == -1) throw java.io.EOFException("Unexpected end of encrypted file")
            offset += bytesRead
        }
    }
}
