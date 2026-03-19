package com.sinxn.teledrive.data.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BiometricPasswordStore @Inject constructor() {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val AUTO_UNLOCK_ALIAS = "teledrive.auto_unlock"
        private const val BIOMETRIC_ALIAS = "teledrive.biometric_unlock"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
    }

    fun encryptForAutoUnlock(password: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey(AUTO_UNLOCK_ALIAS, requiresAuthentication = false))
        return serialize(cipher.iv, cipher.doFinal(password.toByteArray(StandardCharsets.UTF_8)))
    }

    fun decryptForAutoUnlock(payload: String): String? {
        return runCatching {
            val (iv, ciphertext) = deserialize(payload)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(AUTO_UNLOCK_ALIAS, requiresAuthentication = false),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            )
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    fun createBiometricEncryptCipher(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey(BIOMETRIC_ALIAS, requiresAuthentication = true))
        return cipher
    }

    fun createBiometricDecryptCipher(payload: String): Cipher {
        val (iv, _) = deserialize(payload)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(BIOMETRIC_ALIAS, requiresAuthentication = true),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        )
        return cipher
    }

    fun encryptForBiometric(password: String, cipher: Cipher): String {
        return serialize(cipher.iv, cipher.doFinal(password.toByteArray(StandardCharsets.UTF_8)))
    }

    fun decryptFromBiometric(payload: String, cipher: Cipher): String {
        val (_, ciphertext) = deserialize(payload)
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    fun clearBiometricKey() {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        keyStore.deleteEntry(BIOMETRIC_ALIAS)
    }

    fun isBiometricKeyInvalidated(error: Throwable): Boolean =
        error is KeyPermanentlyInvalidatedException

    private fun getOrCreateSecretKey(alias: String, requiresAuthentication: Boolean): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)

        if (requiresAuthentication) {
            builder.setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder.setInvalidatedByBiometricEnrollment(true)
            }
        }

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    private fun serialize(iv: ByteArray, ciphertext: ByteArray): String {
        return "${Base64.encodeToString(iv, Base64.NO_WRAP)}:${Base64.encodeToString(ciphertext, Base64.NO_WRAP)}"
    }

    private fun deserialize(payload: String): Pair<ByteArray, ByteArray> {
        val parts = payload.split(':', limit = 2)
        require(parts.size == 2) { "Invalid encrypted payload format" }
        return Base64.decode(parts[0], Base64.NO_WRAP) to Base64.decode(parts[1], Base64.NO_WRAP)
    }
}
