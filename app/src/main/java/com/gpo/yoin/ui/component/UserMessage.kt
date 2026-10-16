package com.gpo.yoin.ui.component

import androidx.annotation.StringRes
import com.gpo.yoin.R
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiException
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiFailure
import com.gpo.yoin.data.repository.SubsonicException
import com.gpo.yoin.data.source.spotify.SpotifyAuthException
import com.gpo.yoin.data.source.spotify.SpotifyRateLimitException
import com.gpo.yoin.ui.common.UiText
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
): String = appLine()?.let { line -> resources?.getString(line.id) ?: line.english } ?: when (this) {
    // Subsonic protocol errors carry a server-authored, human-readable
    // message ("Wrong username or password", …); the constructor guarantees
    // a non-null fallback message.
    is SubsonicException -> message ?: fallback
    // code 0 = client-side precondition; its message is already
    // user-authored ("Spotify client id is not configured. …").
    is SpotifyAuthException -> if (code == 0) message ?: fallback else fallback
    else -> fallback
}

/**
 * [toUserMessage]'s line as [UiText], for a ViewModel without Resources: the
 * app's own lines stay resources, so they show in the app's language. Null
 * when the failure has no line of its own and the screen says its [fallback]
 * instead — the sentinel never reaches the screen.
 */
fun Throwable.toUserMessageText(): UiText? {
    appLine()?.let { return UiText.Res(it.id) }
    val resolved = toUserMessage(NO_LINE)
    return if (resolved == NO_LINE) null else UiText.Raw(resolved)
}

/** One of the app's own lines, and the English said when no Resources were passed. */
private class AppLine(@StringRes val id: Int, val english: String)

private fun Throwable.appLine(): AppLine? = when (this) {
    // Connectivity — specific subtypes first, generic IOException as the net.
    is UnknownHostException,
    is ConnectException,
    -> AppLine(
        R.string.cmp_error_unreachable_connect,
        "Can't reach the server. Check your connection.", // i18n-allow: UserMessageTest asserts this English
    )
    is SocketTimeoutException -> AppLine(
        R.string.cmp_error_timeout,
        "The server is taking too long. Try again.", // i18n-allow: callers omit Resources
    )
    is SSLException -> AppLine(
        R.string.cmp_error_ssl,
        "Secure connection failed. Check the server address.", // i18n-allow: callers omit Resources
    )
    is SubsonicException -> null
    is SpotifyRateLimitException -> AppLine(
        R.string.cmp_error_spotify_busy,
        "Spotify is busy right now. Try again in a moment.", // i18n-allow: callers omit Resources
    )
    is SpotifyAuthException -> if (isRefreshTokenRevoked) {
        AppLine(
            R.string.cmp_error_spotify_reconnect,
            "Spotify access expired. Reconnect in Settings.", // i18n-allow: callers omit Resources
        )
    } else {
        null
    }
    // An IOException subtype, but an HTTP answer from Apple — not a connectivity failure.
    is AppleMusicApiException -> when (failure) {
        AppleMusicApiFailure.UserAuthorizationRequired -> AppLine(
            R.string.cmp_error_apple_reconnect,
            "Apple Music access expired. Reconnect in Settings.", // i18n-allow: callers omit Resources
        )
        AppleMusicApiFailure.DeveloperTokenRejected -> AppLine(
            R.string.cmp_error_apple_token,
            "Apple Music rejected the developer token. Check the token service.", // i18n-allow: callers omit Resources
        )
        // A 403 on one read says nothing of the subscription: Apple has refused single playlists, and every
        // library read for minutes, on accounts that played on. A revoked account is the ping's to tell.
        AppleMusicApiFailure.AccessDenied -> AppLine(
            R.string.cmp_error_apple_denied,
            "Apple Music refused this request. Try again later.", // i18n-allow: UserMessageTest asserts this English
        )
        AppleMusicApiFailure.RateLimited -> AppLine(
            R.string.cmp_error_apple_busy,
            "Apple Music is busy right now. Try again in a moment.", // i18n-allow: callers omit Resources
        )
        else -> null
    }
    is IOException -> AppLine(
        R.string.cmp_error_unreachable_io,
        "Can't reach the server. Check your connection.", // i18n-allow: UserMessageTest asserts this English
    )
    else -> null
}

private const val NO_LINE = "\u0000"
