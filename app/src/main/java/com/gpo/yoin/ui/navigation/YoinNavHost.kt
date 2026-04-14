package com.gpo.yoin.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.togetherWith
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
                enterTransition = { YoinMotion.simplePushEnter },
                exitTransition = { YoinMotion.simplePushExit },
                popEnterTransition = { YoinMotion.simplePushPopEnter },
                popExitTransition = { YoinMotion.simplePushPopExit },
            ) { backStackEntry ->
                val route = backStackEntry.toRoute<YoinRoute.AlbumDetail>()
                val app = LocalContext.current.applicationContext as YoinApplication
                val viewModel: AlbumDetailViewModel = viewModel(
                    factory = AlbumDetailViewModel.Factory(route.albumId, app.container),
                )
                val uiState by viewModel.uiState.collectAsState()
                YoinBackSurface(
                    kind = BackSurfaceKind.PushPage,
                    onCommitBack = { navController.popBackStack() },
                ) { predictiveBackModifier, _, requestBack ->
                    AlbumDetailScreen(
                        uiState = uiState,
                        sharedTransitionKey = null,
                        onBackClick = requestBack,
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
                        sharedTransitionScope = null,
                        animatedVisibilityScope = null,
                        modifier = predictiveBackModifier.fillMaxSize(),
                    )
                }
            }

            composable<YoinRoute.ArtistDetail>(
                enterTransition = { YoinMotion.simplePushEnter },
                exitTransition = { YoinMotion.simplePushExit },
                popEnterTransition = { YoinMotion.simplePushPopEnter },
                popExitTransition = { YoinMotion.simplePushPopExit },
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
                ) { predictiveBackModifier, _, requestBack ->
                    ArtistDetailScreen(
                        uiState = uiState,
                        onBackClick = requestBack,
                        onAlbumClick = { albumId ->
                            navController.navigate(YoinRoute.AlbumDetail(albumId))
                        },
                        onRetry = viewModel::retry,
                        modifier = predictiveBackModifier.fillMaxSize(),
                    )
                }
            }

            composable<YoinRoute.PlaylistDetail>(
                enterTransition = { YoinMotion.simplePushEnter },
                exitTransition = { YoinMotion.simplePushExit },
                popEnterTransition = { YoinMotion.simplePushPopEnter },
                popExitTransition = { YoinMotion.simplePushPopExit },
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
                ) { predictiveBackModifier, _, requestBack ->
                    PlaylistDetailScreen(
                        uiState = uiState,
                        onBackClick = requestBack,
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
                enterTransition = { YoinMotion.simplePushEnter },
                exitTransition = { YoinMotion.simplePushExit },
                popEnterTransition = { YoinMotion.simplePushPopEnter },
                popExitTransition = { YoinMotion.simplePushPopExit },
            ) {
                val app = LocalContext.current.applicationContext as YoinApplication
                val viewModel: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.Factory(app.container),
                )
                YoinBackSurface(
                    kind = BackSurfaceKind.PushPage,
                    onCommitBack = { navController.popBackStack() },
                ) { predictiveBackModifier, _, requestBack ->
                    SettingsScreen(
                        viewModel = viewModel,
                        onBackClick = requestBack,
                        modifier = predictiveBackModifier.fillMaxSize(),
                    )
                }
            }
        }
    }
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
    val shellScope = rememberCoroutineScope()
    val memoriesBackController = rememberBackSurfaceController()
    var memoriesMounted by remember { mutableStateOf(homeSurface == HomeSurface.Memories) }
    val npSheetState = rememberNowPlayingSheetState()

    val coverArtUrl = playbackState.currentSong?.coverArt?.let {
        app.container.repository.buildCoverArtUrl(it)
    }

    LaunchedEffect(Unit) {
        app.container.playbackManager.connectInBackground()
    }

    val shellBackOwner = resolveShellBackOwner(
        showNowPlaying = npSheetState.isVisible,
        selectedSection = selectedSection,
        homeSurface = homeSurface,
    )
    val memoriesActive = selectedSection == YoinSection.HOME && homeSurface == HomeSurface.Memories

    val closeMemories = remember(experienceSessionStore) {
        {
            experienceSessionStore.setHomeSurface(HomeSurface.Feed)
            memoriesMounted = false
            Unit
        }
    }

    LaunchedEffect(memoriesActive) {
        if (memoriesActive) {
            memoriesMounted = true
            memoriesBackController.snapTo(1f)
            memoriesBackController.animateCancel()
        }
    }

    LaunchedEffect(selectedSection) {
        if (selectedSection != YoinSection.HOME) {
            memoriesMounted = false
            memoriesBackController.reset()
        }
    }

    // Sync store → sheet: animate expand/collapse when external trigger fires
    LaunchedEffect(showNowPlaying) {
        if (showNowPlaying && npSheetState.progress < 1f) {
            npSheetState.animateExpand()
        } else if (!showNowPlaying && npSheetState.progress > 0f && !npSheetState.isDragging) {
            npSheetState.animateCollapse()
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
                            ) { backModifier, controller, requestBack ->
                                MemoriesScreen(
                                    viewModel = memoriesViewModel,
                                    dismissFraction = controller.fraction,
                                    onDismissGestureProgress = controller::updateFromDrag,
                                    onDismissGestureCommit = { requestBack() },
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
                                    modifier = backModifier.fillMaxSize(),
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

        // ── Bottom navigation ────────────────────────────────────────────
        AnimatedVisibility(
            visible = !npSheetState.isVisible &&
                !(selectedSection == YoinSection.HOME && homeSurface == HomeSurface.Memories),
            enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard) +
                YoinMotion.slideInVertically(role = YoinMotionRole.Standard) { it },
            exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard) +
                YoinMotion.slideOutVertically(role = YoinMotionRole.Standard) { it },
        ) {
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
                        experienceSessionStore.setHomeSurface(HomeSurface.Feed)
                    },
                    onNowPlayingClick = {
                        experienceSessionStore.setNowPlayingExpanded(true)
                        // Directly trigger expand — don't rely solely on LaunchedEffect,
                        // which won't restart if the store was already 'true'.
                        shellScope.launch { npSheetState.animateExpand() }
                    },
                    onLibraryClick = {
                        experienceSessionStore.setSelectedSection(YoinSection.LIBRARY)
                        memoriesMounted = false
                        memoriesBackController.reset()
                        experienceSessionStore.setHomeSurface(HomeSurface.Feed)
                    },
                    sharedTransitionScope = null,
                    animatedVisibilityScope = null,
                )
            }
        }

        // ── Background scrim ─────────────────────────────────────────────
        val scrimAlpha = npSheetState.progress * 0.5f
        if (scrimAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha)),
            )
        }

        // ── Now Playing overlay ──────────────────────────────────────────
        // Predictive back: system gesture feeds progress into the sheet
        // state so dismiss preview, drag-to-dismiss, and the dismiss button
        // all share one controller.
        PredictiveBackHandler(
            enabled = npSheetState.isVisible && !npSheetState.isDragging,
        ) { backEvents ->
            npSheetState.onPredictiveBackStart()
            try {
                backEvents.collect { event ->
                    npSheetState.onPredictiveBackProgress(event.progress)
                }
                // Back committed — animate to collapsed
                npSheetState.onPredictiveBackEnd()
                npSheetState.animateCollapse {
                    experienceSessionStore.setNowPlayingExpanded(false)
                }
            } catch (_: kotlin.coroutines.cancellation.CancellationException) {
                // Back cancelled — spring back to expanded
                npSheetState.onPredictiveBackEnd()
                npSheetState.animateExpand()
            }
        }

        if (npSheetState.isVisible) {
            val density = LocalDensity.current

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val heightPx = with(density) { maxHeight.toPx().coerceAtLeast(1f) }
                val draggableState = rememberDraggableState { delta ->
                    npSheetState.onDrag(delta, heightPx)
                }

                // Corner radius: fully rounded when collapsed, sharp when expanded
                val cornerFraction = (1f - npSheetState.progress).coerceIn(0f, 1f)
                val npCornerShape = remember(cornerFraction) {
                    RoundedCornerShape(
                        BackMotionTokens.NowPlayingCornerRadius * cornerFraction,
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .draggable(
                            state = draggableState,
                            orientation = Orientation.Vertical,
                            onDragStarted = { npSheetState.onDragStart() },
                            onDragStopped = { velocity ->
                                npSheetState.settle(
                                    velocityPxPerSec = velocity,
                                    heightPx = heightPx,
                                    onSettledCollapsed = {
                                        experienceSessionStore.setNowPlayingExpanded(false)
                                    },
                                )
                            },
                        )
                        .graphicsLayer {
                            translationY = (1f - npSheetState.progress) * size.height
                            alpha = 0.7f + npSheetState.progress * 0.3f
                            shape = npCornerShape
                            clip = cornerFraction > 0f
                        },
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
                                npSheetState.animateCollapse {
                                    experienceSessionStore.setNowPlayingExpanded(false)
                                }
                            }
                        },
                        castState = castState,
                        onCastClick = { },
                        sharedTransitionScope = null,
                        animatedVisibilityScope = null,
                        dismissProgress = 1f - npSheetState.progress,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
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
