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
import androidx.navigation.compose.rememberNavController
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import com.gpo.yoin.ui.navigation.YoinNavHost
import com.gpo.yoin.ui.theme.CoverColorState
import com.gpo.yoin.ui.theme.LocalCoverColorState
import com.gpo.yoin.ui.theme.YoinTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            val coverColorState = remember { CoverColorState() }
            CompositionLocalProvider(LocalCoverColorState provides coverColorState) {
                YoinTheme(coverBitmap = coverColorState.coverBitmap) {
                    YoinApp()
                }
            }
        }
    }
}

@Composable
fun YoinApp(modifier: Modifier = Modifier) {
    val navController = rememberNavController()

    // ── Cover color extraction driven by playback state ──────────────────
    val app = LocalContext.current.applicationContext as? YoinApplication
    val coverColorState = LocalCoverColorState.current

    if (app != null) {
        val context = LocalContext.current
        val imageLoader = remember { ImageLoader(context) }
        val playbackState by app.container.playbackManager.playbackState.collectAsState()
        val coverArtId = playbackState.currentSong?.coverArt

        LaunchedEffect(coverArtId) {
            if (coverArtId != null) {
                val url = app.container.repository.buildCoverArtUrl(coverArtId)
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(Size(200, 200))
                    .allowHardware(false)
                    .build()
                val result = imageLoader.execute(request)
                if (result is SuccessResult) {
                    coverColorState.updateCover(result.image.toBitmap())
                }
            } else {
                coverColorState.clearCover()
            }
        }
    }

    YoinNavHost(
        navController = navController,
        modifier = modifier,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun YoinAppPreview() {
    YoinTheme {
        YoinApp()
    }
}
