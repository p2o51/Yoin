package com.gpo.yoin.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.metadata
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.ui.component.AddToPlaylistSheet
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
import com.gpo.yoin.ui.experience.rememberRevealState
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryEntry
import com.gpo.yoin.ui.memories.MemoriesScreen
import com.gpo.yoin.ui.memories.MemoriesViewModel
import com.gpo.yoin.ui.navigation.back.BackSurfaceKind
import com.gpo.yoin.ui.navigation.back.ShellBackOwner
import com.gpo.yoin.ui.navigation.back.YoinBackSurface
import com.gpo.yoin.ui.navigation.back.resolveShellBackOwner
import com.gpo.yoin.player.PlaybackEvent
import com.gpo.yoin.player.SpotifyConnectFailure
import com.gpo.yoin.ui.nowplaying.NowPlayingScreen
import com.gpo.yoin.ui.nowplaying.NowPlayingViewModel
import com.gpo.yoin.ui.settings.SettingsScreen
import com.gpo.yoin.ui.settings.SettingsViewModel
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun YoinNavHost(
    modifier: Modifier = Modifier,
) {
    SharedTransitionLayout(modifier = modifier) {
        val sharedTransitionScope = this
        val app = LocalContext.current.applicationContext as YoinApplication
        val nowPlayingViewModel: NowPlayingViewModel = viewModel(
            factory = NowPlayingViewModel.Factory(app.container),
        )
        val addToPlaylistSnackbarHostState = remember { SnackbarHostState() }

        LaunchedEffect(nowPlayingViewModel) {
            nowPlayingViewModel.addToPlaylistMessages.collect { message ->
                addToPlaylistSnackbarHostState.showSnackbar(
                    message = message,
                    duration = SnackbarDuration.Short,
                )
            }
        }

        // Single top-level route (Shell). Detail pushes are appended onto this
        // one stack. `rememberNavBackStack` persists across config changes and
        // process death because every YoinRoute subtype is @Serializable + NavKey.
        val backStack = rememberNavBackStack(YoinRoute.Shell)

        Box(modifier = Modifier.fillMaxSize()) {
            NavDisplay(
                backStack = backStack,
                modifier = Modifier.fillMaxSize(),
                onBack = { backStack.removeLastOrNull() },
                // Required when NavDisplay runs inside a SharedTransitionLayout —
                // otherwise entries participating in shared bounds jump on scene
                // transitions. Docs: "Animate between destinations" §
                // SharedTransitionScope.
                sharedTransitionScope = sharedTransitionScope,
                // SaveableStateHolder preserves composable state per entry;
                // ViewModelStore scopes ViewModels to each NavEntry instance,
                // so navigating to the same route twice gives a fresh VM.
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                transitionSpec = {
                    YoinMotion.navHostStableEnter togetherWith YoinMotion.navHostStableExit
                },
                popTransitionSpec = {
                    YoinMotion.navHostStableEnter togetherWith YoinMotion.navHostStableExit
                },
                predictivePopTransitionSpec = {
                    YoinMotion.navHostStableEnter togetherWith YoinMotion.navHostStableExit
                },
                entryProvider = entryProvider {
                    entry<YoinRoute.Shell> {
                        val shellAnimatedVisibilityScope = LocalNavAnimatedContentScope.current
                        val homeViewModel: HomeViewModel = viewModel(
                            factory = HomeViewModel.Factory(app.container),
                        )
                        val libraryViewModel: LibraryViewModel = viewModel(
                            factory = LibraryViewModel.Factory(app.container),
                        )
                        val memoriesViewModel: MemoriesViewModel = viewModel(
                            factory = MemoriesViewModel.Factory(app.container),
                        )

                        YoinShell(
                            app = app,
                            homeViewModel = homeViewModel,
                            libraryViewModel = libraryViewModel,
                            memoriesViewModel = memoriesViewModel,
                            nowPlayingViewModel = nowPlayingViewModel,
                            onNavigateToSettings = { focusSection ->
                                backStack.add(YoinRoute.Settings(focusSection = focusSection))
                            },
                            onNavigateToAlbum = { albumId, sharedTransitionKey ->
                                backStack.add(
                                    YoinRoute.AlbumDetail(
                                        albumId = albumId,
                                        sharedTransitionKey = sharedTransitionKey,
                                    ),
                                )
                            },
                            onNavigateToArtist = { artistId, sharedTransitionKey ->
                                backStack.add(
                                    YoinRoute.ArtistDetail(
                                        artistId = artistId,
                                        sharedTransitionKey = sharedTransitionKey,
                                    ),
                                )
                            },
                            onNavigateToPlaylist = { playlistId, sharedTransitionKey ->
                                backStack.add(
                                    YoinRoute.PlaylistDetail(
                                        playlistId = playlistId,
                                        sharedTransitionKey = sharedTransitionKey,
                                    ),
                                )
                            },
                            sharedTransitionScope = sharedTransitionScope,
                            shellAnimatedVisibilityScope = shellAnimatedVisibilityScope,
                        )
                    }

                    entry<YoinRoute.AlbumDetail>(
                        metadata = metadata {
                            put(NavDisplay.TransitionKey) {
                                YoinMotion.simplePushEnter togetherWith YoinMotion.simplePushExit
                            }
                            put(NavDisplay.PopTransitionKey) {
                                YoinMotion.simplePushPopEnter togetherWith YoinMotion.simplePushPopExit
                            }
                            put(NavDisplay.PredictivePopTransitionKey) {
                                YoinMotion.simplePushPopEnter togetherWith YoinMotion.simplePushPopExit
                            }
                        },
                    ) { route ->
                        val navAnimatedVisibilityScope = LocalNavAnimatedContentScope.current
                        val viewModel: AlbumDetailViewModel = viewModel(
                            factory = AlbumDetailViewModel.Factory(route.albumId, app.container),
                        )
                        val uiState by viewModel.uiState.collectAsState()
                        val notedSongIds by viewModel.notedSongIds.collectAsState()
                        val expandedSongId by viewModel.expandedSongId.collectAsState()
                        val expandedNoteBundle by viewModel.expandedNoteBundle.collectAsState()
                        YoinBackSurface(
                            kind = BackSurfaceKind.PushPage,
                            onCommitBack = { backStack.removeLastOrNull() },
                        ) { predictiveBackModifier, _, requestBack ->
                            AlbumDetailScreen(
                                uiState = uiState,
                                sharedTransitionKey = route.sharedTransitionKey,
                                onBackClick = requestBack,
                                onSongClick = { songId ->
                                    val songs = viewModel.getAlbumSongs()
                                    val index = songs.indexOfFirst { it.id.toString() == songId }
                                        .coerceAtLeast(0)
                                    val activityContext = (uiState as? AlbumDetailUiState.Content)?.let { content ->
                                        ActivityContext.Album(
                                            albumId = content.albumId,
                                            albumName = content.albumName,
                                            artistName = content.artistName,
                                            artistId = content.artistId,
                                            coverArtId = content.coverArtId,
                                        )
                                    } ?: ActivityContext.None
                                    app.container.profileManager.activeSource.value?.let { source ->
                                        app.container.playbackManager.play(
                                            tracks = songs,
                                            startIndex = index,
                                            source = source,
                                            activityContext = activityContext,
                                        )
                                    }
                                },
                                onToggleStar = viewModel::toggleStar,
                                notedSongIds = notedSongIds,
                                expandedSongId = expandedSongId,
                                expandedNoteBundle = expandedNoteBundle,
                                onToggleExpandedSong = viewModel::toggleExpandedSong,
                                onRatingCommit = viewModel::setUserRating,
                                onReviewDraftChange = viewModel::onReviewDraftChange,
                                onSaveReview = viewModel::saveUserReview,
                                onRetry = viewModel::retry,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = navAnimatedVisibilityScope,
                                modifier = predictiveBackModifier.fillMaxSize(),
                            )
                        }
                    }

                    entry<YoinRoute.ArtistDetail>(
                        metadata = metadata {
                            put(NavDisplay.TransitionKey) {
                                YoinMotion.simplePushEnter togetherWith YoinMotion.simplePushExit
                            }
                            put(NavDisplay.PopTransitionKey) {
                                YoinMotion.simplePushPopEnter togetherWith YoinMotion.simplePushPopExit
                            }
                            put(NavDisplay.PredictivePopTransitionKey) {
                                YoinMotion.simplePushPopEnter togetherWith YoinMotion.simplePushPopExit
                            }
                        },
                    ) { route ->
                        val navAnimatedVisibilityScope = LocalNavAnimatedContentScope.current
                        val viewModel: ArtistDetailViewModel = viewModel(
                            factory = ArtistDetailViewModel.Factory(route.artistId, app.container),
                        )
                        val uiState by viewModel.uiState.collectAsState()
                        YoinBackSurface(
                            kind = BackSurfaceKind.PushPage,
                            onCommitBack = { backStack.removeLastOrNull() },
                        ) { predictiveBackModifier, _, requestBack ->
                            ArtistDetailScreen(
                                uiState = uiState,
                                onBackClick = requestBack,
                                onAlbumClick = { albumId ->
                                    backStack.add(YoinRoute.AlbumDetail(albumId))
                                },
                                onRetry = viewModel::retry,
                                sharedTransitionKey = route.sharedTransitionKey,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = navAnimatedVisibilityScope,
                                modifier = predictiveBackModifier.fillMaxSize(),
                            )
                        }
                    }

                    entry<YoinRoute.PlaylistDetail>(
                        metadata = metadata {
                            put(NavDisplay.TransitionKey) {
                                YoinMotion.simplePushEnter togetherWith YoinMotion.simplePushExit
                            }
                            put(NavDisplay.PopTransitionKey) {
                                YoinMotion.simplePushPopEnter togetherWith YoinMotion.simplePushPopExit
                            }
                            put(NavDisplay.PredictivePopTransitionKey) {
                                YoinMotion.simplePushPopEnter togetherWith YoinMotion.simplePushPopExit
                            }
                        },
                    ) { route ->
                        val navAnimatedVisibilityScope = LocalNavAnimatedContentScope.current
                        val viewModel: PlaylistDetailViewModel = viewModel(
                            factory = PlaylistDetailViewModel.Factory(route.playlistId, app.container),
                        )
                        val uiState by viewModel.uiState.collectAsState()
                        val notedSongIds by viewModel.notedSongIds.collectAsState()
                        val detailSnackbarHostState = remember { SnackbarHostState() }
                        // Surface rename/delete/remove outcomes in the shell snackbar.
                        LaunchedEffect(viewModel) {
                            viewModel.messages.collect { message ->
                                detailSnackbarHostState.showSnackbar(
                                    message = message,
                                    duration = SnackbarDuration.Short,
                                )
                            }
                        }
                        // Leave the detail route after a successful delete so the
                        // user lands back on Library / whatever pushed us here.
                        LaunchedEffect(viewModel) {
                            viewModel.deleted.collect {
                                backStack.removeLastOrNull()
                            }
                        }
                        YoinBackSurface(
                            kind = BackSurfaceKind.PushPage,
                            onCommitBack = { backStack.removeLastOrNull() },
                        ) { predictiveBackModifier, _, requestBack ->
                            Box(modifier = predictiveBackModifier.fillMaxSize()) {
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
                                                    coverArtId = songs.firstNotNullOfOrNull(::trackCoverArtId),
                                                )
                                            } ?: ActivityContext.None
                                            app.container.profileManager.activeSource.value?.let { source ->
                                                app.container.playbackManager.play(
                                                    tracks = songs,
                                                    startIndex = 0,
                                                    source = source,
                                                    activityContext = activityContext,
                                                )
                                            }
                                        }
                                    },
                                    onSongClick = { songId ->
                                        val songs = viewModel.getPlaylistSongs()
                                        val index = songs.indexOfFirst { it.id.toString() == songId }
                                            .coerceAtLeast(0)
                                        val activityContext = (uiState as? PlaylistDetailUiState.Content)?.let { content ->
                                            ActivityContext.Playlist(
                                                playlistId = route.playlistId,
                                                playlistName = content.playlistName,
                                                owner = content.owner.takeIf { it.isNotBlank() },
                                                coverArtId = songs.firstNotNullOfOrNull(::trackCoverArtId),
                                            )
                                        } ?: ActivityContext.None
                                        app.container.profileManager.activeSource.value?.let { source ->
                                            app.container.playbackManager.play(
                                                tracks = songs,
                                                startIndex = index,
                                                source = source,
                                                activityContext = activityContext,
                                            )
                                        }
                                    },
                                    onRetry = viewModel::retry,
                                    onAddSongToPlaylist = { songId ->
                                        nowPlayingViewModel.requestAddTracksToPlaylist(
                                            listOf(MediaId.parse(songId)),
                                        )
                                    },
                                    onRename = viewModel::rename,
                                    onDelete = viewModel::delete,
                                    onRemoveTrack = viewModel::removeTrack,
                                    notedSongIds = notedSongIds,
                                    sharedTransitionKey = route.sharedTransitionKey,
                                    sharedTransitionScope = sharedTransitionScope,
                                    animatedVisibilityScope = navAnimatedVisibilityScope,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                SnackbarHost(
                                    hostState = detailSnackbarHostState,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 24.dp, start = 12.dp, end = 12.dp),
                                ) { data ->
                                    Snackbar(snackbarData = data)
                                }
                            }
                        }
                    }

                    entry<YoinRoute.Settings>(
                        metadata = metadata {
                            put(NavDisplay.TransitionKey) {
                                YoinMotion.simplePushEnter togetherWith YoinMotion.simplePushExit
                            }
                            put(NavDisplay.PopTransitionKey) {
                                YoinMotion.simplePushPopEnter togetherWith YoinMotion.simplePushPopExit
                            }
                            put(NavDisplay.PredictivePopTransitionKey) {
                                YoinMotion.simplePushPopEnter togetherWith YoinMotion.simplePushPopExit
                            }
                        },
                    ) { route ->
                        val viewModel: SettingsViewModel = viewModel(
                            factory = SettingsViewModel.Factory(app.container),
                        )
                        YoinBackSurface(
                            kind = BackSurfaceKind.PushPage,
                            onCommitBack = { backStack.removeLastOrNull() },
                        ) { predictiveBackModifier, _, requestBack ->
                            SettingsScreen(
                                viewModel = viewModel,
                                onBackClick = requestBack,
                                focusSection = route.focusSection,
                                modifier = predictiveBackModifier.fillMaxSize(),
                            )
                        }
                    }
                },
            )

            val addTargets by nowPlayingViewModel.addToPlaylistTarget.collectAsState()
            if (addTargets != null) {
                val writablePlaylists by nowPlayingViewModel.writablePlaylists.collectAsState()
                // Null out the create callback when the active source can't
                // actually create playlists — the sheet drops the row entirely
                // rather than showing an action that will fail downstream.
                val canCreate = Capability.PLAYLISTS_WRITE in
                    app.container.repository.currentCapabilities()
                AddToPlaylistSheet(
                    writablePlaylists = writablePlaylists,
                    onCreateAndAdd = nowPlayingViewModel::createPlaylistAndAddTargets
                        .takeIf { canCreate },
                    onAddToExisting = nowPlayingViewModel::addTargetsToExistingPlaylist,
                    onDismiss = nowPlayingViewModel::dismissAddToPlaylistSheet,
                )
            }

            SnackbarHost(
                hostState = addToPlaylistSnackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp, start = 12.dp, end = 12.dp),
            ) { data ->
                Snackbar(snackbarData = data)
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
    onNavigateToSettings: (focusSection: String?) -> Unit,
    onNavigateToAlbum: (String, String?) -> Unit,
    onNavigateToArtist: (String, String?) -> Unit,
    onNavigateToPlaylist: (String, String?) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    shellAnimatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    val experienceSessionStore = app.container.experienceSessionStore
    val experienceSession by experienceSessionStore.state.collectAsState()
    val selectedSection = experienceSession.selectedSection
    val homeSurface = experienceSession.homeSurface
    val showNowPlaying = experienceSession.nowPlayingExpanded
    val musicConfigurationRevision by app.container.musicConfigurationRevision.collectAsState()
    val playlistMutationRevision by app.container.playlistMutationRevision.collectAsState()
    val playbackState by app.container.playbackManager.playbackState.collectAsState()
    // playbackSignal is a heavily-throttled Float (≤3% change to emit); safe
    // to collect at the shell level without recomposing at ~30Hz.
    // The full VisualizerData stream is subscribed only inside the Now
    // Playing overlay where the FFT bars actually render.
    val playbackSignal by app.container.audioVisualizerManager.playbackSignal.collectAsState()
    val castState by app.container.castManager.castState.collectAsState()
    val nowPlayingUiState by nowPlayingViewModel.uiState.collectAsState()
    val songInfoState by nowPlayingViewModel.songInfoState.collectAsState()
    val notesState by nowPlayingViewModel.notesState.collectAsState()
    val devicesState by nowPlayingViewModel.devicesState.collectAsState()
    val memoriesReveal = rememberRevealState(
        initialFraction = if (homeSurface == HomeSurface.Memories) 0f else 1f,
    )
    val memoriesMounted = homeSurface == HomeSurface.Memories || memoriesReveal.isVisible
    val shellScope = rememberCoroutineScope()
    var dismissDragPx by rememberSaveable { mutableStateOf(0f) }
    var predictiveBackProgress by rememberSaveable { mutableStateOf(0f) }
    val dragResetSpec = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Standard)
    val overlayOffsetPx by animateFloatAsState(
        targetValue = predictiveBackProgress * 1200f,
        animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Standard),
        label = "overlayOffsetPx",
    )

    val coverArtUrl = playbackState.currentTrack?.coverArt?.let { coverArt ->
        app.container.repository.resolveCoverUrl(coverArt)
    }

    LaunchedEffect(Unit) {
        app.container.playbackManager.connectInBackground()
    }

    LaunchedEffect(musicConfigurationRevision) {
        if (musicConfigurationRevision == 0L) return@LaunchedEffect
        homeViewModel.refresh()
        libraryViewModel.refresh()
        memoriesViewModel.refresh()
    }

    LaunchedEffect(playlistMutationRevision) {
        if (playlistMutationRevision == 0L) return@LaunchedEffect
        libraryViewModel.invalidatePlaylists()
    }

    val shellBackOwner = resolveShellBackOwner(
        showNowPlaying = showNowPlaying,
        selectedSection = selectedSection,
        homeSurface = homeSurface,
    )
    val memoriesActive = selectedSection == YoinSection.HOME && homeSurface == HomeSurface.Memories
    // Memories is a Home-owned overlay. If we leave Home for another shell
    // surface while it is active, collapse it first so closing the new
    // surface returns to Feed instead of unexpectedly revealing Memories.
    val dismissMemoriesIfActive = {
        if (selectedSection == YoinSection.HOME && homeSurface == HomeSurface.Memories) {
            experienceSessionStore.setHomeSurface(HomeSurface.Feed)
        }
    }
    val navigateToSettingsFromShell: (String?) -> Unit = { focusSection ->
        dismissMemoriesIfActive()
        onNavigateToSettings(focusSection)
    }
    val navigateToAlbumFromShell: (String, String?) -> Unit = { albumId, sharedTransitionKey ->
        dismissMemoriesIfActive()
        onNavigateToAlbum(albumId, sharedTransitionKey)
    }
    val navigateToArtistFromShell: (String, String?) -> Unit = { artistId, sharedTransitionKey ->
        dismissMemoriesIfActive()
        onNavigateToArtist(artistId, sharedTransitionKey)
    }
    val navigateToPlaylistFromShell: (String, String?) -> Unit = { playlistId, sharedTransitionKey ->
        dismissMemoriesIfActive()
        onNavigateToPlaylist(playlistId, sharedTransitionKey)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        app.container.playbackManager.events.collect { event ->
            when (event) {
                is PlaybackEvent.SpotifyConnectError -> {
                    val actionLabel = actionLabelForFailure(event.failure)
                    val result = snackbarHostState.showSnackbar(
                        message = event.message,
                        actionLabel = actionLabel,
                        withDismissAction = actionLabel == null,
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed &&
                        event.failure.shouldOpenSpotifySettings()
                    ) {
                        navigateToSettingsFromShell("spotify")
                    }
                }

                is PlaybackEvent.SpotifyActionRequired -> {
                    val actionLabel = actionLabelForFailure(event.failure)
                    val result = snackbarHostState.showSnackbar(
                        message = event.message,
                        actionLabel = actionLabel,
                        withDismissAction = actionLabel == null,
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed &&
                        event.failure.shouldOpenSpotifySettings()
                    ) {
                        navigateToSettingsFromShell("spotify")
                    }
                }
            }
        }
    }
    // Library-side playlist mutations (currently: create from the "+" FAB).
    // PlaylistDetail ViewModel has its own messages flow wired at its
    // composable scope since it's a short-lived push page.
    LaunchedEffect(Unit) {
        libraryViewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short,
            )
        }
    }

    val closeMemories = remember(experienceSessionStore) {
        {
            experienceSessionStore.setHomeSurface(HomeSurface.Feed)
        }
    }

    // Drive open/close animation from the surface flag. If a gesture has
    // already brought the reveal to the matching endpoint, the guard skips
    // the no-op animation so the gesture-driven settle isn't interrupted.
    LaunchedEffect(homeSurface) {
        when (homeSurface) {
            HomeSurface.Memories -> if (memoriesReveal.fraction > 0.001f) {
                memoriesReveal.animateTo(0f)
            }
            HomeSurface.Feed -> if (memoriesReveal.fraction < 0.999f) {
                memoriesReveal.animateTo(1f)
            }
        }
    }

    LaunchedEffect(selectedSection) {
        if (selectedSection != YoinSection.HOME) {
            memoriesReveal.snapTo(1f)
        }
    }

    val closeNowPlaying = {
        dismissDragPx = 0f
        predictiveBackProgress = 0f
        experienceSessionStore.setNowPlayingExpanded(false)
    }

    BackHandler(enabled = showNowPlaying, onBack = closeNowPlaying)

    PredictiveBackHandler(enabled = showNowPlaying) { progress ->
        try {
            progress.collectLatest { event ->
                predictiveBackProgress = event.progress
            }
            dismissDragPx = 0f
            experienceSessionStore.setNowPlayingExpanded(false)
        } finally {
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

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(homeBgColor),
                    ) {
                        HomeScreen(
                            viewModel = homeViewModel,
                            isPlaying = playbackState.isPlaying,
                            playbackSignal = if (playbackState.isPlaying) playbackSignal else 0f,
                            activeSongId = playbackState.currentTrack?.id?.toString(),
                            onNavigateToSettings = { navigateToSettingsFromShell(null) },
                            onNavigateToMemories = {
                                experienceSessionStore.setHomeSurface(HomeSurface.Memories)
                            },
                            memoriesRevealState = memoriesReveal,
                            onCommitMemoriesReveal = {
                                experienceSessionStore.setHomeSurface(HomeSurface.Memories)
                            },
                            onAlbumClick = navigateToAlbumFromShell,
                            onArtistClick = { artistId -> navigateToArtistFromShell(artistId, null) },
                            onPlaylistClick = { playlistId -> navigateToPlaylistFromShell(playlistId, null) },
                            onSongClick = { song ->
                                app.container.profileManager.activeSource.value?.let { source ->
                                    app.container.playbackManager.playSingle(
                                        track = song,
                                        source = source,
                                    )
                                }
                            },
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = shellAnimatedVisibilityScope,
                            modifier = Modifier.fillMaxSize(),
                        )

                        if (memoriesMounted) {
                            BackHandler(enabled = shellBackOwner == ShellBackOwner.Memories) {
                                closeMemories()
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        translationY = -memoriesReveal.fraction * size.height
                                    },
                            ) {
                                MemoriesScreen(
                                    viewModel = memoriesViewModel,
                                    revealState = memoriesReveal,
                                    onDismissed = closeMemories,
                                    onNavigateToNeoDbSettings = {
                                        navigateToSettingsFromShell("neodb")
                                    },
                                    onPlayMemoryTrack = { memory, trackIndex ->
                                        val queue = memory.playbackSongs
                                        if (queue.isNotEmpty()) {
                                            val startIndex = trackIndex.coerceIn(0, queue.lastIndex)
                                            val selectedSong = queue[startIndex]
                                            val activityContext = memory.toPlaybackActivityContext()

                                            if (memory.entityType == MemoryEntityType.SONG || queue.size <= 1) {
                                                app.container.profileManager.activeSource.value?.let { source ->
                                                    app.container.playbackManager.playSingle(
                                                        track = selectedSong,
                                                        source = source,
                                                        activityContext = activityContext,
                                                    )
                                                }
                                            } else {
                                                app.container.profileManager.activeSource.value?.let { source ->
                                                    app.container.playbackManager.play(
                                                        tracks = queue,
                                                        startIndex = startIndex,
                                                        source = source,
                                                        activityContext = activityContext,
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }

                YoinSection.LIBRARY -> LibraryScreen(
                    viewModel = libraryViewModel,
                    activeSongId = playbackState.currentTrack?.id?.toString(),
                    isPlaying = playbackState.isPlaying,
                    playbackSignal = if (playbackState.isPlaying) playbackSignal else 0f,
                    onNavigateToSettings = { navigateToSettingsFromShell(null) },
                    onArtistClick = { artistId -> navigateToArtistFromShell(artistId, null) },
                    onAlbumClick = { albumId -> navigateToAlbumFromShell(albumId, null) },
                    onPlaylistClick = { playlistId -> navigateToPlaylistFromShell(playlistId, null) },
                    onSongClick = { song ->
                        app.container.profileManager.activeSource.value?.let { source ->
                            app.container.playbackManager.playSingle(
                                track = song,
                                source = source,
                            )
                        }
                    },
                    onAddSongToPlaylist = { song ->
                        nowPlayingViewModel.requestAddTracksToPlaylist(listOf(song.id))
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ── Background scrim ─────────────────────────────────────────────
        val scrimAlpha by animateFloatAsState(
            targetValue = if (showNowPlaying) 0.5f else 0f,
            animationSpec = YoinMotion.defaultEffectsSpec(role = YoinMotionRole.Standard),
            label = "scrimAlpha",
        )
        if (scrimAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha)),
            )
        }

        // ── Now Playing overlay ──────────────────────────────────────────
        AnimatedVisibility(
            visible = showNowPlaying,
            enter = YoinMotion.slideInVertically(role = YoinMotionRole.Expressive) { it } +
                YoinMotion.fadeIn(role = YoinMotionRole.Standard),
            exit = YoinMotion.slideOutVertically(role = YoinMotionRole.Standard) { it } +
                YoinMotion.fadeOut(role = YoinMotionRole.Standard),
            modifier = Modifier.fillMaxSize(),
        ) {
            val npAvScope = this
            // Subscribe to the full VisualizerData stream only while NP is
            // composed; keeps the shell clear of 30Hz recompositions.
            val visualizerData by app.container.audioVisualizerManager.visualizerData
                .collectAsState()
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
                                dismissDragPx = 0f
                                predictiveBackProgress = 0f
                                experienceSessionStore.setNowPlayingExpanded(false)
                            } else {
                                animate(
                                    initialValue = dismissDragPx,
                                    targetValue = 0f,
                                    animationSpec = dragResetSpec,
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
                    onAddCurrentToPlaylist = nowPlayingViewModel::requestAddCurrentToPlaylist,
                    onSkipToQueueItem = nowPlayingViewModel::skipToQueueItem,
                    onToggleShuffle = nowPlayingViewModel::toggleShuffle,
                    onAlbumClick = { albumId ->
                        closeNowPlaying()
                        navigateToAlbumFromShell(albumId, null)
                    },
                    onArtistClick = { artistId ->
                        closeNowPlaying()
                        navigateToArtistFromShell(artistId, null)
                    },
                    onPlaylistClick = { playlistId ->
                        closeNowPlaying()
                        navigateToPlaylistFromShell(playlistId, null)
                    },
                    onDismiss = closeNowPlaying,
                    dismissFraction = {
                        val dragProgress = (dismissDragPx / 240f).coerceIn(0f, 1f)
                        maxOf(dragProgress, predictiveBackProgress).coerceIn(0f, 1f)
                    },
                    songInfoState = songInfoState,
                    onRetryFetchSongInfo = nowPlayingViewModel::retryFetchSongInfo,
                    notesState = notesState,
                    onSaveNote = nowPlayingViewModel::saveCurrentNote,
                    onDeleteNote = nowPlayingViewModel::deleteNote,
                    devicesState = devicesState,
                    onRefreshDevices = nowPlayingViewModel::refreshDevices,
                    onSelectDevice = nowPlayingViewModel::selectDevice,
                    castState = castState,
                    onCastClick = { },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = npAvScope,
                    modifier = Modifier
                        .fillMaxSize()
                        .offset {
                            IntOffset(
                                x = 0,
                                y = (overlayOffsetPx + dismissDragPx).roundToInt(),
                            )
                        },
                )

            }
        }

        // ── Bottom navigation ────────────────────────────────────────────
        AnimatedVisibility(
            visible = !showNowPlaying,
            enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard) +
                YoinMotion.slideInVertically(role = YoinMotionRole.Standard) { it },
            exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard) +
                YoinMotion.slideOutVertically(role = YoinMotionRole.Standard) { it },
        ) {
            val bgAvScope = this

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Couple the mini player to the Memories reveal so it
                        // slides/fades out together with the open gesture
                        // instead of waiting for the surface flip.
                        val hide = (1f - memoriesReveal.fraction).coerceIn(0f, 1f)
                        alpha = (1f - hide * 1.4f).coerceAtLeast(0f)
                        translationY = hide * 120.dp.toPx()
                    },
                contentAlignment = Alignment.BottomCenter,
            ) {
                YoinButtonGroup(
                    selectedSection = selectedSection,
                    currentTrackId = playbackState.currentTrack?.id?.toString(),
                    currentTrackTitle = playbackState.currentTrack?.title,
                    currentTrackArtist = playbackState.currentTrack?.artist,
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
                        // LaunchedEffect(homeSurface) handles the close animation.
                    },
                    onNowPlayingClick = {
                        dismissMemoriesIfActive()
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

        // ── Shell-level snackbar host ────────────────────────────────────
        // Anchored bottom, overlaid above Now Playing / bottom nav. Spotify
        // connect failures surface here with actionable labels.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp, start = 12.dp, end = 12.dp),
        ) { data ->
            Snackbar(snackbarData = data)
        }
    }
}

/**
 * Action label shown on the shell snackbar for a given Spotify connect
 * failure. Returns null when the failure has no user-actionable recovery
 * yet (UX will just show a dismiss affordance instead).
 */
private fun actionLabelForFailure(failure: SpotifyConnectFailure): String? = when (failure) {
    SpotifyConnectFailure.NoClientId -> "Open Settings"
    SpotifyConnectFailure.SpotifyAppMissing -> null // phase 3 wires Play Store intent
    SpotifyConnectFailure.PremiumRequired -> null
    is SpotifyConnectFailure.AuthFailure -> "Open Settings"
    is SpotifyConnectFailure.TransportFailure -> null
}

private fun SpotifyConnectFailure.shouldOpenSpotifySettings(): Boolean = when (this) {
    SpotifyConnectFailure.NoClientId -> true
    is SpotifyConnectFailure.AuthFailure -> true
    SpotifyConnectFailure.SpotifyAppMissing -> false
    SpotifyConnectFailure.PremiumRequired -> false
    is SpotifyConnectFailure.TransportFailure -> false
}

private fun MemoryEntry.toPlaybackActivityContext(): ActivityContext {
    val firstSong = playbackSongs.firstOrNull()
    val coverArtId = playbackSongs.firstNotNullOfOrNull(::trackCoverArtId)

    return when (entityType) {
        MemoryEntityType.ALBUM -> ActivityContext.Album(
            albumId = entityId,
            albumName = title,
            artistName = firstSong?.artist,
            artistId = firstSong?.artistId?.rawId,
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

/**
 * Flatten a track's cover art into the storage-key shape used by
 * `ActivityEvent` / `PlayHistory`. On Subsonic this is the classic raw id;
 * on Spotify it's the direct image URL. Falls back to the Subsonic album
 * id only when the track has no cover ref *and* the provider is Subsonic
 * (Spotify album ids aren't URL-shaped and would poison the storage key).
 */
private fun trackCoverArtId(track: Track): String? =
    CoverRef.toStorageKey(track.coverArt)
        ?: track.albumId?.rawId?.takeIf { track.id.provider == MediaId.PROVIDER_SUBSONIC }

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun YoinNavHostPreview() {
    YoinTheme {
        YoinNavHost()
    }
}
