package com.gpo.yoin.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.withTabularFigures
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * One run of a fast-scrolled list. [label] is what the bubble says while
 * the handle is over the run; [startIndex] is its first item. Adjacent
 * sections with the same [tickLabel] share one tick — a timeline's months
 * under their year.
 */
@Immutable
data class FastScrollSection(
    val label: String,
    val startIndex: Int,
    val tickLabel: String = label
)

/**
 * The Library fast scroller (U2 / D2): a small handle at the end edge of
 * its bounds that slides in while the list scrolls (content over three
 * screens only) and fades out once the list has been still for a moment.
 * Hold it and a column of ticks (letters, years) unfolds beside it with a
 * bubble naming the current section; the handle follows the finger 1:1, no
 * inertia, and the list jumps once per frame at most. Release folds the
 * ticks and bubble away.
 *
 * Not a back surface: it never consumes back. It excludes system gestures
 * only over the handle, and only while the handle is visible, so a hidden
 * scroller leaves the edge back gesture untouched (RootSection rule).
 *
 * Give it the track's bounds (e.g. `matchParentSize()` over the list, inset
 * to the list's content padding); it draws at their end edge and passes
 * every touch outside the handle through. Uniform, single-span items only
 * (a vertical list or grid); [itemsPerLine] is the grid's column count.
 *
 * Attach it once the list is fully loaded (the handle's place and the ticks
 * are proportions of the whole list). A list that still changes length
 * anyway (a refresh) never moves the target under a held finger: a drag
 * measures in the length it was grabbed at, and the handle it leaves behind
 * glides to the list's new place.
 *
 * @param firstVisibleIndex the first visible item, fractional (the item's
 *   line plus how far it has scrolled past). Read in layout only.
 * @param itemsPerScreen how many items fill the viewport. Read in layout
 *   and in a derived state (whether the list is long enough to show).
 * @param isScrollInProgress whether the list is being scrolled (by touch or
 *   fling): the handle wakes with it and sleeps a moment after.
 * @param onJump scrolls the list so the line of this item is at the top.
 * @param onGrab the handle was pressed: stop any scroll in progress (a
 *   fling) without moving the list. The handle sits over the list, so the
 *   list never sees this touch, and a fling left running would carry the
 *   content away under the held handle.
 */
@Composable
fun YoinFastScroller(
    itemCount: Int,
    firstVisibleIndex: () -> Float,
    itemsPerScreen: () -> Float,
    isScrollInProgress: () -> Boolean,
    sections: List<FastScrollSection>,
    onJump: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    showTicks: Boolean = true,
    itemsPerLine: Int = 1,
    onGrab: () -> Unit = {}
) {
    FastScroller(
        controller = remember { FastScrollerController() },
        itemCount = itemCount,
        firstVisibleIndex = firstVisibleIndex,
        itemsPerScreen = itemsPerScreen,
        sections = sections,
        onJump = onJump,
        modifier = modifier,
        showTicks = showTicks,
        itemsPerLine = itemsPerLine,
        isScrollInProgress = isScrollInProgress,
        onGrab = onGrab,
        forceShown = false
    )
}

/**
 * [YoinFastScroller] over a vertical [LazyGridState]. Jumps go through
 * [LazyGridState.requestScrollToItem]; [onJumped] follows each one with the
 * grid index jumped to (e.g. to move a width-change anchor along with it).
 * Grabbing the handle stops a fling where it is.
 *
 * @param leadingItems items before the first section item, each a full-span
 *   row of its own (a sort row, a header). [sections] count from the item
 *   after them; the handle's top end still brings back the very top.
 */
@Composable
fun YoinFastScroller(
    state: LazyGridState,
    sections: List<FastScrollSection>,
    modifier: Modifier = Modifier,
    showTicks: Boolean = true,
    leadingItems: Int = 0,
    onJumped: (index: Int) -> Unit = {}
) {
    val leading = leadingItems.coerceAtLeast(0)
    val itemCount by remember(state, leading) {
        derivedStateOf { (state.layoutInfo.totalItemsCount - leading).coerceAtLeast(0) }
    }
    val itemsPerLine by remember(state) { derivedStateOf { state.layoutInfo.maxSpan.coerceAtLeast(1) } }
    val reader = remember(state, leading) { GridScrollReader(state, leading) }
    val jumped by rememberUpdatedState(onJumped)
    YoinFastScroller(
        itemCount = itemCount,
        firstVisibleIndex = reader::firstVisibleItem,
        itemsPerScreen = reader::itemsPerScreen,
        sections = sections,
        onJump = { index ->
            val target = lazyIndexFor(index, leading)
            state.requestScrollToItem(target)
            jumped(target)
        },
        modifier = modifier,
        showTicks = showTicks,
        itemsPerLine = itemsPerLine,
        isScrollInProgress = { state.isScrollInProgress },
        onGrab = reader::stopFling
    )
}

/**
 * [YoinFastScroller] over a vertical [LazyListState]; see the grid overload
 * ([leadingItems] here are items before the first section item).
 */
@Composable
fun YoinFastScroller(
    state: LazyListState,
    sections: List<FastScrollSection>,
    modifier: Modifier = Modifier,
    showTicks: Boolean = true,
    leadingItems: Int = 0,
    onJumped: (index: Int) -> Unit = {}
) {
    val leading = leadingItems.coerceAtLeast(0)
    val itemCount by remember(state, leading) {
        derivedStateOf { (state.layoutInfo.totalItemsCount - leading).coerceAtLeast(0) }
    }
    val reader = remember(state, leading) { ListScrollReader(state, leading) }
    val jumped by rememberUpdatedState(onJumped)
    YoinFastScroller(
        itemCount = itemCount,
        firstVisibleIndex = reader::firstVisibleItem,
        itemsPerScreen = reader::itemsPerScreen,
        sections = sections,
        onJump = { index ->
            val target = lazyIndexFor(index, leading)
            state.requestScrollToItem(target)
            jumped(target)
        },
        modifier = modifier,
        showTicks = showTicks,
        isScrollInProgress = { state.isScrollInProgress },
        onGrab = reader::stopFling
    )
}

/**
 * The lazy layout index of section item [index]. The first item maps to the
 * very top, so the handle's top end shows the leading rows again.
 */
private fun lazyIndexFor(index: Int, leadingItems: Int): Int = if (index <= 0) 0 else index + leadingItems

// ── Geometry (pure) ─────────────────────────────────────────────────────

@Immutable
internal data class FastScrollTick(
    val label: String,
    val startIndex: Int
)

/**
 * The scroller's mappings, in lines (a grid row, a list item).
 *
 * The bubble and the ticks are linear in the item index: the bubble names
 * the section of the last item of line `round(f × lastLine)`, and a
 * section's tick sits where that first becomes its own, `line(start) /
 * lastLine`. The list position follows the same line for as long as it
 * can — so dropping the handle on a tick puts that section's line at the
 * top (mid-row starts stop on their row, accepted by the owner) — and the
 * last stretch of the track, where the list can no longer bring a line to
 * the top, covers the final screen instead, so the handle reaches the
 * bottom exactly when the list does. The handle's resting place is the
 * same mapping run backwards, so grabbing it never jumps the list.
 */
internal object FastScrollMath {

    /** Content shorter than this many screens shows no scroller. */
    const val MIN_SCREENS = 3f

    fun lineCount(itemCount: Int, itemsPerLine: Int): Int {
        if (itemCount <= 0) return 0
        val perLine = itemsPerLine.coerceAtLeast(1)
        return (itemCount + perLine - 1) / perLine
    }

    fun isWorthShowing(itemCount: Int, itemsPerLine: Int, linesPerScreen: Float): Boolean =
        linesPerScreen > 0f && lineCount(itemCount, itemsPerLine) > MIN_SCREENS * linesPerScreen

    /** The handle fraction for a list resting at line [position]. */
    fun handleFraction(position: Float, lines: Int, linesPerScreen: Float): Float {
        val range = scrollRange(lines, linesPerScreen)
        if (range <= 0f) return 0f
        val p = position.coerceIn(0f, range)
        val last = (lines - 1).toFloat()
        val tail = last - range
        val knee = range - tail
        if (tail <= 0f || knee <= 0f) return p / range
        if (p <= knee) return p / last
        val kneeFraction = knee / last
        return kneeFraction + (p - knee) / tail * (1f - kneeFraction)
    }

    /** The list line (fractional) for handle fraction [fraction]: [handleFraction] inverted. */
    fun positionFor(fraction: Float, lines: Int, linesPerScreen: Float): Float {
        val range = scrollRange(lines, linesPerScreen)
        if (range <= 0f) return 0f
        val f = fraction.coerceIn(0f, 1f)
        val last = (lines - 1).toFloat()
        val tail = last - range
        val knee = range - tail
        if (tail <= 0f || knee <= 0f) return f * range
        val kneeFraction = knee / last
        if (f <= kneeFraction) return f * last
        return knee + (f - kneeFraction) / (1f - kneeFraction) * tail
    }

    /** The item to jump to for [fraction]: the first item of its line. */
    fun jumpIndex(fraction: Float, itemCount: Int, itemsPerLine: Int, linesPerScreen: Float): Int {
        if (itemCount <= 0) return 0
        val perLine = itemsPerLine.coerceAtLeast(1)
        val line = positionFor(fraction, lineCount(itemCount, perLine), linesPerScreen).roundToInt()
        return (line * perLine).coerceIn(0, itemCount - 1)
    }

    /** The item whose section the bubble names at [fraction]. */
    fun labelIndex(fraction: Float, itemCount: Int, itemsPerLine: Int): Int {
        if (itemCount <= 0) return 0
        val perLine = itemsPerLine.coerceAtLeast(1)
        val last = lineCount(itemCount, perLine) - 1
        val line = (fraction.coerceIn(0f, 1f) * last).roundToInt().coerceIn(0, last)
        return min(itemCount - 1, line * perLine + perLine - 1)
    }

    fun tickFraction(startIndex: Int, itemCount: Int, itemsPerLine: Int): Float {
        val perLine = itemsPerLine.coerceAtLeast(1)
        val last = lineCount(itemCount, perLine) - 1
        if (last <= 0) return 0f
        return ((startIndex / perLine).toFloat() / last).coerceIn(0f, 1f)
    }

    /** The section holding [index] (sections in list order), or -1. */
    fun sectionAt(sections: List<FastScrollSection>, index: Int): Int {
        var low = 0
        var high = sections.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (sections[mid].startIndex <= index) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /**
     * One tick per run of sections sharing a tick label, and one per line at
     * most: when several runs start on the same line, the last one keeps the
     * tick. The bubble names a line's last item, so of the runs starting on
     * a line only the last can ever be named there; a tick for an earlier
     * one would light up beside a bubble naming another.
     */
    fun ticks(sections: List<FastScrollSection>, itemsPerLine: Int = 1): List<FastScrollTick> {
        val perLine = itemsPerLine.coerceAtLeast(1)
        val ticks = ArrayList<FastScrollTick>()
        sections.forEachIndexed { i, section ->
            if (i > 0 && sections[i - 1].tickLabel == section.tickLabel) return@forEachIndexed
            val tick = FastScrollTick(section.tickLabel, section.startIndex)
            val last = ticks.lastOrNull()
            if (last != null && last.startIndex / perLine == section.startIndex / perLine) {
                ticks[ticks.lastIndex] = tick
            } else {
                ticks += tick
            }
        }
        return ticks
    }

    /**
     * For each section, the index of its run of sections sharing a tick
     * label. The handle passing from one run into the next is what plays the
     * segment tick: a letter, a year; a timeline's months under one year
     * only change the bubble's words.
     */
    fun tickRuns(sections: List<FastScrollSection>): IntArray {
        val runs = IntArray(sections.size)
        var run = -1
        sections.forEachIndexed { i, section ->
            if (i == 0 || sections[i - 1].tickLabel != section.tickLabel) run++
            runs[i] = run
        }
        return runs
    }

    /**
     * Which ticks fit, given their centres (ascending) and the minimum gap
     * between two drawn ticks: top-down greedy, then the last tick wins its
     * place back from the ones crowding it, so both ends stay labelled.
     */
    fun visibleTicks(centers: FloatArray, minGap: Float): BooleanArray {
        val keep = BooleanArray(centers.size)
        if (centers.isEmpty()) return keep
        var lastKept = centers[0]
        keep[0] = true
        for (i in 1 until centers.size) {
            if (centers[i] - lastKept >= minGap) {
                keep[i] = true
                lastKept = centers[i]
            }
        }
        val end = centers.lastIndex
        if (end > 0 && !keep[end]) {
            keep[end] = true
            for (i in end - 1 downTo 1) {
                if (centers[end] - centers[i] >= minGap) break
                keep[i] = false
            }
        }
        return keep
    }

    private fun scrollRange(lines: Int, linesPerScreen: Float): Float = (lines - linesPerScreen).coerceAtLeast(0f)
}

// ── State ───────────────────────────────────────────────────────────────

@Immutable
internal data class JumpRequest(val gesture: Int, val index: Int)

/**
 * The drag. While [held] the handle sits at [fraction] (the finger, 1:1);
 * after release it stays [pinned] there until the list moves on its own or
 * changes under it, then [handOver]s to the list's position through a short
 * [correction] spring, so the release never shows a jump.
 */
@Stable
internal class FastScrollerController {
    var held by mutableStateOf(false)
    var fraction by mutableFloatStateOf(0f)
    var pinned by mutableStateOf(false)
    var request by mutableStateOf<JumpRequest?>(null)
        private set
    val correction = Animatable(0f)

    // Written in composition (SideEffect) and layout; read by the gesture.
    var itemCount by mutableIntStateOf(0)
    var itemsPerLine by mutableIntStateOf(1)
    var firstVisibleIndex: () -> Float = { 0f }
    var itemsPerScreen: () -> Float = { 0f }
    var onGrab: () -> Unit = {}
    var trackExtentPx = 0f
    var lastShownSection = -1

    /**
     * The list's length and columns as the handle was grabbed: a drag, its
     * bubble and the handle it leaves pinned all measure in these, so a list
     * that grows or reflows meanwhile (a refresh, a page arriving) never
     * moves the target under a still finger, nor keeps pulling the next
     * page in while the handle is held at the bottom.
     */
    var dragItemCount by mutableIntStateOf(0)
        private set
    var dragItemsPerLine by mutableIntStateOf(1)
        private set

    private var gesture = 0
    private var raw = 0f
    private var linesPerScreen = 0f
    private var lastIndex = -1

    /** The list changed length or columns since the grab: a pinned handle no longer marks its place. */
    val isPinStale: Boolean get() = itemCount != dragItemCount || itemsPerLine != dragItemsPerLine

    fun restFraction(): Float = FastScrollMath.handleFraction(
        position = firstVisibleIndex() / itemsPerLine,
        lines = FastScrollMath.lineCount(itemCount, itemsPerLine),
        linesPerScreen = itemsPerScreen() / itemsPerLine
    )

    fun displayFraction(): Float =
        if (held || pinned) fraction else (restFraction() + correction.value).coerceIn(0f, 1f)

    fun begin() {
        // First stop a fling, which would otherwise carry the content away
        // under the held handle. It stops where it is, so the position read
        // next is the one the drag starts from.
        onGrab()
        val start = displayFraction()
        gesture++
        raw = start
        fraction = start
        // Frozen for the drag: a list whose rows measure differently as it
        // jumps, or that changes length, must not move the target under a
        // still finger.
        dragItemCount = itemCount
        dragItemsPerLine = itemsPerLine
        linesPerScreen = itemsPerScreen() / itemsPerLine
        lastIndex = FastScrollMath.jumpIndex(start, dragItemCount, dragItemsPerLine, linesPerScreen)
        pinned = false
        held = true
    }

    fun dragBy(deltaPx: Float) {
        if (trackExtentPx <= 0f) return
        raw += deltaPx / trackExtentPx
        fraction = raw.coerceIn(0f, 1f)
        val index = FastScrollMath.jumpIndex(fraction, dragItemCount, dragItemsPerLine, linesPerScreen)
        if (index != lastIndex) {
            lastIndex = index
            request = JumpRequest(gesture, index)
        }
    }

    fun end() {
        if (!held) return
        held = false
        pinned = true
    }

    /** Held (or left pinned) at [fraction] of a list of [itemCount], as a preview shows it. */
    fun preset(held: Boolean, fraction: Float, itemCount: Int, itemsPerLine: Int) {
        this.itemCount = itemCount
        this.itemsPerLine = itemsPerLine
        dragItemCount = itemCount
        dragItemsPerLine = itemsPerLine
        this.fraction = fraction
        pinned = !held
        this.held = held
    }

    /**
     * Let go of the pinned spot without a jump: the [correction] starts at
     * the gap between the finger's spot and the list's own, and springs it
     * shut.
     */
    suspend fun handOver(spec: AnimationSpec<Float>) {
        if (!pinned || held) return
        correction.snapTo(fraction - restFraction())
        pinned = false
        correction.animateTo(0f, spec)
    }
}

// ── Composition ─────────────────────────────────────────────────────────

internal object FastScrollerTags {
    const val THUMB = "fastScroller.thumb"
    const val TICKS = "fastScroller.ticks"
    const val BUBBLE = "fastScroller.bubble"
}

private val ThumbVisualWidth = 24.dp
private val ThumbVisualHeight = 48.dp
private val ThumbTouchWidth = 32.dp
private val ThumbTouchHeight = 64.dp
private val ThumbSlideDistance = 32.dp
private val ChevronHalfWidth = 4.dp
private val ChevronHeight = 3.dp
private val ChevronOffset = 5.dp
private val ChevronStroke = 2.dp
private val PanelGap = 4.dp
private val PanelPaddingHorizontal = 8.dp
private val PanelShift = 8.dp
private const val PANEL_START_SCALE = 0.92f
private val TickMinGap = 18.dp
private val BubbleGap = 8.dp
private val BubbleMinSize = 56.dp
private val BubblePaddingHorizontal = 16.dp
private const val BUBBLE_START_SCALE = 0.6f

// The bubble's section-change pulse: a velocity kick on the bouncy fast
// spatial spring, peaking a few percent over rest — the visual twin of the
// segment tick (the Pixel Tablet has no vibrator).
private const val PULSE_KICK = 3f
private const val HIDE_DELAY_MILLIS = 1_600L
private const val HAPTIC_MIN_INTERVAL_NANOS = 45_000_000L

private const val THUMB_ID = "thumb"
private const val PANEL_ID = "ticks"
private const val BUBBLE_ID = "bubble"

@Composable
internal fun FastScroller(
    controller: FastScrollerController,
    itemCount: Int,
    firstVisibleIndex: () -> Float,
    itemsPerScreen: () -> Float,
    sections: List<FastScrollSection>,
    onJump: (index: Int) -> Unit,
    modifier: Modifier,
    showTicks: Boolean,
    itemsPerLine: Int,
    isScrollInProgress: () -> Boolean,
    forceShown: Boolean,
    onGrab: () -> Unit = {}
) {
    val perLine = itemsPerLine.coerceAtLeast(1)
    SideEffect {
        controller.itemCount = itemCount
        controller.itemsPerLine = perLine
        controller.firstVisibleIndex = firstVisibleIndex
        controller.itemsPerScreen = itemsPerScreen
        controller.onGrab = onGrab
    }
    val reduced = rememberReducedMotion()
    val haptics = rememberYoinHaptics()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val currentPerScreen by rememberUpdatedState(itemsPerScreen)
    val currentScrolling by rememberUpdatedState(isScrollInProgress)
    val currentOnJump by rememberUpdatedState(onJump)

    val worthShowing by remember(itemCount, perLine) {
        derivedStateOf { FastScrollMath.isWorthShowing(itemCount, perLine, currentPerScreen() / perLine) }
    }
    var awake by remember { mutableStateOf(false) }
    LaunchedEffect(controller) {
        snapshotFlow { controller.held || currentScrolling() }.collectLatest { active ->
            if (active) {
                awake = true
            } else {
                delay(HIDE_DELAY_MILLIS)
                awake = false
            }
        }
    }
    val correctionSpec = YoinMotion.fastSpatialSpec<Float>()
    // A pinned handle hands over to the list's own place, on a spring, as
    // soon as the list moves by itself or changes length or columns under
    // it. Launched apart, so a handover still springing never holds up the
    // next one.
    LaunchedEffect(controller) {
        snapshotFlow {
            controller.pinned && !controller.held && (currentScrolling() || controller.isPinStale)
        }.collect { due ->
            if (due) launch { controller.handOver(correctionSpec) }
        }
    }
    // Jumps, coalesced per frame: a newer request cancels the one waiting.
    LaunchedEffect(controller) {
        snapshotFlow { controller.request }.filterNotNull().collectLatest { request ->
            withFrameNanos { }
            currentOnJump(request.index)
        }
    }

    // While held, everything the finger drives measures in the list as it
    // was grabbed (see FastScrollerController.dragItemCount).
    val mapCount = if (controller.held) controller.dragItemCount else itemCount
    val mapPerLine = if (controller.held) controller.dragItemsPerLine else perLine
    val ticks = remember(sections, mapPerLine) { FastScrollMath.ticks(sections, mapPerLine) }
    val tickRuns = remember(sections) { FastScrollMath.tickRuns(sections) }
    val currentSection by remember(controller, sections) {
        derivedStateOf {
            if (!controller.held) {
                -1
            } else {
                FastScrollMath.sectionAt(
                    sections,
                    FastScrollMath.labelIndex(
                        controller.fraction,
                        controller.dragItemCount,
                        controller.dragItemsPerLine
                    )
                )
            }
        }
    }
    // The last section stays named while the bubble fades out — unless the
    // list it named has since changed under it.
    val shownSection = (currentSection.takeIf { it >= 0 } ?: controller.lastShownSection)
        .takeIf { it in sections.indices } ?: -1
    SideEffect { controller.lastShownSection = shownSection }

    val pulse = remember { Animatable(1f) }
    val pulseSpec = YoinMotion.fastSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    LaunchedEffect(controller, sections) {
        var previous = -1
        var lastTick = 0L
        snapshotFlow { currentSection }.collect { section ->
            // One beat per tick passed (a letter, a year), not per change of
            // the bubble's words (a timeline's months within a year).
            val run = tickRuns.getOrElse(section) { -1 }
            if (run >= 0 && previous >= 0 && run != previous) {
                val now = System.nanoTime()
                if (now - lastTick >= HAPTIC_MIN_INTERVAL_NANOS) {
                    haptics.performSegmentTick()
                    lastTick = now
                }
                if (!reduced) launch { pulse.animateTo(1f, pulseSpec, initialVelocity = PULSE_KICK) }
            }
            previous = run
        }
    }

    val shown = forceShown || (worthShowing && (awake || controller.held))
    val expanded = controller.held && sections.isNotEmpty()
    val appear = animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "fastScrollerAppear"
    )
    // Gone from sight, the handle lets go of a pinned spot, so the next
    // showing starts where the list really is. Waiting for the fade to end
    // keeps that move unseen: the handle fades out where the finger left it.
    LaunchedEffect(controller, shown) {
        if (shown) return@LaunchedEffect
        snapshotFlow { appear.value }.first { it == 0f }
        controller.pinned = false
    }
    val slide = animateFloatAsState(
        targetValue = if (shown || reduced) 0f else 1f,
        animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Expressive),
        label = "fastScrollerSlide"
    )
    val expandAlpha = animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = YoinMotion.fastEffectsSpec(),
        label = "fastScrollerExpandAlpha"
    )
    val expandMotion = animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Expressive),
        label = "fastScrollerExpandMotion"
    )
    val unfolded by remember { derivedStateOf { expandAlpha.value > 0f } }
    val composeExtras = sections.isNotEmpty() && (expanded || unfolded)
    val endward = if (rtl) -1f else 1f
    var trackHeightPx by remember { mutableIntStateOf(0) }

    Layout(
        modifier = modifier.onSizeChanged { trackHeightPx = it.height },
        content = {
            FastScrollThumb(
                held = controller.held,
                // Exclusion and touch sit outside the slide layer: their rect is
                // the resting place, not wherever the slide-in has got to.
                modifier = Modifier
                    .layoutId(THUMB_ID)
                    .testTag(FastScrollerTags.THUMB)
                    .clearAndSetSemantics { }
                    .then(
                        if (shown) {
                            Modifier
                                .systemGestureExclusion()
                                .fastScrollThumbGesture(controller)
                        } else {
                            Modifier
                        }
                    )
                    .graphicsLayer {
                        alpha = appear.value
                        translationX = slide.value * ThumbSlideDistance.toPx() * endward
                    }
            )
            if (composeExtras && showTicks && ticks.isNotEmpty()) {
                FastScrollTickColumn(
                    ticks = ticks,
                    itemCount = mapCount,
                    itemsPerLine = mapPerLine,
                    trackHeightPx = trackHeightPx,
                    currentIndex = sections.getOrNull(shownSection)?.startIndex ?: -1,
                    modifier = Modifier
                        .layoutId(PANEL_ID)
                        .testTag(FastScrollerTags.TICKS)
                        .clearAndSetSemantics { }
                        .graphicsLayer {
                            alpha = expandAlpha.value
                            if (!reduced) {
                                val motion = expandMotion.value
                                translationX = (1f - motion) * PanelShift.toPx() * endward
                                val scale = lerp(PANEL_START_SCALE, 1f, motion)
                                scaleY = scale
                                val thumbCenter = controller.displayFraction() * controller.trackExtentPx +
                                    ThumbTouchHeight.toPx() / 2f
                                val pivot = if (size.height > 0f) thumbCenter / size.height else 0.5f
                                transformOrigin = TransformOrigin(0.5f, pivot.coerceIn(0f, 1f))
                            }
                        }
                )
            }
            if (composeExtras && shownSection in sections.indices) {
                FastScrollBubble(
                    label = sections[shownSection].label,
                    modifier = Modifier
                        .layoutId(BUBBLE_ID)
                        .testTag(FastScrollerTags.BUBBLE)
                        .clearAndSetSemantics { }
                        .graphicsLayer {
                            alpha = expandAlpha.value
                            if (!reduced) {
                                val scale = lerp(BUBBLE_START_SCALE, 1f, expandMotion.value) * pulse.value
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin(if (rtl) 0f else 1f, 0.5f)
                            }
                        }
                )
            }
        }
    ) { measurables, constraints ->
        val loose = Constraints()
        val thumb = measurables.first { it.layoutId == THUMB_ID }.measure(loose)
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else thumb.height
        val extent = (height - thumb.height).coerceAtLeast(0)
        controller.trackExtentPx = extent.toFloat()
        val panel = measurables.firstOrNull { it.layoutId == PANEL_ID }
            ?.measure(Constraints(minHeight = height, maxHeight = height))
        val bubble = measurables.firstOrNull { it.layoutId == BUBBLE_ID }?.measure(loose)
        val visualWidth = ThumbVisualWidth.roundToPx()
        val panelGap = PanelGap.roundToPx()
        val bubbleGap = BubbleGap.roundToPx()
        val panelWidth = panel?.let { it.width + panelGap } ?: 0
        val needed = max(thumb.width, visualWidth + panelWidth + (bubble?.let { it.width + bubbleGap } ?: 0))
        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            needed.coerceAtLeast(constraints.minWidth)
        }
        layout(width, height) {
            val thumbY = (controller.displayFraction() * extent).roundToInt()
            thumb.placeRelative(width - thumb.width, thumbY)
            val panelX = width - visualWidth - panelWidth
            panel?.placeRelative(panelX, 0)
            bubble?.let {
                val y = (thumbY + thumb.height / 2 - it.height / 2).coerceIn(0, max(0, height - it.height))
                it.placeRelative(panelX - bubbleGap - it.width, y)
            }
        }
    }
}

/** Press anywhere on the handle and drag: 1:1, from the first pixel (no slop). */
private fun Modifier.fastScrollThumbGesture(controller: FastScrollerController): Modifier = pointerInput(controller) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        controller.begin()
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    change.consume()
                    break
                }
                val dy = change.positionChange().y
                if (dy != 0f) {
                    change.consume()
                    controller.dragBy(dy)
                }
            }
        } finally {
            controller.end()
        }
    }
}

/**
 * A tab hugging the end edge with an up/down glyph — a filled container,
 * deliberately unlike the pane divider's thin M3 VerticalDragHandle.
 */
@Composable
private fun FastScrollThumb(held: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (held) colors.primary else colors.secondaryContainer,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "fastScrollerThumb"
    )
    val glyph by animateColorAsState(
        targetValue = if (held) colors.onPrimary else colors.onSecondaryContainer,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "fastScrollerGlyph"
    )
    val shape = MaterialTheme.shapes.extraSmall.copy(
        topStart = CornerSize(percent = 50),
        bottomStart = CornerSize(percent = 50)
    )
    Box(
        modifier = modifier.size(width = ThumbTouchWidth, height = ThumbTouchHeight),
        contentAlignment = Alignment.CenterEnd
    ) {
        Canvas(
            Modifier
                .size(width = ThumbVisualWidth, height = ThumbVisualHeight)
                .background(container, shape)
        ) {
            val half = ChevronHalfWidth.toPx()
            val rise = ChevronHeight.toPx()
            val offset = ChevronOffset.toPx()
            val stroke = ChevronStroke.toPx()
            val cx = size.width / 2f
            val cy = size.height / 2f
            for (direction in intArrayOf(-1, 1)) {
                val tipY = cy + direction * (offset + rise / 2f)
                val baseY = tipY - direction * rise
                drawLine(glyph, Offset(cx - half, baseY), Offset(cx, tipY), stroke, StrokeCap.Round)
                drawLine(glyph, Offset(cx, tipY), Offset(cx + half, baseY), stroke, StrokeCap.Round)
            }
        }
    }
}

/**
 * The unfolded column: every tick at its section's place on the track,
 * thinned so neighbours keep [TickMinGap]; the tick of the current section
 * (or the nearest drawn one before it) in primary.
 */
@Composable
private fun FastScrollTickColumn(
    ticks: List<FastScrollTick>,
    itemCount: Int,
    itemsPerLine: Int,
    trackHeightPx: Int,
    currentIndex: Int,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.labelMedium.withTabularFigures()
    val currentStyle = style.copy(fontWeight = FontWeight.SemiBold)
    val density = LocalDensity.current
    // Centres on the track the handle's centre travels; the column is
    // exactly the track's height (the scroller measures it so).
    val centers = remember(ticks, itemCount, itemsPerLine, trackHeightPx, density) {
        val touch = with(density) { ThumbTouchHeight.toPx() }
        val extent = (trackHeightPx - touch).coerceAtLeast(0f)
        FloatArray(ticks.size) {
            FastScrollMath.tickFraction(ticks[it].startIndex, itemCount, itemsPerLine) * extent + touch / 2f
        }
    }
    val keep = remember(centers, density) {
        FastScrollMath.visibleTicks(centers, with(density) { TickMinGap.toPx() })
    }
    var current = -1
    ticks.forEachIndexed { i, tick -> if (keep[i] && tick.startIndex <= currentIndex) current = i }
    Layout(
        modifier = modifier.background(colors.surfaceContainerHigh, MaterialTheme.shapes.extraLarge),
        content = {
            ticks.forEachIndexed { i, tick ->
                Text(
                    text = tick.label,
                    style = if (i == current) currentStyle else style,
                    color = if (i == current) colors.primary else colors.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val height = constraints.maxHeight
        val padding = PanelPaddingHorizontal.roundToPx()
        val width = (placeables.maxOfOrNull { it.width } ?: 0) + padding * 2
        layout(width, height) {
            placeables.forEachIndexed { i, placeable ->
                if (!keep[i]) return@forEachIndexed
                val y = (centers[i] - placeable.height / 2f).roundToInt()
                    .coerceIn(0, max(0, height - placeable.height))
                placeable.placeRelative((width - placeable.width) / 2, y)
            }
        }
    }
}

@Composable
private fun FastScrollBubble(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .sizeIn(minWidth = BubbleMinSize, minHeight = BubbleMinSize)
            .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.extraLarge)
            .padding(horizontal = BubblePaddingHorizontal),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge.withTabularFigures(),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1
        )
    }
}

/** Battery saver / low RAM (AdaptiveReduced) or "remove animations": fades only. */
@Composable
private fun rememberReducedMotion(): Boolean {
    val profile = LocalMotionProfile.current
    val scale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor
    return profile == MotionProfile.AdaptiveReduced || scale == 0f
}

// ── Lazy layout readers ─────────────────────────────────────────────────

/**
 * Reads a vertical grid in section-item terms: items past [leading] (each
 * leading item a full-span row of its own), lines of single-span items.
 * While a leading row is still on screen the list counts as at the top.
 */
private class GridScrollReader(private val state: LazyGridState, private val leading: Int) {
    fun firstVisibleItem(): Float {
        val info = state.layoutInfo
        val perLine = info.maxSpan.coerceAtLeast(1)
        val lazyIndex = state.firstVisibleItemIndex
        val index = lazyIndex - leading
        if (index < 0) return 0f
        val first = info.visibleItemsInfo.firstOrNull { it.index == lazyIndex } ?: return index.toFloat()
        val linePx = (first.size.height + info.mainAxisItemSpacing).toFloat()
        val offset = if (linePx > 0f) state.firstVisibleItemScrollOffset / linePx else 0f
        return (index / perLine + offset) * perLine
    }

    fun itemsPerScreen(): Float {
        val info = state.layoutInfo
        // A section item's line, not a leading row's (a sort row is shorter).
        val first = info.visibleItemsInfo.firstOrNull { it.index >= leading } ?: return 0f
        val linePx = (first.size.height + info.mainAxisItemSpacing).toFloat()
        if (linePx <= 0f) return 0f
        val viewport = info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
        return viewport.coerceAtLeast(0) / linePx * info.maxSpan.coerceAtLeast(1)
    }

    /** requestScrollToItem cancels any scroll in progress; asking for where the list already is stops it there. */
    fun stopFling() {
        if (state.isScrollInProgress) {
            state.requestScrollToItem(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
        }
    }
}

/** [GridScrollReader] for a vertical list: every item is a line. */
private class ListScrollReader(private val state: LazyListState, private val leading: Int) {
    fun firstVisibleItem(): Float {
        val info = state.layoutInfo
        val lazyIndex = state.firstVisibleItemIndex
        val index = lazyIndex - leading
        if (index < 0) return 0f
        val first = info.visibleItemsInfo.firstOrNull { it.index == lazyIndex } ?: return index.toFloat()
        val linePx = (first.size + info.mainAxisItemSpacing).toFloat()
        return index + if (linePx > 0f) state.firstVisibleItemScrollOffset / linePx else 0f
    }

    fun itemsPerScreen(): Float {
        val info = state.layoutInfo
        val items = info.visibleItemsInfo
        if (items.isEmpty()) return 0f
        // The average section item; leading items only when nothing else shows.
        var sum = 0
        var count = 0
        for (item in items) {
            if (item.index < leading) continue
            sum += item.size
            count++
        }
        if (count == 0) {
            sum = items.sumOf { it.size }
            count = items.size
        }
        val linePx = sum.toFloat() / count + info.mainAxisItemSpacing
        if (linePx <= 0f) return 0f
        val viewport = info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
        return viewport.coerceAtLeast(0) / linePx
    }

    fun stopFling() {
        if (state.isScrollInProgress) {
            state.requestScrollToItem(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
        }
    }
}

// ── Previews ────────────────────────────────────────────────────────────

private val PreviewAlphabet: List<FastScrollSection> = run {
    val labels = ('A'..'Z').map { it.toString() } + listOf("あ", "か", "さ", "ㄱ", "ㅇ", "#")
    labels.mapIndexed { i, label -> FastScrollSection(label, startIndex = i * 15) }
}

private val PreviewTimeline: List<FastScrollSection> = run {
    val months = listOf("Jan", "Mar", "Jun", "Sep", "Nov")
    (2024 downTo 2016).flatMap { year -> months.reversed().map { month -> year to month } }
        .mapIndexed { i, (year, month) ->
            FastScrollSection(label = "$month $year", startIndex = i * 12, tickLabel = year.toString())
        }
}

@Composable
private fun FastScrollerPreviewHost(
    sections: List<FastScrollSection>,
    itemCount: Int,
    held: Boolean,
    fraction: Float,
    showTicks: Boolean = true
) {
    YoinTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                repeat(12) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .padding(vertical = 4.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small)
                    )
                }
            }
            val controller = remember {
                FastScrollerController().apply { preset(held, fraction, itemCount, itemsPerLine = 1) }
            }
            FastScroller(
                controller = controller,
                itemCount = itemCount,
                firstVisibleIndex = { fraction * itemCount },
                itemsPerScreen = { 12f },
                sections = sections,
                onJump = {},
                modifier = Modifier.matchParentSize().padding(top = 8.dp, bottom = 24.dp),
                showTicks = showTicks,
                itemsPerLine = 1,
                isScrollInProgress = { true },
                forceShown = true
            )
        }
    }
}

@Preview(name = "Letters", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun YoinFastScrollerLettersPreview() {
    FastScrollerPreviewHost(PreviewAlphabet, itemCount = PreviewAlphabet.size * 15, held = true, fraction = 0.42f)
}

@Preview(name = "Years", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun YoinFastScrollerYearsPreview() {
    FastScrollerPreviewHost(PreviewTimeline, itemCount = PreviewTimeline.size * 12, held = true, fraction = 0.6f)
}

@Preview(name = "Dragging, bubble only", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun YoinFastScrollerDraggingPreview() {
    FastScrollerPreviewHost(
        sections = PreviewAlphabet,
        itemCount = PreviewAlphabet.size * 15,
        held = true,
        fraction = 0.8f,
        showTicks = false
    )
}

@Preview(name = "Resting", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun YoinFastScrollerRestingPreview() {
    FastScrollerPreviewHost(PreviewAlphabet, itemCount = PreviewAlphabet.size * 15, held = false, fraction = 0.3f)
}
