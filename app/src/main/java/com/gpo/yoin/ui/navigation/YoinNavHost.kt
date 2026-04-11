package com.gpo.yoin.ui.navigation

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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.platform.LocalDensity
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
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryEntry
import com.gpo.yoin.ui.memories.MemoriesScreen
import com.gpo.yoin.ui.memories.MemoriesViewModel
import com.gpo.yoin.ui.navigation.back.BackMotionTokens
import com.gpo.yoin.ui.navigation.back.BackSurfaceKind
import com.gpo.yoin.ui.navigation.back.ShellBackOwner
import com.gpo.yoin.ui.navigation.back.YoinBackSurface
import com.gpo.yoin.ui.navigation.back.rememberBackSurfaceController
import com.gpo.yoin.ui.navigation.back.resolveShellBackOwner
import com.gpo.yoin.ui.nowplaying.NowPlayingScreen
import com.gpo.yoin.ui.nowplaying.NowPlayingViewModel
import com.gpo.yoin.ui.settings.SettingsScreen
import com.gpo.yoin.ui.settings.SettingsViewModel
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.launch

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
                YoinBackSurface(
                    kind = BackSurfaceKind.PushPage,
                    onCommitBack = { navController.popBackStack() },
                ) { predictiveBackModifier, _ ->
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
                YoinBackSurface(
                    kind = BackSurfaceKind.PushPage,
                    onCommitBack = { navController.popBackStack() },
                ) { predictiveBackModifier, _ ->
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
                YoinBackSurface(
                    kind = BackSurfaceKind.PushPage,
                    onCommitBack = { navController.popBackStack() },
                ) { predictiveBackModifier, _ ->
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
                YoinBackSurface(
                    kind = BackSurfaceKind.PushPage,
                    onCommitBack = { navController.popBackStack() },
                ) { predictiveBackModifier, _ ->
                    SettingsScreen(
                        viewModel = viewModel,
                        onBackClick = { navController.popBackStack() },
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
    val playbackState by app.container.playbackManager.playbackState.collectAsState()
    val visualizerData by app.container.audioVisualizerManager.visualizerData.collectAsState()
    val castState by app.container.castManager.castState.collectAsState()
    val nowPlayingUiState by nowPlayingViewModel.uiState.collectAsState()
    val memoriesEnterSpec = YoinMotion.slowSpatialSpec<Float>(role = YoinMotionRole.Standard)
    val shellFadeSpec = YoinMotion.defaultEffectsSpec<Float>(role = YoinMotionRole.Standard)
    val shellScope = rememberCoroutineScope()
    val nowPlayingBackController = rememberBackSurfaceController()
    val memoriesBackController = rememberBackSurfaceController()
    var memoriesMounted by remember { mutableStateOf(homeSurface == HomeSurface.Memories) }

    val coverArtUrl = playbackState.currentSong?.coverArt?.let {
        app.container.repository.buildCoverArtUrl(it)
    }

    LaunchedEffect(Unit) {
        app.container.playbackManager.connectInBackground()
    }

    val memoriesEntryOffset = remember {
        Animatable(-1f).also { animatable ->
            // Prevent spring overshoot from crossing the resting position and "bouncing" on open.
            animatable.updateBounds(lowerBound = -1f, upperBound = 0f)
        }
    }
    val shellBackOwner = resolveShellBackOwner(
        showNowPlaying = showNowPlaying,
        selectedSection = selectedSection,
        homeSurface = homeSurface,
    )
    val memoriesActive = selectedSection == YoinSection.HOME && homeSurface == HomeSurface.Memories
    val closeNowPlaying = remember(experienceSessionStore, nowPlayingBackController) {
        {
            experienceSessionStore.setNowPlayingExpanded(false)
            nowPlayingBackController.reset()
            Unit
        }
    }
    val closeMemories = remember(
        experienceSessionStore,
        memoriesBackController,
        memoriesEntryOffset,
        shellScope,
    ) {
        {
            memoriesMounted = false
            experienceSessionStore.setHomeSurface(HomeSurface.Feed)
            memoriesBackController.reset()
            shellScope.launch {
                memoriesEntryOffset.snapTo(-1f)
            }
            Unit
        }
    }

    LaunchedEffect(memoriesActive) {
        if (memoriesActive) {
            memoriesMounted = true
            memoriesEntryOffset.snapTo(-1f)
            memoriesEntryOffset.animateTo(
                targetValue = 0f,
                animationSpec = memoriesEnterSpec,
            )
        }
    }

    LaunchedEffect(selectedSection) {
        if (selectedSection != YoinSection.HOME) {
            memoriesMounted = false
            memoriesBackController.reset()
            memoriesEntryOffset.snapTo(-1f)
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
                                memoriesBackController.reset()
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

                        if (memoriesMounted) {
                            YoinBackSurface(
                                kind = BackSurfaceKind.ShellOverlayUp,
                                enabled = shellBackOwner == ShellBackOwner.Memories,
                                controller = memoriesBackController,
                                onCommitBack = closeMemories,
                            ) { backModifier, controller ->
                                MemoriesScreen(
                                    viewModel = memoriesViewModel,
                                    dismissFraction = controller.fraction,
                                    onDismissGestureProgress = controller::updateFromDrag,
                                    onDismissGestureCommit = {
                                        controller.animateCommit(closeMemories)
                                    },
                                    onDismissGestureCancel = controller::animateCancel,
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
                                    modifier = backModifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            translationY += memoriesEntryOffset.value * size.height
                                        },
                                )
                            }
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
        val baseScrimAlpha by animateFloatAsState(
            targetValue = if (showNowPlaying) 0.5f else 0f,
            animationSpec = shellFadeSpec,
            label = "scrimAlpha",
        )
        val scrimAlpha = if (showNowPlaying) {
            baseScrimAlpha * (1f - nowPlayingBackController.fraction)
        } else {
            baseScrimAlpha
        }
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
            exit = ExitTransition.None,
            modifier = Modifier.fillMaxSize(),
        ) {
            val npAvScope = this
            YoinBackSurface(
                kind = BackSurfaceKind.ShellOverlayDown,
                enabled = shellBackOwner == ShellBackOwner.NowPlaying,
                controller = nowPlayingBackController,
                onCommitBack = closeNowPlaying,
            ) { backModifier, controller ->
                val density = LocalDensity.current

                BoxWithConstraints(
                    modifier = backModifier.fillMaxSize(),
                ) {
                    val heightPx = with(density) { maxHeight.toPx().coerceAtLeast(1f) }
                    val draggableState = rememberDraggableState { delta ->
                        if (delta > 0f || controller.fraction > 0f) {
                            val dragPx = ((controller.fraction * heightPx) + delta).coerceAtLeast(0f)
                            controller.updateFromDrag((dragPx / heightPx).coerceIn(0f, 1f))
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .draggable(
                                state = draggableState,
                                orientation = Orientation.Vertical,
                                onDragStopped = { velocity ->
                                    shellScope.launch {
                                        if (
                                            controller.fraction >= BackMotionTokens.OverlayDismissThresholdFraction ||
                                            velocity > BackMotionTokens.SharedVelocityThresholdPx
                                        ) {
                                            controller.animateCommit(closeNowPlaying)
                                        } else {
                                            controller.animateCancel()
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
                                shellScope.launch {
                                    controller.animateCommit(closeNowPlaying)
                                }
                            },
                            castState = castState,
                            onCastClick = { },
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = npAvScope,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
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
                        memoriesMounted = false
                        memoriesBackController.reset()
                        shellScope.launch {
                            memoriesEntryOffset.snapTo(-1f)
                        }
                        experienceSessionStore.setHomeSurface(HomeSurface.Feed)
                    },
                    onNowPlayingClick = {
                        nowPlayingBackController.reset()
                        experienceSessionStore.setNowPlayingExpanded(true)
                    },
                    onLibraryClick = {
                        experienceSessionStore.setSelectedSection(YoinSection.LIBRARY)
                        memoriesMounted = false
                        memoriesBackController.reset()
                        shellScope.launch {
                            memoriesEntryOffset.snapTo(-1f)
                        }
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
