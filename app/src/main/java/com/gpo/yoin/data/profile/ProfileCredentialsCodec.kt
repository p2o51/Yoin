package com.gpo.yoin.data.profile

import kotlinx.serialization.json.Json

/**
 * Serialises [ProfileCredentials] to/from the opaque string stored in Room.
 *
 * v1 uses [PlaintextProfileCredentialsCodec] which matches the app's existing
 * security posture (the legacy `server_config` row also stored the password in
 * clear). Swap to an `EncryptedSharedPreferences`-backed impl before shipping
 * to users who expect at-rest protection.
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
        private val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "provider"
        }
    }
}
