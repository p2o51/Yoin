package com.gpo.yoin.ui.navigation

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.gpo.yoin.YoinApplication
import androidx.navigation.toRoute
import com.gpo.yoin.player.CastState
import com.gpo.yoin.player.VisualizerData
import com.gpo.yoin.ui.detail.AlbumDetailScreen
import com.gpo.yoin.ui.detail.AlbumDetailViewModel
import com.gpo.yoin.ui.detail.ArtistDetailScreen
import com.gpo.yoin.ui.detail.ArtistDetailViewModel
import com.gpo.yoin.ui.home.HomeScreen
import com.gpo.yoin.ui.home.HomeViewModel
import com.gpo.yoin.ui.library.LibraryScreen
import com.gpo.yoin.ui.library.LibraryViewModel
import com.gpo.yoin.ui.nowplaying.NowPlayingScreen
import com.gpo.yoin.ui.nowplaying.NowPlayingViewModel
import com.gpo.yoin.ui.settings.SettingsScreen
import com.gpo.yoin.ui.settings.SettingsViewModel
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinTheme

@Composable
fun YoinNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = YoinRoute.Home,
        modifier = modifier,
    ) {
        // Home — crossfade (Effects Spring)
        composable<YoinRoute.Home>(
            enterTransition = {
                fadeIn(spring(stiffness = Spring.StiffnessLow))
            },
            exitTransition = {
                fadeOut(spring(stiffness = Spring.StiffnessLow))
            },
            popEnterTransition = {
                fadeIn(spring(stiffness = Spring.StiffnessLow))
            },
            popExitTransition = {
                fadeOut(spring(stiffness = Spring.StiffnessLow))
            },
        ) {
            val app = LocalContext.current.applicationContext as YoinApplication
            val viewModel: HomeViewModel = viewModel(
                factory = HomeViewModel.Factory(app.container),
            )
            val playbackState by app.container.playbackManager
                .playbackState.collectAsState()
            val visualizerData by app.container.audioVisualizerManager
                .visualizerData.collectAsState()
            HomeScreen(
                viewModel = viewModel,
                isPlaying = playbackState.isPlaying,
                visualizerData = visualizerData,
                onNavigateToSettings = {
                    navController.navigate(YoinRoute.Settings)
                },
                onAlbumClick = { albumId ->
                    navController.navigate(YoinRoute.AlbumDetail(albumId))
                },
                onSongClick = { /* Phase 10: play song */ },
            )
        }

        // Library — crossfade (Effects Spring)
        composable<YoinRoute.Library>(
            enterTransition = {
                fadeIn(spring(stiffness = Spring.StiffnessLow))
            },
            exitTransition = {
                fadeOut(spring(stiffness = Spring.StiffnessLow))
            },
            popEnterTransition = {
                fadeIn(spring(stiffness = Spring.StiffnessLow))
            },
            popExitTransition = {
                fadeOut(spring(stiffness = Spring.StiffnessLow))
            },
        ) {
            val app = LocalContext.current.applicationContext as YoinApplication
            val viewModel: LibraryViewModel = viewModel(
                factory = LibraryViewModel.Factory(app.container),
            )
            LibraryScreen(
                viewModel = viewModel,
                onNavigateToSettings = {
                    navController.navigate(YoinRoute.Settings)
                },
                onArtistClick = { artistId ->
                    navController.navigate(YoinRoute.ArtistDetail(artistId))
                },
                onAlbumClick = { albumId ->
                    navController.navigate(YoinRoute.AlbumDetail(albumId))
                },
                onSongClick = { /* Phase 10: play song */ },
            )
        }

        // Now Playing — overlay transition
        composable<YoinRoute.NowPlaying>(
            enterTransition = { YoinMotion.navEnterOverlay },
            exitTransition = { YoinMotion.navExitOverlay },
            popEnterTransition = { YoinMotion.navEnterOverlay },
            popExitTransition = { YoinMotion.navExitOverlay },
        ) {
            val app = LocalContext.current.applicationContext as YoinApplication
            val viewModel: NowPlayingViewModel = viewModel(
                factory = NowPlayingViewModel.Factory(app.container),
            )
            val uiState by viewModel.uiState.collectAsState()
            val visualizerData by app.container.audioVisualizerManager
                .visualizerData.collectAsState()
            val castState by app.container.castManager
                .castState.collectAsState()
            NowPlayingScreen(
                uiState = uiState,
                visualizerData = visualizerData,
                onTogglePlayPause = viewModel::togglePlayPause,
                onSkipNext = viewModel::skipNext,
                onSkipPrevious = viewModel::skipPrevious,
                onSeek = viewModel::seekTo,
                onRatingChange = viewModel::setRating,
                onToggleFavorite = viewModel::toggleFavorite,
                onSkipToQueueItem = viewModel::skipToQueueItem,
                castState = castState,
                onCastClick = { /* Cast dialog handled by system UI */ },
            )
        }

        // Album Detail — forward sub-route
        composable<YoinRoute.AlbumDetail>(
            enterTransition = { YoinMotion.navEnterForward },
            exitTransition = { YoinMotion.navExitForward },
            popEnterTransition = { YoinMotion.navEnterBack },
            popExitTransition = { YoinMotion.navExitBack },
        ) { backStackEntry ->
            val route = backStackEntry.toRoute<YoinRoute.AlbumDetail>()
            val app = LocalContext.current.applicationContext as YoinApplication
            val viewModel: AlbumDetailViewModel = viewModel(
                factory = AlbumDetailViewModel.Factory(route.albumId, app.container),
            )
            val uiState by viewModel.uiState.collectAsState()
            AlbumDetailScreen(
                uiState = uiState,
                onBackClick = { navController.popBackStack() },
                onSongClick = { /* Phase 10: play song */ },
                onToggleStar = viewModel::toggleStar,
            )
        }

        // Artist Detail — forward sub-route
        composable<YoinRoute.ArtistDetail>(
            enterTransition = { YoinMotion.navEnterForward },
            exitTransition = { YoinMotion.navExitForward },
            popEnterTransition = { YoinMotion.navEnterBack },
            popExitTransition = { YoinMotion.navExitBack },
        ) { backStackEntry ->
            val route = backStackEntry.toRoute<YoinRoute.ArtistDetail>()
            val app = LocalContext.current.applicationContext as YoinApplication
            val viewModel: ArtistDetailViewModel = viewModel(
                factory = ArtistDetailViewModel.Factory(route.artistId, app.container),
            )
            val uiState by viewModel.uiState.collectAsState()
            ArtistDetailScreen(
                uiState = uiState,
                onBackClick = { navController.popBackStack() },
                onAlbumClick = { albumId ->
                    navController.navigate(YoinRoute.AlbumDetail(albumId))
                },
            )
        }

        // Settings — forward sub-route
        composable<YoinRoute.Settings>(
            enterTransition = { YoinMotion.navEnterForward },
            exitTransition = { YoinMotion.navExitForward },
            popEnterTransition = { YoinMotion.navEnterBack },
            popExitTransition = { YoinMotion.navExitBack },
        ) {
            val app = LocalContext.current.applicationContext as YoinApplication
            val viewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModel.Factory(app.container),
            )
            SettingsScreen(viewModel = viewModel)
        }
    }
}

// ── Previews ────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun YoinNavHostPreview() {
    YoinTheme {
        YoinNavHost(navController = rememberNavController())
    }
}
