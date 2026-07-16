package com.gpo.yoin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.gpo.yoin.ui.navigation.YoinNavHost

/**
 * The app's main shell Activity: hosts Home / Library, the global mini player,
 * and the Now Playing overlay (via [YoinNavHost]). Detail pages (album, artist,
 * playlist, settings) are SEPARATE Activities so they get the device-native
 * cross-Activity predictive back animation — see their `*Activity` classes.
 *
 * Cold start shows the system SplashScreen (`Theme.Yoin.Splash`): the three-arrow
 * mark blooms as the splash's animated icon WHILE the app loads (Gmail-style),
 * then the system hands off to the running app once the first frame is ready and
 * `postSplashScreenTheme` swaps the window to `Theme.Yoin`. The splash is the
 * launcher Activity's only — detail Activities never re-trigger it.
 *
 * The playback host lifecycle (Spotify App Remote warm-up) is driven from
 * [YoinApplication] across the whole activity stack, NOT here, so the remote
 * stays connected when a detail Activity comes to the foreground.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before super.onCreate(); also applies postSplashScreenTheme.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        consumeExpandNowPlaying(intent)
        setContent {
            YoinActivityRoot {
                YoinNavHost()
            }
        }
    }

    // CLEAR_TOP|SINGLE_TOP relaunch from a detail Activity's mini-player dock.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeExpandNowPlaying(intent)
    }

    /**
     * Expand Now Playing on arrival. The detail dock sends this as an extra
     * (instead of writing the session-store flag before launching) so the
     * expansion starts here — around the shell's first visible frames — and
     * the bar→NP transition actually plays for the user. Compose's frame
     * clock is process-wide: a flag set while this Activity was stopped would
     * run the whole enter transition invisibly in the background. Registered
     * as a REQUEST rather than set directly: the shell fulfils it after a
     * short stagger, once the detail window's dissolve has revealed the shell
     * (an immediate expand plays its first rise hidden behind the still-
     * opaque detail page). One-shot: the extra is removed so a config-change
     * redelivery can't re-expand.
     */
    private fun consumeExpandNowPlaying(intent: Intent) {
        // Dock-bloom path: the detail window is dissolving over us showing a
        // fullscreen cover, so NP must compose ALREADY settled — set the
        // state directly and let the stage transition finish while stopped.
        if (intent.getBooleanExtra(EXTRA_EXPAND_NOW_PLAYING_INSTANT, false)) {
            intent.removeExtra(EXTRA_EXPAND_NOW_PLAYING_INSTANT)
            (application as YoinApplication).container.experienceSessionStore
                .snapNowPlayingExpanded()
            return
        }
        if (!intent.getBooleanExtra(EXTRA_EXPAND_NOW_PLAYING, false)) return
        intent.removeExtra(EXTRA_EXPAND_NOW_PLAYING)
        (application as YoinApplication).container.experienceSessionStore
            .requestNowPlayingExpand()
    }

    companion object {
        const val EXTRA_EXPAND_NOW_PLAYING = "expandNowPlaying"
        const val EXTRA_EXPAND_NOW_PLAYING_INSTANT = "expandNowPlayingInstant"
    }
}
