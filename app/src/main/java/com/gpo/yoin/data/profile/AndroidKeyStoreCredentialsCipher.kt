package com.gpo.yoin.data.profile

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Production [CredentialsCipher] backed by `AndroidKeyStore`.
 *
 * The 256-bit AES key lives in the device-isolated keystore under the
 * alias [KEY_ALIAS_V1] — never extractable, generated lazily on first
 * encrypt. The `_v1` suffix lets a future cipher rotation introduce a
 * `_v2` key without overwriting users mid-session.
 *
 * GCM uses a 96-bit IV per `Cipher.init` invocation; we extract it via
 * `cipher.iv` and stash it on the [EncryptedBlob] for decrypt.
 *
 * Each `Cipher.getInstance("AES/GCM/NoPadding")` call returns a fresh
 * cipher object — ciphers aren't thread-safe, so callers can hit this
 * cipher from any thread without external synchronisation.
 */
class AndroidKeyStoreCredentialsCipher : CredentialsCipher {

    override fun encrypt(plaintext: ByteArray): EncryptedBlob {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedBlob(iv = cipher.iv, ciphertext = ciphertext)
    }

    override fun decrypt(blob: EncryptedBlob): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, blob.iv),
        )
        return cipher.doFinal(blob.ciphertext)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        keyStore.getKey(KEY_ALIAS_V1, null)?.let { return it as SecretKey }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS_V1,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                // Don't require user authentication — credentials must be
                // accessible during background token refresh, before the
                // user has unlocked the device since reboot.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS_V1 = "yoin_credentials_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_TAG_BITS = 128
    }
}
