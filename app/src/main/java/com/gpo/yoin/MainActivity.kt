package com.gpo.yoin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.gpo.yoin.ui.navigation.YoinNavHost

/**
 * The app's main shell Activity: hosts Home / Library, the global mini player,
 * and the Now Playing overlay (via [YoinNavHost]). Detail pages (album, artist,
 * playlist, settings) are SEPARATE Activities so they get the device-native
 * cross-Activity predictive back animation — see their `*Activity` classes.
 *
 * The playback host lifecycle (Spotify App Remote warm-up) is driven from
 * [YoinApplication] across the whole activity stack, NOT here, so the remote
 * stays connected when a detail Activity comes to the foreground.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        setContent {
            YoinActivityRoot {
                YoinNavHost()
            }
        }
    }
}
