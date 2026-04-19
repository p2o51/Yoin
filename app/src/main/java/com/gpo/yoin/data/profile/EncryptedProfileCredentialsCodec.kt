package com.gpo.yoin.data.profile

import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * Persists [ProfileCredentials] as a versioned envelope:
 *
 * ```
 * enc:v1:<base64-iv>:<base64-ciphertext-and-tag>
 * ```
 *
 * - `enc:` is the unique sigil. Plaintext JSON starts with `{`, never
 *   `enc:`, so callers can sniff `startsWith("enc:")` to distinguish
 *   legacy inline blobs from encrypted files.
 * - `v1` reserves room for a future cipher / format rotation; the
 *   decoder rejects unrecognised versions loudly so we don't silently
 *   feed bytes to the wrong cipher.
 *
 * Encrypt always produces an `enc:v1:` envelope. Decrypt **only**
 * accepts envelopes with that exact prefix — anything else is the
 * caller's mistake (they should have routed legacy inline blobs through
 * [PlaintextProfileCredentialsCodec] during the migration window).
 *
 * Base64 is the URL-safe-without-padding flavour to keep the envelope
 * grep-friendly and avoid `=` characters in stack traces.
 */
class EncryptedProfileCredentialsCodec(
    private val cipher: CredentialsCipher,
    private val json: Json = PlaintextProfileCredentialsCodec.DEFAULT_JSON,
) : ProfileCredentialsCodec {

    override fun encode(credentials: ProfileCredentials): String {
        val plaintext = json.encodeToString(ProfileCredentials.serializer(), credentials)
            .toByteArray(Charsets.UTF_8)
        val blob = cipher.encrypt(plaintext)
        return buildString {
            append(PREFIX_V1)
            append(B64_ENCODER.encodeToString(blob.iv))
            append(SEPARATOR)
            append(B64_ENCODER.encodeToString(blob.ciphertext))
        }
    }

    override fun decode(blob: String): ProfileCredentials {
        val payload = stripV1Prefix(blob)
            ?: throw EncryptedCredentialsFormatException(
                "Expected envelope to start with `$PREFIX_V1` but got `${blob.take(VERSION_PROBE_LENGTH)}`",
            )
        val parts = payload.split(SEPARATOR)
        if (parts.size != 2) {
            throw EncryptedCredentialsFormatException(
                "Envelope must contain exactly one `$SEPARATOR` separator after the version prefix",
            )
        }
        val (ivPart, ctPart) = parts
        val iv = runCatching { B64_DECODER.decode(ivPart) }
            .getOrElse {
                throw EncryptedCredentialsFormatException("IV is not valid base64", it)
            }
        val ciphertext = runCatching { B64_DECODER.decode(ctPart) }
            .getOrElse {
                throw EncryptedCredentialsFormatException("Ciphertext is not valid base64", it)
            }
        if (iv.size != EXPECTED_IV_BYTES) {
            throw EncryptedCredentialsFormatException(
                "IV must be $EXPECTED_IV_BYTES bytes (GCM), got ${iv.size}",
            )
        }

        val plaintext = cipher.decrypt(EncryptedBlob(iv = iv, ciphertext = ciphertext))
        return json.decodeFromString(
            ProfileCredentials.serializer(),
            plaintext.toString(Charsets.UTF_8),
        )
    }

    private fun stripV1Prefix(blob: String): String? = when {
        blob.startsWith(PREFIX_V1) -> blob.removePrefix(PREFIX_V1)
        // Other `enc:vN` prefixes are reserved — fail loudly rather than
        // silently misinterpret. Callers shouldn't be feeding random
        // strings to this codec.
        blob.startsWith(GENERIC_ENC_PREFIX) -> {
            // Strip the `enc:` sigil first; substringBefore(":") on the
            // raw blob would return "enc", losing the version we want
            // to surface in the error.
            val afterSigil = blob.removePrefix(GENERIC_ENC_PREFIX)
            val versionTag = afterSigil.substringBefore(SEPARATOR, missingDelimiterValue = afterSigil)
            throw EncryptedCredentialsFormatException(
                "Unsupported envelope version `$versionTag` (this build only knows `v1`)",
            )
        }
        else -> null
    }

    companion object {
        const val PREFIX_V1 = "enc:v1:"
        private const val GENERIC_ENC_PREFIX = "enc:"
        private const val SEPARATOR = ":"
        private const val EXPECTED_IV_BYTES = 12 // GCM nonce
        private const val VERSION_PROBE_LENGTH = 16
        // URL-safe, no padding — friendlier for logs / grep, no `=` chars.
        private val B64_ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        private val B64_DECODER: Base64.Decoder = Base64.getUrlDecoder()
    }
}

/**
 * Thrown when [EncryptedProfileCredentialsCodec] receives a malformed
 * envelope. Distinct from cipher failures (those propagate directly
 * from the underlying [CredentialsCipher]) so callers can tell
 * "the bytes were never going to decrypt" from "decryption itself
 * blew up — probably a missing key or restore-from-backup".
 */
class EncryptedCredentialsFormatException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
