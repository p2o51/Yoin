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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
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
import com.gpo.yoin.ui.detail.AlbumDetailActivity
import com.gpo.yoin.ui.detail.ArtistDetailActivity
import com.gpo.yoin.ui.detail.PlaylistDetailActivity
import com.gpo.yoin.ui.settings.SettingsActivity
import com.gpo.yoin.ui.home.HomeScreen
import com.gpo.yoin.ui.home.HomeViewModel
import com.gpo.yoin.ui.library.LibraryScreen
import com.gpo.yoin.ui.library.LibrarySearchScope
import com.gpo.yoin.ui.library.LibraryViewModel
import com.gpo.yoin.ui.experience.HomeSurface
import com.gpo.yoin.ui.experience.rememberRevealState
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryEntry
import com.gpo.yoin.ui.memories.MemoriesScreen
import com.gpo.yoin.ui.memories.MemoriesViewModel
import com.gpo.yoin.ui.navigation.back.ShellBackOwner
import com.gpo.yoin.ui.navigation.back.resolveShellBackOwner
import com.gpo.yoin.player.PlaybackEvent
import com.gpo.yoin.player.SpotifyConnectFailure
import com.gpo.yoin.ui.nowplaying.NowPlayingStageMode
import com.gpo.yoin.ui.nowplaying.NowPlayingScreen
import com.gpo.yoin.ui.nowplaying.NowPlayingViewModel
import com.gpo.yoin.ui.nowplaying.rememberNowPlayingStageProgress
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.CancellationException
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
        val context = LocalContext.current
        val app = context.applicationContext as YoinApplication
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

        LaunchedEffect(nowPlayingViewModel) {
            nowPlayingViewModel.lyricsTranslationSwitchOffers.collect { offer ->
                val result = addToPlaylistSnackbarHostState.showSnackbar(
                    message = "${offer.providerName} translation is available",
                    actionLabel = "Switch",
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    nowPlayingViewModel.applyLyricsTranslationSwitchOffer()
                }
            }
        }

        // Only the Shell route lives in this NavDisplay now — detail pages are
        // separate Activities. The stack therefore stays at a single entry, so
        // onBack is effectively inert; the size>1 guard just keeps NavDisplay's
        // required non-empty invariant safe.
        val backStack = rememberNavBackStack(YoinRoute.Shell)
        val popPage: () -> Boolean = remember(backStack) {
            {
                if (backStack.size > 1) {
                    backStack.removeLastOrNull()
                    true
                } else {
                    false
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            NavDisplay(
                backStack = backStack,
                modifier = Modifier.fillMaxSize(),
                onBack = { popPage() },
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
                // Only the Shell route lives in this NavDisplay now (detail pages
                // are separate Activities). Shell never pushes/pops within the
                // NavDisplay, so these specs are inert — keep them as no-ops.
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
                            // Detail pages are now separate Activities — launch
                            // them so back navigation plays the device-native
                            // cross-Activity predictive back. sharedTransitionKey
                            // is no longer used (no cross-page shared element).
                            onNavigateToSettings = { focusSection ->
                                context.startActivity(SettingsActivity.intent(context, focusSection))
                            },
                            onNavigateToAlbum = { albumId, _ ->
                                context.startActivity(AlbumDetailActivity.intent(context, albumId))
                            },
                            onNavigateToArtist = { artistId, _ ->
                                context.startActivity(ArtistDetailActivity.intent(context, artistId))
                            },
                            onNavigateToPlaylist = { playlistId, _ ->
                                context.startActivity(PlaylistDetailActivity.intent(context, playlistId))
                            },
                            sharedTransitionScope = sharedTransitionScope,
                            shellAnimatedVisibilityScope = shellAnimatedVisibilityScope,
                        )
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
    val aboutUiState by nowPlayingViewModel.aboutUiState.collectAsState()
    val askState by nowPlayingViewModel.askState.collectAsState()
    val stageMode by nowPlayingViewModel.stageMode.collectAsState()
    val detailPage by nowPlayingViewModel.detailPage.collectAsState()
    val notesState by nowPlayingViewModel.notesState.collectAsState()
    val devicesState by nowPlayingViewModel.devicesState.collectAsState()
    val lyricsSearchState by nowPlayingViewModel.lyricsSearchState.collectAsState()
    val memoriesReveal = rememberRevealState(
        initialFraction = if (homeSurface == HomeSurface.Memories) 0f else 1f,
    )
    val memoriesMounted = homeSurface == HomeSurface.Memories || memoriesReveal.isVisible
    val shellScope = rememberCoroutineScope()
    var dismissDragPx by remember { mutableStateOf(0f) }
    var predictiveBackProgress by remember { mutableStateOf(0f) }
    val stageProgress = rememberNowPlayingStageProgress(initialMode = stageMode)
    val dragResetSpec = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Standard)
    // Fast, near-critical spring owns the whole stage reshape (expand, collapse,
    // and gesture-release settle). Non-bouncy so the open never overshoots past
    // 1.0 (which would re-trigger the cover-flight flash); fast so a released
    // back gesture reads as a continuation rather than a slow snap.
    val stageAnimationSpec = YoinMotion.stageSettleSpring<Float>()
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

    // isGestureDriving is a KEY, not just an early-return guard: when a gesture
    // ends (endGesture flips the flag) this effect re-runs and reconciles the
    // shared progress to the CURRENT stageMode. That re-convergence is what
    // (a) restores a cancelled back gesture — stageMode is still Expanded, so
    // detail springs back to 1 (velocity-continuous via the Animatable) without
    // needing a settle inside the already-cancelled handler coroutine — and
    // (b) recovers any stageMode change that landed mid-gesture (e.g. a tap to
    // re-expand during the post-commit settle), which a one-shot guard would
    // silently drop, wedging stageMode and stageProgress apart.
    LaunchedEffect(stageMode, stageProgress, stageProgress.isGestureDriving) {
        if (stageProgress.isGestureDriving) return@LaunchedEffect
        launch {
            stageProgress.animateDetailTo(
                target = if (stageMode == NowPlayingStageMode.Expanded) 1f else 0f,
                spec = stageAnimationSpec,
            )
        }
        launch {
            stageProgress.animateImmersiveTo(
                target = if (stageMode == NowPlayingStageMode.Immersive) 1f else 0f,
                spec = stageAnimationSpec,
            )
        }
    }

    val closeNowPlaying = {
        dismissDragPx = 0f
        predictiveBackProgress = 0f
        nowPlayingViewModel.setStageMode(NowPlayingStageMode.Compact)
        experienceSessionStore.setNowPlayingExpanded(false)
    }

    // Layered back priority: Expanded collapses in place first. Immersive is
    // a transient cover-focus variant of Compact, so it does not enter the
    // stage back chain; Back closes Now Playing just like Compact.
    BackHandler(
        enabled = showNowPlaying && stageMode == NowPlayingStageMode.Expanded,
    ) {
        nowPlayingViewModel.stepBackStage()
    }

    BackHandler(
        enabled = showNowPlaying && stageMode != NowPlayingStageMode.Expanded,
        onBack = closeNowPlaying,
    )

    // Predictive-back drive for stage collapse. Expanded reshapes back toward
    // Compact before Compact/Immersive can dismiss the overlay.
    // Full-range eased scrub: the finger drives the ENTIRE reshape (detail
    // 1 → 0), not a capped preview, so the whole collapse is visible and
    // tracks the gesture. progress is eased (backGestureEasing) and snapped
    // directly — the system already spring-smooths it, so no chase coroutine
    // is needed (the old cap + chase made the gesture show little, then the
    // release snapped the remainder, which read as an animation-less flash).
    // On release the reconciling LaunchedEffect settles the small remainder:
    // commit → detail 0 (Compact), cancel → detail 1 (still Expanded).
    PredictiveBackHandler(
        enabled = showNowPlaying && stageMode == NowPlayingStageMode.Expanded,
    ) { progress ->
        stageProgress.beginGesture()
        try {
            progress.collect { event ->
                val eased = YoinMotion.backGestureEasing.transform(event.progress)
                stageProgress.snapDetail(1f - eased)
            }
            nowPlayingViewModel.stepBackStage()
        } catch (e: CancellationException) {
            throw e
        } finally {
            stageProgress.endGesture()
        }
    }

    // Predictive-back drive for the compact dismissal animation.
    PredictiveBackHandler(
        enabled = showNowPlaying && stageMode != NowPlayingStageMode.Expanded,
    ) { progress ->
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
                    onFavoriteSongClick = { song, queue, startIndex ->
                        val safeQueue = queue.ifEmpty { listOf(song) }
                        val safeIndex = startIndex.takeIf { it in safeQueue.indices }
                            ?: safeQueue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                        app.container.profileManager.activeSource.value?.let { source ->
                            app.container.playbackManager.play(
                                tracks = safeQueue,
                                startIndex = safeIndex,
                                source = source,
                                activityContext = ActivityContext.LikedSongs(
                                    coverArtId = safeQueue
                                        .firstOrNull()
                                        ?.let(::trackCoverArtId),
                                ),
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
                        // Drag-to-dismiss is a Compact-only gesture. In
                        // Expanded/Immersive panes have
                        // their own vertical scroll / IME interactions;
                        // letting draggable eat those deltas is what
                        // causes Lyrics scroll to fight dismiss.
                        enabled = stageMode != NowPlayingStageMode.Expanded,
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
                    onSeekToMs = nowPlayingViewModel::seekToMs,
                    lyricsSearchState = lyricsSearchState,
                    onOpenLyricsSearch = nowPlayingViewModel::openLyricsSearch,
                    onLyricsSearchQueryChange = nowPlayingViewModel::updateLyricsSearchQuery,
                    onSearchLyrics = nowPlayingViewModel::searchLyrics,
                    onApplyLyricsSearchResult = nowPlayingViewModel::applyLyricsSearchResult,
                    onDismissLyricsSearch = nowPlayingViewModel::dismissLyricsSearch,
                    onTranslateLyrics = nowPlayingViewModel::translateLyrics,
                    onApplyLyrics = nowPlayingViewModel::applyLyrics,
                    onRatingChange = nowPlayingViewModel::setRating,
                    onToggleFavorite = nowPlayingViewModel::toggleFavorite,
                    onAddCurrentToPlaylist = nowPlayingViewModel::requestAddCurrentToPlaylist,
                    onSkipToQueueItem = nowPlayingViewModel::skipToQueueItem,
                    onToggleShuffle = nowPlayingViewModel::toggleShuffle,
                    // Keep Now Playing expanded across the push. NP is a
                    // Shell-scoped overlay, so when AlbumDetail becomes the
                    // active NavDisplay entry, Shell (and NP with it) stops
                    // rendering automatically — no need to collapse state.
                    // Popping back to Shell restores NP in whatever stage
                    // the user left it (Compact or Expanded Lyrics/
                    // About/Note), which is what Apple Music does.
                    onAlbumClick = { albumId ->
                        navigateToAlbumFromShell(albumId, null)
                    },
                    onArtistClick = { artistId ->
                        navigateToArtistFromShell(artistId, null)
                    },
                    onPlaylistClick = { playlistId ->
                        navigateToPlaylistFromShell(playlistId, null)
                    },
                    onDismiss = closeNowPlaying,
                    dismissFraction = {
                        val dragProgress = (dismissDragPx / 240f).coerceIn(0f, 1f)
                        maxOf(dragProgress, predictiveBackProgress).coerceIn(0f, 1f)
                    },
                    aboutUiState = aboutUiState,
                    onRetryFetchSongInfo = nowPlayingViewModel::retryFetchSongInfo,
                    askState = askState,
                    onAboutOpened = nowPlayingViewModel::onAboutOpened,
                    onAskQuestion = nowPlayingViewModel::askQuestion,
                    onAskBarFocused = nowPlayingViewModel::onAskBarFocused,
                    onAskBarCollapseRequested = nowPlayingViewModel::onAskBarCollapseRequested,
                    onDismissAskError = nowPlayingViewModel::dismissAskError,
                    stageMode = stageMode,
                    stageProgress = stageProgress,
                    detailPage = detailPage,
                    onStageModeChange = nowPlayingViewModel::setStageMode,
                    onStageBack = nowPlayingViewModel::stepBackStage,
                    onDetailPageChange = nowPlayingViewModel::setDetailPage,
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
        // Slide the bottom group fully off-screen, BELOW the nav bar: the group
        // carries the nav-bar inset as internal bottom padding, so a plain
        // slide of `it` (its own height) leaves it starting part-way up the
        // screen rather than off the edge. Adding the inset to the offset makes
        // it enter from truly off-screen while keeping its resting position and
        // edge-to-edge transparency intact.
        val navBarBottomPx = with(LocalDensity.current) {
            WindowInsets.navigationBars.getBottom(this)
        }
        AnimatedVisibility(
            visible = !showNowPlaying,
            enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard) +
                YoinMotion.slideInVertically(role = YoinMotionRole.Standard) { it + navBarBottomPx },
            exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard) +
                YoinMotion.slideOutVertically(role = YoinMotionRole.Standard) { it + navBarBottomPx },
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
                        libraryViewModel.showLibraryHome()
                        experienceSessionStore.setSelectedSection(YoinSection.LIBRARY)
                        experienceSessionStore.setHomeSurface(HomeSurface.Feed)
                    },
                    onLibraryLongClick = {
                        val scope = if (
                            app.container.repository.currentProviderId() == MediaId.PROVIDER_SPOTIFY
                        ) {
                            LibrarySearchScope.SpotifyGlobal
                        } else {
                            LibrarySearchScope.CurrentLibrary
                        }
                        libraryViewModel.openSearchShortcut(scope)
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
internal fun trackCoverArtId(track: Track): String? =
    CoverRef.toStorageKey(track.coverArt)
        ?: track.albumId?.rawId?.takeIf { track.id.provider == MediaId.PROVIDER_SUBSONIC }

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun YoinNavHostPreview() {
    YoinTheme {
        YoinNavHost()
    }
}
