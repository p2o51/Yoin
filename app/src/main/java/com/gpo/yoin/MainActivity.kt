package com.gpo.yoin

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
        setContent {
            YoinActivityRoot {
                YoinNavHost()
            }
        }
    }

}
