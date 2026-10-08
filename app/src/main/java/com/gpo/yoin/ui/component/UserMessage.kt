package com.gpo.yoin.ui.component

import com.gpo.yoin.R
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiException
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiFailure
import com.gpo.yoin.data.repository.SubsonicException
import com.gpo.yoin.data.source.spotify.SpotifyAuthException
import com.gpo.yoin.data.source.spotify.SpotifyRateLimitException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Maps a data-layer failure onto one short, actionable line for error
 * surfaces (e.g. [DetailErrorState]).
 *
 * Connectivity failures collapse into a friendly "check your connection"
 * story; provider-typed failures keep their specific story (Subsonic servers
 * return human-readable messages, Spotify distinguishes auth from rate
 * limits); everything else returns the screen-supplied [fallback] rather
 * than leaking a raw exception message.
 */
fun Throwable.toUserMessage(
    fallback: String,
    resources: android.content.res.Resources? = null,
): String = when (this) {
    // Connectivity — specific subtypes first, generic IOException as the net.
    is UnknownHostException,
    is ConnectException,
    -> resources?.getString(R.string.cmp_error_unreachable_connect)
        ?: "Can't reach the server. Check your connection." // i18n-allow: UserMessageTest asserts this English
    is SocketTimeoutException -> resources?.getString(R.string.cmp_error_timeout)
        ?: "The server is taking too long. Try again." // i18n-allow: callers omit Resources
    is SSLException -> resources?.getString(R.string.cmp_error_ssl)
        ?: "Secure connection failed. Check the server address." // i18n-allow: callers omit Resources
    // Subsonic protocol errors carry a server-authored, human-readable
    // message ("Wrong username or password", …); the constructor guarantees
    // a non-null fallback message.
    is SubsonicException -> message ?: fallback
    is SpotifyRateLimitException -> resources?.getString(R.string.cmp_error_spotify_busy)
        ?: "Spotify is busy right now. Try again in a moment." // i18n-allow: callers omit Resources
    is SpotifyAuthException -> when {
        isRefreshTokenRevoked -> resources?.getString(R.string.cmp_error_spotify_reconnect)
            ?: "Spotify access expired. Reconnect in Settings." // i18n-allow: callers omit Resources
        // code 0 = client-side precondition; its message is already
        // user-authored ("Spotify client id is not configured. …").
        code == 0 -> message ?: fallback
        else -> fallback
    }
    // An IOException subtype, but an HTTP answer from Apple — not a connectivity failure.
    is AppleMusicApiException -> {
        val tokenRejected =
            "Apple Music rejected the developer token. Check the token service." // i18n-allow: callers omit Resources
        val accessDenied =
            "Apple Music denied access. Check your subscription." // i18n-allow: UserMessageTest asserts this English
        when (failure) {
            AppleMusicApiFailure.UserAuthorizationRequired -> resources?.getString(R.string.cmp_error_apple_reconnect)
                ?: "Apple Music access expired. Reconnect in Settings." // i18n-allow: callers omit Resources
            AppleMusicApiFailure.DeveloperTokenRejected ->
                resources?.getString(R.string.cmp_error_apple_token) ?: tokenRejected
            AppleMusicApiFailure.AccessDenied ->
                resources?.getString(R.string.cmp_error_apple_denied) ?: accessDenied
            AppleMusicApiFailure.RateLimited -> resources?.getString(R.string.cmp_error_apple_busy)
                ?: "Apple Music is busy right now. Try again in a moment." // i18n-allow: callers omit Resources
            else -> fallback
        }
    }
    is IOException -> resources?.getString(R.string.cmp_error_unreachable_io)
        ?: "Can't reach the server. Check your connection." // i18n-allow: UserMessageTest asserts this English
    else -> fallback
}
