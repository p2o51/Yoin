package com.gpo.yoin.ui.home.edit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.home.HomeSection
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Large-screen Home, stage 1 (owner 2026-11-16): the resize handle's
// HORIZONTAL axis. In two columns every section's ⌟ also drags sideways —
// the first direction a drag takes locks it — and a section turns half or
// full width: a full row dragged left past the midpoint becomes a half and
// pulls the row after it up beside it; a left half dragged right (a right
// half left) past it becomes full, its partner on its own row after it.
// While the finger is down the block's width follows it 1:1 and its content
// re-lays out for that width; a dashed outline shows the width it will snap
// to. On release it springs to that width and the change is written (one
// Undo step); with nothing to pair with it springs back. ← / → on a focused
// handle do the same. Phones keep the vertical axis only.

/** Where a section sits in its row. */
enum class HomeSpan { Full, LeftHalf, RightHalf }

/** The axis a handle drag has locked to. */
enum class HomeHandleAxis { Horizontal, Vertical }

/** What a span drag or key writes. */
enum class HomeSpanCommit { None, ToHalf, ToFull }

/** Numbers of the horizontal resize. */
internal object HomeSpanTokens {
    /** The gap between the two halves of a row. */
    val ColumnGap = 20.dp

    /** A drag can pull a block this much narrower than a half (a rubber band past it). */
    const val UnderHalf = .82f

    /** The snap outline: dash, gap, stroke. */
    val OutlineDash = 8.dp
    val OutlineGap = 6.dp
    val OutlineStroke = 2.dp
}

/**
 * The axis a drag locks to once it has moved past [slopPx] (the larger
 * component wins; a tie is vertical, today's axis), or null while it hasn't.
 * Without [horizontal] (one column, or a section that can't pair) it is
 * always vertical. Pure.
 */
internal fun homeHandleAxis(dx: Float, dy: Float, slopPx: Float, horizontal: Boolean): HomeHandleAxis? {
    if (abs(dx) <= slopPx && abs(dy) <= slopPx) return null
    if (!horizontal) return HomeHandleAxis.Vertical
    return if (abs(dx) > abs(dy)) HomeHandleAxis.Horizontal else HomeHandleAxis.Vertical
}

/**
 * The dragged block's live width, px: [startPx] moved by the finger's [dx]
 * — a right half grows leftward, so its width follows −dx — held between a
 * little under [halfPx] ([HomeSpanTokens.UnderHalf]) and [fullPx]. Pure.
 */
internal fun homeSpanDragWidth(from: HomeSpan, startPx: Float, dx: Float, halfPx: Float, fullPx: Float): Float {
    val raw = if (from == HomeSpan.RightHalf) startPx - dx else startPx + dx
    return raw.coerceIn(halfPx * HomeSpanTokens.UnderHalf, fullPx)
}

/** The span a block of [widthPx] snaps to: half below the midpoint of [halfPx] and [fullPx], else full. Pure. */
internal fun homeSpanTarget(widthPx: Float, halfPx: Float, fullPx: Float): HomeSpan =
    if (widthPx < (halfPx + fullPx) / 2f) HomeSpan.LeftHalf else HomeSpan.Full

/**
 * What a release from [from] snapping to [target] writes: a full row turning
 * half needs a row after it ([hasNext]); a half turning full always can;
 * anything else is no change (the block springs back). Pure.
 */
internal fun homeSpanCommit(from: HomeSpan, target: HomeSpan, hasNext: Boolean): HomeSpanCommit = when {
    from == HomeSpan.Full && target != HomeSpan.Full -> if (hasNext) HomeSpanCommit.ToHalf else HomeSpanCommit.None
    from != HomeSpan.Full && target == HomeSpan.Full -> HomeSpanCommit.ToFull
    else -> HomeSpanCommit.None
}

/**
 * ← / → on a focused handle: a full row ← turns half (with a row after it), a
 * left half → and a right half ← turn full. Null for a key that does nothing. Pure.
 */
internal fun homeSpanKey(from: HomeSpan, left: Boolean, hasNext: Boolean): HomeSpanCommit? = when (from) {
    HomeSpan.Full -> if (left && hasNext) HomeSpanCommit.ToHalf else null
    HomeSpan.LeftHalf -> if (!left) HomeSpanCommit.ToFull else null
    HomeSpan.RightHalf -> if (left) HomeSpanCommit.ToFull else null
}

/** Where [section]'s block sits now, for a span drag: its span, width, a half's and a full row's width (px). */
internal data class HomeSpanInfo(
    val span: HomeSpan,
    val currentPx: Float,
    val halfPx: Float,
    val fullPx: Float,
    /** A full row has a row after it to pull up beside it. */
    val hasNext: Boolean,
)

/**
 * The one horizontal drag in flight (there is one finger on one handle).
 * [width] is the dragged block's live width, the single driver the row
 * reads in layout; [section] null = nothing dragged. Commits go through
 * [commit] (the edit controller), after the spring lands.
 */
@Stable
internal class HomeSpanDrag(
    private val scope: CoroutineScope,
    private val spec: () -> AnimationSpec<Float>,
    private val commit: (HomeSection, HomeSpanCommit) -> Boolean,
    private val tick: () -> Unit,
) {
    var section: HomeSection? by mutableStateOf(null)
        private set
    var from: HomeSpan by mutableStateOf(HomeSpan.Full)
        private set

    /** Live width (px) of [section]'s block. Layout and draw only. */
    val width = Animatable(0f)

    /** The width the outline shows and a release springs to. */
    var target: HomeSpan by mutableStateOf(HomeSpan.Full)
        private set

    /** The finger is down (as opposed to the release spring). */
    var dragging: Boolean by mutableStateOf(false)
        private set

    /** The feed's answer for a section's place, or null when it can't change span (one column). Set by the feed. */
    var info: (HomeSection) -> HomeSpanInfo? = { null }

    /** A focused handle's ← / →: writes at once. False when the key does nothing here. */
    fun key(section: HomeSection, left: Boolean): Boolean {
        val place = info(section) ?: return false
        val write = homeSpanKey(place.span, left, place.hasNext) ?: return false
        return commit(section, write)
    }

    private var startX = 0f
    private var startWidth = 0f
    private var halfPx = 0f
    private var fullPx = 0f
    private var hasNext = false
    private var settle: Job? = null

    /** Starts a drag of [section] (sitting at [span], [currentPx] wide) at finger x [fingerX]. */
    fun grab(
        section: HomeSection,
        span: HomeSpan,
        fingerX: Float,
        currentPx: Float,
        halfPx: Float,
        fullPx: Float,
        hasNext: Boolean,
    ) {
        settle?.cancel()
        this.section = section
        from = span
        startX = fingerX
        startWidth = currentPx
        this.halfPx = halfPx
        this.fullPx = fullPx
        this.hasNext = hasNext
        target = if (span == HomeSpan.Full) HomeSpan.Full else HomeSpan.LeftHalf
        dragging = true
        scope.launch { width.snapTo(currentPx) }
    }

    fun dragTo(fingerX: Float) {
        if (section == null || !dragging) return
        val w = homeSpanDragWidth(from, startWidth, fingerX - startX, halfPx, fullPx)
        scope.launch { width.snapTo(w) }
        val next = homeSpanTarget(w, halfPx, fullPx)
        if (next != target) {
            target = next
            tick()
        }
    }

    /** Springs to the target (or back) and writes the change once it lands. */
    fun release(cancelled: Boolean) {
        val dragged = section ?: return
        dragging = false
        val write = if (cancelled) HomeSpanCommit.None else homeSpanCommit(from, target, hasNext)
        val landing = when (write) {
            HomeSpanCommit.ToHalf -> halfPx
            HomeSpanCommit.ToFull -> fullPx
            HomeSpanCommit.None -> startWidth
        }
        settle = scope.launch {
            try {
                width.animateTo(landing, spec())
                if (write != HomeSpanCommit.None) commit(dragged, write)
            } finally {
                if (section == dragged && !dragging) section = null
            }
        }
    }

    /** Drops any drag at once (edit mode left, a carry started). */
    fun snap() {
        settle?.cancel()
        dragging = false
        section = null
    }
}
