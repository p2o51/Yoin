package com.gpo.yoin.ui.navigation

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.lifecycle.withResumed
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import com.gpo.yoin.R
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.perf.YoinPerf
import com.gpo.yoin.player.PlaybackEvent
import com.gpo.yoin.player.SpotifyConnectFailure
import com.gpo.yoin.ui.component.AddToPlaylistSheet
import com.gpo.yoin.ui.component.BarEditPose
import com.gpo.yoin.ui.component.BarPlaySplitActions
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.LocalSharedPageBackground
import com.gpo.yoin.ui.component.YoinChromeGroup
import com.gpo.yoin.ui.detail.AlbumDetailActivity
import com.gpo.yoin.ui.detail.ArtistDetailActivity
import com.gpo.yoin.ui.detail.DetailLaunchMode
import com.gpo.yoin.ui.detail.PlaylistDetailActivity
import com.gpo.yoin.ui.detail.findActivityOrNull
import com.gpo.yoin.ui.detail.hasOverlayHidingBottomBar
import com.gpo.yoin.ui.detail.launchDetailFromShell
import com.gpo.yoin.ui.experience.DetailBackPhase
import com.gpo.yoin.ui.experience.EdgeSplitSide
import com.gpo.yoin.ui.experience.HomeSurface
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.LocalPaneWidthInMotion
import com.gpo.yoin.ui.experience.LocalShellChromeInsets
import com.gpo.yoin.ui.experience.LocalWindowCovered
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.ShellChromeForm
import com.gpo.yoin.ui.experience.hasChromeHandoff
import com.gpo.yoin.ui.experience.hasDetailPane
import com.gpo.yoin.ui.experience.rememberEdgeSplitSide
import com.gpo.yoin.ui.experience.rememberRevealState
import com.gpo.yoin.ui.experience.voteHighFrameRate
import com.gpo.yoin.ui.home.AccountSwitcherViewModel
import com.gpo.yoin.ui.home.HomeScreen
import com.gpo.yoin.ui.home.HomeViewModel
import com.gpo.yoin.ui.home.edit.HomeEditExitReason
import com.gpo.yoin.ui.home.edit.rememberHomeEditController
import com.gpo.yoin.ui.library.LibraryScreen
import com.gpo.yoin.ui.library.LibrarySearchScope
import com.gpo.yoin.ui.library.LibraryViewModel
import com.gpo.yoin.ui.memories.MemoriesScreen
import com.gpo.yoin.ui.memories.MemoriesViewModel
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryEntry
import com.gpo.yoin.ui.memories.rememberMemoriesHomeBehind
import com.gpo.yoin.ui.memories.rememberMemoriesHostPose
import com.gpo.yoin.ui.navigation.back.OverlayChromeVisibility
import com.gpo.yoin.ui.navigation.back.ShellBackOwner
import com.gpo.yoin.ui.navigation.back.rememberDetailBackEnteringModifier
import com.gpo.yoin.ui.navigation.back.rememberShellBarChromeMorph
import com.gpo.yoin.ui.navigation.back.resolveShellBackOwner
import com.gpo.yoin.ui.navigation.pane.DetailColumnsLayout
import com.gpo.yoin.ui.navigation.pane.DetailPaneCloseHandler
import com.gpo.yoin.ui.navigation.pane.DetailPaneDivider
import com.gpo.yoin.ui.navigation.pane.DetailPaneHost
import com.gpo.yoin.ui.navigation.pane.DetailPaneRoute
import com.gpo.yoin.ui.navigation.pane.DetailPaneViewModelStoreOwner
import com.gpo.yoin.ui.navigation.pane.PaneBarRegistry
import com.gpo.yoin.ui.navigation.pane.TwoColumnsMinWidth
import com.gpo.yoin.ui.navigation.pane.animateOpen
import com.gpo.yoin.ui.navigation.pane.awaitPrewarm
import com.gpo.yoin.ui.navigation.pane.detailPaneClosePreview
import com.gpo.yoin.ui.navigation.pane.rememberColumnWindowInfos
import com.gpo.yoin.ui.navigation.pane.rememberDetailPaneState
import com.gpo.yoin.ui.navigation.pane.rememberPaneSplitState
import com.gpo.yoin.ui.navigation.pane.requestColumn
import com.gpo.yoin.ui.navigation.pane.snapClosed
import com.gpo.yoin.ui.nowplaying.NowPlayingAccessories
import com.gpo.yoin.ui.nowplaying.NowPlayingOverlayHost
import com.gpo.yoin.ui.nowplaying.NowPlayingPanelMinContentWidth
import com.gpo.yoin.ui.nowplaying.NowPlayingPresentation
import com.gpo.yoin.ui.nowplaying.NowPlayingScreen
import com.gpo.yoin.ui.nowplaying.NowPlayingStageMode
import com.gpo.yoin.ui.nowplaying.NowPlayingViewModel
import com.gpo.yoin.ui.nowplaying.besideNowPlayingPanel
import com.gpo.yoin.ui.nowplaying.canOpenNowPlayingPanel
import com.gpo.yoin.ui.nowplaying.nowPlayingCoversHome
import com.gpo.yoin.ui.nowplaying.rememberNowPlayingFrame
import com.gpo.yoin.ui.nowplaying.rememberNowPlayingPanelInset
import com.gpo.yoin.ui.nowplaying.rememberNowPlayingPanelMotion
import com.gpo.yoin.ui.nowplaying.rememberNowPlayingStageProgress
import com.gpo.yoin.ui.nowplaying.rememberWindowWidthDp
import com.gpo.yoin.ui.settings.SettingsActivity
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun YoinNavHost(
    modifier: Modifier = Modifier,
) {
    SharedTransitionLayout(modifier = modifier) {
        val sharedTransitionScope = this
        val context = LocalContext.current
        val app = context.applicationContext as YoinApplication
        // Activity launches serve every window WITHOUT a detail column (Wide +
        // tall windows host details as a column inside YoinShell instead —
        // adaptive principle 3). INVARIANT: cross-window bar choreography
        // exists ONLY when the shell is Compact, has a bottom bar, and will
        // be fully covered — every other configuration launches without the
        // hand-off.
        val shellWindowInfo = LocalYoinWindowInfo.current
        val detailLaunchMode = {
            when {
                // 竖屏底栏（Compact、非 Tabletop）与手机横屏的分离式组：两个
                // 窗口的 Button Group 逐像素同位，完整交接。居中底栏（Medium
                // 整窗）的详情形态换了排布（外提动作 + 定宽 pill），不做跨窗口
                // morph → 纯推入；Tabletop 照旧纯推入。
                shellWindowInfo.hasChromeHandoff -> DetailLaunchMode.FullChoreography
                else -> DetailLaunchMode.PlainPush
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
    // Held while a detail window covers the shell (nothing of Home is seen).
    val shellCovered = LocalWindowCovered.current
    val playbackSignal by remember(app, shellCovered) {
        app.container.audioVisualizerManager.playbackSignal
            .combine(snapshotFlow { shellCovered.value }) { signal, covered -> signal to covered }
            .filter { (_, covered) -> !covered }
            .map { (signal, _) -> signal }
            .distinctUntilChanged()
    }.collectAsState(initial = app.container.audioVisualizerManager.playbackSignal.value)
    val windowInfo = LocalYoinWindowInfo.current
    // One Button Group, three forms (断点交接 §1): portrait bar, edge-split
    // capsules for short windows, centred capped bar from Medium up. The old
    // Medium+ left rail is gone — tall windows no longer ration height.
    val chromeForm = windowInfo.chromeForm
    val edgeSplit = chromeForm == ShellChromeForm.EdgeSplit
    val edgeSplitSide = rememberEdgeSplitSide()
    val shellChromeInsets = LocalShellChromeInsets.current
    val shellLayoutDirection = LocalLayoutDirection.current
    val edgeContentPadding = if (edgeSplit) {
        Modifier.padding(
            start = shellChromeInsets.calculateStartPadding(shellLayoutDirection),
            end = shellChromeInsets.calculateEndPadding(shellLayoutDirection),
        )
    } else {
        Modifier
    }
    // The detail COLUMN (adaptive principle 3): on a Wide + tall window the
    // detail pages open as a second column of THIS window — a Navigation 3
    // stack beside the shell content — instead of as Activities. One bar then
    // spans both columns and the Now Playing panel slides in beside both.
    val hasDetailPane = windowInfo.hasDetailPane
    val paneState = rememberDetailPaneState()
    val paneSplit = rememberPaneSplitState()
    val paneStack = rememberNavBackStack()
    val paneViewModels: DetailPaneViewModelStoreOwner = viewModel()
    val paneRegistry = remember { PaneBarRegistry() }
    val paneSpring = YoinMotion.defaultSpatialSpec<Float>()
    val paneHasEntries = paneStack.isNotEmpty()
    val paneOpen = paneHasEntries && !paneState.closing
    // Now Playing's frame in this window: on a Medium or Wide full window the
    // pill opens a phone-width side panel and the shell content keeps working
    // beside it, narrower and read by its own width (adaptive principle 4).
    // Beside the panel the shell keeps a phone column — two phone columns and
    // the gutter while the detail column is open; where that cannot fit, the
    // player goes straight to its full state.
    val npPanelMinContent = if (paneHasEntries) TwoColumnsMinWidth else NowPlayingPanelMinContentWidth
    val npFrame = rememberNowPlayingFrame(nowPlayingViewModel, npPanelMinContent)
    val npPanelMotion = rememberNowPlayingPanelMotion()
    val npPanel = rememberNowPlayingPanelInset(npFrame, showNowPlaying, npPanelMotion)
    val npSharesCover = npFrame.presentation == NowPlayingPresentation.Phone ||
        npFrame.presentation == NowPlayingPresentation.Tabletop
    val memoriesReveal = rememberRevealState(
        initialFraction = if (homeSurface == HomeSurface.Memories) 0f else 1f,
    )
    // Derived: a pull or spring frame invalidates the shell only when the
    // deck's mounted-ness flips, never per frame.
    val memoriesVisible by remember(memoriesReveal) { derivedStateOf { memoriesReveal.isVisible } }
    val memoriesMounted = homeSurface == HomeSurface.Memories || memoriesVisible
    val shellScope = rememberCoroutineScope()
    // Home edit mode: the only writer of the Edit surface and its progress P.
    // Hoisted here so back, the bar and every exit trigger below reach it.
    val homeEdit = rememberHomeEditController(experienceSessionStore, homeViewModel)
    LaunchedEffect(homeEdit) {
        homeViewModel.homeLayout.collect(homeEdit::onVmLayout)
    }
    // A profile switch ends edit mode: the draft belongs to the old profile.
    // Not musicConfigurationRevision — that also ticks on credential edits and
    // replays on every recreated shell.
    LaunchedEffect(homeEdit) {
        homeViewModel.activeProfileId.drop(1).collect { homeEdit.onProfileSwitched() }
    }

    val coverArtUrl = currentTrack?.coverArt?.let { coverArt ->
        app.container.repository.resolveCoverUrl(coverArt)
    }

    LaunchedEffect(Unit) {
        app.container.playbackManager.connectInBackground()
    }

    LaunchedEffect(musicConfigurationRevision) {
        if (musicConfigurationRevision == 0L) return@LaunchedEffect
        // A profile switch (Settings is reachable beside an open column):
        // the column's pages hold the old profile's ids, while play / star /
        // go-to-artist would act on the new source. The column ends.
        if (paneStack.isNotEmpty()) {
            paneStack.clear()
            paneState.snapClosed()
        }
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
        detailPaneOpen = paneOpen,
    )
    // The column's back handlers (NavDisplay's stacked pop, the last page's
    // close) register on a CHILD dispatcher that is live only while the
    // column owns back. Handler priority is registration order, and the
    // column mounts after Now Playing — without this gate a stacked page
    // would pop under the open player.
    val paneBackOwner = rememberNavigationEventDispatcherOwner(
        enabled = shellBackOwner == ShellBackOwner.DetailPane,
    )
    // Column open / close: ONE spring drives the width (DetailPaneState is
    // the single owner). Closing keeps the last entry on screen until the
    // column has slid out, then clears the stack; opening another page
    // mid-close simply retargets the spring.
    LaunchedEffect(paneHasEntries, paneState.closing) {
        when {
            !paneHasEntries -> paneState.snapClosed()
            paneState.closing -> {
                paneState.animateOpen(open = false, spec = paneSpring)
                paneStack.clear()
                paneState.closing = false
            }
            else -> {
                paneState.awaitPrewarm()
                paneState.animateOpen(open = true, spec = paneSpring)
            }
        }
    }
    val openPane: (DetailPaneRoute) -> Unit = { route ->
        // From the shell's content or Now Playing the column shows the
        // tapped page as its root; pushes come only from inside the column.
        // The shell re-tiers in this same frame, beside the new page's first
        // composition — all before the open spring starts (awaitPrewarm).
        paneState.requestColumn()
        if (paneStack.lastOrNull() != route || paneState.closing) {
            paneState.closing = false
            paneStack.clear()
            paneStack.add(route)
        }
        // The page the user asked for must be visible. A full-window player
        // would cover the column: step it back to the side panel — or, where
        // the panel cannot sit beside two columns, close it.
        if (showNowPlaying) {
            val panelFitsBesideColumns = canOpenNowPlayingPanel(
                layoutMode = windowInfo.layoutMode,
                heightAtLeastMedium = windowInfo.isHeightAtLeastMedium,
                windowWidth = npFrame.windowWidth,
                minContentWidth = TwoColumnsMinWidth,
            )
            if (!panelFitsBesideColumns) {
                experienceSessionStore.setNowPlayingExpanded(false)
            } else if (npFrame.presentation == NowPlayingPresentation.DualPane) {
                nowPlayingViewModel.setMediumFullscreen(false)
            }
        }
    }
    val pushPane: (DetailPaneRoute) -> Unit = { route ->
        markDetailClick(route, via = "pane-push")
        paneStack.add(route)
    }
    // Opening or closing the column re-tiers the shell — never mid-edit: edit
    // mode animates out first (P settles at 0), then the column moves.
    val currentOpenPane by rememberUpdatedState(openPane)
    val openPaneFromShell: (DetailPaneRoute) -> Unit = { route ->
        if (homeEdit.isEditing) {
            shellScope.launch {
                homeEdit.commitAndExitAndAwait()
                currentOpenPane(route)
            }
        } else {
            openPane(route)
        }
    }
    val closePane: () -> Unit = {
        if (homeEdit.isEditing) {
            shellScope.launch {
                homeEdit.commitAndExitAndAwait()
                if (paneStack.isNotEmpty()) paneState.closing = true
            }
        } else if (paneHasEntries) {
            paneState.closing = true
        }
    }
    val popPaneEntry = {
        if (paneStack.size > 1) paneStack.removeLastOrNull() else closePane()
    }
    val homeEditing = selectedSection == YoinSection.HOME && homeSurface == HomeSurface.Edit
    // The shell bar's [Undo|Add] [Done] pose: it reads P and the left slot
    // from the controller and acts only through its two clicks.
    val barEditPose = remember(homeEdit) {
        BarEditPose(
            progress = homeEdit.progressReader,
            leftSlot = { homeEdit.leftSlot },
            onLeftSlotClick = homeEdit::barLeftSlotClick,
            onDone = homeEdit::barDone,
        )
    }
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
        homeEdit.snapExit()
        onNavigateToSettings(focusSection)
    }
    // Flip the bar to detail chrome the moment the tap lands — the nav→split
    // morph IS the tap feedback; the detail window's delayed slide then lands
    // on its own identical bar (see detail_bar_handoff_enter.xml). The flag
    // stays up while the detail stack is on top so the predictive-back
    // preview reveals a matching bar; the restore effect below flips it back.
    // With Now Playing or Memories open the shell bar is hidden and the back reveal
    // is the overlay itself — arming chrome would only queue a phantom morph (and a
    // wrong split→nav scrub on the detail's back), so skip it.
    val shellContext = LocalContext.current
    val shellHostActivity = remember(shellContext) { shellContext.findActivityOrNull() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var detailLaunchPending by remember { mutableStateOf(false) }
    var detailLaunchSawPause by remember { mutableStateOf(false) }

    // A lifecycle gate alone still has a short hole between startActivity()
    // and MainActivity receiving ON_PAUSE. Two taps inside that hole used to
    // launch two translucent detail windows into the same WM transition. Arm
    // this latch synchronously with the first tap and retire it only after the
    // shell really returns. Embedded/no-op launches never pause the shell, so
    // release those after a bounded grace period.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> detailLaunchSawPause = true
                Lifecycle.Event.ON_RESUME -> {
                    if (detailLaunchSawPause) {
                        detailLaunchPending = false
                        detailLaunchSawPause = false
                    }
                }
                // Returning to the app never finds Home still in edit mode.
                Lifecycle.Event.ON_STOP -> homeEdit.snapExit()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(detailLaunchPending, detailLaunchSawPause) {
        if (detailLaunchPending && !detailLaunchSawPause) {
            delay(DETAIL_LAUNCH_PAUSE_GRACE_MS)
            if (!detailLaunchSawPause &&
                lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED
            ) {
                detailLaunchPending = false
            }
        }
    }
    val canLaunchDetail = {
        shellHostActivity?.let {
            canLaunchDetailFromShell(
                lifecycleState = lifecycleOwner.lifecycle.currentState,
                backPhase = experienceSessionStore.detailBackPhase.value,
                launchPending = detailLaunchPending,
            )
        } == true
    }
    val armDetailChrome = {
        // Edit mode never meets detail chrome (its morph forces the pill to
        // compose, and the hand-off needs the plain nav pose): snap out first.
        homeEdit.snapExit()
        // INVARIANT: cross-window bar choreography exists ONLY when both
        // windows draw the SAME group geometry and the shell will be fully
        // covered: the portrait bar (Compact, not Tabletop) or the edge-split
        // capsules (hasChromeHandoff). The centred bar's detail pose differs
        // (→ PlainPush) — it may not arm; one-to-one with detailLaunchMode's
        // choice. (A Wide window never gets here: its details are a column.)
        if (!experienceSessionStore.state.value.hasOverlayHidingBottomBar &&
            windowInfo.hasChromeHandoff
        ) {
            experienceSessionStore.prepareDetailEnterSlide()
            experienceSessionStore.setDetailChromeActive(true)
        }
    }
    val navigateToAlbumFromShell: (String, String?) -> Unit = { albumId, sharedTransitionKey ->
        if (hasDetailPane) {
            YoinPerf.detailClick("album", albumId, via = "pane")
            openPaneFromShell(DetailPaneRoute.Album(albumId))
        } else if (canLaunchDetail()) {
            YoinPerf.detailClick("album", albumId, via = "activity")
            detailLaunchPending = true
            try {
                armDetailChrome()
                dismissMemoriesIfActive()
                onNavigateToAlbum(albumId, sharedTransitionKey)
            } catch (error: RuntimeException) {
                detailLaunchPending = false
                experienceSessionStore.setDetailChromeActive(false)
                throw error
            }
        }
    }
    val navigateToArtistFromShell: (String, String?) -> Unit = { artistId, sharedTransitionKey ->
        if (hasDetailPane) {
            YoinPerf.detailClick("artist", artistId, via = "pane")
            openPaneFromShell(DetailPaneRoute.Artist(artistId))
        } else if (canLaunchDetail()) {
            YoinPerf.detailClick("artist", artistId, via = "activity")
            detailLaunchPending = true
            try {
                armDetailChrome()
                dismissMemoriesIfActive()
                onNavigateToArtist(artistId, sharedTransitionKey)
            } catch (error: RuntimeException) {
                detailLaunchPending = false
                experienceSessionStore.setDetailChromeActive(false)
                throw error
            }
        }
    }
    val navigateToPlaylistFromShell: (String, String?) -> Unit = { playlistId, sharedTransitionKey ->
        if (hasDetailPane) {
            YoinPerf.detailClick("playlist", playlistId, via = "pane")
            openPaneFromShell(DetailPaneRoute.Playlist(playlistId))
        } else if (canLaunchDetail()) {
            YoinPerf.detailClick("playlist", playlistId, via = "activity")
            detailLaunchPending = true
            try {
                armDetailChrome()
                dismissMemoriesIfActive()
                onNavigateToPlaylist(playlistId, sharedTransitionKey)
            } catch (error: RuntimeException) {
                detailLaunchPending = false
                experienceSessionStore.setDetailChromeActive(false)
                throw error
            }
        }
    }

    // Leaving the Wide tier with a column open (rotating a tablet to portrait,
    // a window snapped narrower): the column cannot exist there, so it is gone
    // before the next frame and its top page continues as the pushed window
    // page that tier uses — launched through the same gate and chrome
    // hand-off as a tap, once the shell is resumed.
    LaunchedEffect(hasDetailPane) {
        if (!hasDetailPane && paneStack.isNotEmpty()) {
            val top = paneStack.last() as DetailPaneRoute
            // A column the user is already closing is dropped, not relaunched.
            val relaunch = !paneState.closing
            paneStack.clear()
            paneState.snapClosed()
            // Now Playing outranks the column (it was the front surface):
            // leave it in front instead of pushing the page over it.
            if (!relaunch || showNowPlaying) return@LaunchedEffect
            lifecycleOwner.lifecycle.withResumed {
                // The tap gate and chrome hand-off, but Memories stays where
                // it was (as an album opened from Memories keeps it).
                if (canLaunchDetail()) {
                    detailLaunchPending = true
                    try {
                        armDetailChrome()
                        when (top) {
                            is DetailPaneRoute.Album -> onNavigateToAlbum(top.albumId, null)
                            is DetailPaneRoute.Artist -> onNavigateToArtist(top.artistId, null)
                            is DetailPaneRoute.Playlist -> onNavigateToPlaylist(top.playlistId, null)
                        }
                    } catch (error: RuntimeException) {
                        detailLaunchPending = false
                        experienceSessionStore.setDetailChromeActive(false)
                        throw error
                    }
                }
            }
        }
    }

    // Reverse morph BACKSTOP: restore nav chrome when a detail window leaves
    // the screen while the shell is visible (the outer shell-launched detail
    // publishes an onStop tick after its exit animation) — the primary
    // restores are the detail's own onBackClick and the commit settle above.
    // Keep drop(1) INSIDE repeatOnLifecycle so a replayed old completion tick
    // is discarded on every app foreground resubscription.
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
                    val actionLabel = actionLabelForFailure(event.failure, shellContext)
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
                    val actionLabel = actionLabelForFailure(event.failure, shellContext)
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
            val pending = shellContext.getString(R.string.library_feedback_apple_music_pending)
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = if (message == pending) {
                    shellContext.getString(R.string.library_action_check)
                } else {
                    null
                },
                duration = if (message == pending) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                libraryViewModel.checkPendingLibraryAddition()
            }
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
            HomeSurface.Feed, HomeSurface.Edit -> if (memoriesReveal.fraction < 0.999f) {
                memoriesReveal.launchAnimateTo(this, 1f)
            }
        }
    }
    // Consistency heal: whatever replaced the Edit surface ends the session —
    // without writing the surface back over it. Reads the live surface, not
    // this effect's key: an enter() that lands before the effect runs is
    // already Edit again and must survive.
    LaunchedEffect(homeSurface) {
        val live = experienceSessionStore.state.value.homeSurface
        if (live != HomeSurface.Edit && homeEdit.isEditing) homeEdit.onSurfaceLost()
    }

    LaunchedEffect(selectedSection) {
        if (selectedSection != YoinSection.HOME) {
            memoriesReveal.snapTo(1f)
            if (homeEdit.isEditing) homeEdit.snapExit()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // The columns region: everything beside the Now Playing panel. The
        // shell column takes what the detail column (gutter + page) leaves,
        // on the column's open/close spring; each column reads its OWN width.
        // Column tiers come from the region at rest (window − a settled
        // panel); the live widths are the layout's (measure phase only).
        val windowWidth = rememberWindowWidthDp()
        val restingRegionWidth = (windowWidth - if (npPanel.panelOpen) npPanel.panelWidth else 0.dp)
            .coerceAtLeast(0.dp)
        val columnWindowInfos = rememberColumnWindowInfos(restingRegionWidth, paneState, paneSplit)
        // Post-release settles have no touch boost: vote High while the
        // column or the panel beside it moves (ARR panels pace them at 60Hz).
        val columnsMoving by remember(paneState, npPanel) {
            derivedStateOf { paneState.openFraction.isRunning || npPanel.isMoving }
        }
        // The shell column's width is moving (springs, a gesture carrying the
        // panel, or the handle): feeds whose height follows width place their
        // items 1:1 meanwhile. One State for the shell's lifetime — the panel
        // inset is re-created when it opens or closes, and a new object in
        // this static local would invalidate the whole shell subtree.
        val currentNpPanel by rememberUpdatedState(npPanel)
        val paneWidthInMotion = remember(paneState, paneSplit) {
            derivedStateOf {
                paneState.openFraction.isRunning || currentNpPanel.isMoving ||
                    currentNpPanel.isCarried || paneSplit.dragging
            }
        }
        DetailColumnsLayout(
            paneState = paneState,
            split = paneSplit,
            modifier = Modifier
                .fillMaxSize()
                .voteHighFrameRate(columnsMoving)
                .besideNowPlayingPanel(npPanel),
            shell = {
        CompositionLocalProvider(
            LocalYoinWindowInfo provides columnWindowInfos.shell.value,
            LocalPaneWidthInMotion provides paneWidthInMotion,
        ) {
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
                        // The page wash runs full-bleed, under the edge-split
                        // capsules and into the cutout band too: the page
                        // itself is inset past them (edgeContentPadding), and
                        // the wash is a vertical gradient with Home's own
                        // parameters, so the two meet without a seam.
                        ExpressivePageBackground(modifier = Modifier.fillMaxSize()) {
                            val accountSwitcher: AccountSwitcherViewModel = viewModel(
                                factory = AccountSwitcherViewModel.Factory(app.container),
                            )
                            HomeScreen(
                                viewModel = homeViewModel,
                                accountSwitcher = accountSwitcher,
                                isPlaying = isPlaying,
                                playbackSignal = if (isPlaying) playbackSignal else 0f,
                                activeSongId = currentTrack?.id?.toString(),
                                // Something above Home owns the screen (Now
                                // Playing, the detail column): Home keeps quiet
                                // and leaves back to it.
                                homeCovered = showNowPlaying || paneOpen,
                                editController = homeEdit,
                                onNavigateToSettings = { navigateToSettingsFromShell(null) },
                                // Memories never opens over edit mode (the surface
                                // heal would end the edit under the deck).
                                onNavigateToMemories = {
                                    if (!homeEdit.isEditing) {
                                        experienceSessionStore.setHomeSurface(HomeSurface.Memories)
                                    }
                                },
                                onOpenMemoryFocus = { sessionId ->
                                    if (!homeEdit.isEditing) {
                                        // Park the focus, then open — MemoriesViewModel's
                                        // observer builds the deck stopped on this album.
                                        experienceSessionStore.requestMemoriesFocus(sessionId)
                                        experienceSessionStore.setHomeSurface(HomeSurface.Memories)
                                    }
                                },
                                memoriesRevealState = memoriesReveal,
                                onCommitMemoriesReveal = {
                                    if (homeEdit.isEditing) {
                                        memoriesReveal.launchAnimateTo(shellScope, 1f)
                                    } else {
                                        experienceSessionStore.setHomeSurface(HomeSurface.Memories)
                                    }
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
                                // Edge-split: the feed starts past the capsules (84dp)
                                // and stops short of a right cutout; the background
                                // above stays full-bleed. Memories below is NOT
                                // shifted — the group hides there, it only clears
                                // the cutout band (§6).
                                modifier = Modifier
                                    .fillMaxSize()
                                    // Behind Memories: 0.94 / 0.5 → 1 / 1 as it retreats (q, layer only).
                                    .then(rememberMemoriesHomeBehind(memoriesReveal))
                                    .then(edgeContentPadding),
                            )

                            if (memoriesMounted) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        // −q·H, or a fade in place under reduced motion (layer only).
                                        .then(rememberMemoriesHostPose(memoriesReveal)),
                                ) {
                                    MemoriesScreen(
                                        viewModel = memoriesViewModel,
                                        revealState = memoriesReveal,
                                        onDismissed = closeMemories,
                                        // Memories mounts its own predictive back
                                        // (card level: q scrubs toward Home) here,
                                        // where the plain BackHandler used to be.
                                        backEnabled = shellBackOwner == ShellBackOwner.Memories,
                                        // The detail column keeps the bar up (it carries the
                                        // column's Play): the deck stays clear of it.
                                        bottomInset = if (paneOpen) {
                                            shellChromeInsets.calculateBottomPadding()
                                        } else {
                                            0.dp
                                        },
                                        // 印章卡唯一的导航出口：走 shell 的标准
                                        // detail 前进推入（隐藏底栏不参与 morph 交接）。
                                        // 不 dismiss —— Memories 留在原地，back
                                        // 从专辑页回来时它还在。
                                        onOpenAlbum = { memory ->
                                            // MemoryEntry.entityId is the RAW id
                                            // (the coordinator strips the provider
                                            // prefix); AlbumDetailViewModel parses a
                                            // full MediaId — recombine or parse throws
                                            // and the page lands on "Couldn't load
                                            // this album." (memory → goto album).
                                            if (hasDetailPane) {
                                                markMemoryAlbumClick(memory, via = "pane")
                                                openPane(
                                                    DetailPaneRoute.Album("${memory.entityProvider}:${memory.entityId}"),
                                                )
                                            } else if (canLaunchDetail()) {
                                                markMemoryAlbumClick(memory, via = "activity")
                                                detailLaunchPending = true
                                                try {
                                                    // Keep the deck mounted. Its hidden bottom bar must
                                                    // not participate in a shell chrome hand-off.
                                                    armDetailChrome()
                                                    onNavigateToAlbum(
                                                        "${memory.entityProvider}:${memory.entityId}",
                                                        null,
                                                    )
                                                } catch (error: RuntimeException) {
                                                    detailLaunchPending = false
                                                    experienceSessionStore.setDetailChromeActive(false)
                                                    throw error
                                                }
                                            }
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

                    // Full-bleed page wash under the capsule / cutout band, as Home.
                    YoinSection.LIBRARY -> ExpressivePageBackground(modifier = Modifier.fillMaxSize()) {
                        LibraryScreen(
                            viewModel = libraryViewModel,
                            // The expanded search is a full-window dialog: a
                            // result opened as a column must close it to be seen.
                            collapseSearchOnOpen = hasDetailPane,
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
                            modifier = Modifier
                                .fillMaxSize()
                                .then(edgeContentPadding),
                        )
                    }
                }
            }
        }
            },
            pane = {
                // ── Detail column: gutter (the M3 drag handle) + page, laid
                // out at the column's full width; DetailColumnsLayout slides
                // and clips it at the region's end.
                if (paneHasEntries) {
                    // The column ends when this branch leaves: its pages' view
                    // models end with it (a stack clear never pops entries).
                    val hostActivity = LocalActivity.current
                    DisposableEffect(Unit) {
                        onDispose {
                            if (hostActivity?.isChangingConfigurations != true) paneViewModels.endColumn()
                        }
                    }
                    // The gutter and the column sit on the shell's own neutral
                    // page wash (the column's page draws the same one), so the
                    // two columns meet as one surface — no pale strip between.
                    ExpressivePageBackground(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        DetailPaneDivider(split = paneSplit)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                // A page leaving the column (a push's 96dp
                                // recede) never draws over the gutter or the
                                // shell column (owner 2026-10-05, Fold).
                                .clipToBounds()
                                .detailPaneClosePreview(paneState),
                        ) {
                            // The page builds once the column has landed (see
                            // DetailPaneState.pageReady); the column slides in as
                            // the plain page surface and the page fades in over it.
                            androidx.compose.animation.AnimatedVisibility(
                                visible = paneState.pageReady,
                                enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard),
                                exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                            CompositionLocalProvider(
                                LocalYoinWindowInfo provides columnWindowInfos.pane.value,
                                LocalNavigationEventDispatcherOwner provides paneBackOwner,
                                LocalViewModelStoreOwner provides paneViewModels,
                                LocalSharedPageBackground provides true,
                            ) {
                                DetailPaneHost(
                                    backStack = paneStack,
                                    app = app,
                                    isPlaying = isPlaying,
                                    currentTrackId = currentTrack?.id?.toString(),
                                    playbackSignal = if (isPlaying) playbackSignal else 0f,
                                    registry = paneRegistry,
                                    onPopEntry = { popPaneEntry() },
                                    onPush = pushPane,
                                    onAddToPlaylist = nowPlayingViewModel::requestAddTracksToPlaylist,
                                    onMessage = { message ->
                                        shellScope.launch {
                                            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            }
                        }
                    }
                    }
                }
            },
        )
        // The column's last page closes it — on the column's gated child
        // dispatcher, beside its NavDisplay (which pops stacked pages).
        CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides paneBackOwner) {
            DetailPaneCloseHandler(
                state = paneState,
                enabled = shellBackOwner == ShellBackOwner.DetailPane && paneStack.size == 1,
                onClose = closePane,
            )
        }
        // Home edit mode: back is a discrete Done without the haptic. On the
        // ROOT dispatcher, outside the section and column content, so it is
        // live whenever the resolver names it.
        BackHandler(enabled = shellBackOwner == ShellBackOwner.HomeEdit) {
            homeEdit.commitAndExit(HomeEditExitReason.Back)
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
            panelMotion = npPanelMotion,
            panelMinContentWidth = npPanelMinContent,
        )

        // ── Navigation chrome: the Button Group in the window's form ──────
        // Bar forms slide the group fully off-screen BELOW the nav bar when
        // Now Playing rises: the group carries the nav-bar inset as internal
        // bottom padding, so a plain slide of `it` (its own height) would
        // leave it starting part-way up the screen. The edge capsules slide
        // off to the left instead. A form change (rotate, fold) crossfades.
        val navBarBottomPx = with(LocalDensity.current) {
            WindowInsets.navigationBars.getBottom(this)
        }
        // NOTE: a dock hand-off (shell → detail morph) deliberately does NOT
        // touch the bar. The detail window fades in with a group at the exact
        // same bounds/color, so the true crossfade happens between the two
        // windows; the real group stays put beneath and is simply there again
        // on return (including the predictive-back preview).
        // Cold-launch entrance: start hidden for one frame so the same
        // slide+fade that plays after a Now Playing dismiss also greets the
        // app open — the group rises in instead of just being there.
        var barEntered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { barEntered = true }
        // The column's top page publishes its Play actions; while the column
        // swaps pages the old entry retires before the new one publishes, so
        // the bar keeps the last actions for that gap instead of falling back
        // to the theme stand-in for a frame.
        val livePaneBarActions = paneStack.lastOrNull()?.let { paneRegistry[it] }
        val lastPaneBarActions = remember { arrayOfNulls<BarPlaySplitActions>(1) }
        SideEffect {
            when {
                livePaneBarActions != null -> lastPaneBarActions[0] = livePaneBarActions
                !paneHasEntries -> lastPaneBarActions[0] = null
            }
        }
        // While a new column's page is still building, the bar shows the
        // theme stand-in, never the last column's page.
        val paneBarActions = livePaneBarActions
            ?: lastPaneBarActions[0].takeIf { paneHasEntries && paneState.pageReady }
        Crossfade(
            targetState = chromeForm,
            animationSpec = YoinMotion.effectsSpring(),
            label = "shellChromeForm",
        ) { form ->
            val formIsEdge = form == ShellChromeForm.EdgeSplit
            // Edge capsules slide over their own edge — the cutout's.
            val edgeOut = if (edgeSplitSide == EdgeSplitSide.Right) 1 else -1
            OverlayChromeVisibility(
                // Beside the side panel the group stays: the content it
                // navigates is still usable (it folds to Home / Library).
                expanded = showNowPlaying && !npPanel.panelOpen,

                enabled = barEntered,
                enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard) + if (formIsEdge) {
                    YoinMotion.slideInHorizontally(role = YoinMotionRole.Standard) { edgeOut * it }
                } else {
                    YoinMotion.slideInVertically(role = YoinMotionRole.Standard) { it + navBarBottomPx }
                },
                exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard) + if (formIsEdge) {
                    YoinMotion.slideOutHorizontally(role = YoinMotionRole.Standard) { edgeOut * it }
                } else {
                    YoinMotion.slideOutVertically(role = YoinMotionRole.Standard) { it + navBarBottomPx }
                },
            ) {
                val bgAvScope = this
                // The mini player's progress is the ONLY shell consumer of the
                // 4Hz position tick. Derive it inside this chrome subtree so
                // ticks recompose just this block — and stop entirely while Now
                // Playing is open (this AnimatedVisibility content is disposed).
                // Held while a detail window covers the shell: the tick would
                // otherwise redraw the frozen shell 4 times a second, each draw
                // stalling the main thread the visible window shares on the
                // busy RenderThread. Catches up on the first uncovered frame.
                val windowCovered = LocalWindowCovered.current
                val playbackProgress by remember(playbackManager, windowCovered) {
                    playbackManager.playbackState
                        .map { state ->
                            if (state.duration > 0L) {
                                (state.position.toFloat() / state.duration).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                        }
                        .distinctUntilChanged()
                        .combine(snapshotFlow { windowCovered.value }) { progress, covered -> progress to covered }
                        .filter { (_, covered) -> !covered }
                        .map { (progress, _) -> progress }
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
                        .besideNowPlayingPanel(npPanel)
                        .graphicsLayer {
                            // Couple the group to the Memories reveal so it
                            // slides/fades out together with the open gesture
                            // instead of waiting for the surface flip — down
                            // for the bar forms, left for the edge capsules.
                            // With the detail column open the one bar carries
                            // the column page's Play split; Memories then covers
                            // only the shell column and must not take the bar.
                            val hide = (1f - memoriesReveal.fraction).coerceIn(0f, 1f) *
                                (1f - paneState.openFraction.value).coerceIn(0f, 1f)
                            alpha = (1f - hide * 1.4f).coerceAtLeast(0f)
                            if (formIsEdge) {
                                translationX = edgeOut * hide * 120.dp.toPx()
                            } else {
                                translationY = hide * 120.dp.toPx()
                            }
                        },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    YoinChromeGroup(
                        form = form,
                        wide = windowInfo.layoutMode == LayoutMode.Wide,
                        // The pill became the side panel: the bar keeps only
                        // the two destinations, centred in the content pane.
                        navOnly = npPanel.panelOpen,
                        // Detail column open: the one bar spans both columns —
                        // nav on the shell's side, the column's page Play on
                        // its side — on the column's own open/close spring.
                        paneProgress = { paneState.openFraction.value },
                        // Home edit mode: [Undo|Add] [Done] on P; clicks route
                        // on the discrete editing flag.
                        editPose = barEditPose,
                        editing = homeEditing,
                        playSplitActions = paneBarActions,
                        selectedSection = selectedSection,
                        // Single settle owner for the group pose: open/restore
                        // morphs AND the detail-back commit settle (seeded from
                        // the frozen scrub pose bridged through the store, so the
                        // dissolve above crossfades onto a matching group).
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
                            if (homeEdit.isEditing && !nowPlayingCoversHome(npFrame.presentation)) {
                                // The side panel leaves Home in view: animate out
                                // of edit, then open once P has settled.
                                shellScope.launch {
                                    homeEdit.commitAndExitAndAwait()
                                    experienceSessionStore.setNowPlayingExpanded(true)
                                }
                            } else {
                                homeEdit.snapExit()
                                experienceSessionStore.setNowPlayingExpanded(true)
                            }
                        },
                        onLibraryClick = {
                            libraryViewModel.showLibraryHome()
                            experienceSessionStore.setSelectedSection(YoinSection.LIBRARY)
                            experienceSessionStore.setHomeSurface(HomeSurface.Feed)
                        },
                        onLibraryLongClick = {
                            val scope = when (app.container.repository.currentProviderId()) {
                                MediaId.PROVIDER_SPOTIFY -> LibrarySearchScope.SpotifyGlobal
                                MediaId.PROVIDER_APPLE_MUSIC -> LibrarySearchScope.AppleMusicGlobal
                                else -> LibrarySearchScope.CurrentLibrary
                            }
                            libraryViewModel.openSearchShortcut(scope)
                            experienceSessionStore.setSelectedSection(YoinSection.LIBRARY)
                            experienceSessionStore.setHomeSurface(HomeSurface.Feed)
                        },
                        // Only the full-window phone column (and Tabletop) takes the
                        // mini-player → cover morph; the side panel, the enlarged
                        // phone and the two-column player drop it. Leaving the mini
                        // cover's shared element here would make it a no-peer
                        // shared element, which the SharedTransitionLayout
                        // lookahead measures with degenerate constraints — so the
                        // shell's side is disabled exactly where NP's is.
                        sharedTransitionScope = if (npSharesCover) {
                            sharedTransitionScope
                        } else {
                            null
                        },
                        animatedVisibilityScope = if (npSharesCover) {
                            bgAvScope
                        } else {
                            null
                        },
                    )
                }
            }
        }

        // ── Shell-level snackbar host ────────────────────────────────────
        // Anchored bottom, overlaid above Now Playing / bottom nav. Spotify
        // connect failures surface here with actionable labels.
        // Beside the Now Playing panel and clear of the bar (its reserve).
        Box(
            modifier = Modifier
                .matchParentSize()
                .besideNowPlayingPanel(npPanel),
            contentAlignment = Alignment.BottomCenter,
        ) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(
                    bottom = shellChromeInsets.calculateBottomPadding(),
                    start = 12.dp,
                    end = 12.dp,
                ),
            ) { data ->
                Snackbar(snackbarData = data)
            }
        }
    }
}

/**
 * Action label shown on the shell snackbar for a given Spotify connect
 * failure. Returns null when the failure has no user-actionable recovery
 * yet (UX will just show a dismiss affordance instead).
 */
private fun actionLabelForFailure(
    failure: SpotifyConnectFailure,
    context: Context,
): String? = when (failure) {
    SpotifyConnectFailure.NoClientId ->
        context.getString(R.string.shell_snackbar_open_settings_no_client_id)
    SpotifyConnectFailure.SpotifyAppMissing -> null // phase 3 wires Play Store intent
    SpotifyConnectFailure.PremiumRequired -> null
    is SpotifyConnectFailure.AuthFailure ->
        context.getString(R.string.shell_snackbar_open_settings_auth)
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

internal fun canLaunchDetailFromShell(
    lifecycleState: Lifecycle.State,
    backPhase: DetailBackPhase,
    launchPending: Boolean = false,
): Boolean = lifecycleState == Lifecycle.State.RESUMED &&
    backPhase == DetailBackPhase.Idle &&
    !launchPending

private const val DETAIL_LAUNCH_PAUSE_GRACE_MS = 500L

/** Debug-only `detail.click` for a page opened into the shell's detail column. */
private fun markDetailClick(route: DetailPaneRoute, via: String) {
    val kind = when (route) {
        is DetailPaneRoute.Album -> "album"
        is DetailPaneRoute.Artist -> "artist"
        is DetailPaneRoute.Playlist -> "playlist"
    }
    YoinPerf.detailClick(kind, route.entityId, via)
}

/** Debug-only `detail.click` for a Memories stamp's album (its full MediaId, as the page is opened with). */
private fun markMemoryAlbumClick(memory: MemoryEntry, via: String) {
    YoinPerf.detailClick("album", "${memory.entityProvider}:${memory.entityId}", via)
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun YoinNavHostPreview() {
    YoinTheme {
        YoinNavHost()
    }
}
