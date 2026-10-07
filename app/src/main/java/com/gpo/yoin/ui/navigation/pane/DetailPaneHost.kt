package com.gpo.yoin.ui.navigation.pane

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.VerticalDragHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.ui.component.BarPlaySplitActions
import com.gpo.yoin.ui.detail.DetailHostMode
import com.gpo.yoin.ui.detail.LocalDetailHostMode
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.YoinWindowInfo
import com.gpo.yoin.ui.experience.forPaneWidth
import com.gpo.yoin.ui.experience.voteHighFrameRate
import com.gpo.yoin.ui.navigation.back.BackMotionTokens
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotionSpeed
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/*
 * The detail COLUMN of a Wide window (adaptive principle 3): a Navigation 3
 * back stack of Album / Artist / Playlist entries beside the shell's own
 * content, in the shell's own composition — so the window's one bar spans
 * both columns and the Now Playing panel slides in beside both.
 *
 * Back class: the column is a destination stack INSIDE the shell window.
 * Stacked entries pop through NavDisplay's own predictive pop (a uniform
 * scale preview of the leaving page, the previous one waiting 96dp left —
 * the AOSP shape, see predictive-back skill); the LAST entry's back closes
 * the column (DetailPaneCloseHandler): the page scales toward 0.9 under the
 * finger, and on commit the column slides out while the shell widens on
 * the same spring. Both handlers live on a CHILD back dispatcher the shell
 * enables only while the column owns back (ShellBackResolver): handler
 * priority is registration order, and the column mounts after Now Playing,
 * so mount order alone would let a stacked page pop under the open player.
 */

/** The column's open/close spring and close-gesture preview, owned by the shell. */
@Stable
class DetailPaneState internal constructor() {
    /** 0 = closed (no column), 1 = open. The ONLY driver of the column's width. */
    internal val openFraction = Animatable(0f)

    /** Close-gesture preview progress (eased), read inside graphicsLayer only. */
    internal var closeProgress by mutableFloatStateOf(0f)

    /** True from the last entry's back commit until the column has left. */
    var closing by mutableStateOf(false)
        internal set

    /**
     * The shell lays out for a column: true from the open request until the
     * close spring has finished. The shell column's TIER follows this, never
     * the moving width — it re-tiers before the open spring starts (inside
     * [awaitPrewarm]) and after the close spring ends, so the heavy re-layout
     * of a whole section never lands mid-motion.
     */
    var holdsColumn by mutableStateOf(false)
        internal set

    /**
     * The column's page may compose. A column opening from nothing slides in
     * as an empty surface and builds its page once the open spring has
     * settled: a page's first composition, layout and raster cost several
     * frames, and spent inside the spring they make it skip.
     */
    var pageReady by mutableStateOf(false)
        internal set

    val isVisible: Boolean get() = openFraction.value > 0.001f
}

@Composable
fun rememberDetailPaneState(): DetailPaneState = remember { DetailPaneState() }

/**
 * The column's own ViewModelStore: its entries' view models live here and
 * end WITH the column. A column ends by clearing its stack while its
 * NavDisplay leaves composition, which never pops the entries — so without
 * this every page ever opened in a column would keep its ViewModel (and its
 * live collectors) for the rest of the session, and reopening a page would
 * reuse a stale one. Held in the shell's own store, so it survives a
 * configuration change.
 */
class DetailPaneViewModelStoreOwner : ViewModel(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    /** The column has ended: every page's view model goes with it. */
    fun endColumn() = viewModelStore.clear()

    override fun onCleared() = viewModelStore.clear()
}

/**
 * Column content: the entries' NavDisplay. Mount only while [backStack] is
 * non-empty (NavDisplay needs an entry); keep the last entry until the
 * column has fully closed so it stays on screen for the slide-out.
 */
@Composable
fun DetailPaneHost(
    backStack: NavBackStack<NavKey>,
    app: YoinApplication,
    isPlaying: Boolean,
    currentTrackId: String?,
    playbackSignal: Float,
    registry: PaneBarRegistry,
    onPopEntry: () -> Unit,
    onPush: (DetailPaneRoute) -> Unit,
    onMessage: (String) -> Unit,
    // The ▾ Add to playlist: the shell's sheet (Now Playing's), with these songs.
    onAddToPlaylist: (List<MediaId>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playback = PanePlaybackFacts(
        isPlaying = isPlaying,
        currentTrackId = currentTrackId,
        playbackSignal = playbackSignal,
    )
    val top = backStack.lastOrNull()
    val enterOffsetPx = with(LocalDensity.current) { BackMotionTokens.EnteringStartOffset.roundToPx() }

    CompositionLocalProvider(LocalDetailHostMode provides DetailHostMode.Pane) {
        NavDisplay(
            backStack = backStack,
            modifier = modifier,
            onBack = { onPopEntry() },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            // Push: the AOSP cross-activity open — the incoming page slides in
            // opaque from 96dp right on EMPHASIZED while the page beneath
            // recedes 96dp left (the same trajectory the window pages mirror).
            transitionSpec = {
                // A tap in the shell REPLACES the column's root (openPane:
                // clear + add) — a new selection, not a step forward — so it
                // must not ride the push (owner 2026-10-05, Fold: the old
                // album slid left past the column and lingered before the new
                // one opened). A push always lands on top of what was shown.
                val rootSwap = targetState.previousEntries.isEmpty() &&
                    initialState.entries.lastOrNull()?.contentKey != targetState.entries.lastOrNull()?.contentKey
                if (rootSwap) {
                    // Selection change = M3 fade-through: the old page
                    // leaves quickly where it is, the new one fades up from
                    // just under full size (never two pages' text at once).
                    (
                        YoinMotion.fadeIn(role = YoinMotionRole.Standard) +
                            YoinMotion.scaleIn(role = YoinMotionRole.Standard, initialScale = RootSwapInitialScale)
                        ) togetherWith YoinMotion.fadeOut(role = YoinMotionRole.Standard, speed = YoinMotionSpeed.Fast)
                } else {
                    YoinMotion.crossActivitySlideIn(enterOffsetPx) togetherWith
                        YoinMotion.crossActivitySlideOut(-enterOffsetPx)
                }
            },
            // Pop (button back): the leaving page shrinks and dissolves, the
            // previous one rides back in from its 96dp rest.
            popTransitionSpec = {
                YoinMotion.crossActivitySlideIn(-enterOffsetPx) togetherWith
                    YoinMotion.scaleOut(
                        role = YoinMotionRole.Standard,
                        targetScale = BackMotionTokens.PopPageScaleTarget,
                    ) + YoinMotion.fadeOut(role = YoinMotionRole.Standard)
            },
            // Predictive pop: the same shape, seeked by the finger — a uniform
            // scale of the complete page, never a layout scrub (invariant 3).
            predictivePopTransitionSpec = {
                YoinMotion.crossActivitySlideIn(-enterOffsetPx) togetherWith
                    YoinMotion.scaleOut(
                        role = YoinMotionRole.Standard,
                        targetScale = BackMotionTokens.PopPageScaleTarget,
                    ) + YoinMotion.fadeOut(role = YoinMotionRole.Standard)
            },
            entryProvider = entryProvider {
                entry<DetailPaneRoute.Album> { route ->
                    AlbumPaneEntry(
                        route = route,
                        app = app,
                        playback = playback,
                        registry = registry,
                        onBack = onPopEntry,
                        onOpenArtist = { id -> onPush(DetailPaneRoute.Artist(id)) },
                        onMessage = onMessage,
                        onAddToPlaylist = onAddToPlaylist,
                    )
                }
                entry<DetailPaneRoute.Artist> { route ->
                    ArtistPaneEntry(
                        route = route,
                        app = app,
                        playback = playback,
                        registry = registry,
                        isTop = route == top,
                        onBack = onPopEntry,
                        onOpenAlbum = { id -> onPush(DetailPaneRoute.Album(id)) },
                        onMessage = onMessage,
                    )
                }
                entry<DetailPaneRoute.Playlist> { route ->
                    PlaylistPaneEntry(
                        route = route,
                        app = app,
                        playback = playback,
                        registry = registry,
                        onBack = onPopEntry,
                        onMessage = onMessage,
                    )
                }
            },
        )
    }
}

/** A root swap's incoming page starts this close to full size (a selection change, not a push). */
private const val RootSwapInitialScale = 0.96f

/**
 * The last entry's back: closes the column. Pre-commit the page scales
 * toward the platform's 0.9 under the finger (1:1, eased like the window
 * pages); commit hands the close to the shell's open/close spring; cancel
 * springs the scale home. Button back (no gesture events) commits at once.
 */
@Composable
fun DetailPaneCloseHandler(
    state: DetailPaneState,
    enabled: Boolean,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val settleSpec = YoinMotion.predictiveBackSettleSpring<Float>()
    val preview = remember { Animatable(0f) }
    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { event ->
                // The finger owns the preview 1:1 (invariant 2).
                preview.snapTo(YoinMotion.backGestureEasing.transform(event.progress))
                state.closeProgress = preview.value
            }
            // Commit: the shell's spring takes the column out; the preview
            // releases on the same ride so the page is whole as it slides.
            onClose()
            scope.launch {
                preview.animateTo(0f, settleSpec) { state.closeProgress = value }
            }
        } catch (e: CancellationException) {
            scope.launch {
                preview.animateTo(0f, settleSpec) { state.closeProgress = value }
            }
            throw e
        }
    }
}

/** The close-gesture preview on the column's content: uniform scale + the window corner. */
fun Modifier.detailPaneClosePreview(state: DetailPaneState): Modifier = graphicsLayer {
    val p = state.closeProgress
    if (p <= 0f) return@graphicsLayer
    val scale = 1f - (1f - BackMotionTokens.PopPageScaleTarget) * p
    scaleX = scale
    scaleY = scale
    shape = RoundedCornerShape(BackMotionTokens.PopPageCornerRadius * p)
    clip = true
}

/**
 * The gutter between the columns with its M3 drag handle. The whole gutter
 * drags (the handle is 4dp wide — its hit area is the gutter); the handle
 * grows while held through the shared interaction source. Deltas resolve
 * against the region width the columns layout last measured.
 */
@Composable
fun DetailPaneDivider(
    split: PaneSplitState,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // Deltas are in logical start→end terms; RTL mirrors the finger.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val interaction = remember { MutableInteractionSource() }
    val dragState = rememberDraggableState { deltaPx ->
        split.dragBy(with(density) { deltaPx.toDp() }, split.lastRegionWidth)
    }
    Box(
        modifier = modifier
            .width(PaneGutterWidth)
            .fillMaxHeight()
            .voteHighFrameRate(split.dragging)
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                reverseDirection = rtl,
                interactionSource = interaction,
                onDragStarted = { split.dragging = true },
                onDragStopped = { split.dragging = false },
            ),
        contentAlignment = Alignment.Center,
    ) {
        VerticalDragHandle(interactionSource = interaction)
    }
}

/**
 * Opening from nothing: wait out the frames in which the shell re-tiers for
 * its narrower column (requested at the tap, [requestColumn]) — until
 * [PrewarmCalmFrames] consecutive frames arrive on time (capped at
 * [PrewarmMaxNanos]) — so the open spring's clock starts on a light frame
 * and never skips. The page itself composes after the spring ([pageReady]).
 */
suspend fun DetailPaneState.awaitPrewarm() {
    holdsColumn = true
    if (isVisible) return
    val start = withFrameNanos { it }
    var last = start
    var calm = 0
    while (calm < PrewarmCalmFrames && last - start < PrewarmMaxNanos) {
        val now = withFrameNanos { it }
        calm = if (now - last <= PrewarmCalmFrameNanos) calm + 1 else 0
        last = now
    }
}

private const val PrewarmCalmFrames = 2
private const val PrewarmCalmFrameNanos = 24_000_000L
private const val PrewarmMaxNanos = 900_000_000L

/** Grows the column open/closed on the app's spatial spring; an open that lands builds the page. */
suspend fun DetailPaneState.animateOpen(open: Boolean, spec: androidx.compose.animation.core.AnimationSpec<Float>) {
    openFraction.animateTo(if (open) 1f else 0f, spec)
    if (open) pageReady = true
}

/** Snap the column closed without motion (a window too narrow for columns, or the close spring done). */
suspend fun DetailPaneState.snapClosed() {
    openFraction.snapTo(0f)
    closeProgress = 0f
    closing = false
    holdsColumn = false
    pageReady = false
}

/** An open request (tap, restore): the shell starts laying out for a column at once. */
fun DetailPaneState.requestColumn() {
    holdsColumn = true
}

@Composable
fun rememberPaneSplitState(): PaneSplitState = remember { PaneSplitState() }

/** Column widths for this window (a stale dragged fraction is re-clamped here). */
fun PaneSplitState.budgetFor(windowWidth: Dp): PaneBudget =
    resolvePaneBudget(windowWidth, fractionFor(windowWidth))

/**
 * What each column reads as its window (adaptive principle 1 / 5): the
 * shell column's width with the column open (while [DetailPaneState.holdsColumn])
 * or the whole region, and the detail column's budget width. The open/close
 * spring never re-tiers anything mid-motion; the handle does, 1:1, because
 * the finger owns it. Derived, so a drag frame re-provides — and recomposes
 * the column's subtree — only when a column actually crosses a breakpoint.
 *
 * [regionWidth] is the columns region at rest: the window minus a settled
 * Now Playing panel. The panel's own spring and gesture travel move the
 * columns (layout phase) but never re-tier them mid-motion — the panel's
 * tier flips at the start, as [com.gpo.yoin.ui.nowplaying.ProvideBesidePanelWindowInfo]
 * does in the detail windows.
 */
@Stable
class ColumnWindowInfos internal constructor(
    val shell: State<YoinWindowInfo>,
    val pane: State<YoinWindowInfo>,
)

@Composable
fun rememberColumnWindowInfos(
    regionWidth: Dp,
    paneState: DetailPaneState,
    split: PaneSplitState,
): ColumnWindowInfos {
    val window = LocalYoinWindowInfo.current
    return remember(window, regionWidth, paneState, split) {
        ColumnWindowInfos(
            shell = derivedStateOf(structuralEqualityPolicy()) {
                val shellWidth = if (paneState.holdsColumn) split.budgetFor(regionWidth).shellWidth else regionWidth
                window.forPaneWidth(shellWidth.coerceAtLeast(0.dp))
            },
            pane = derivedStateOf(structuralEqualityPolicy()) {
                window.forPaneWidth(split.budgetFor(regionWidth).paneWidth)
            },
        )
    }
}

/**
 * The columns region of a Wide shell. [shell] takes what the detail column
 * leaves; [pane] (gutter + page) is laid out at its full budget width from
 * the shell column's end and clipped at the region's end, so it slides in
 * from — and back out past — the region's edge. Every width is resolved in
 * the measure pass from the open spring and the split fraction: a spring or
 * handle frame re-lays the region without recomposing it.
 */
@Composable
fun DetailColumnsLayout(
    paneState: DetailPaneState,
    split: PaneSplitState,
    modifier: Modifier = Modifier,
    shell: @Composable () -> Unit,
    pane: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(shell, pane),
        // Clip (drawing and touch) only while the column is placed: it runs
        // past the region's end then. Every other window — phones included —
        // keeps the shell's content unclipped, as before the column existed.
        modifier = modifier.graphicsLayer { clip = paneState.openFraction.value > 0f },
    ) { (shellMeasurables, paneMeasurables), constraints ->
        val regionPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        val regionWidth = regionPx.toDp()
        split.lastRegionWidth = regionWidth
        val budget = split.budgetFor(regionWidth)
        val paneFullPx = (budget.paneWidth + PaneGutterWidth).roundToPx().coerceAtMost(regionPx)
        val takePx = (paneFullPx * paneState.openFraction.value).roundToInt().coerceIn(0, regionPx)
        val shellPx = regionPx - takePx
        val shellPlaceables = shellMeasurables.map { it.measure(Constraints.fixed(shellPx, heightPx)) }
        val panePlaceables = if (takePx > 0) {
            paneMeasurables.map { it.measure(Constraints.fixed(paneFullPx, heightPx)) }
        } else {
            emptyList()
        }
        layout(regionPx, heightPx) {
            if (takePx > 0) {
                shellPlaceables.forEach { it.placeRelative(0, 0) }
                panePlaceables.forEach { it.placeRelative(shellPx, 0) }
            } else {
                shellPlaceables.forEach { it.placeRelative(0, 0) }
            }
        }
    }
}
