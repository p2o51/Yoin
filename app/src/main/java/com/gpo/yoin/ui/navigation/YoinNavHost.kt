package com.gpo.yoin.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.Crossfade
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
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
import com.gpo.yoin.ui.detail.DetailLaunchMode
import com.gpo.yoin.ui.detail.PlaylistDetailActivity
import com.gpo.yoin.ui.detail.findActivityOrNull
import com.gpo.yoin.ui.detail.isDetailSplitEligible
import com.gpo.yoin.ui.detail.launchDetailFromShell
import com.gpo.yoin.ui.settings.SettingsActivity
import com.gpo.yoin.ui.home.HomeScreen
import com.gpo.yoin.ui.home.HomeViewModel
import com.gpo.yoin.ui.library.LibraryScreen
import com.gpo.yoin.ui.library.LibrarySearchScope
import com.gpo.yoin.ui.library.LibraryViewModel
import com.gpo.yoin.ui.experience.HomeSurface
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.isDualPaneNowPlaying
import com.gpo.yoin.ui.experience.rememberRevealState
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryEntry
import com.gpo.yoin.ui.memories.MemoriesScreen
import com.gpo.yoin.ui.memories.MemoriesViewModel
import com.gpo.yoin.ui.navigation.back.ShellBackOwner
import com.gpo.yoin.ui.navigation.back.rememberShellBarChromeMorph
import com.gpo.yoin.ui.navigation.back.rememberDetailBackEnteringModifier
import com.gpo.yoin.ui.navigation.back.resolveShellBackOwner
import com.gpo.yoin.player.PlaybackEvent
import com.gpo.yoin.player.SpotifyConnectFailure
import com.gpo.yoin.ui.nowplaying.NowPlayingStageMode
import com.gpo.yoin.ui.nowplaying.NowPlayingScreen
import com.gpo.yoin.ui.nowplaying.NowPlayingAccessories
import com.gpo.yoin.ui.nowplaying.NowPlayingOverlayHost
import com.gpo.yoin.ui.nowplaying.NowPlayingViewModel
import com.gpo.yoin.ui.nowplaying.rememberNowPlayingStageProgress
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
        // P0 修正案：embedding 判定不能读 LocalYoinWindowInfo —— 分栏激活后
        // Activity 读到的是自己的窗格宽（576dp 级，判成 Compact），会把编舞
        // 错误地放行。isDetailSplitEligible 走「已嵌入 || 任务窗 >= 840」。
        val hostActivity = remember(context) { context.findActivityOrNull() }
        val detailSplitEligible = { hostActivity?.let(::isDetailSplitEligible) == true }
        // 三值启动签名（方案 §2③）。INVARIANT: cross-window bar choreography
        // exists ONLY when the shell is Compact, has a bottom bar, and will
        // be fully covered — every other configuration launches without the
        // hand-off. PANE-relative LayoutMode 在这里读是对的：它描述用户此刻
        // 看到的 shell 窗格；分栏里的窗格读 Compact，但 detailSplitEligible()
        // 先命中 Embedded，轮不到它。
        val shellLayoutMode = LocalYoinWindowInfo.current.layoutMode
        val detailLaunchMode = {
            when {
                detailSplitEligible() -> DetailLaunchMode.Embedded
                // Medium+ 全窗 shell：rail 在场、没有底部 bar，detail 的返回
                // scrub 不许朝一根不存在的 bar 做 morph → 纯推入。
                shellLayoutMode != LayoutMode.Compact -> DetailLaunchMode.PlainPush
                else -> DetailLaunchMode.FullChoreography
            }
        }
        val nowPlayingViewModel: NowPlayingViewModel = viewModel(
            factory = NowPlayingViewModel.Factory(app.container),
        )

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
                            // launchDetailFromShell delays the incoming
                            // window's fade so the shell bar's nav→split
                            // morph plays first (detail_bar_handoff_enter).
                            onNavigateToSettings = { focusSection ->
                                context.startActivity(SettingsActivity.intent(context, focusSection))
                            },
                            onNavigateToAlbum = { albumId, _ ->
                                launchDetailFromShell(
                                    context,
                                    AlbumDetailActivity.intent(context, albumId),
                                    mode = detailLaunchMode(),
                                )
                            },
                            onNavigateToArtist = { artistId, _ ->
                                launchDetailFromShell(
                                    context,
                                    ArtistDetailActivity.intent(context, artistId),
                                    mode = detailLaunchMode(),
                                )
                            },
                            onNavigateToPlaylist = { playlistId, _ ->
                                launchDetailFromShell(
                                    context,
                                    PlaylistDetailActivity.intent(context, playlistId),
                                    mode = detailLaunchMode(),
                                )
                            },
                            sharedTransitionScope = sharedTransitionScope,
                            shellAnimatedVisibilityScope = shellAnimatedVisibilityScope,
                        )
                    }
                },
            )

            NowPlayingAccessories(
                viewModel = nowPlayingViewModel,
                container = app.container,
            )
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
    val playbackManager = app.container.playbackManager
    // PlaybackState carries `position`, which ticks 4×/s while music plays.
    // Collecting the FULL state here re-executed the whole shell body (Home/
    // Library + bottom nav) on every tick, even with Now Playing collapsed.
    // The shell only needs these rarely-changing slices, so collect narrow
    // distinct projections instead; the 4Hz progress for the mini player is
    // derived inside the bottom-nav subtree below, and Now Playing reads the
    // ViewModel's positionMs/bufferedMs flows inside its own overlay subtree.
    // One snapshot seeds all four projections. Reading `.value` per projection
    // would take four independent reads that can straddle a state emission, so
    // the first frame could mix slices from two different PlaybackStates — and
    // a bare `.value` inside composition is a lint error besides. The seed only
    // feeds the first composition; every later value arrives through the flow.
    val playbackSeed = remember(playbackManager) { playbackManager.playbackState.value }
    val currentTrack by remember(playbackManager) {
        playbackManager.playbackState.map { it.currentTrack }.distinctUntilChanged()
    }.collectAsState(initial = playbackSeed.currentTrack)
    val isPlaying by remember(playbackManager) {
        playbackManager.playbackState.map { it.isPlaying }.distinctUntilChanged()
    }.collectAsState(initial = playbackSeed.isPlaying)
    val isPlaybackReady by remember(playbackManager) {
        playbackManager.playbackState.map { it.controllerReady }.distinctUntilChanged()
    }.collectAsState(initial = playbackSeed.controllerReady)
    val playbackConnectionError by remember(playbackManager) {
        playbackManager.playbackState.map { it.connectionErrorMessage }.distinctUntilChanged()
    }.collectAsState(initial = playbackSeed.connectionErrorMessage)
    // playbackSignal is a heavily-throttled Float (≤3% change to emit); safe
    // to collect at the shell level without recomposing at ~30Hz.
    // The full VisualizerData stream stays out of composition entirely — the
    // Now Playing overlay only derives a Boolean spectrum-presence from it.
    val playbackSignal by app.container.audioVisualizerManager.playbackSignal.collectAsState()
    val layoutMode = LocalYoinWindowInfo.current.layoutMode
    // Medium+ 全窗 shell 用左侧 rail；Compact 与 Tabletop 保持底部 bar（合页
    // 上下分屏依赖 bar 的位置）。门按 doctrine 写成 != Compact（禁止
    // == Medium）。分栏里本 Activity 读到的是窗格宽（Compact）→ 自动回落到
    // bar，正确。
    val chromeUsesRail = layoutMode != LayoutMode.Compact && layoutMode != LayoutMode.Tabletop
    val memoriesReveal = rememberRevealState(
        initialFraction = if (homeSurface == HomeSurface.Memories) 0f else 1f,
    )
    val memoriesMounted = homeSurface == HomeSurface.Memories || memoriesReveal.isVisible
    val shellScope = rememberCoroutineScope()

    val coverArtUrl = currentTrack?.coverArt?.let { coverArt ->
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
    // Flip the bar to detail chrome the moment the tap lands — the nav→split
    // morph IS the tap feedback; the detail window's delayed fade then lands
    // on its own identical bar (see detail_bar_handoff_enter.xml). The flag
    // stays up while the detail stack is on top so the predictive-back
    // preview reveals a matching bar; the restore effect below flips it back.
    // With Now Playing expanded the shell bar is hidden and the back reveal
    // is NP itself — arming chrome would only queue a phantom morph (and a
    // wrong split→nav scrub on the detail's back), so skip it.
    // 分栏接住 detail 时 shell 永远不会被覆盖：onStop 的恢复 tick 不会来，
    // morph 一旦 arm 就卡死（首点卡死、后续点击伪 morph 抖动）。判定必须用
    // isDetailSplitEligible —— 分栏激活后本 Activity 的 LayoutMode 读到的是
    // 窗格宽（Compact），拿它做门会把编舞错误放行（P0 修正案 2026-07-27）。
    val shellContext = LocalContext.current
    val shellHostActivity = remember(shellContext) { shellContext.findActivityOrNull() }
    val armDetailChrome = {
        val splitTakesIt = shellHostActivity?.let(::isDetailSplitEligible) == true
        // INVARIANT: cross-window bar choreography exists ONLY when the shell
        // is Compact, has a bottom bar, and will be fully covered. Medium+
        // shows the rail（没有 bar 可 morph → PlainPush），分栏永远盖不住
        // shell（→ Embedded）—— 两者都绝不许 arm，与 detailLaunchMode 的三值
        // 选择一一对应。
        if (!experienceSessionStore.state.value.nowPlayingExpanded &&
            !splitTakesIt &&
            layoutMode == LayoutMode.Compact
        ) {
            experienceSessionStore.setDetailChromeActive(true)
        }
    }
    val navigateToAlbumFromShell: (String, String?) -> Unit = { albumId, sharedTransitionKey ->
        armDetailChrome()
        dismissMemoriesIfActive()
        onNavigateToAlbum(albumId, sharedTransitionKey)
    }
    val navigateToArtistFromShell: (String, String?) -> Unit = { artistId, sharedTransitionKey ->
        armDetailChrome()
        dismissMemoriesIfActive()
        onNavigateToArtist(artistId, sharedTransitionKey)
    }
    val navigateToPlaylistFromShell: (String, String?) -> Unit = { playlistId, sharedTransitionKey ->
        armDetailChrome()
        dismissMemoriesIfActive()
        onNavigateToPlaylist(playlistId, sharedTransitionKey)
    }

    // Reverse morph BACKSTOP: restore nav chrome when a detail window leaves
    // the screen while the shell is visible (its onStop tick fires after the
    // exit animation) — the primary restores are the detail's own onBackClick
    // and the commit settle above. The drop(1) sits INSIDE repeatOnLifecycle
    // so the StateFlow's replayed value is discarded on EVERY resubscription:
    // a detail back gesture flips the window translucent, which restarts the
    // shell, and a globally-applied drop(1) let those replays through —
    // clearing the chrome mid-gesture. Detail→detail hops still tick only
    // while the shell is covered (collector down), so they can't reset it.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            experienceSessionStore.detailWindowSettledTick
                .drop(1)
                .collect { experienceSessionStore.setDetailChromeActive(false) }
        }
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
    // launchAnimateTo is settleJob-tracked, so a drag that starts while the
    // panel is animating cancels the spring instead of fighting it.
    LaunchedEffect(homeSurface) {
        when (homeSurface) {
            HomeSurface.Memories -> if (memoriesReveal.fraction > 0.001f) {
                memoriesReveal.launchAnimateTo(this, 0f)
            }
            HomeSurface.Feed -> if (memoriesReveal.fraction < 0.999f) {
                memoriesReveal.launchAnimateTo(this, 1f)
            }
        }
    }

    LaunchedEffect(selectedSection) {
        if (selectedSection != YoinSection.HOME) {
            memoriesReveal.snapTo(1f)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedContent<YoinSection>(
            targetState = selectedSection,
            transitionSpec = {
                YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                    YoinMotion.fadeOut(role = YoinMotionRole.Standard)
            },
            // AOSP "entering target": while a detail page's predictive back
            // collapses its card above this (now-visible) window, the shell
            // CONTENT sits 96dp left, scales in sync and follows the finger,
            // then settles on commit. The bar below stays put — it is the
            // static twin under the detail window's bar.
            modifier = Modifier
                .fillMaxSize()
                // Rail chrome: the section content shifts right so the rail
                // owns the left edge. Compact/Tabletop 取空 Modifier ——
                // `then(Modifier)` 原样返回 receiver，Compact 的修饰链逐字节
                // 不变。
                .then(
                    if (chromeUsesRail) {
                        Modifier.padding(start = YoinNavRailWidth)
                    } else {
                        Modifier
                    },
                )
                .then(
                    rememberDetailBackEnteringModifier(
                        experienceSessionStore,
                        experienceSession.detailChromeActive,
                    ),
                ),
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
                            isPlaying = isPlaying,
                            playbackSignal = if (isPlaying) playbackSignal else 0f,
                            activeSongId = currentTrack?.id?.toString(),
                            suppressBackHandling = showNowPlaying,
                            onNavigateToSettings = { navigateToSettingsFromShell(null) },
                            onNavigateToMemories = {
                                experienceSessionStore.setHomeSurface(HomeSurface.Memories)
                            },
                            onOpenMemoryFocus = { sessionId ->
                                // Park the focus, then open — MemoriesViewModel's
                                // observer builds the deck stopped on this album.
                                experienceSessionStore.requestMemoriesFocus(sessionId)
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
                                    // 印章卡唯一的导航出口：走 shell 的标准
                                    // detail 前进推入（含 bar morph 交接）。
                                    // 不 dismiss —— Memories 留在原地，back
                                    // 从专辑页回来时它还在。
                                    onOpenAlbum = { memory ->
                                        onNavigateToAlbum(memory.entityId, null)
                                    },
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
                    activeSongId = currentTrack?.id?.toString(),
                    isPlaying = isPlaying,
                    playbackSignal = if (isPlaying) playbackSignal else 0f,
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

        // ── Now Playing overlay (scrim + slide-up + back layering) ───────
        NowPlayingOverlayHost(
            viewModel = nowPlayingViewModel,
            container = app.container,
            expanded = showNowPlaying,
            onExpandedChange = experienceSessionStore::setNowPlayingExpanded,
            // Keep Now Playing expanded across the push. NP is a shell-scoped
            // overlay, so when a detail Activity covers the shell, NP stops
            // rendering with it; returning restores NP in whatever stage the
            // user left it (Compact or Expanded Lyrics/About/Note) — the
            // Apple Music behaviour.
            onAlbumClick = { albumId -> navigateToAlbumFromShell(albumId, null) },
            onArtistClick = { artistId -> navigateToArtistFromShell(artistId, null) },
            onPlaylistClick = { playlistId -> navigateToPlaylistFromShell(playlistId, null) },
            sharedTransitionScope = sharedTransitionScope,
        )

        // ── Navigation chrome: bottom bar (Compact / Tabletop) ⇄ left rail ─
        // Simple if/else inside a Crossfade on the effects spring — no shape
        // morph between the two chromes. On Compact windows the target never
        // flips, so the bar branch composes exactly as it always has and the
        // rail code never runs.
        Crossfade(
            targetState = chromeUsesRail,
            animationSpec = YoinMotion.effectsSpring(),
            label = "shellNavChrome",
        ) { railChrome ->
            if (railChrome) {
                // ── Left navigation rail (Medium+ full-window shell) ─────
                // Mirrors the bar's Now Playing choreography: the rail sits
                // above NowPlayingOverlayHost in z-order, so it slides off
                // LEFT while the player owns the window instead of drawing
                // over the stage. Other overlays are untouched v1: Memories
                // rises inside the shifted content pane (the rail stays
                // visible beside it) and snackbars anchor to the full
                // window, overlapping the rail area.
                AnimatedVisibility(
                    visible = !showNowPlaying,
                    enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard) +
                        YoinMotion.slideInHorizontally(role = YoinMotionRole.Standard) { -it },
                    exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard) +
                        YoinMotion.slideOutHorizontally(role = YoinMotionRole.Standard) { -it },
                ) {
                    // Twin of the bar branch's derivation below (the bar code
                    // must stay untouched, so the projection is duplicated,
                    // not hoisted): the 4Hz position tick recomposes only
                    // this chrome subtree and stops entirely while Now
                    // Playing is open (this content is disposed). Same
                    // narrow projection — no new shell-level collector.
                    val railPlaybackProgress by remember(playbackManager) {
                        playbackManager.playbackState
                            .map { state ->
                                if (state.duration > 0L) {
                                    (state.position.toFloat() / state.duration).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                            }
                            .distinctUntilChanged()
                    }.collectAsState(
                        initial = remember(playbackManager) {
                            playbackManager.playbackState.value.let { state ->
                                if (state.duration > 0L) {
                                    (state.position.toFloat() / state.duration).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                            }
                        },
                    )
                    YoinNavRail(
                        selectedSection = selectedSection,
                        // Same session mutations as the bar's nav buttons;
                        // haptics live inside the rail, like the bar's.
                        onSelectHome = {
                            experienceSessionStore.setSelectedSection(YoinSection.HOME)
                            experienceSessionStore.setHomeSurface(HomeSurface.Feed)
                        },
                        onSelectLibrary = {
                            libraryViewModel.showLibraryHome()
                            experienceSessionStore.setSelectedSection(YoinSection.LIBRARY)
                            experienceSessionStore.setHomeSurface(HomeSurface.Feed)
                        },
                        playbackTrackId = currentTrack?.id?.toString(),
                        playbackCoverUrl = coverArtUrl,
                        playbackProgress = railPlaybackProgress,
                        isPlaying = isPlaying,
                        connectionErrorMessage = playbackConnectionError,
                        onOpenNowPlaying = {
                            dismissMemoriesIfActive()
                            experienceSessionStore.setNowPlayingExpanded(true)
                        },
                    )
                }
            } else {
                // ── Bottom navigation ────────────────────────────────────
                // Slide the bottom group fully off-screen, BELOW the nav bar: the group
                // carries the nav-bar inset as internal bottom padding, so a plain
                // slide of `it` (its own height) leaves it starting part-way up the
                // screen rather than off the edge. Adding the inset to the offset makes
                // it enter from truly off-screen while keeping its resting position and
                // edge-to-edge transparency intact.
                val navBarBottomPx = with(LocalDensity.current) {
                    WindowInsets.navigationBars.getBottom(this)
                }
                // NOTE: a dock hand-off (shell → detail morph) deliberately does NOT
                // touch the bar. The detail window fades in with a pill at the bar's
                // exact bounds/color, so the true crossfade bar→pill happens between
                // the two windows; the real bar stays put beneath and is simply there
                // again on return (including the predictive-back preview).
                // Cold-launch entrance: start hidden for one frame so the same
                // slide+fade that plays after a Now Playing dismiss also greets the
                // app open — the bar rises in instead of just being there.
                var barEntered by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { barEntered = true }
                AnimatedVisibility(
                    visible = barEntered && !showNowPlaying,
                    enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard) +
                        YoinMotion.slideInVertically(role = YoinMotionRole.Standard) { it + navBarBottomPx },
                    exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard) +
                        YoinMotion.slideOutVertically(role = YoinMotionRole.Standard) { it + navBarBottomPx },
                ) {
                    val bgAvScope = this
                    // The mini player's progress ring is the ONLY shell consumer of the
                    // 4Hz position tick. Derive it inside this bottom-nav subtree so
                    // ticks recompose just this block — and stop entirely while Now
                    // Playing is open (this AnimatedVisibility content is disposed).
                    val playbackProgress by remember(playbackManager) {
                        playbackManager.playbackState
                            .map { state ->
                                if (state.duration > 0L) {
                                    (state.position.toFloat() / state.duration).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                            }
                            .distinctUntilChanged()
                    }.collectAsState(
                        // Seed from the live state, not 0f: this subtree remounts every
                        // time Now Playing closes, and a 0% first frame reads as a blip.
                        // Read inside remember so the StateFlow is not touched from
                        // composition; the seed matters only for that first frame.
                        initial = remember(playbackManager) {
                            playbackManager.playbackState.value.let { state ->
                                if (state.duration > 0L) {
                                    (state.position.toFloat() / state.duration).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                            }
                        },
                    )

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
                            // Single settle owner for the bar pose: open/restore
                            // morphs AND the detail-back commit settle (seeded from
                            // the frozen scrub pose bridged through the store, so the
                            // dissolve above crossfades onto a matching bar).
                            chromeProgress = rememberShellBarChromeMorph(
                                experienceSessionStore,
                                experienceSession.detailChromeActive,
                            ),
                            currentTrackId = currentTrack?.id?.toString(),
                            currentTrackTitle = currentTrack?.title,
                            currentTrackArtist = currentTrack?.artist,
                            currentTrackCoverArtUrl = coverArtUrl,
                            isPlaybackReady = isPlaybackReady,
                            connectionErrorMessage = playbackConnectionError,
                            playbackProgress = playbackProgress,
                            isPlaying = isPlaying,
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
                            // In dual-pane NP there is NO mini-player → cover morph (the
                            // two-column player drops the cover shared element). Leaving
                            // the mini cover's shared element here makes it a no-peer
                            // shared element, which the SharedTransitionLayout lookahead
                            // measures with degenerate constraints and crashes M3
                            // ButtonGroup. Semantic flip 2026-07-27: was `== Wide`; the
                            // hazard follows WidePlayingContent, which now renders from
                            // Medium up, so disable the shell's shared elements wherever
                            // it does (isDualPaneNowPlaying). Tabletop keeps its scopes
                            // exactly as before the flip.
                            sharedTransitionScope = if (layoutMode.isDualPaneNowPlaying) {
                                null
                            } else {
                                sharedTransitionScope
                            },
                            animatedVisibilityScope = if (layoutMode.isDualPaneNowPlaying) {
                                null
                            } else {
                                bgAvScope
                            },
                        )
                    }
                }
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
            // Prefer the playlist's own art. The memory's cover is a resolved
            // URL — a valid storage key on Spotify only; Subsonic resolved
            // URLs embed a rotating token, so those keep the track fallback.
            coverArtId = coverArtUrl.takeIf { entityProvider == MediaId.PROVIDER_SPOTIFY }
                ?: coverArtId,
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
