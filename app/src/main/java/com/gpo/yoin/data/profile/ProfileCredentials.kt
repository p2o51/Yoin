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
     *
     * @property revoked Set when a refresh attempt is rejected with
     *   `error: "invalid_grant"` (user revoked access from the Spotify
     *   dashboard, scope-bump invalidated the token, etc). The token is
     *   effectively dead; UI surfaces a Reconnect affordance instead of
     *   silently 401-ing on every API call. Cleared by overwriting
     *   credentials via the OAuth reconnect flow. Defaults to false so
     *   existing persisted blobs migrate without a Room change.
     */
    @Serializable
    @SerialName("spotify")
    data class Spotify(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtEpochMs: Long,
        val scopes: List<String>,
        val revoked: Boolean = false,
    ) : ProfileCredentials()

    val providerId: String
        get() = when (this) {
            is Subsonic -> MediaId.PROVIDER_SUBSONIC
            is Spotify -> MediaId.PROVIDER_SPOTIFY
        }
}
