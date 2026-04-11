package com.gpo.yoin.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.ui.component.YoinButtonGroup
import com.gpo.yoin.ui.detail.AlbumDetailScreen
import com.gpo.yoin.ui.detail.AlbumDetailUiState
import com.gpo.yoin.ui.detail.AlbumDetailViewModel
import com.gpo.yoin.ui.detail.ArtistDetailScreen
import com.gpo.yoin.ui.detail.ArtistDetailViewModel
import com.gpo.yoin.ui.detail.PlaylistDetailScreen
import com.gpo.yoin.ui.detail.PlaylistDetailUiState
import com.gpo.yoin.ui.detail.PlaylistDetailViewModel
import com.gpo.yoin.ui.home.HomeScreen
import com.gpo.yoin.ui.home.HomeViewModel
import com.gpo.yoin.ui.library.LibraryScreen
import com.gpo.yoin.ui.library.LibraryViewModel
import com.gpo.yoin.ui.experience.HomeSurface
import com.gpo.yoin.ui.experience.rememberPredictiveBackPreviewState
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryEntry
import com.gpo.yoin.ui.memories.MemoriesScreen
import com.gpo.yoin.ui.memories.MemoriesViewModel
import com.gpo.yoin.ui.nowplaying.NowPlayingScreen
import com.gpo.yoin.ui.nowplaying.NowPlayingViewModel
import com.gpo.yoin.ui.settings.SettingsScreen
import com.gpo.yoin.ui.settings.SettingsViewModel
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

private val PredictiveBackPreviewMaxCornerRadius: Dp = 28.dp
private val PredictiveBackPreviewMaxEdgeInset: Dp = 12.dp
private const val OverlayPredictiveBackPreviewCap = 0.8f
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun YoinNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    SharedTransitionLayout(modifier = modifier) {
        val sharedTransitionScope = this

        NavHost(
            navController = navController,
            startDestination = YoinRoute.Shell,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { YoinMotion.navHostStableEnter },
            exitTransition = { YoinMotion.navHostStableExit },
            popEnterTransition = { YoinMotion.navHostStableEnter },
            popExitTransition = { YoinMotion.navHostStableExit },
        ) {
            composable<YoinRoute.Shell> {
                val shellAnimatedVisibilityScope = this
                val app = LocalContext.current.applicationContext as YoinApplication
                val homeViewModel: HomeViewModel = viewModel(
                    factory = HomeViewModel.Factory(app.container),
                )
                val libraryViewModel: LibraryViewModel = viewModel(
                    factory = LibraryViewModel.Factory(app.container),
                )
                val memoriesViewModel: MemoriesViewModel = viewModel(
                    factory = MemoriesViewModel.Factory(app.container),
                )
                val nowPlayingViewModel: NowPlayingViewModel = viewModel(
                    factory = NowPlayingViewModel.Factory(app.container),
                )

                YoinShell(
                    app = app,
                    homeViewModel = homeViewModel,
                    libraryViewModel = libraryViewModel,
                    memoriesViewModel = memoriesViewModel,
                    nowPlayingViewModel = nowPlayingViewModel,
                    onNavigateToSettings = { navController.navigate(YoinRoute.Settings) },
                    onNavigateToAlbum = { albumId, sharedTransitionKey ->
                        navController.navigate(
                            YoinRoute.AlbumDetail(
                                albumId = albumId,
                                sharedTransitionKey = sharedTransitionKey,
                            ),
                        )
                    },
                    onNavigateToArtist = { navController.navigate(YoinRoute.ArtistDetail(it)) },
                    onNavigateToPlaylist = { navController.navigate(YoinRoute.PlaylistDetail(it)) },
                    sharedTransitionScope = sharedTransitionScope,
                    shellAnimatedVisibilityScope = shellAnimatedVisibilityScope,
                )
            }

            composable<YoinRoute.AlbumDetail>(
                enterTransition = { albumDetailEnterTransition() },
                exitTransition = { YoinMotion.navExitForward },
                popEnterTransition = { YoinMotion.navEnterBack },
                popExitTransition = { albumDetailPopExitTransition() },
            ) { backStackEntry ->
                val albumDetailAnimatedVisibilityScope = this
                val route = backStackEntry.toRoute<YoinRoute.AlbumDetail>()
                val app = LocalContext.current.applicationContext as YoinApplication
                val viewModel: AlbumDetailViewModel = viewModel(
                    factory = AlbumDetailViewModel.Factory(route.albumId, app.container),
                )
                val uiState by viewModel.uiState.collectAsState()
                PredictiveBackPreviewContainer(
                    onBackCommitted = { navController.popBackStack() },
                ) { predictiveBackModifier ->
                    AlbumDetailScreen(
                        uiState = uiState,
                        sharedTransitionKey = route.sharedTransitionKey,
                        onBackClick = { navController.popBackStack() },
                        onSongClick = { songId ->
                            val songs = viewModel.getAlbumSongs()
                            val index = songs.indexOfFirst { it.id == songId }.coerceAtLeast(0)
                            val activityContext = (uiState as? AlbumDetailUiState.Content)?.let { content ->
                                ActivityContext.Album(
                                    albumId = content.albumId,
                                    albumName = content.albumName,
                                    artistName = content.artistName,
                                    artistId = content.artistId,
                                    coverArtId = content.coverArtId,
                                )
                            } ?: ActivityContext.None
                            app.container.playbackManager.play(
                                songs = songs,
                                startIndex = index,
                                credentials = app.container.getCredentials(),
                                activityContext = activityContext,
                            )
                        },
                        onToggleStar = viewModel::toggleStar,
                        onRetry = viewModel::retry,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = albumDetailAnimatedVisibilityScope,
                        modifier = predictiveBackModifier.fillMaxSize(),
                    )
                }
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
                PredictiveBackPreviewContainer(
                    onBackCommitted = { navController.popBackStack() },
                ) { predictiveBackModifier ->
                    ArtistDetailScreen(
                        uiState = uiState,
                        onBackClick = { navController.popBackStack() },
                        onAlbumClick = { albumId ->
                            navController.navigate(YoinRoute.AlbumDetail(albumId))
                        },
                        onRetry = viewModel::retry,
                        modifier = predictiveBackModifier.fillMaxSize(),
                    )
                }
            }

            composable<YoinRoute.PlaylistDetail>(
                enterTransition = { YoinMotion.navEnterForward },
                exitTransition = { YoinMotion.navExitForward },
                popEnterTransition = { YoinMotion.navEnterBack },
                popExitTransition = { YoinMotion.navExitBack },
            ) { backStackEntry ->
                val route = backStackEntry.toRoute<YoinRoute.PlaylistDetail>()
                val app = LocalContext.current.applicationContext as YoinApplication
                val viewModel: PlaylistDetailViewModel = viewModel(
                    factory = PlaylistDetailViewModel.Factory(route.playlistId, app.container),
                )
                val uiState by viewModel.uiState.collectAsState()
                PredictiveBackPreviewContainer(
                    onBackCommitted = { navController.popBackStack() },
                ) { predictiveBackModifier ->
                    PlaylistDetailScreen(
                        uiState = uiState,
                        onBackClick = { navController.popBackStack() },
                        onPlayAllClick = {
                            val songs = viewModel.getPlaylistSongs()
                            if (songs.isNotEmpty()) {
                                val activityContext = (uiState as? PlaylistDetailUiState.Content)?.let { content ->
                                    ActivityContext.Playlist(
                                        playlistId = route.playlistId,
                                        playlistName = content.playlistName,
                                        owner = content.owner.takeIf { it.isNotBlank() },
                                        coverArtId = songs.firstNotNullOfOrNull { song ->
                                            song.coverArt ?: song.albumId
                                        },
                                    )
                                } ?: ActivityContext.None
                                app.container.playbackManager.play(
                                    songs = songs,
                                    startIndex = 0,
                                    credentials = app.container.getCredentials(),
                                    activityContext = activityContext,
                                )
                            }
                        },
                        onSongClick = { songId ->
                            val songs = viewModel.getPlaylistSongs()
                            val index = songs.indexOfFirst { it.id == songId }.coerceAtLeast(0)
                            val activityContext = (uiState as? PlaylistDetailUiState.Content)?.let { content ->
                                ActivityContext.Playlist(
                                    playlistId = route.playlistId,
                                    playlistName = content.playlistName,
                                    owner = content.owner.takeIf { it.isNotBlank() },
                                    coverArtId = songs.firstNotNullOfOrNull { song ->
                                        song.coverArt ?: song.albumId
                                    },
                                )
                            } ?: ActivityContext.None
                            app.container.playbackManager.play(
                                songs = songs,
                                startIndex = index,
                                credentials = app.container.getCredentials(),
                                activityContext = activityContext,
                            )
                        },
                        onRetry = viewModel::retry,
                        modifier = predictiveBackModifier.fillMaxSize(),
                    )
                }
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
                PredictiveBackPreviewContainer(
                    onBackCommitted = { navController.popBackStack() },
                ) { predictiveBackModifier ->
                    SettingsScreen(
                        viewModel = viewModel,
                        modifier = predictiveBackModifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.albumDetailEnterTransition():
    EnterTransition =
    if (initialState.destination.hasRoute<YoinRoute.Shell>()) {
        YoinMotion.albumDetailSharedEnter
    } else {
        YoinMotion.navEnterForward
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.albumDetailPopExitTransition():
    ExitTransition =
    if (targetState.destination.hasRoute<YoinRoute.Shell>()) {
        YoinMotion.albumDetailSharedPopExit
    } else {
        YoinMotion.navExitBack
    }

@Composable
private fun PredictiveBackPreviewContainer(
    onBackCommitted: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val previewState = rememberPredictiveBackPreviewState()
    val layoutDirection = LocalLayoutDirection.current
    val previewFraction = previewState.previewFraction
    val transformOrigin = remember(layoutDirection) {
        TransformOrigin(
            pivotFractionX = if (layoutDirection == LayoutDirection.Rtl) 1f else 0f,
            pivotFractionY = 0.5f,
        )
    }
    val previewShape = remember(previewFraction) {
        androidx.compose.foundation.shape.RoundedCornerShape(
            lerp(0.dp, PredictiveBackPreviewMaxCornerRadius, previewFraction),
        )
    }

    PredictiveBackHandler { progress ->
        var committed = false
        try {
            progress.collectLatest { event ->
                previewState.update(event.progress)
            }
            committed = true
            onBackCommitted()
        } finally {
            if (!committed) {
                if (previewState.progress > 0f) {
                    previewState.animateReset()
                }
                previewState.snapToHidden()
            }
        }
    }

    content(
        modifier.graphicsLayer {
            val scale = 1f - (0.1f * previewState.progress)
            val edgeInsetPx = lerp(0.dp, PredictiveBackPreviewMaxEdgeInset, previewFraction)
                .toPx()
            scaleX = scale
            scaleY = scale
            translationX = if (layoutDirection == LayoutDirection.Rtl) {
                -edgeInsetPx
            } else {
                edgeInsetPx
            }
            shape = previewShape
            clip = previewState.progress > 0f
            this.transformOrigin = transformOrigin
        },
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun YoinShell(
    app: YoinApplication,
    homeViewModel: HomeViewModel,
    libraryViewModel: LibraryViewModel,
    memoriesViewModel: MemoriesViewModel,
    nowPlayingViewModel: NowPlayingViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToAlbum: (String, String?) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToPlaylist: (String) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    shellAnimatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    val experienceSessionStore = app.container.experienceSessionStore
    val experienceSession by experienceSessionStore.state.collectAsState()
    val selectedSection = experienceSession.selectedSection
    val homeSurface = experienceSession.homeSurface
    val showNowPlaying = experienceSession.nowPlayingExpanded
    var predictiveBackProgress by remember { mutableStateOf(0f) }

    val playbackState by app.container.playbackManager.playbackState.collectAsState()
    val visualizerData by app.container.audioVisualizerManager.visualizerData.collectAsState()
    val castState by app.container.castManager.castState.collectAsState()
    val nowPlayingUiState by nowPlayingViewModel.uiState.collectAsState()
    val overlaySpatialSpec = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    val memoriesEnterSpec = YoinMotion.slowSpatialSpec<Float>(role = YoinMotionRole.Standard)
    val memoriesExitSpec = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Standard)
    val shellFadeSpec = YoinMotion.defaultEffectsSpec<Float>(role = YoinMotionRole.Standard)

    val coverArtUrl = playbackState.currentSong?.coverArt?.let {
        app.container.repository.buildCoverArtUrl(it)
    }

    LaunchedEffect(Unit) {
        app.container.playbackManager.connectInBackground()
    }

    var memoriesBackProgress by remember { mutableStateOf(0f) }
    val memoriesSlideOffset = remember {
        Animatable(-1f).also { animatable ->
            // Prevent spring overshoot from crossing the resting position and "bouncing" on open.
            animatable.updateBounds(lowerBound = -1f, upperBound = 0f)
        }
    }

    BackHandler(enabled = showNowPlaying) {
        predictiveBackProgress = 0f
        experienceSessionStore.setNowPlayingExpanded(false)
    }

    PredictiveBackHandler(
        enabled = !showNowPlaying &&
            selectedSection == YoinSection.HOME &&
            homeSurface == HomeSurface.Memories,
    ) { progress ->
        var committed = false
        try {
            progress.collectLatest { event ->
                memoriesBackProgress = event.progress * OverlayPredictiveBackPreviewCap
            }
            committed = true
            animate(
                initialValue = memoriesBackProgress,
                targetValue = 1f,
                animationSpec = memoriesExitSpec,
            ) { value, _ ->
                memoriesBackProgress = value
            }
            memoriesSlideOffset.snapTo(-1f)
            experienceSessionStore.setHomeSurface(HomeSurface.Feed)
        } finally {
            if (!committed && memoriesBackProgress > 0f) {
                animate(
                    initialValue = memoriesBackProgress,
                    targetValue = 0f,
                    animationSpec = YoinMotion.predictiveBackSettleSpring(),
                ) { value, _ ->
                    memoriesBackProgress = value
                }
            }
            memoriesBackProgress = 0f
        }
    }

    PredictiveBackHandler(enabled = showNowPlaying) { progress ->
        var committed = false
        try {
            progress.collectLatest { event ->
                predictiveBackProgress = event.progress * OverlayPredictiveBackPreviewCap
            }
            committed = true
            animate(
                initialValue = predictiveBackProgress,
                targetValue = 1f,
                animationSpec = overlaySpatialSpec,
            ) { value, _ ->
                predictiveBackProgress = value
            }
            experienceSessionStore.setNowPlayingExpanded(false)
        } finally {
            if (!committed && predictiveBackProgress > 0f) {
                animate(
                    initialValue = predictiveBackProgress,
                    targetValue = 0f,
                    animationSpec = YoinMotion.predictiveBackSettleSpring(),
                ) { value, _ ->
                    predictiveBackProgress = value
                }
            }
            predictiveBackProgress = 0f
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedContent<YoinSection>(
            targetState = selectedSection,
            transitionSpec = {
                YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                    YoinMotion.fadeOut(role = YoinMotionRole.Standard)
            },
            modifier = Modifier.fillMaxSize(),
            label = "shellSection",
        ) { section: YoinSection ->
            when (section) {
                YoinSection.HOME -> {
                    val homeBgColor = MaterialTheme.colorScheme.background
                    val memoriesActive = homeSurface == HomeSurface.Memories
                    val memoriesVisible = memoriesActive ||
                        memoriesSlideOffset.value > -0.99f ||
                        memoriesBackProgress > 0f

                    LaunchedEffect(memoriesActive) {
                        if (memoriesActive) {
                            memoriesSlideOffset.snapTo(-1f)
                            memoriesSlideOffset.animateTo(
                                0f,
                                memoriesEnterSpec,
                            )
                        } else if (memoriesSlideOffset.value > -0.99f) {
                            // Regular back — animate slide out
                            memoriesSlideOffset.animateTo(
                                -1f,
                                memoriesExitSpec,
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(homeBgColor),
                    ) {
                        HomeScreen(
                            viewModel = homeViewModel,
                            isPlaying = playbackState.isPlaying,
                            visualizerData = visualizerData,
                            activeSongId = playbackState.currentSong?.id,
                            onNavigateToSettings = onNavigateToSettings,
                            onNavigateToMemories = {
                                experienceSessionStore.setHomeSurface(HomeSurface.Memories)
                            },
                            onAlbumClick = onNavigateToAlbum,
                            onArtistClick = onNavigateToArtist,
                            onPlaylistClick = onNavigateToPlaylist,
                            onSongClick = { song ->
                                app.container.playbackManager.playSingle(
                                    song,
                                    app.container.getCredentials(),
                                )
                            },
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = shellAnimatedVisibilityScope,
                            modifier = Modifier.fillMaxSize(),
                        )

                        if (memoriesVisible) {
                            MemoriesScreen(
                                viewModel = memoriesViewModel,
                                onBackClick = {
                                    experienceSessionStore.setHomeSurface(HomeSurface.Feed)
                                },
                                onPlayMemoryTrack = { memory, trackIndex ->
                                    val queue = memory.playbackSongs
                                    if (queue.isNotEmpty()) {
                                        val startIndex = trackIndex.coerceIn(0, queue.lastIndex)
                                        val selectedSong = queue[startIndex]
                                        val activityContext = memory.toPlaybackActivityContext()

                                        if (memory.entityType == MemoryEntityType.SONG || queue.size <= 1) {
                                            app.container.playbackManager.playSingle(
                                                song = selectedSong,
                                                credentials = app.container.getCredentials(),
                                                activityContext = activityContext,
                                            )
                                        } else {
                                            app.container.playbackManager.play(
                                                songs = queue,
                                                startIndex = startIndex,
                                                credentials = app.container.getCredentials(),
                                                activityContext = activityContext,
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        translationY = if (memoriesBackProgress > 0f) {
                                            -memoriesBackProgress * size.height
                                        } else {
                                            memoriesSlideOffset.value * size.height
                                        }
                                    },
                            )
                        }
                    }
                }

                YoinSection.LIBRARY -> LibraryScreen(
                    viewModel = libraryViewModel,
                    activeSongId = playbackState.currentSong?.id,
                    isPlaying = playbackState.isPlaying,
                    visualizerData = visualizerData,
                    onNavigateToSettings = onNavigateToSettings,
                    onArtistClick = onNavigateToArtist,
                    onAlbumClick = { albumId -> onNavigateToAlbum(albumId, null) },
                    onPlaylistClick = onNavigateToPlaylist,
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
            animationSpec = shellFadeSpec,
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
            enter = YoinMotion.slideInVertically(role = YoinMotionRole.Expressive) { it } +
                YoinMotion.fadeIn(role = YoinMotionRole.Standard),
            exit = YoinMotion.slideOutVertically(role = YoinMotionRole.Standard) { it } +
                YoinMotion.fadeOut(role = YoinMotionRole.Standard),
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
                                experienceSessionStore.setNowPlayingExpanded(false)
                                dismissDragPx = 0f
                            } else {
                                animate(
                                    initialValue = dismissDragPx,
                                    targetValue = 0f,
                                    animationSpec = overlaySpatialSpec,
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
                        predictiveBackProgress = 0f
                        experienceSessionStore.setNowPlayingExpanded(false)
                    },
                    castState = castState,
                    onCastClick = { },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = npAvScope,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationY = (predictiveBackProgress * size.height) + dismissDragPx
                        },
                )
            }
        }

        // ── Bottom navigation — slides down when NP opens ─────────────────
        AnimatedVisibility(
            visible = !showNowPlaying &&
                !(selectedSection == YoinSection.HOME && homeSurface == HomeSurface.Memories),
            enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard) +
                YoinMotion.slideInVertically(role = YoinMotionRole.Standard) { it },
            exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard) +
                YoinMotion.slideOutVertically(role = YoinMotionRole.Standard) { it },
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
                    isPlaying = playbackState.isPlaying,
                    onHomeClick = {
                        experienceSessionStore.setSelectedSection(YoinSection.HOME)
                        experienceSessionStore.setHomeSurface(HomeSurface.Feed)
                    },
                    onNowPlayingClick = {
                        experienceSessionStore.setNowPlayingExpanded(true)
                    },
                    onLibraryClick = {
                        experienceSessionStore.setSelectedSection(YoinSection.LIBRARY)
                        experienceSessionStore.setHomeSurface(HomeSurface.Feed)
                    },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = bgAvScope,
                )
            }
        }
    }
}

private fun MemoryEntry.toPlaybackActivityContext(): ActivityContext {
    val firstSong = playbackSongs.firstOrNull()
    val coverArtId = playbackSongs.firstNotNullOfOrNull { song ->
        song.coverArt ?: song.albumId
    }

    return when (entityType) {
        MemoryEntityType.ALBUM -> ActivityContext.Album(
            albumId = entityId,
            albumName = title,
            artistName = firstSong?.artist,
            artistId = firstSong?.artistId,
            coverArtId = coverArtId ?: entityId,
        )

        MemoryEntityType.PLAYLIST -> ActivityContext.Playlist(
            playlistId = entityId,
            playlistName = title,
            coverArtId = coverArtId,
        )

        MemoryEntityType.SONG -> ActivityContext.None
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun YoinNavHostPreview() {
    YoinTheme {
        YoinNavHost(navController = rememberNavController())
    }
}
