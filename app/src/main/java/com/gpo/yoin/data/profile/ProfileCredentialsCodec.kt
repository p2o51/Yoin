package com.gpo.yoin.data.profile

import kotlinx.serialization.json.Json

/**
 * Serialises [ProfileCredentials] to/from the opaque string persisted by
 * the credentials store.
 *
 * Two implementations:
 * - [PlaintextProfileCredentialsCodec] — read-only path used by the
 *   one-shot startup migration to thaw legacy inline blobs that lived
 *   directly in `profiles.credentialsJson`. NOT used for normal
 *   read/write traffic after Batch 3D.
 * - [EncryptedProfileCredentialsCodec] — owns the `enc:v1:` envelope
 *   used for blobs persisted on disk by
 *   [FileBackedProfileCredentialsStore].
 */
interface ProfileCredentialsCodec {
    fun encode(credentials: ProfileCredentials): String
    fun decode(blob: String): ProfileCredentials
}

class PlaintextProfileCredentialsCodec(
    private val json: Json = DEFAULT_JSON,
) : ProfileCredentialsCodec {

    override fun encode(credentials: ProfileCredentials): String =
        json.encodeToString(ProfileCredentials.serializer(), credentials)

    override fun decode(blob: String): ProfileCredentials =
        json.decodeFromString(ProfileCredentials.serializer(), blob)

    companion object {
        internal val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "provider"
        }
    }
}
