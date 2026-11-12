package com.gpo.yoin.ui.component

import android.content.Context
import android.content.Intent
import android.util.Log
import com.gpo.yoin.player.SpotifyAppRemoteCompat

/**
 * Spotify's Connect devices, picked without leaving Yoin (owner 2026-11-08).
 *
 * Spotify publishes its devices (Sonos, Cast, PS5) to Android's output switcher through a
 * MediaRoute2 provider, visible only to Spotify and System UI, so Yoin can't list or pick them
 * itself. System UI opens that switcher for another app's media session on this broadcast
 * (exported, no permission; checked on Android 17, Pixel Tablet). It is not public API, so it
 * may be missing or refused: the caller falls back to [openSpotify] when no panel shows up.
 */
internal object SpotifyOutputSwitcher {
    private const val TAG = "SpotifyOutputSwitcher"
    private const val ACTION = "com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG"
    private const val EXTRA_PACKAGE = "package_name"
    private const val SYSTEM_UI = "com.android.systemui"
    private const val SPOTIFY_PACKAGE = "com.spotify.music"

    /** How long the panel may take to take focus before Yoin opens Spotify instead. */
    const val SHOW_WAIT_MS = 900L

    /** The longest Yoin waits on the open panel before reading the devices anyway. */
    const val PANEL_MAX_MS = 120_000L

    /** Spotify Connect lags a switch by a second or so before /me/player shows it. */
    const val SETTLE_MS = 1_200L

    /** Sends the request; false when it can't even be sent. */
    fun request(context: Context): Boolean = runCatching {
        context.sendBroadcast(
            Intent(ACTION)
                .setPackage(SYSTEM_UI)
                .putExtra(EXTRA_PACKAGE, SPOTIFY_PACKAGE),
        )
    }.onFailure { Log.w(TAG, "output switcher request failed", it) }.isSuccess

    /** The Spotify app, on its own launcher entry; false when it can't be opened. */
    fun openSpotify(context: Context): Boolean {
        val intent = SpotifyAppRemoteCompat.launchIntent(context) ?: return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
