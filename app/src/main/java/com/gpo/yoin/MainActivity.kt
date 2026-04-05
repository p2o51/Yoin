package com.gpo.yoin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import com.gpo.yoin.ui.component.YoinButtonGroup
import com.gpo.yoin.ui.navigation.YoinNavHost
import com.gpo.yoin.ui.navigation.YoinRoute
import com.gpo.yoin.ui.navigation.YoinSection
import com.gpo.yoin.ui.theme.CoverColorState
import com.gpo.yoin.ui.theme.LocalCoverColorState
import com.gpo.yoin.ui.theme.YoinTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
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
    val navBackStackEntry by navController.currentBackStackEntryAsState()

    val currentRoute = navBackStackEntry?.destination?.route.orEmpty()
    val selectedSection = when {
        currentRoute.contains(YoinRoute.Library::class.qualifiedName.orEmpty()) ->
            YoinSection.LIBRARY
        else -> YoinSection.HOME
    }

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

    Scaffold(
        modifier = modifier,
        bottomBar = {
            YoinButtonGroup(
                selectedSection = selectedSection,
                currentTrackTitle = null,
                currentTrackArtist = null,
                onHomeClick = {
                    navController.navigate(YoinRoute.Home) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onNowPlayingClick = {
                    navController.navigate(YoinRoute.NowPlaying) {
                        launchSingleTop = true
                    }
                },
                onLibraryClick = {
                    navController.navigate(YoinRoute.Library) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        },
    ) { paddingValues ->
        YoinNavHost(
            navController = navController,
            modifier = Modifier.padding(paddingValues),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun YoinAppPreview() {
    YoinTheme {
        YoinApp()
    }
}

