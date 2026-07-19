package com.gpo.yoin.ui.nowplaying

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.ui.component.AddToPlaylistSheet
import com.gpo.yoin.ui.component.DevicesSheet
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The complete Now Playing overlay — scrim, slide-up visibility, drag-to-
 * dismiss, stage reshape ownership, layered (predictive) back handling — as a
 * host-agnostic composable. The shell mounts it over the nav content; the
 * detail Activities mount it over their pages so the pill opens NP IN PLACE
 * and back returns to the page beneath (no shell relaunch, no home cameo).
 *
 * The host owns only the expanded flag ([expanded]/[onExpandedChange]) and
 * where its nav callbacks go; everything NP-internal (stage animatable, back
 * layering, per-tick readers) lives here. Back handlers are all gated on
 * [expanded], so a closed overlay never intercepts the host's native back.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NowPlayingOverlayHost(
    viewModel: NowPlayingViewModel,
    container: AppContainer,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onPlaylistClick: (String) -> Unit = {},
    sharedTransitionScope: SharedTransitionScope? = null,
) {
    val nowPlayingUiState by viewModel.uiState.collectAsState()
    val aboutUiState by viewModel.aboutUiState.collectAsState()
    val askState by viewModel.askState.collectAsState()
    val stageMode by viewModel.stageMode.collectAsState()
    val detailPage by viewModel.detailPage.collectAsState()
    val notesState by viewModel.notesState.collectAsState()
    val devicesState by viewModel.devicesState.collectAsState()
    val lyricsSearchState by viewModel.lyricsSearchState.collectAsState()
    val castState by container.castManager.castState.collectAsState()
    val layoutMode = LocalYoinWindowInfo.current.layoutMode

    var dismissDragPx by remember { mutableStateOf(0f) }
    var predictiveBackProgress by remember { mutableStateOf(0f) }
    // Expanded-collapse predictive back drives a uniform SCALE-down preview
    // (below) instead of scrubbing the layout reshape — a partial reshape
    // freezes a half-built, truncated stage; a uniform scale of the complete
    // layout cannot.
    var stageBackProgress by remember { mutableStateOf(0f) }
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
    // Expanded-collapse predictive-back PREVIEW scale: 1f → ~0.90f (the
    // platform's ~90% min back-scale) as the gesture progresses; animated so
    // the release settles smoothly back to 1f instead of snapping. Inert (1f)
    // when not gesturing.
    val stageBackScale by animateFloatAsState(
        targetValue = 1f - 0.10f * stageBackProgress,
        animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Standard),
        label = "stageBackScale",
    )

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

    // Wide has no Expanded substate (the right column is always expanded), so
    // collapse a stale Expanded to Compact when entering Wide. Writes ONLY
    // stageMode; the reconcile effect above stays the sole driver of the stage
    // Animatable (it re-runs on the stageMode change and settles detail → 0).
    LaunchedEffect(layoutMode, stageMode) {
        if (layoutMode == LayoutMode.Wide && stageMode == NowPlayingStageMode.Expanded) {
            viewModel.setStageMode(NowPlayingStageMode.Compact)
        }
    }

    val closeNowPlaying = {
        dismissDragPx = 0f
        predictiveBackProgress = 0f
        stageBackProgress = 0f
        viewModel.setStageMode(NowPlayingStageMode.Compact)
        onExpandedChange(false)
    }

    // Layered back priority: Expanded collapses in place first. Immersive is
    // a transient cover-focus variant of Compact, so it does not enter the
    // stage back chain; Back closes Now Playing just like Compact.
    BackHandler(
        enabled = expanded && stageMode == NowPlayingStageMode.Expanded &&
            layoutMode != LayoutMode.Wide,
    ) {
        viewModel.stepBackStage()
    }

    BackHandler(
        enabled = expanded &&
            (stageMode != NowPlayingStageMode.Expanded || layoutMode == LayoutMode.Wide),
        onBack = closeNowPlaying,
    )

    // Predictive-back drive for stage collapse (Expanded → Compact). Uniform
    // SCALE-DOWN preview, NOT a layout scrub: the finger peeks the WHOLE expanded
    // stage toward ~90% (the platform's min back-scale) while the layout stays
    // fully expanded (detail = 1, held there by the gesture-gated reconcile). A
    // partial layout reshape would freeze a half-built, truncated stage (the
    // collapsing cover row clips the square art); a uniform scale of the complete
    // layout cannot truncate. COMMIT runs the real detail 1→0 reshape and the
    // scale springs back to 1; CANCEL just springs the scale back, detail stays 1.
    PredictiveBackHandler(
        enabled = expanded && stageMode == NowPlayingStageMode.Expanded &&
            layoutMode != LayoutMode.Wide,
    ) { progress ->
        stageProgress.beginGesture()
        try {
            progress.collect { event ->
                val eased = YoinMotion.backGestureEasing.transform(event.progress)
                // Peek the whole stage; detail is NOT scrubbed (stays 1).
                stageBackProgress = eased
            }
            // COMMIT: run the real reshape (detail 1→0) via the reconcile; the
            // scale springs back to 1 (below) as the stage un-scales into Compact.
            viewModel.stepBackStage()
        } catch (e: CancellationException) {
            throw e
        } finally {
            stageProgress.endGesture()
            stageBackProgress = 0f
        }
    }

    // Predictive-back drive for the compact dismissal animation.
    PredictiveBackHandler(
        enabled = expanded &&
            (stageMode != NowPlayingStageMode.Expanded || layoutMode == LayoutMode.Wide),
    ) { progress ->
        try {
            progress.collectLatest { event ->
                predictiveBackProgress = event.progress
            }
            dismissDragPx = 0f
            onExpandedChange(false)
        } finally {
            predictiveBackProgress = 0f
        }
    }

    // ── Background scrim ─────────────────────────────────────────────────
    val scrimAlpha by animateFloatAsState(
        targetValue = if (expanded) 0.5f else 0f,
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

    // ── Now Playing overlay ──────────────────────────────────────────────
    AnimatedVisibility(
        visible = expanded,
        enter = YoinMotion.slideInVertically(role = YoinMotionRole.Expressive) { it } +
            YoinMotion.fadeIn(role = YoinMotionRole.Standard),
        exit = YoinMotion.slideOutVertically(role = YoinMotionRole.Standard) { it } +
            YoinMotion.fadeOut(role = YoinMotionRole.Standard),
        modifier = Modifier.fillMaxSize(),
    ) {
        val npAvScope = this
        // The 4Hz playhead is collected HERE (not in the host body) and
        // handed to the screen as reader lambdas, so only the leaves that
        // invoke them (progress bar, lyrics) recompose per tick.
        val nowPlayingPositionMs = viewModel.positionMs.collectAsState()
        val nowPlayingBufferedMs = viewModel.bufferedMs.collectAsState()
        val nowPlayingIsPlaying by viewModel.isPlayingLive.collectAsState()
        // The raw FFT stream updates 10–30Hz; NowPlayingScreen only needs
        // "is a spectrum present", so subscribe to that distinct Boolean
        // and keep the frames out of composition entirely.
        val hasAudioSpectrum by remember(container) {
            container.audioVisualizerManager.visualizerData
                .map { it.fft.isNotEmpty() }
                .distinctUntilChanged()
        }.collectAsState(
            initial = container.audioVisualizerManager
                .visualizerData.value.fft.isNotEmpty(),
        )
        val draggableState = rememberDraggableState { delta ->
            if (delta > 0f || dismissDragPx > 0f) {
                dismissDragPx = (dismissDragPx + delta).coerceAtLeast(0f)
            }
        }
        // Cast lives in the devices sheet (the Chromecast rows carry the cast
        // status), so the Cast pill opens the same sheet the Devices pill
        // does. That pill's open flag is private to NowPlayingScreen, so the
        // host mounts its own instance of the shared sheet over the overlay,
        // bound to the same devicesState / refresh / select flow.
        var showCastDevicesSheet by remember { mutableStateOf(false) }

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
                    enabled = stageMode != NowPlayingStageMode.Expanded &&
                        layoutMode != LayoutMode.Wide,
                    onDragStopped = { velocity ->
                        if (dismissDragPx > 240f || velocity > 800f) {
                            dismissDragPx = 0f
                            predictiveBackProgress = 0f
                            onExpandedChange(false)
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
                // Playing.isPlaying is overridden with the EAGER projection:
                // uiState's combine can serve a stale cached snapshot after a
                // cold resubscribe (see isPlayingLive) and the wave bar must
                // never disagree with the ticking playhead.
                uiState = when (val s = nowPlayingUiState) {
                    is NowPlayingUiState.Playing ->
                        if (s.isPlaying == nowPlayingIsPlaying) s
                        else s.copy(isPlaying = nowPlayingIsPlaying)
                    else -> s
                },
                // Mirrors the dismissFraction pattern below: reader lambdas
                // over collected State, invoked only at the consuming leaves.
                positionMs = { nowPlayingPositionMs.value },
                bufferedMs = { nowPlayingBufferedMs.value },
                hasAudioSpectrum = hasAudioSpectrum,
                onTogglePlayPause = viewModel::togglePlayPause,
                onSkipNext = viewModel::skipNext,
                onSkipPrevious = viewModel::skipPrevious,
                onSeek = viewModel::seekTo,
                onSeekToMs = viewModel::seekToMs,
                lyricsSearchState = lyricsSearchState,
                onOpenLyricsSearch = viewModel::openLyricsSearch,
                onLyricsSearchQueryChange = viewModel::updateLyricsSearchQuery,
                onSearchLyrics = viewModel::searchLyrics,
                onApplyLyricsSearchResult = viewModel::applyLyricsSearchResult,
                onDismissLyricsSearch = viewModel::dismissLyricsSearch,
                onTranslateLyrics = viewModel::translateLyrics,
                onApplyLyrics = viewModel::applyLyrics,
                onRatingChange = viewModel::setRating,
                onToggleFavorite = viewModel::toggleFavorite,
                onAddCurrentToPlaylist = viewModel::requestAddCurrentToPlaylist,
                onSkipToQueueItem = viewModel::skipToQueueItem,
                onToggleShuffle = viewModel::toggleShuffle,
                onAlbumClick = onAlbumClick,
                onArtistClick = onArtistClick,
                onPlaylistClick = onPlaylistClick,
                onDismiss = closeNowPlaying,
                dismissFraction = {
                    val dragProgress = (dismissDragPx / 240f).coerceIn(0f, 1f)
                    maxOf(dragProgress, predictiveBackProgress).coerceIn(0f, 1f)
                },
                aboutUiState = aboutUiState,
                onRetryFetchSongInfo = viewModel::retryFetchSongInfo,
                askState = askState,
                onAboutOpened = viewModel::onAboutOpened,
                onAskQuestion = viewModel::askQuestion,
                onAskBarFocused = viewModel::onAskBarFocused,
                onAskBarCollapseRequested = viewModel::onAskBarCollapseRequested,
                onDismissAskError = viewModel::dismissAskError,
                stageMode = stageMode,
                stageProgress = stageProgress,
                detailPage = detailPage,
                onStageModeChange = viewModel::setStageMode,
                onStageBack = viewModel::stepBackStage,
                onDetailPageChange = viewModel::setDetailPage,
                notesState = notesState,
                onSaveNote = viewModel::saveCurrentNote,
                onDeleteNote = viewModel::deleteNote,
                devicesState = devicesState,
                onRefreshDevices = viewModel::refreshDevices,
                onSelectDevice = viewModel::selectDevice,
                castState = castState,
                onCastClick = { showCastDevicesSheet = true },
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = npAvScope,
                // Collapse PREVIEW recedes the CONTENT (inside NowPlayingScreen,
                // over the full-screen aurora) — NOT the whole overlay, which
                // would reveal the host behind and read as the app shrinking.
                contentScale = stageBackScale,
                modifier = Modifier
                    .fillMaxSize()
                    .offset {
                        IntOffset(
                            x = 0,
                            y = (overlayOffsetPx + dismissDragPx).roundToInt(),
                        )
                    },
            )

            if (showCastDevicesSheet) {
                // Pixel-twin of NowPlayingScreen's own Devices-pill mount:
                // same component, same motion role, same callbacks.
                ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
                    DevicesSheet(
                        providerId = devicesState.providerId,
                        devices = devicesState.devices,
                        loading = devicesState.loading,
                        busyDeviceId = devicesState.busyDeviceId,
                        errorMessage = devicesState.errorMessage,
                        onRefresh = viewModel::refreshDevices,
                        onSelect = viewModel::selectDevice,
                        onDismiss = { showCastDevicesSheet = false },
                    )
                }
            }
        }
    }
}

/**
 * The Add-to-Playlist sheet + its snackbar, bound to a [NowPlayingViewModel].
 * Hoisted separately from the overlay because the sheet also serves non-NP
 * entry points (Library rows); every window that mounts
 * [NowPlayingOverlayHost] should mount this beside it, last in its root Box.
 */
@Composable
fun BoxScope.NowPlayingAccessories(
    viewModel: NowPlayingViewModel,
    container: AppContainer,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.addToPlaylistMessages.collect { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short,
            )
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.lyricsTranslationSwitchOffers.collect { offer ->
            val result = snackbarHostState.showSnackbar(
                message = "${offer.providerName} translation is available",
                actionLabel = "Switch",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.applyLyricsTranslationSwitchOffer()
            }
        }
    }

    val addTargets by viewModel.addToPlaylistTarget.collectAsState()
    if (addTargets != null) {
        val writablePlaylists by viewModel.writablePlaylists.collectAsState()
        // Null out the create callback when the active source can't
        // actually create playlists — the sheet drops the row entirely
        // rather than showing an action that will fail downstream.
        val canCreate = Capability.PLAYLISTS_WRITE in
            container.repository.currentCapabilities()
        AddToPlaylistSheet(
            writablePlaylists = writablePlaylists,
            onCreateAndAdd = viewModel::createPlaylistAndAddTargets
                .takeIf { canCreate },
            onAddToExisting = viewModel::addTargetsToExistingPlaylist,
            onDismiss = viewModel::dismissAddToPlaylistSheet,
        )
    }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 24.dp, start = 12.dp, end = 12.dp),
    ) { data ->
        Snackbar(snackbarData = data)
    }
}
