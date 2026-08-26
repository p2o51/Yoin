package com.gpo.yoin

import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import com.gpo.yoin.ui.experience.LocalMotionCapabilityProvider
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.MotionCapabilityProvider
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.rememberYoinWindowInfo
import com.gpo.yoin.ui.theme.CoverColorState
import com.gpo.yoin.ui.theme.LocalCoverColorState
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.delay

/**
 * Transparent edge-to-edge for every Yoin Activity. Call before `setContent`
 * so the window is configured before the first frame (avoids inset jumps on
 * cold start). `SystemBarStyle.auto` lets the system flip icon colors.
 */
fun ComponentActivity.enableYoinEdgeToEdge() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
    )
    requestPeakRefreshRate()
}

/**
 * Opt the window into the display's fastest refresh mode at the CURRENT
 * resolution. The app never asked before, and several OEMs (foldables
 * especially) hold un-opted apps at 60Hz via adaptive-refresh heuristics —
 * every spring in the app then paces at 60 even though nothing in code caps
 * it. preferredDisplayModeId (not preferredRefreshRate) because the soft
 * hint is exactly what those heuristics ignore.
 */
private fun ComponentActivity.requestPeakRefreshRate() {
    val display = if (Build.VERSION.SDK_INT >= 30) display else return
    val current = display?.mode ?: return
    val best = display.supportedModes
        .filter {
            it.physicalWidth == current.physicalWidth &&
                it.physicalHeight == current.physicalHeight
        }
        .maxByOrNull { it.refreshRate }
        ?: return
    if (best.modeId != current.modeId) {
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = best.modeId
        }
    }
}

/**
 * Shared composition root for EVERY Yoin Activity (the main shell and each
 * detail Activity). Provides the cover-palette state, the theme keyed on the
 * current playback cover, the cover-color extraction, and the motion-profile
 * locals — so a detail Activity looks and animates exactly like the shell.
 *
 * Detail Activities reuse this and simply pass their screen as [content];
 * they deliberately do NOT host the Now Playing overlay or mini player (that
 * lives only in [MainActivity], by design).
 */
@Composable
fun YoinActivityRoot(content: @Composable () -> Unit) {
    val coverColorState = remember { CoverColorState() }
    CompositionLocalProvider(LocalCoverColorState provides coverColorState) {
        YoinTheme(coverBitmap = coverColorState.coverBitmap) {
            YoinAppEnvironment(content = content)
        }
    }
}

@Composable
private fun YoinAppEnvironment(content: @Composable () -> Unit) {
    val app = LocalContext.current.applicationContext as? YoinApplication
    val coverColorState = LocalCoverColorState.current
    val fallbackMotionCapabilityProvider = remember { MotionCapabilityProvider(lowRamDevice = false) }
    val motionCapabilityProvider = app?.container?.motionCapabilityProvider ?: fallbackMotionCapabilityProvider
    val motionProfile by motionCapabilityProvider.profile.collectAsState(initial = MotionProfile.Full)

    if (app != null) {
        val context = LocalContext.current
        val imageLoader = remember(context) { SingletonImageLoader.get(context) }
        val playbackState by app.container.playbackManager.playbackState.collectAsState()
        val coverArt = playbackState.currentTrack?.coverArt

        LaunchedEffect(coverArt, playbackState.queue.size) {
            if (coverArt != null) {
                val url = app.container.repository.resolveCoverUrl(coverArt)
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(Size(200, 200))
                    .allowHardware(false)
                    .build()
                val result = imageLoader.execute(request)
                if (result is SuccessResult) {
                    coverColorState.updateCover(result.image.toBitmap())
                }
            } else if (playbackState.queue.isEmpty()) {
                // Keep the previous palette during track handoff so nothing flashes.
                delay(220)
                coverColorState.clearCover()
            }
        }
    }

    // Window size + fold posture, observed once per Activity. Drives the
    // Compact / Wide / Tabletop render dimension (orthogonal to stage mode).
    val windowInfo = rememberYoinWindowInfo()

    CompositionLocalProvider(
        LocalMotionCapabilityProvider provides motionCapabilityProvider,
        LocalMotionProfile provides motionProfile,
        LocalYoinWindowInfo provides windowInfo,
    ) {
        content()
    }
}
