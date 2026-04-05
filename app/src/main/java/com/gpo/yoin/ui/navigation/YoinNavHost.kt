package com.gpo.yoin.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.ui.component.YoinButtonGroup
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
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

@Composable
fun YoinNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = YoinRoute.Shell,
        modifier = modifier,
    ) {
        composable<YoinRoute.Shell> {
            val app = LocalContext.current.applicationContext as YoinApplication
            val homeViewModel: HomeViewModel = viewModel(
                factory = HomeViewModel.Factory(app.container),
            )
            val libraryViewModel: LibraryViewModel = viewModel(
                factory = LibraryViewModel.Factory(app.container),
            )
            val nowPlayingViewModel: NowPlayingViewModel = viewModel(
                factory = NowPlayingViewModel.Factory(app.container),
            )

            YoinShell(
                app = app,
                homeViewModel = homeViewModel,
                libraryViewModel = libraryViewModel,
                nowPlayingViewModel = nowPlayingViewModel,
                onNavigateToSettings = { navController.navigate(YoinRoute.Settings) },
                onNavigateToAlbum = { navController.navigate(YoinRoute.AlbumDetail(it)) },
                onNavigateToArtist = { navController.navigate(YoinRoute.ArtistDetail(it)) },
            )
        }

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
                onSongClick = { songId ->
                    val songs = viewModel.getAlbumSongs()
                    val index = songs.indexOfFirst { it.id == songId }.coerceAtLeast(0)
                    app.container.playbackManager.play(
                        songs = songs,
                        startIndex = index,
                        credentials = app.container.getCredentials(),
                    )
                },
                onToggleStar = viewModel::toggleStar,
                onRetry = viewModel::retry,
            )
        }

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
                onRetry = viewModel::retry,
            )
        }

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

@Composable
private fun YoinShell(
    app: YoinApplication,
    homeViewModel: HomeViewModel,
    libraryViewModel: LibraryViewModel,
    nowPlayingViewModel: NowPlayingViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedSection by rememberSaveable { mutableStateOf(YoinSection.HOME) }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var dragOffsetPx by rememberSaveable { mutableStateOf(0f) }
    var predictiveBackProgress by rememberSaveable { mutableStateOf(0f) }

    val playbackState by app.container.playbackManager.playbackState.collectAsState()
    val visualizerData by app.container.audioVisualizerManager.visualizerData.collectAsState()
    val castState by app.container.castManager.castState.collectAsState()
    val nowPlayingUiState by nowPlayingViewModel.uiState.collectAsState()

    val overlayOffsetPx by animateFloatAsState(
        targetValue = predictiveBackProgress * 1200f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "overlayOffsetPx",
    )

    val coverArtUrl = playbackState.currentSong?.coverArt?.let {
        app.container.repository.buildCoverArtUrl(it)
    }

    LaunchedEffect(Unit) {
        app.container.playbackManager.connectInBackground()
    }

    BackHandler(enabled = showNowPlaying) {
        dragOffsetPx = 0f
        predictiveBackProgress = 0f
        showNowPlaying = false
    }

    PredictiveBackHandler(enabled = showNowPlaying) { progress ->
        try {
            progress.collectLatest { event ->
                predictiveBackProgress = event.progress
            }
            dragOffsetPx = 0f
            showNowPlaying = false
        } finally {
            predictiveBackProgress = 0f
        }
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    SharedTransitionLayout(modifier = modifier.fillMaxSize()) {
        val sharedScope = this

        AnimatedContent<YoinSection>(
            targetState = selectedSection,
            transitionSpec = {
                fadeIn(spring(stiffness = Spring.StiffnessLow)) togetherWith
                    fadeOut(spring(stiffness = Spring.StiffnessLow))
            },
            modifier = Modifier.fillMaxSize(),
            label = "shellSection",
        ) { section: YoinSection ->
            when (section) {
                YoinSection.HOME -> HomeScreen(
                    viewModel = homeViewModel,
                    isPlaying = playbackState.isPlaying,
                    visualizerData = visualizerData,
                    onNavigateToSettings = onNavigateToSettings,
                    onAlbumClick = onNavigateToAlbum,
                    onSongClick = { song ->
                        app.container.playbackManager.playSingle(
                            song,
                            app.container.getCredentials(),
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                YoinSection.LIBRARY -> LibraryScreen(
                    viewModel = libraryViewModel,
                    onNavigateToSettings = onNavigateToSettings,
                    onArtistClick = onNavigateToArtist,
                    onAlbumClick = onNavigateToAlbum,
                    onSongClick = { song ->
                        app.container.playbackManager.playSingle(
                            song,
                            app.container.getCredentials(),
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ── Background scrim — dims content as NP opens ───────────────────
        val scrimAlpha by animateFloatAsState(
            targetValue = if (showNowPlaying) 0.5f else 0f,
            animationSpec = spring(stiffness = Spring.StiffnessLow),
            label = "scrimAlpha",
        )
        if (scrimAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha)),
            )
        }

        // ── Now Playing overlay ────────────────────────────────────────────
        // Slide + fade for non-shared content; cover/title/artist morph via shared elements.
        AnimatedVisibility(
            visible = showNowPlaying,
            enter = slideInVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ) { it } +
                fadeIn(spring(stiffness = Spring.StiffnessLow)),
            exit = slideOutVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ) { it } +
                fadeOut(spring(stiffness = Spring.StiffnessLow)),
            modifier = Modifier.fillMaxSize(),
        ) {
            val npAvScope = this

            var dismissDragPx by remember { mutableStateOf(0f) }
            val draggableState = rememberDraggableState { delta ->
                if (delta > 0f || dismissDragPx > 0f) {
                    dismissDragPx = (dismissDragPx + delta).coerceAtLeast(0f)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .draggable(
                        state = draggableState,
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity ->
                            if (dismissDragPx > 240f || velocity > 800f) {
                                showNowPlaying = false
                                dismissDragPx = 0f
                            } else {
                                animate(
                                    initialValue = dismissDragPx,
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                ) { value, _ ->
                                    dismissDragPx = value
                                }
                            }
                        },
                    ),
            ) {
                NowPlayingScreen(
                    uiState = nowPlayingUiState,
                    visualizerData = visualizerData,
                    onTogglePlayPause = nowPlayingViewModel::togglePlayPause,
                    onSkipNext = nowPlayingViewModel::skipNext,
                    onSkipPrevious = nowPlayingViewModel::skipPrevious,
                    onSeek = nowPlayingViewModel::seekTo,
                    onRatingChange = nowPlayingViewModel::setRating,
                    onToggleFavorite = nowPlayingViewModel::toggleFavorite,
                    onSkipToQueueItem = nowPlayingViewModel::skipToQueueItem,
                    onDismiss = {
                        dragOffsetPx = 0f
                        predictiveBackProgress = 0f
                        showNowPlaying = false
                    },
                    castState = castState,
                    onCastClick = { },
                    sharedTransitionScope = sharedScope,
                    animatedVisibilityScope = npAvScope,
                    modifier = Modifier
                        .fillMaxSize()
                        .offset {
                            IntOffset(
                                0,
                                (overlayOffsetPx + dismissDragPx).roundToInt(),
                            )
                        },
                )
            }
        }

        // ── Bottom navigation — slides down when NP opens ─────────────────
        AnimatedVisibility(
            visible = !showNowPlaying,
            enter = fadeIn(spring(stiffness = Spring.StiffnessLow)) +
                slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it },
            exit = fadeOut(spring(stiffness = Spring.StiffnessLow)) +
                slideOutVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it },
        ) {
            val bgAvScope = this
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                YoinButtonGroup(
                    selectedSection = selectedSection,
                    currentTrackTitle = playbackState.currentSong?.title,
                    currentTrackArtist = playbackState.currentSong?.artist,
                    currentTrackCoverArtUrl = coverArtUrl,
                    isPlaybackReady = playbackState.controllerReady,
                    connectionErrorMessage = playbackState.connectionErrorMessage,
                    playbackProgress = if (playbackState.duration > 0L) {
                        (playbackState.position.toFloat() / playbackState.duration)
                            .coerceIn(0f, 1f)
                    } else {
                        0f
                    },
                    onHomeClick = { selectedSection = YoinSection.HOME },
                    onNowPlayingClick = { showNowPlaying = true },
                    onLibraryClick = { selectedSection = YoinSection.LIBRARY },
                    sharedTransitionScope = sharedScope,
                    animatedVisibilityScope = bgAvScope,
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun YoinNavHostPreview() {
    YoinTheme {
        YoinNavHost(navController = rememberNavController())
    }
}
