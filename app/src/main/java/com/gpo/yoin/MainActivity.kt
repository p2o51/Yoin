package com.gpo.yoin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import com.gpo.yoin.ui.experience.LocalMotionCapabilityProvider
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionCapabilityProvider
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.navigation.YoinNavHost
import com.gpo.yoin.ui.theme.CoverColorState
import com.gpo.yoin.ui.theme.LocalCoverColorState
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge. Transparent on both bars; `SystemBarStyle.auto` lets
        // the system flip icon colors against the device light/dark theme so
        // we don't have to touch WindowInsetsController manually.
        // Must be called before setContent so the window is configured
        // before the first frame — avoids status bar height jumps on cold
        // start. Previously done in YoinTheme's SideEffect (post-composition).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        setContent {
            val coverColorState = remember { CoverColorState() }
            CompositionLocalProvider(LocalCoverColorState provides coverColorState) {
                YoinTheme(coverBitmap = coverColorState.coverBitmap) {
                    YoinApp()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as? YoinApplication)?.container?.playbackManager?.onHostStart(this)
    }

    override fun onStop() {
        (application as? YoinApplication)?.container?.playbackManager?.onHostStop()
        super.onStop()
    }
}

@Composable
fun YoinApp(modifier: Modifier = Modifier) {
    // ── Cover color extraction driven by playback state ──────────────────
    val app = LocalContext.current.applicationContext as? YoinApplication
    val coverColorState = LocalCoverColorState.current
    val fallbackMotionCapabilityProvider = remember { MotionCapabilityProvider(lowRamDevice = false) }
    val motionCapabilityProvider = app?.container?.motionCapabilityProvider ?: fallbackMotionCapabilityProvider
    val motionProfile by motionCapabilityProvider.profile.collectAsState(initial = MotionProfile.Full)

    if (app != null) {
        val context = LocalContext.current
        val imageLoader = remember { ImageLoader(context) }
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
                // Keep the previous palette during track handoff so the whole app doesn't flash.
                delay(220)
                coverColorState.clearCover()
            }
        }
    }

    CompositionLocalProvider(
        LocalMotionCapabilityProvider provides motionCapabilityProvider,
        LocalMotionProfile provides motionProfile,
    ) {
        YoinNavHost(modifier = modifier)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun YoinAppPreview() {
    YoinTheme {
        YoinApp()
    }
}
