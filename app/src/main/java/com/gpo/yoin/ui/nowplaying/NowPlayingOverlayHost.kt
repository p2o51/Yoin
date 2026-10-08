package com.gpo.yoin.ui.nowplaying

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.gpo.yoin.AppContainer
import com.gpo.yoin.R
import com.gpo.yoin.data.model.YoinDevice
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.common.asString
import com.gpo.yoin.ui.component.AddToPlaylistSheet
import com.gpo.yoin.ui.component.DevicesSheet
import com.gpo.yoin.ui.component.QueueEditActions
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.LocalShellChromeInsets
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.navigation.back.OverlayPlayerVisibility
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
    // Where the panel's live travel is published for the content beside it
    // (see NowPlayingPanelMotion / besideNowPlayingPanel).
    panelMotion: NowPlayingPanelMotion? = null,
    // The floor the host keeps beside a side panel (two columns while the
    // Wide shell shows its detail column) — the same value the host passes
    // to its own rememberNowPlayingFrame.
    panelMinContentWidth: Dp = NowPlayingPanelMinContentWidth,
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
    val skipDirection by viewModel.skipDirection.collectAsState()

    // The frame this window gives Now Playing (断点交接 §3.4 / §14.1) — the
    // same resolution the host content beside a side panel reads.
    val liveFrame = rememberNowPlayingFrame(viewModel, panelMinContentWidth)
    // The player leaves in the frame it was last SHOWN in: the shell can raise
    // the panel floor (a detail column opening) in the same event that closes
    // it, which would otherwise re-resolve the closing panel into the
    // full-window two-column player for its whole exit.
    val shownFrame = remember { arrayOfNulls<NowPlayingFrame>(1) }
    if (expanded) shownFrame[0] = liveFrame
    val frame = if (expanded) liveFrame else shownFrame[0] ?: liveFrame
    val presentation = frame.presentation
    val dualPaneNowPlaying = presentation == NowPlayingPresentation.DualPane
    val panelMode = presentation == NowPlayingPresentation.Panel
    // Medium or Wide full window: panel ⇄ Full (enlarged phone / two columns)
    // share one container that slides in from the right; everything else
    // rises from the bottom.
    val panelFamily = frame.panelAvailable &&
        (panelMode || presentation == NowPlayingPresentation.Enlarged || dualPaneNowPlaying)

    // Size switches while open: unfolding a phone lands in the Full state,
    // Medium ⇄ Wide keeps the user's choice, any other arrival starts as the
    // panel; a fresh open is always the panel. The flag lives in the
    // ViewModel, so a recreated detail Activity keeps the user's choice.
    val windowInfo = LocalYoinWindowInfo.current
    val layoutMode = windowInfo.layoutMode
    // Height first (adaptive principle 1): a short window is a handset whatever
    // its width — so unfolding from a landscape outer screen also counts as
    // growing out of Compact. Saveable: the detail Activities are recreated on
    // fold / unfold, and the rule needs the size class before it.
    val npSizeClass = if (!windowInfo.isHeightAtLeastMedium && layoutMode != LayoutMode.Tabletop) {
        LayoutMode.Compact
    } else {
        layoutMode
    }
    var lastLayoutMode by rememberSaveable { mutableStateOf(npSizeClass) }
    LaunchedEffect(npSizeClass) {
        val previous = lastLayoutMode
        lastLayoutMode = npSizeClass
        if (previous != npSizeClass) {
            viewModel.setMediumFullscreen(
                fullscreenAfterLayoutChange(
                    previous = previous,
                    current = npSizeClass,
                    expanded = expanded,
                    wasFullscreen = viewModel.mediumFullscreen.value,
                ),
            )
        }
    }
    var wasExpanded by rememberSaveable { mutableStateOf(expanded) }
    val expandedNow by rememberUpdatedState(expanded)
    var dismissDragPx by remember { mutableStateOf(0f) }
    var predictiveBackProgress by remember { mutableStateOf(0f) }
    // Expanded-collapse (and enlarged → panel) predictive back drives a
    // uniform SCALE-down preview (below) instead of scrubbing a layout
    // reshape — a partial reshape freezes a half-built, truncated stage; a
    // uniform scale of the complete layout cannot.
    // The stage's back preview pose: snapped from the finger on every back
    // event, sprung home only on release (StageBackPreview).
    val stageBack = remember { StageBackPreview() }
    val stageBackScope = rememberCoroutineScope()
    val stageProgress = rememberNowPlayingStageProgress(initialMode = stageMode)
    val dragResetSpec = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Standard)
    // A fresh open starts as the panel. (The Full flag is also reset once a
    // closed player's exit has finished — see the content's DisposableEffect —
    // so a re-open never resolves Full for its first frames.) A re-open
    // during the exit takes the dismiss pose back on the drag-reset spring.
    LaunchedEffect(expanded) {
        if (expanded && !wasExpanded) viewModel.setMediumFullscreen(false)
        wasExpanded = expanded
        if (expanded) {
            predictiveBackProgress = 0f
            if (dismissDragPx != 0f) {
                animate(dismissDragPx, 0f, animationSpec = dragResetSpec) { value, _ -> dismissDragPx = value }
            }
        }
    }
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
    // The release spring that takes the preview home (commit or cancel). The
    // gesture itself snaps — the old chase (a spring restarted on every back
    // event) kept the stage trailing the finger: "不跟手" (owner, 2026-10-07).
    val stageBackSpec = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Standard)
    fun releaseStageBack() {
        stageBackScope.launch { stageBack.progress.animateTo(0f, stageBackSpec) }
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

    // Dual-pane NP has no Expanded substate (the right column is always
    // expanded), so collapse a stale Expanded to Compact when entering the
    // two-column player (Wide + tall only). Writes ONLY stageMode; the
    // reconcile effect above stays the sole driver of the stage Animatable.
    LaunchedEffect(dualPaneNowPlaying, stageMode) {
        if (dualPaneNowPlaying && stageMode == NowPlayingStageMode.Expanded) {
            viewModel.setStageMode(NowPlayingStageMode.Compact)
        }
    }

    val closeNowPlaying = {
        dismissDragPx = 0f
        predictiveBackProgress = 0f
        stageBackScope.launch { stageBack.progress.snapTo(0f) }
        viewModel.setStageMode(NowPlayingStageMode.Compact)
        onExpandedChange(false)
    }

    // Layered back, one level at a time (断点交接 §3.4): the Expanded stage
    // collapses in place first (single-column only — Immersive is a transient
    // cover-focus variant of Compact and never enters the chain); the Full
    // player (enlarged phone / two columns) steps back to its side panel; then
    // Now Playing closes. The three levels' `enabled` flags are mutually
    // exclusive, so a closed overlay — or a level that doesn't exist here —
    // never swallows the host's back.
    val stageBackLevel = expanded && stageMode == NowPlayingStageMode.Expanded && !dualPaneNowPlaying
    val fullscreenBackLevel = expanded && !stageBackLevel &&
        (presentation == NowPlayingPresentation.Enlarged || dualPaneNowPlaying) &&
        frame.panelAvailable
    val closeBackLevel = expanded && !stageBackLevel && !fullscreenBackLevel

    BackHandler(enabled = stageBackLevel) {
        viewModel.stepBackStage()
    }
    BackHandler(enabled = fullscreenBackLevel) {
        viewModel.setMediumFullscreen(false)
    }
    BackHandler(enabled = closeBackLevel, onBack = closeNowPlaying)

    // Predictive-back drive for stage collapse (Expanded → Compact). Uniform
    // SCALE-DOWN preview, NOT a layout scrub: the finger peeks the WHOLE expanded
    // stage toward ~90% (the platform's min back-scale) while the layout stays
    // fully expanded (detail = 1, held there by the gesture-gated reconcile).
    // COMMIT runs the real detail 1→0 reshape and the scale springs back to 1;
    // CANCEL just springs the scale back, detail stays 1.
    PredictiveBackHandler(enabled = stageBackLevel) { progress ->
        stageProgress.beginGesture()
        try {
            var startY = Float.NaN
            progress.collect { event ->
                // Peek the whole stage; detail is NOT scrubbed (stays 1). The
                // pose follows the finger directly (snap), AOSP-style.
                if (startY.isNaN()) startY = event.touchY
                stageBack.swipeEdge = event.swipeEdge
                stageBack.touchYDelta = event.touchY - startY
                stageBack.progress.snapTo(YoinMotion.backGestureEasing.transform(event.progress))
            }
            // COMMIT: run the real reshape (detail 1→0) via the reconcile; the
            // scale springs back to 1 (below) as the stage un-scales into Compact.
            viewModel.stepBackStage()
        } catch (e: CancellationException) {
            throw e
        } finally {
            stageProgress.endGesture()
            releaseStageBack()
        }
    }

    // Full → panel (enlarged phone or two columns): the same uniform scale
    // preview of the complete stage; the container's width change runs only
    // on commit, on its own spring.
    PredictiveBackHandler(enabled = fullscreenBackLevel) { progress ->
        try {
            var startY = Float.NaN
            progress.collect { event ->
                if (startY.isNaN()) startY = event.touchY
                stageBack.swipeEdge = event.swipeEdge
                stageBack.touchYDelta = event.touchY - startY
                stageBack.progress.snapTo(YoinMotion.backGestureEasing.transform(event.progress))
            }
            viewModel.setMediumFullscreen(false)
        } catch (e: CancellationException) {
            throw e
        } finally {
            releaseStageBack()
        }
    }

    // Predictive-back drive for the dismissal: slides down (right, for the
    // side panel) on the same channel as the drag-to-dismiss.
    PredictiveBackHandler(enabled = closeBackLevel) { progress ->
        try {
            progress.collectLatest { event ->
                predictiveBackProgress = event.progress
            }
            // Commit: the exit continues from the previewed pose — the panel
            // (and the content following it) never jumps back first. The pose
            // resets once the player is gone (the content's DisposableEffect).
            onExpandedChange(false)
        } catch (e: CancellationException) {
            predictiveBackProgress = 0f
            throw e
        }
    }

    // The panel's horizontal travel — back preview + drag — handed to the
    // content beside it as a READER, installed once per presentation: it is
    // called only in that content's layout pass, so a drag or back frame
    // re-lays the column without recomposing this host or the shell, and the
    // two edges move in the same frame (no bare window background between
    // them). Zero whenever the panel is not the presentation.
    val panelTravelReader: () -> Float = remember(panelMode) {
        if (panelMode) {
            { overlayOffsetPx * PanelBackTravelFraction + dismissDragPx }
        } else {
            { 0f }
        }
    }
    DisposableEffect(panelMotion, panelTravelReader) {
        panelMotion?.travelReader = panelTravelReader
        onDispose { panelMotion?.travelReader = { 0f } }
    }

    // ── Background scrim ─────────────────────────────────────────────────
    // None beside the side panel: the host content stays usable next to it.
    val scrimAlpha by animateFloatAsState(
        targetValue = if (expanded && !panelMode) 0.5f else 0f,
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

    // Panel (0) ⇄ Full (1) — enlarged phone or two columns: one container,
    // one spatial spring.
    val fullFraction by animateFloatAsState(
        targetValue = if (panelMode) 0f else 1f,
        animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Standard),
        label = "nowPlayingPanelFull",
    )
    // Panel ⇄ two columns (Wide): the body swaps at the container spring's
    // midpoint, so the two-column player is never laid out at panel width
    // (a sliver of a cover column) and the phone column never stretches to
    // the window. Medium's panel ⇄ enlarged phone keeps one body throughout.
    val containerPastMidpoint by remember { derivedStateOf { fullFraction > 0.5f } }
    // Medium Full → panel: the enlarged phone shrinks WITH the container and
    // hands over to the panel body at rest (its spec already lerps with
    // fullFraction); swapping at the start popped the cover 520 → 312dp.
    val containerLeavingFull by remember { derivedStateOf { fullFraction > 0f } }
    val bodyPresentation = when {
        layoutMode == LayoutMode.Wide && panelFamily && (panelMode || dualPaneNowPlaying) ->
            if (containerPastMidpoint) NowPlayingPresentation.DualPane else NowPlayingPresentation.Panel
        layoutMode == LayoutMode.Medium && panelMode && containerLeavingFull ->
            NowPlayingPresentation.Enlarged
        else -> presentation
    }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val panelTravelPx = with(LocalDensity.current) { frame.panelWidth.roundToPx() }

    // ── Now Playing overlay ──────────────────────────────────────────────
    OverlayPlayerVisibility(
        expanded = expanded,
        fromEnd = panelFamily,
        // The panel travels exactly its own width (the content beside it moves
        // that far on the same spring); the full-window states the window.
        endTravel = { full -> if (panelMode) panelTravelPx else full },
        modifier = Modifier.fillMaxSize(),
    ) {
        val npAvScope = this
        // The player is gone (its exit finished): clear the dismiss pose and
        // the Full flag, so the next open starts as the panel from frame one.
        DisposableEffect(Unit) {
            onDispose {
                dismissDragPx = 0f
                predictiveBackProgress = 0f
                if (!expandedNow) viewModel.setMediumFullscreen(false)
            }
        }
        // The 4Hz playhead is collected HERE (not in the host body) and
        // handed to the screen as reader lambdas, so only the leaves that
        // invoke them (progress bar, lyrics) recompose per tick.
        // Tagged with its song: at a song change the playhead moves on a few
        // dispatches before uiState does, and the screen must keep reading the
        // song it is still drawing (see SongScopedPosition).
        val nowPlayingPlayhead = viewModel.playhead.collectAsState()
        val renderedSongId = rememberUpdatedState(
            (nowPlayingUiState as? NowPlayingUiState.Playing)?.songId,
        )
        val songScopedPosition = remember { SongScopedPosition() }
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
            // Read inside remember so the StateFlow is not touched from
            // composition; this only seeds the first frame.
            initial = remember(container) {
                container.audioVisualizerManager.visualizerData.value.fft.isNotEmpty()
            },
        )
        // ONE dismiss controller: drag and predictive back both feed
        // dismissFraction. Down on the phone / enlarged phone, rightward on
        // the side panel ("swipe right to close", §3.4).
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

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val containerWidth = if (panelFamily) {
                frame.panelWidth + (maxWidth - frame.panelWidth) * fullFraction
            } else {
                maxWidth
            }
            val panelCorner = if (panelFamily) PanelCornerRadius * (1f - fullFraction) else 0.dp
            val panelShape = RoundedCornerShape(topStart = panelCorner, bottomStart = panelCorner)
            val panelShadowPx = with(density) { PanelShadowElevation.toPx() }
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(containerWidth)
                    .fillMaxHeight()
                    // Panel: back / drag carry the WHOLE container right —
                    // corners, clip and shadow with it — so the content beside
                    // it meets a real panel edge.
                    .offset {
                        if (panelMode) {
                            IntOffset(
                                x = (overlayOffsetPx * PanelBackTravelFraction + dismissDragPx).roundToInt(),
                                y = 0,
                            )
                        } else {
                            IntOffset.Zero
                        }
                    }
                    .graphicsLayer {
                        if (panelFamily) {
                            shape = panelShape
                            clip = true
                            shadowElevation = panelShadowPx * (1f - fullFraction)
                        }
                    }
                    .draggable(
                        state = draggableState,
                        orientation = if (panelMode) Orientation.Horizontal else Orientation.Vertical,
                        // Drag-to-dismiss is a single-column affordance. In
                        // Expanded/Immersive panes have their own vertical
                        // scroll / IME interactions; letting draggable eat
                        // those deltas is what causes Lyrics scroll to fight
                        // dismiss. The two-column player has no bar beneath
                        // and closes via explicit affordances (its top bar +
                        // system back), so the drag is gated off there.
                        enabled = stageMode != NowPlayingStageMode.Expanded &&
                            !dualPaneNowPlaying,
                        // RTL mirrors the panel's end edge (vertical drags never reverse).
                        reverseDirection = panelMode && isRtl,
                        onDragStopped = { velocity ->
                            if (dismissDragPx > 240f || velocity > 800f) {
                                // The exit continues from the dragged pose.
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
                    positionMs = {
                        songScopedPosition.resolve(nowPlayingPlayhead.value, renderedSongId.value)
                    },
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
                    onLyricsMessage = viewModel::showMessage,
                    onRatingChange = viewModel::setRating,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onAddCurrentToLibrary = viewModel::addCurrentToLibrary,
                    onAddCurrentToPlaylist = viewModel::requestAddCurrentToPlaylist,
                    onSkipToQueueItem = viewModel::skipToQueueItem,
                    queueEditor = remember(viewModel) {
                        QueueEditActions(
                            onMove = viewModel::moveQueueItem,
                            onRemove = viewModel::removeQueueItem,
                            onClearQueued = viewModel::clearUserQueue,
                        )
                    },
                    onCyclePlayMode = viewModel::cyclePlayMode,
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
                    noteDraft = viewModel.noteDraft,
                    onSaveNote = viewModel::saveCurrentNote,
                    onDeleteNote = viewModel::deleteNote,
                    onRealignNote = viewModel::realignNote,
                    devicesState = devicesState,
                    onRefreshDevices = viewModel::refreshDevices,
                    onSelectDevice = viewModel::selectDevice,
                    castState = castState,
                    onCastClick = { showCastDevicesSheet = true },
                    // The pill → cover morph only where the phone column fills
                    // the window; panel / enlarged / two-column bodies drop it
                    // (bounded, lookahead-safe, and the pill folds away there).
                    sharedTransitionScope = sharedTransitionScope.takeIf {
                        presentation == NowPlayingPresentation.Phone ||
                            presentation == NowPlayingPresentation.Tabletop
                    },
                    animatedVisibilityScope = npAvScope,
                    // Collapse PREVIEW recedes the CONTENT (inside NowPlayingScreen,
                    // over the full-screen aurora) — NOT the whole overlay, which
                    // would reveal the host behind and read as the app shrinking.
                    backPreview = stageBack,
                    skipDirection = skipDirection,
                    presentation = bodyPresentation,
                    // Built only where it is used (the enlarged phone): on Wide
                    // a per-frame spec would recompose the player all spring long.
                    enlarged = if (bodyPresentation != NowPlayingPresentation.Enlarged) null else nowPlayingEnlargedSpec(frame.windowWidth).let { spec ->
                        // Grows in with the container, so the panel → full
                        // screen change never pops the rating column.
                        spec.copy(
                            ratingColumn = 56.dp + (spec.ratingColumn - 56.dp) * fullFraction,
                            controlSize = if (fullFraction > 0.5f) spec.controlSize else 56.dp,
                        )
                    },
                    topBarAction = if (panelFamily) {
                        {
                            PanelToggleButton(
                                fullscreen = !panelMode,
                                onClick = { viewModel.setMediumFullscreen(panelMode) },
                            )
                        }
                    } else {
                        null
                    },
                    onClaimLyricIdleHint = { viewModel.claimLyricIdleHint() },
                    modifier = Modifier
                        .fillMaxSize()
                        .offset {
                            // Phone / enlarged: back / drag carry the player
                            // down; the panel's travel is on its container.
                            if (panelMode) {
                                IntOffset.Zero
                            } else {
                                IntOffset(
                                    x = 0,
                                    y = (overlayOffsetPx + dismissDragPx).roundToInt(),
                                )
                            }
                        },
                )

                if (showCastDevicesSheet) {
                    // Pixel-twin of NowPlayingScreen's own Devices-pill mount:
                    // same component, same motion role, same callbacks.
                    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
                        DevicesSheet(
                            providerId = devicesState.providerId,
                            devices = devicesState.devices.localizedForSheet(),
                            loading = devicesState.loading,
                            busyDeviceId = devicesState.busyDeviceId,
                            errorMessage = devicesState.errorMessage?.asString(),
                            onRefresh = viewModel::refreshDevices,
                            onSelect = viewModel::selectDevice,
                            onDismiss = { showCastDevicesSheet = false },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The panel's corner button: full screen (the enlarged phone on Medium, the
 * two columns on Wide), or back to the side panel.
 */
@Composable
private fun PanelToggleButton(
    fullscreen: Boolean,
    onClick: () -> Unit,
) {
    val haptics = rememberYoinHaptics()
    FilledTonalIconButton(
        onClick = {
            haptics.performClick()
            onClick()
        },
        modifier = Modifier.size(44.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Icon(
            imageVector = if (fullscreen) YoinSymbols.CloseFullscreen else YoinSymbols.OpenInFull,
            contentDescription = if (fullscreen) {
                stringResource(R.string.np_cd_back_to_panel)
            } else {
                stringResource(R.string.np_cd_full_screen)
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Left corners of the side panel (FoldNPPanel). */
private val PanelCornerRadius = 28.dp
private val PanelShadowElevation = 16.dp

/** The panel's back preview travels a third of the phone's 1200px chase. */
private const val PanelBackTravelFraction = 0.35f

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
    val context = LocalContext.current

    LaunchedEffect(viewModel, context) {
        viewModel.addToPlaylistMessages.collect { message ->
            snackbarHostState.showSnackbar(
                message = message.asString(context),
                duration = SnackbarDuration.Short,
            )
        }
    }

    LaunchedEffect(viewModel, context) {
        viewModel.lyricsTranslationSwitchOffers.collect { offer ->
            val result = snackbarHostState.showSnackbar(
                message = translationOfferMessage(context, offer.providerName),
                actionLabel = context.getString(R.string.np_msg_switch),
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
            // Clear of the window's bar (its reserve), never over it.
            .padding(bottom = LocalShellChromeInsets.current.calculateBottomPadding(), start = 12.dp, end = 12.dp),
    ) { data ->
        Snackbar(snackbarData = data)
    }
}

@Composable
internal fun List<YoinDevice>.localizedForSheet(): List<YoinDevice> {
    val switchBack = stringResource(R.string.np_device_switch_back_cast)
    val connected = stringResource(R.string.np_device_connected_cast)
    val usePill = stringResource(R.string.np_device_use_cast_pill)
    return map { device ->
        val status = when (device.statusText) {
            NpDeviceStatusSwitchBack -> switchBack
            NpDeviceStatusConnected -> connected
            NpDeviceStatusUsePill -> usePill
            else -> return@map device
        }
        when (device) {
            is YoinDevice.LocalPlayback -> device.copy(statusText = status)
            is YoinDevice.Chromecast -> device.copy(statusText = status)
            is YoinDevice.SpotifyConnect -> device.copy(statusText = status)
        }
    }
}

private fun translationOfferMessage(context: Context, providerName: String): String {
    val resources = context.resources
    return when (providerName) {
        "qq" -> resources.getString(R.string.np_msg_translation_qq)
        "netease" -> resources.getString(R.string.np_msg_translation_netease)
        "huawei" -> resources.getString(R.string.np_msg_translation_huawei)
        "lrclib" -> resources.getString(R.string.np_msg_translation_lrclib)
        else -> resources.getString(
            R.string.np_msg_translation_available,
            providerName.toLyricsProviderLabel(resources),
        )
    }
}
