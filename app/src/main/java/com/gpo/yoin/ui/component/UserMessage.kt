package com.gpo.yoin.ui.component

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
fun Throwable.toUserMessage(fallback: String): String = when (this) {
    // Connectivity — specific subtypes first, generic IOException as the net.
    is UnknownHostException,
    is ConnectException,
    -> "Can't reach the server. Check your connection."
    is SocketTimeoutException -> "The server is taking too long. Try again."
    is SSLException -> "Secure connection failed. Check the server address."
    // Subsonic protocol errors carry a server-authored, human-readable
    // message ("Wrong username or password", …); the constructor guarantees
    // a non-null fallback message.
    is SubsonicException -> message ?: fallback
    is SpotifyRateLimitException -> "Spotify is busy right now. Try again in a moment."
    is SpotifyAuthException -> when {
        isRefreshTokenRevoked -> "Spotify access expired. Reconnect in Settings."
        // code 0 = client-side precondition; its message is already
        // user-authored ("Spotify client id is not configured. …").
        code == 0 -> message ?: fallback
        else -> fallback
    }
    // An IOException subtype, but an HTTP answer from Apple — not a connectivity failure.
    is AppleMusicApiException -> when (failure) {
        AppleMusicApiFailure.UserAuthorizationRequired -> "Apple Music access expired. Reconnect in Settings."
        AppleMusicApiFailure.DeveloperTokenRejected -> "Apple Music rejected the developer token. Check the token service."
        AppleMusicApiFailure.AccessDenied -> "Apple Music denied access. Check your subscription."
        AppleMusicApiFailure.RateLimited -> "Apple Music is busy right now. Try again in a moment."
        else -> fallback
    }
    is IOException -> "Can't reach the server. Check your connection."
    else -> fallback
}
