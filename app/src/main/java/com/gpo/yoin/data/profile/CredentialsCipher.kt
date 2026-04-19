package com.gpo.yoin.data.profile

/**
 * Authenticated symmetric cipher used to protect on-disk credential
 * blobs. Pulled behind an interface so JVM unit tests can substitute a
 * deterministic fake — `AndroidKeyStore` isn't available off-device and
 * we don't want Robolectric in the unit-test classpath just for this.
 *
 * Production implementations MUST use authenticated encryption (AES/GCM
 * or equivalent). Both encrypt and decrypt may throw — callers wrap and
 * surface to UI as "credentials unavailable" rather than crash.
 */
interface CredentialsCipher {
    fun encrypt(plaintext: ByteArray): EncryptedBlob
    fun decrypt(blob: EncryptedBlob): ByteArray
}

/**
 * Output of [CredentialsCipher.encrypt].
 *
 * @property iv 12-byte GCM nonce (or whatever the cipher's IV size is).
 *   Stored alongside [ciphertext] in the persisted envelope.
 * @property ciphertext Ciphertext with the GCM authentication tag
 *   appended (standard JCE behaviour for `AES/GCM/NoPadding`).
 */
data class EncryptedBlob(
    val iv: ByteArray,
    val ciphertext: ByteArray,
) {
    // Manual equals / hashCode because data classes use array identity
    // for ByteArray, which would surprise tests that round-trip blobs.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedBlob) return false
        return iv.contentEquals(other.iv) && ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int =
        31 * iv.contentHashCode() + ciphertext.contentHashCode()
}
