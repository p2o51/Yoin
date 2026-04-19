package com.gpo.yoin.data.profile

/**
 * Deterministic test fake for [CredentialsCipher].
 *
 * "Encryption" is identity — the codec under test only cares that the
 * cipher round-trips bytes correctly. Each `encrypt` returns a fresh
 * 12-byte IV (incrementing counter) so the produced envelopes are
 * distinguishable, exercising the codec's IV path without depending on
 * the JVM's missing `AndroidKeyStore`.
 */
internal class InMemoryCredentialsCipher : CredentialsCipher {

    private var counter = 0

    override fun encrypt(plaintext: ByteArray): EncryptedBlob {
        val iv = ByteArray(12)
        val n = ++counter
        for (i in 0..3) iv[i] = ((n shr (i * 8)) and 0xFF).toByte()
        return EncryptedBlob(iv = iv, ciphertext = plaintext.copyOf())
    }

    override fun decrypt(blob: EncryptedBlob): ByteArray = blob.ciphertext.copyOf()
}
