package com.gpo.yoin.player.applemusic

import android.content.Context
import androidx.core.content.edit
import com.gpo.yoin.data.remote.applemusic.developerTokenExpiry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The developer token MusicKit sends with every Apple request it makes: the queue's catalog read
 * (`CatalogPlaybackQueueItemProvider`), each song's webPlayback and FairPlay key, play reporting.
 *
 * MusicKit (1.1.1) asks the `TokenProvider` for it once, in `MediaPlayerControllerFactory.createLocalController`,
 * files the answer in its own preferences ([installMusicKitDeveloperToken]) and reads it from there for as long as
 * the controller lives. The token is a JWT that expires after an hour, so a session older than that sent an expired
 * one: Apple refused the catalog read and MusicKit failed the queue (onPlaybackError type 3), whatever song was
 * tapped, until the app was restarted (2026-10-16).
 *
 * This keeps the token MusicKit holds fresh: ahead of time while the app runs ([keepFresh]), and before each call
 * that sends MusicKit to Apple ([run]). After the device has slept, the ahead-of-time renewal hasn't run yet, so
 * that call waits for a new token instead of failing.
 */
internal class MusicKitDeveloperToken(
    installed: String,
    private val scope: CoroutineScope,
    private val fetch: suspend () -> String,
    private val install: (String) -> Unit,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private var installed = installed
    private var expiresAt = developerTokenExpiry(installed) ?: 0
    private var refreshing: Job? = null
    private var retryAt = 0L
    private val waiting = ArrayDeque<() -> Unit>()

    /** A call waits for a new token, so what MusicKit reports still belongs to the state before it. */
    val holding: Boolean get() = waiting.isNotEmpty()

    /**
     * Runs [request], a MusicKit call that reaches Apple: at once while the token has more than
     * [MUSICKIT_TOKEN_RENEW_BEFORE_S] left, otherwise after a refresh, in the order the calls came. A refresh that
     * fails lets them go anyway: a token that hasn't quite run out still works, and one that has fails in MusicKit
     * with its own error.
     */
    fun run(request: () -> Unit) {
        if (waiting.isEmpty() && isFresh()) {
            request()
            return
        }
        waiting.addLast(request)
        refresh()
    }

    /** Calls still waiting belong to a queue that has since been replaced or stopped. */
    fun dropWaiting() = waiting.clear()

    /** Renews the token before it runs short. A refresh that failed is tried again [MUSICKIT_TOKEN_RETRY_S] later. */
    fun keepFresh() {
        if (!isFresh() && nowEpochSeconds() >= retryAt) refresh()
    }

    private fun isFresh() = expiresAt > nowEpochSeconds() + MUSICKIT_TOKEN_RENEW_BEFORE_S

    private fun refresh() {
        if (refreshing != null) return
        refreshing = scope.launch {
            val fetching = scope.async {
                try {
                    fetch()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
            // The token service's blocking HTTP call can't be cancelled, so a slow one isn't waited out: it finishes
            // on its own (the provider caches the token for the next refresh) and the calls go ahead.
            withTimeoutOrNull(MUSICKIT_TOKEN_FETCH_TIMEOUT_MS) { fetching.await() }?.let(::offer)
            // A device clock a few minutes fast can make even a new token look short: wait before asking again.
            if (!isFresh()) retryAt = nowEpochSeconds() + MUSICKIT_TOKEN_RETRY_S
            try {
                // Still refreshing while the calls run, so a call that one of them starts lines up behind the rest.
                while (waiting.isNotEmpty()) waiting.removeFirst().invoke()
            } finally {
                refreshing = null
            }
        }
    }

    private fun offer(token: String) {
        val expiry = developerTokenExpiry(token) ?: return
        if (token != installed) {
            install(token)
            installed = token
        }
        expiresAt = expiry
    }
}

/**
 * Puts [token] where MusicKit (1.1.1, `playback.d.b`) reads its developer token for each request: the preferences
 * `MediaPlayerControllerFactory.createLocalController` files it in. A process shares one instance per preferences
 * file, so MusicKit's next read sees the new token. `MusicKitDeveloperTokenTest` checks these names against the SDK.
 */
internal fun installMusicKitDeveloperToken(context: Context, token: String) {
    context.applicationContext.getSharedPreferences(MUSICKIT_AUTH_PREFERENCES, Context.MODE_PRIVATE)
        .edit { putString(MUSICKIT_DEVELOPER_TOKEN_KEY, token) }
}

internal const val MUSICKIT_AUTH_PREFERENCES = "com.apple.android.music.auth_preferences"
internal const val MUSICKIT_DEVELOPER_TOKEN_KEY = "developer-token"

/**
 * Renewal starts with this long left. Less than the five minutes a playback token is fetched with
 * (`AppleMusicSource.refreshPlaybackToken`), so a fetched token counts as fresh, and enough to cover a device clock
 * that runs a little slow.
 */
internal const val MUSICKIT_TOKEN_RENEW_BEFORE_S = 180L
internal const val MUSICKIT_TOKEN_RETRY_S = 30L

/** A call that waits for a token goes ahead without one after this long. */
internal const val MUSICKIT_TOKEN_FETCH_TIMEOUT_MS = 8_000L
