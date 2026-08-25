package com.sinxn.teledrive.data.crypto

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PushbackInputStream
import java.io.InputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
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
 * - **Salt**: Used with the raw master password via PBKDF2 to derive a unique
 *             per-file AES-256 key. Each file gets a fresh random salt.
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
        private const val GCM_IV_PREFIX_BYTES = 8
        private const val SALT_LENGTH_BYTES = 16
        private const val PBKDF2_ITERATIONS = 100_000
        private const val KEY_LENGTH_BITS = 256
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private val CHUNKED_MAGIC = byteArrayOf('T'.code.toByte(), 'D'.code.toByte(), 'C'.code.toByte(), '2'.code.toByte())
        private const val CHUNK_PLAINTEXT_SIZE_BYTES = 4 * 1024 * 1024

        /** Size of the fixed header prepended to every .enc file */
        const val HEADER_SIZE = SALT_LENGTH_BYTES + GCM_IV_LENGTH_BYTES  // 28 bytes
    }

    /**
     * Encrypts [inputFile] and writes the result to [outputFile].
     *
     * The raw master password is combined with a fresh random salt via PBKDF2
     * to derive a unique AES-256 key for this file.
     *
     * @return the output file (for chaining)
     */
    fun encryptFile(inputFile: File, outputFile: File): File {
        FileInputStream(inputFile).use { fis ->
            encryptStream(fis, outputFile)
        }

        return outputFile
    }

    /**
     * Encrypts an [InputStream] directly into [outputFile], avoiding an intermediate plaintext file.
     */
    fun encryptStream(inputStream: InputStream, outputFile: File): Long {
        val password = encryptionManager.getSessionPassword()

        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH_BYTES).also { random.nextBytes(it) }
        val ivPrefix = ByteArray(GCM_IV_PREFIX_BYTES).also { random.nextBytes(it) }

        val perFileKey = derivePerFileKey(password, salt)
        val secretKey = SecretKeySpec(perFileKey, "AES")

        var bytesReadTotal = 0L
        DataOutputStream(FileOutputStream(outputFile).buffered()).use { output ->
            output.write(CHUNKED_MAGIC)
            output.write(salt)
            output.write(ivPrefix)
            output.writeInt(CHUNK_PLAINTEXT_SIZE_BYTES)

            val buffer = ByteArray(CHUNK_PLAINTEXT_SIZE_BYTES)
            var chunkIndex = 0
            while (true) {
                val bytesRead = inputStream.readChunk(buffer)
                if (bytesRead <= 0) break

                val cipher = Cipher.getInstance(AES_ALGORITHM)
                cipher.init(
                    Cipher.ENCRYPT_MODE,
                    secretKey,
                    GCMParameterSpec(GCM_TAG_LENGTH_BITS, buildChunkIv(ivPrefix, chunkIndex))
                )

                val ciphertext = cipher.doFinal(buffer, 0, bytesRead)
                output.writeInt(ciphertext.size)
                output.write(ciphertext)
                bytesReadTotal += bytesRead.toLong()
                chunkIndex++
            }
        }

        return bytesReadTotal
    }

    /**
     * Decrypts [inputFile] (a .enc file) and writes the plaintext to [outputFile].
     *
     * Reads the 28-byte header (salt + IV) from the encrypted file, derives the AES key
     * using the raw master password, and streams the decrypted content out.
     *
     * @return the output file (for chaining)
     * @throws javax.crypto.AEADBadTagException if the password is wrong or data was tampered with
     */
    /**
     * Decrypts [inputFile] (a .enc file) and writes the plaintext to [outputFile].
     *
     * Reads the 28-byte header (salt + IV) from the encrypted file, derives the AES key
     * using the raw master password, and streams the decrypted content out.
     *
     * @return the output file (for chaining)
     * @throws javax.crypto.AEADBadTagException if the password is wrong or data was tampered with
     */
    fun decryptFile(inputFile: File, outputFile: File): File {
        FileInputStream(inputFile).use { fis ->
            FileOutputStream(outputFile).buffered().use { output ->
                decryptStreamToOutput(fis, output)
            }
        }

        return outputFile
    }

    /**
     * Decrypts an encrypted [InputStream] directly into an [OutputStream] without writing plaintext to disk.
     */
    fun decryptStreamToOutput(inputStream: InputStream, outputStream: java.io.OutputStream) {
        val password = encryptionManager.getSessionPassword()
        PushbackInputStream(inputStream, CHUNKED_MAGIC.size).use { input ->
            val prefix = ByteArray(CHUNKED_MAGIC.size)
            input.readExactly(prefix)
            if (prefix.contentEquals(CHUNKED_MAGIC)) {
                decryptChunkedStream(input, outputStream, password)
            } else {
                input.unread(prefix)
                decryptLegacyStream(input, outputStream, password)
            }
        }
    }

    /**
     * Encrypts raw bytes in memory (useful for small metadata payloads).
     *
     * @return ByteArray in the format: [16B salt][12B iv][ciphertext + tag]
     */
    fun encryptBytes(plaintext: ByteArray): ByteArray {
        val password = encryptionManager.getSessionPassword()

        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { random.nextBytes(it) }

        val perFileKey = derivePerFileKey(password, salt)
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
        val password = encryptionManager.getSessionPassword()
        return decryptBytesWithPassword(encrypted, password)
    }

    fun decryptBytesWithPassword(encrypted: ByteArray, password: String): ByteArray {
        val salt = encrypted.copyOfRange(0, SALT_LENGTH_BYTES)
        val iv = encrypted.copyOfRange(SALT_LENGTH_BYTES, HEADER_SIZE)
        val ciphertext = encrypted.copyOfRange(HEADER_SIZE, encrypted.size)

        val perFileKey = derivePerFileKey(password, salt)
        val secretKey = SecretKeySpec(perFileKey, "AES")

        val cipher = Cipher.getInstance(AES_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

        return cipher.doFinal(ciphertext)
    }

    // ── Private helpers ──────────────────────────────────────────────

    /**
     * Derives a per-file AES-256 key from the raw master password and the per-file salt
     * using PBKDF2-HMAC-SHA256 with 100,000 iterations.
     *
     * This is the only key derivation step. Each .enc file is fully self-contained:
     * you only need the password + the salt embedded in the file header to decrypt it.
     */
    private fun derivePerFileKey(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(
            password.toCharArray(),
            salt,
            PBKDF2_ITERATIONS,
            KEY_LENGTH_BITS
        )
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        return factory.generateSecret(spec).encoded
    }

    private fun decryptLegacyStream(inputStream: InputStream, outputStream: java.io.OutputStream, password: String) {
        val salt = ByteArray(SALT_LENGTH_BYTES)
        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        inputStream.readExactly(salt)
        inputStream.readExactly(iv)

        val perFileKey = derivePerFileKey(password, salt)
        val secretKey = SecretKeySpec(perFileKey, "AES")
        val cipher = Cipher.getInstance(AES_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

        CipherInputStream(inputStream, cipher).use { cis ->
            cis.copyTo(outputStream, bufferSize = 8192)
        }
    }

    private fun decryptChunkedStream(inputStream: InputStream, outputStream: java.io.OutputStream, password: String) {
        val dataInput = DataInputStream(inputStream.buffered())
        val salt = ByteArray(SALT_LENGTH_BYTES)
        val ivPrefix = ByteArray(GCM_IV_PREFIX_BYTES)
        dataInput.readFully(salt)
        dataInput.readFully(ivPrefix)
        val chunkSize = dataInput.readInt()
        require(chunkSize > 0) { "Invalid encrypted file chunk size" }

        val perFileKey = derivePerFileKey(password, salt)
        val secretKey = SecretKeySpec(perFileKey, "AES")
        var chunkIndex = 0

        while (true) {
            val ciphertextSize = try {
                dataInput.readInt()
            } catch (_: EOFException) {
                break
            }
            require(ciphertextSize > 0) { "Invalid encrypted chunk size" }

            val ciphertext = ByteArray(ciphertextSize)
            dataInput.readFully(ciphertext)

            val cipher = Cipher.getInstance(AES_ALGORITHM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey,
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, buildChunkIv(ivPrefix, chunkIndex))
            )

            val plaintext = cipher.doFinal(ciphertext)
            outputStream.write(plaintext)
            chunkIndex++
        }
    }

    private fun buildChunkIv(prefix: ByteArray, chunkIndex: Int): ByteArray {
        return prefix + byteArrayOf(
            (chunkIndex ushr 24).toByte(),
            (chunkIndex ushr 16).toByte(),
            (chunkIndex ushr 8).toByte(),
            chunkIndex.toByte()
        )
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

    private fun InputStream.readChunk(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = read(buffer, total, buffer.size - total)
            if (read == -1) break
            total += read
        }
        return total
    }
}
