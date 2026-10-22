package com.gpo.yoin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.gpo.yoin.ui.experience.CoveredWindowAnimationGate
import com.gpo.yoin.ui.experience.installCoveredWindowAnimationGate
import com.gpo.yoin.ui.landing.LandingGate
import com.gpo.yoin.ui.landing.LandingHost
import com.gpo.yoin.ui.landing.SharedPrefsLandingStore
import com.gpo.yoin.ui.navigation.YoinNavHost
import com.gpo.yoin.widget.WidgetLaunch
import com.gpo.yoin.widget.WidgetDetailRequest
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 * launcher Activity's only — detail Activities never re-trigger it. On a first
 * launch it also holds until the landing knows whether to show (no account yet),
 * so an existing user never sees the landing flash.
 *
 * The first-run landing ([LandingHost]) sits above the shell in the same window,
 * so its pill can become the shell's bar. Settings › About › Welcome guide
 * re-runs it through [welcomeGuideIntent].
 *
 * The playback host lifecycle (Spotify App Remote warm-up) is driven from
 * [YoinApplication] across the whole activity stack, NOT here, so the remote
 * stays connected when a detail Activity comes to the foreground.
 */
class MainActivity : ComponentActivity() {
    private val landingGate = LandingGate()
    private lateinit var shellHandoffHostKey: String
    private var pendingWidgetDetail by mutableStateOf<WidgetDetailRequest?>(null)
    private var shellWindowFocused by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before super.onCreate(); also applies postSplashScreenTheme.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        pendingWidgetDetail = WidgetDetailRequest.parse(
            savedInstanceState?.getString(STATE_WIDGET_DETAIL_ENTITY),
            savedInstanceState?.getString(STATE_WIDGET_DETAIL_ID),
        )
        // A recreated activity keeps whatever the landing was doing (its state is saved): no new check.
        if (savedInstanceState != null) {
            landingGate.restored = true
            landingGate.decided = true
        }
        splash.setKeepOnScreenCondition { !landingGate.decided }
        lifecycleScope.launch {
            delay(SplashHoldLimitMs)
            if (!landingGate.decided) {
                landingGate.splashTimedOut = true
                landingGate.decided = true
            }
        }
        enableYoinEdgeToEdge()
        // Preserve a logical shell through recreation. Only the first fresh shell after the previous
        // windows are gone clears the process-level pose; a second MainActivity cannot retire a live hand-off.
        shellHandoffHostKey = savedInstanceState?.getString(STATE_SHELL_HANDOFF_HOST) ?: UUID.randomUUID().toString()
        val store = (application as YoinApplication).container.experienceSessionStore
        lifecycleScope.launch {
            store.attachShellHandoffHost(shellHandoffHostKey, restored = savedInstanceState != null)
        }
        // A detail Activity covers the shell but keeps it alive (translucent): freeze its animations meanwhile.
        installCoveredWindowAnimationGate(CoveredWindowAnimationGate.ShellWindowKey)
        // A fresh install holds the shell back until the landing check (a cheap prefs read).
        if (savedInstanceState == null && !SharedPrefsLandingStore(this).isDone()) landingGate.holdShell = true
        handleLandingIntent(intent, fresh = savedInstanceState == null)
        setContent {
            YoinActivityRoot {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (landingGate.showing) Modifier.clearAndSetSemantics {} else Modifier),
                    ) {
                        if (!landingGate.holdShell) {
                            YoinNavHost(
                                // Wait until the landing no longer covers the focused shell
                                // before handing its window to a detail.
                                widgetDetailRequest = pendingWidgetDetail.takeIf {
                                    !landingGate.showing && shellWindowFocused
                                },
                                onWidgetDetailConsumed = { request ->
                                    if (pendingWidgetDetail == request) {
                                        pendingWidgetDetail = null
                                        true
                                    } else {
                                        false
                                    }
                                },
                            )
                        }
                    }
                    LandingHost(gate = landingGate)
                }
            }
        }
        // A home-screen widget tap: open its detail over the shell, or Memories at that memory.
        if (savedInstanceState == null) handleWidgetIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SHELL_HANDOFF_HOST, shellHandoffHostKey)
        outState.putString(STATE_WIDGET_DETAIL_ENTITY, pendingWidgetDetail?.entity?.name)
        outState.putString(STATE_WIDGET_DETAIL_ID, pendingWidgetDetail?.id?.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        shellWindowFocused = hasFocus
    }

    override fun onDestroy() {
        (application as YoinApplication).container.experienceSessionStore
            .detachShellHandoffHost(shellHandoffHostKey, isChangingConfigurations)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLandingIntent(intent, fresh = true)
        handleWidgetIntent(intent)
    }

    private fun handleWidgetIntent(intent: Intent?) {
        var detail: WidgetDetailRequest? = null
        if (WidgetLaunch.handle(this, intent, (application as YoinApplication).container) { detail = it }) {
            // A Memories tap supersedes any detail still waiting for the shell.
            pendingWidgetDetail = detail
            setIntent(Intent())
        }
    }

    private fun handleLandingIntent(intent: Intent?, fresh: Boolean) {
        if (fresh && intent?.getBooleanExtra(EXTRA_WELCOME_GUIDE, false) == true) {
            intent.removeExtra(EXTRA_WELCOME_GUIDE)
            landingGate.requestRerun()
        }
    }

    companion object {
        private const val STATE_SHELL_HANDOFF_HOST = "shellHandoffHost"
        private const val STATE_WIDGET_DETAIL_ENTITY = "widgetDetailEntity"
        private const val STATE_WIDGET_DETAIL_ID = "widgetDetailId"

        /** Settings › About › Welcome guide: run the landing again. */
        const val EXTRA_WELCOME_GUIDE = "welcomeGuide"

        /** The Spotify guide's "back to Yoin": the landing's Spotify step reads the copied Client ID. */
        const val EXTRA_FROM_SPOTIFY_GUIDE = "fromSpotifyGuide"

        /** The splash never waits on the landing longer than this. */
        private const val SplashHoldLimitMs = 1_500L

        /**
         * Yoin forward as it is, a landing keeping its step (the Spotoolfy share receiver: the landing picks the file
         * up itself); started when it isn't running.
         */
        fun bringForwardIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

        fun welcomeGuideIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_WELCOME_GUIDE, true)
    }
}
