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
import com.gpo.yoin.symbols.LocalSymbolMotion
import com.gpo.yoin.symbols.SymbolMotion
import com.gpo.yoin.ui.component.ProvideBottomBarShadowHost
import com.gpo.yoin.ui.component.LocalPlaybackWaveState
import com.gpo.yoin.ui.experience.LocalMotionCapabilityProvider
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.LocalShellChromeInsets
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.MotionCapabilityProvider
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.rememberShellChromeInsets
import com.gpo.yoin.ui.experience.rememberYoinWindowInfo
import com.gpo.yoin.ui.theme.YoinTheme

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
 * Detail Activities read the same resolved palette and in-flight color wash
 * from their first frame. Opening a window never resets playback colors.
 */
@Composable
fun YoinActivityRoot(
    // Detail windows open showing only their bar over the previous window's
    // bar; their shadow waits for the page (see BottomBarShadowPageCoverEffect).
    deferBottomBarShadow: Boolean = false,
    content: @Composable () -> Unit,
) {
    val app = LocalContext.current.applicationContext as? YoinApplication
    ProvideBottomBarShadowHost(app?.container?.bottomBarShadows, deferUntilPageCover = deferBottomBarShadow) {
        YoinTheme(playbackThemeState = app?.container?.playbackThemeState) {
            YoinAppEnvironment(content = content)
        }
    }
}

@Composable
private fun YoinAppEnvironment(content: @Composable () -> Unit) {
    val app = LocalContext.current.applicationContext as? YoinApplication
    val fallbackMotionCapabilityProvider = remember { MotionCapabilityProvider(lowRamDevice = false) }
    val motionCapabilityProvider = app?.container?.motionCapabilityProvider ?: fallbackMotionCapabilityProvider
    val motionProfile by motionCapabilityProvider.profile.collectAsState(initial = MotionProfile.Full)

    if (app != null) {
        val context = app.applicationContext
        val imageLoader = remember(context) { SingletonImageLoader.get(context) }
        val playbackState by app.container.playbackManager.playbackState.collectAsState()
        val coverArt = playbackState.currentTrack?.coverArt

        val configurationRevision by app.container.musicConfigurationRevision.collectAsState()
        LaunchedEffect(coverArt, playbackState.queue.isEmpty(), configurationRevision) {
            val model = coverArt?.let { app.container.repository.resolveCoverUrl(it) }
            app.container.playbackThemeState.updateArtwork(
                model = model,
                clearWhenMissing = playbackState.queue.isEmpty(),
            ) { url ->
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(Size(200, 200))
                    .allowHardware(false)
                    .build()
                (imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
            }
        }
    }

    // Window size + fold posture, observed once per Activity. Drives the
    // Compact / Wide / Tabletop render dimension (orthogonal to stage mode)
    // and where the Button Group lives (ShellChromeForm).
    val windowInfo = rememberYoinWindowInfo()
    val shellChromeInsets = rememberShellChromeInsets(windowInfo)
    // Yoin Symbols' animated icons follow the same motion tier as the rest of the app.
    val symbolMotion =
        if (motionProfile == MotionProfile.AdaptiveReduced) SymbolMotion.Reduced else SymbolMotion.Default

    CompositionLocalProvider(
        LocalPlaybackWaveState provides app?.container?.experienceSessionStore?.playbackWave,
        LocalMotionCapabilityProvider provides motionCapabilityProvider,
        LocalMotionProfile provides motionProfile,
        LocalSymbolMotion provides symbolMotion,
        LocalYoinWindowInfo provides windowInfo,
        LocalShellChromeInsets provides shellChromeInsets,
    ) {
        content()
    }
}
