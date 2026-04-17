package com.gpo.yoin.data.profile

import com.gpo.yoin.data.model.MediaId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Provider-specific credential blob persisted inside
 * [com.gpo.yoin.data.local.Profile.credentialsJson].
 *
 * Treat this as opaque outside the Profile layer — callers serialise via
 * [ProfileCredentialsCodec].
 */
@Serializable
sealed class ProfileCredentials {

    @Serializable
    @SerialName("subsonic")
    data class Subsonic(
        val serverUrl: String,
        val username: String,
        val password: String,
    ) : ProfileCredentials()

    /**
     * Bundle returned from Spotify's OAuth token endpoint. [expiresAtEpochMs]
     * is the absolute wall-clock deadline — clients should proactively refresh
     * before this minus a small buffer (see `SpotifyApiClient`).
     */
    @Serializable
    @SerialName("spotify")
    data class Spotify(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtEpochMs: Long,
        val scopes: List<String>,
    ) : ProfileCredentials()

    val providerId: String
        get() = when (this) {
            is Subsonic -> MediaId.PROVIDER_SUBSONIC
            is Spotify -> MediaId.PROVIDER_SPOTIFY
        }
}
