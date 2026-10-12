package com.gpo.yoin.data.profile

import com.gpo.yoin.data.remote.applemusic.AppleMusicApiException
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiFailure
import com.gpo.yoin.data.repository.SubsonicException
import com.gpo.yoin.data.source.spotify.SpotifyAuthConfig
import com.gpo.yoin.data.source.spotify.SpotifyAuthException
import retrofit2.HttpException

/**
 * Whether an account whose stored credentials read as these (null: they
 * couldn't be read) needs its owner to sign in again before it works. The one
 * rule behind every "needs reconnect" Yoin shows — Settings' account cards,
 * the setup page's Spotify reconnect, Home's avatar dot and Reconnect
 * ([ProfileManager.activeNeedsReconnect]):
 *  - null — the secret file is gone (a restore from backup doesn't carry it)
 *    or won't decrypt (AndroidKeyStore's key didn't come along:
 *    [SpotifyProviderStatus.ReconnectReason.BackupRestored]);
 *  - Spotify refused the refresh token (`invalid_grant`, kept as
 *    [ProfileCredentials.Spotify.revoked]:
 *    [SpotifyProviderStatus.ReconnectReason.CredentialsRevoked]);
 *  - Spotify's grant predates a scope the app now requires
 *    ([SpotifyAuthConfig.REQUIRED_SCOPES]).
 *
 * Local state only: nothing is asked of any server.
 */
fun ProfileCredentials?.needsReconnect(): Boolean = when (this) {
    null -> true
    is ProfileCredentials.Spotify ->
        revoked || SpotifyAuthConfig.REQUIRED_SCOPES.any { required -> required !in scopes }
    else -> false
}

/**
 * Whether a ping that failed with this is the service turning the account's
 * credentials down — only its owner can fix that (its page: Subsonic's
 * credentials again, Spotify's or Apple Music's sign-in) — rather than a
 * server that didn't answer: offline, a timeout, a server restarting, a
 * 5xx. [ProfileManager.retryActiveSource] publishes the source on anything
 * else, as a cold start does without a ping, and Home and Library tell their
 * own network errors.
 *  - Subsonic: 40 wrong username or password, 41 token auth refused (LDAP),
 *    42–44 OpenSubsonic's auth mechanism / API key errors, or the server's
 *    HTTP 401 / 403.
 *  - Spotify: the refresh token refused (`invalid_grant`), or a 401 / 403 on
 *    `/me` after the refresh.
 *  - Apple Music: no music user token, or a 403 on the account's storefront.
 */
internal fun Throwable.refusesCredentials(): Boolean = when (this) {
    is SubsonicException -> code in SUBSONIC_AUTH_ERRORS
    is HttpException -> code() == HTTP_UNAUTHORIZED || code() == HTTP_FORBIDDEN
    is SpotifyAuthException -> isRefreshTokenRevoked || code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN
    is AppleMusicApiException ->
        failure == AppleMusicApiFailure.UserAuthorizationRequired || failure == AppleMusicApiFailure.AccessDenied
    else -> false
}

private val SUBSONIC_AUTH_ERRORS = 40..44
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
