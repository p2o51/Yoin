package com.gpo.yoin.data.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codec round-trip + envelope-validation tests. Uses
 * [InMemoryCredentialsCipher] so we don't need `AndroidKeyStore` —
 * those concerns are tested separately on-device.
 */
class EncryptedProfileCredentialsCodecTest {

    private val codec = EncryptedProfileCredentialsCodec(InMemoryCredentialsCipher())

    @Test
    fun encode_then_decode_round_trips_subsonic_credentials() {
        val original = ProfileCredentials.Subsonic(
            serverUrl = "https://music.example",
            username = "alice",
            password = "hunter2",
        )
        val envelope = codec.encode(original)
        assertTrue(
            "envelope must use the v1 prefix so callers can sniff format",
            envelope.startsWith(EncryptedProfileCredentialsCodec.PREFIX_V1),
        )
        val roundTripped = codec.decode(envelope)
        assertEquals(original, roundTripped)
    }

    @Test
    fun encode_then_decode_round_trips_spotify_credentials() {
        val original = ProfileCredentials.Spotify(
            accessToken = "access-token-value",
            refreshToken = "refresh-token-value",
            expiresAtEpochMs = 1_700_000_000_000L,
            scopes = listOf("user-read-private", "playlist-modify-public"),
            revoked = true,
        )
        val roundTripped = codec.decode(codec.encode(original))
        assertEquals(original, roundTripped)
    }

    @Test
    fun decode_rejects_blob_without_enc_prefix() {
        val plaintextLikeJson = """{"provider":"subsonic","serverUrl":"x","username":"y","password":"z"}"""
        val ex = runCatching { codec.decode(plaintextLikeJson) }.exceptionOrNull()
        assertTrue(
            "non-`enc:` payload must be the legacy codec's job, not this one — sees ${ex?.javaClass?.simpleName}",
            ex is EncryptedCredentialsFormatException,
        )
    }

    @Test
    fun decode_rejects_unsupported_envelope_version_loudly() {
        // Hand-craft a v2-shaped envelope. We don't know v2's format yet,
        // so the codec must refuse rather than feed bytes to v1's cipher.
        val futureEnvelope = "enc:v2:abc:def"
        val ex = runCatching { codec.decode(futureEnvelope) }.exceptionOrNull()
        assertTrue(
            "unsupported envelope version must throw a typed format exception",
            ex is EncryptedCredentialsFormatException,
        )
        assertTrue(
            "exception message should call out the offending version",
            ex?.message?.contains("v2") == true,
        )
    }

    @Test
    fun decode_rejects_malformed_base64() {
        val broken = "enc:v1:!!not-base64!!:also-bad"
        val ex = runCatching { codec.decode(broken) }.exceptionOrNull()
        assertTrue(
            "bad base64 should surface as format exception, not the underlying IllegalArgumentException",
            ex is EncryptedCredentialsFormatException,
        )
    }

    @Test
    fun decode_rejects_wrong_iv_length() {
        // Valid base64 but only 4 bytes — GCM needs 12.
        val badIv = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(4))
        val anyCt = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(8))
        val envelope = "enc:v1:$badIv:$anyCt"
        val ex = runCatching { codec.decode(envelope) }.exceptionOrNull()
        assertTrue(
            "IV with wrong length should be rejected as format error",
            ex is EncryptedCredentialsFormatException,
        )
    }

    @Test
    fun decode_rejects_envelope_missing_separator() {
        val malformed = "enc:v1:onlyOnePart"
        val ex = runCatching { codec.decode(malformed) }.exceptionOrNull()
        assertTrue(
            "envelope missing the iv:ciphertext separator must throw a format exception",
            ex is EncryptedCredentialsFormatException,
        )
    }

    @Test
    fun successive_encrypts_use_different_ivs() {
        val sample = ProfileCredentials.Subsonic("a", "b", "c")
        val first = codec.encode(sample)
        val second = codec.encode(sample)
        assertTrue(
            "two encrypts of the same plaintext must produce different envelopes",
            first != second,
        )
    }
}
