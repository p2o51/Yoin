package com.gpo.yoin.ui.nowplaying

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.edit

/**
 * "Keep screen on" for Now Playing (owner, 2026-10-17: a tester asked for the
 * screen to stay lit with Now Playing open). Toggled from the cover's
 * long-press menu, stored per device, and process-wide snapshot state so every
 * window hosting Now Playing (the shell and each detail Activity) reads the
 * same choice live.
 */
internal object NowPlayingKeepScreenOn {
    private const val PREFS_NAME = "yoin_ui_preferences"
    private const val KEY = "now_playing_keep_screen_on"

    private val state = mutableStateOf(false)

    @Volatile
    private var loaded = false

    /** The current choice. A snapshot read. */
    val enabled: Boolean get() = state.value

    /** Reads the stored choice once per process; later calls are free. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        val stored = prefs(context).getBoolean(KEY, false)
        Snapshot.global { state.value = stored }
        loaded = true
    }

    /** The user's choice: applied at once in every window, and stored. */
    fun set(context: Context, on: Boolean) {
        loaded = true
        state.value = on
        prefs(context).edit { putBoolean(KEY, on) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/** The stored keep-screen-on choice; loads it before the first read. */
@Composable
internal fun rememberNowPlayingKeepScreenOn(): Boolean {
    val context = LocalContext.current
    val preference = remember(context) { NowPlayingKeepScreenOn.also { it.ensureLoaded(context) } }
    return preference.enabled
}

/**
 * Holds this window's screen on while [active]: Now Playing is open, the user
 * chose Keep screen on, and music is playing — paused, the normal screen
 * timeout comes back, so a forgotten pause doesn't burn the screen all night.
 * Uses the hosting view's keepScreenOn, which the window drops on its own
 * when the view detaches.
 */
@Composable
internal fun KeepScreenOnWhile(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, active) {
        if (active) view.keepScreenOn = true
        onDispose { if (active) view.keepScreenOn = false }
    }
}
