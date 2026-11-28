package com.gpo.yoin.shared.home

import kotlin.math.abs
import kotlin.math.sqrt

// The in-place drag's decisions (owner 2026-11-22; Android PR #292), extracted unchanged from Android's
// HomeDragEngine for parity phase 4: which arrangement the finger asks for frame by frame (a 12dp travel
// gate after each change, decided only on a layout that has caught up), the edge scroll and its start
// guard, no pairing while the edge scroll moves the rows, the re-decide on release against what is laid
// out, and the rollback. The platforms keep the lift, the glide, the kicks, the haptics and the list
// itself: each frame they hand the core what is laid out ([HomeDragView]) and apply what it answers
// ([HomeDragFrame]). The golden timelines are in shared/src/commonTest/resources/golden/home-drag.json.

/** A laid-out row item, keyed by its first section, and its vertical extent in px (the list's coordinates). */
data class DragItemBand(val key: HomeSection, val top: Float, val bottom: Float)

/**
 * What the list shows on this frame: the row items laid out, top to bottom ([visible]), the content's
 * left edge and width (two columns read the finger across it), the area whose ends scroll a held block
 * ([areaTop], [areaBottom], between the status tide and the bar), and whether the rows are in two columns.
 */
data class HomeDragView(
    val visible: List<DragItemBand>,
    val contentLeft: Float,
    val contentWidth: Float,
    val areaTop: Float,
    val areaBottom: Float,
    val twoColumn: Boolean,
)

/** What a frame asks of the platform. */
sealed interface HomeDragFrame {
    /** Nothing this frame. */
    data object None : HomeDragFrame

    /**
     * The drag moved to [arrangement], the dragged block now on [side] (null: a row of its own). Lay it out,
     * keep the list where it is (by index and offset, not by key) and tick. A frame that steps doesn't scroll.
     */
    data class Step(val arrangement: DragArrangement, val side: HomeDragLane?) : HomeDragFrame

    /** Scroll the list by [px] (+ = toward the end), then report what it consumed ([HomeDragCore.scrolled]). */
    data class Scroll(val px: Float) : HomeDragFrame
}

/**
 * The drag's decision state and reducer. One drag at a time: [start] it, feed every finger move ([move]) and
 * every frame ([frame], then [scrolled] after a [HomeDragFrame.Scroll]), tell it when the dragged block was
 * placed in the live arrangement ([placed]), and end it with [release] (or drop it with [end]). Lengths are
 * px; [density] is px per dp (the dp tokens in [HomeDragTokens] and [HomeDragRuleTokens] scale by it).
 */
class HomeDragCore(density: Float) {

    private val travelPx = HomeDragTokens.ChangeTravel * density
    private val edgePx = HomeDragTokens.AutoScrollEdge * density
    private val minSpeedPx = HomeDragTokens.AutoScrollMin * density
    private val maxSpeedPx = HomeDragTokens.AutoScrollMax * density
    private val pushPx = HomeDragTokens.EdgeStartPush * density
    private val bufferPx = HomeDragRuleTokens.RowBuffer * density

    /** The block being dragged; null between drags. */
    var dragging: HomeSection? = null
        private set

    /** The live arrangement while dragging; null between drags. */
    var arrangement: DragArrangement? = null
        private set

    /** The dragged block's side in a pair; null when it is a row of its own. */
    var side: HomeDragLane? = null
        private set

    /** The arrangement before the lift: a rollback brings it back. */
    var origin: DragArrangement? = null
        private set

    /** The finger, px. */
    var fingerX: Float = 0f
        private set
    var fingerY: Float = 0f
        private set

    /** The finger's offset from the dragged block's top-left at the start. */
    var grabX: Float = 0f
        private set
    var grabY: Float = 0f
        private set

    // How far the finger (or the scroll under it) went since the last change: a change waits for a
    // little of it, so a layout that moved under a resting finger never decides the next one alone.
    private var travel = 0f

    // The edge scroll moved the rows on the last frame that scrolled: no block pairs while it runs.
    private var edgeScrolling = false

    // The start-in-edge guard: the band the finger was lifted in (±1, 0 none), where, and how long it has rested there.
    private var edgeGuard = 0
    private var edgeGuardY = 0f
    private var edgeGuardMs = 0f

    // The finger where the last decision was made (NaN: none yet), the arrangement (and side) before the
    // last change, and the arrangement the dragged block was last placed in (the one the list laid out).
    private var decidedX = Float.NaN
    private var decidedY = Float.NaN
    private var beforeStep: Pair<DragArrangement, HomeDragLane?>? = null
    private var laidOut: DragArrangement? = null

    private var rowsFor: Pair<DragArrangement, Boolean>? = null
    private var rowsCache: List<HomeFeedRow> = emptyList()

    /** [arrangement]'s rows, cached for the last arrangement asked. */
    fun rowsOf(arrangement: DragArrangement, twoColumn: Boolean): List<HomeFeedRow> {
        val key = arrangement to twoColumn
        if (rowsFor != key) {
            rowsCache = dragRows(arrangement.ids, arrangement.pairs, twoColumn)
            rowsFor = key
        }
        return rowsCache
    }

    /**
     * Starts dragging [dragged] in [before] (the enabled order and, in [twoColumn], the pairs laid out side
     * by side) with the finger at ([fingerX], [fingerY]) and the block drawn with its top-left at
     * ([blockLeft], [blockTop]). A half starts beside its partner. A finger already in an edge band of
     * [areaTop]..[areaBottom] arms the start guard.
     */
    fun start(
        dragged: HomeSection,
        before: DragArrangement,
        twoColumn: Boolean,
        fingerX: Float,
        fingerY: Float,
        blockLeft: Float,
        blockTop: Float,
        areaTop: Float,
        areaBottom: Float,
    ) {
        val lane = dragLaneOf(dragged, rowsOf(before, twoColumn))
        this.fingerX = fingerX
        this.fingerY = fingerY
        grabX = fingerX - blockLeft
        grabY = fingerY - blockTop
        decidedX = Float.NaN
        decidedY = Float.NaN
        beforeStep = null
        laidOut = null
        side = lane.takeIf { it != HomeDragLane.Full }
        origin = before
        travel = 0f
        edgeScrolling = false
        edgeGuard = dragEdgeOf(fingerY, areaTop, areaBottom, dragEdgeBand(edgePx, areaTop, areaBottom))
        edgeGuardY = fingerY
        edgeGuardMs = 0f
        arrangement = before
        dragging = dragged
    }

    /** The finger moved to ([x], [y]); a NaN [x] keeps the last x. The next frame decides on it. */
    fun move(x: Float, y: Float) {
        if (dragging == null) return
        val nx = if (x.isNaN()) fingerX else x
        val dx = nx - fingerX
        val dy = y - fingerY
        travel += sqrt(dx * dx + dy * dy)
        fingerX = nx
        fingerY = y
    }

    /** The dragged block was placed in the live arrangement (the list laid it out). */
    fun placed() {
        laidOut = arrangement
    }

    /**
     * One frame, [dtSeconds] after the last ([HomeDragTokens.MaxFrameSeconds] at most; 0 on the first), against
     * the layout of the frame before ([view]): one decision, or else the edge scroll.
     */
    fun frame(dtSeconds: Float, view: HomeDragView): HomeDragFrame {
        val dragged = dragging ?: return HomeDragFrame.None
        val live = arrangement ?: return HomeDragFrame.None
        val rows = rowsOf(live, view.twoColumn)
        val k = dragRowIndexOf(dragged, rows)
        if (k < 0) return HomeDragFrame.None
        if (travel >= travelPx && dragLayoutFollows(view.visible.map { it.key }, rows, rows[k].first)) {
            val step = decide(dragged, live, side, rows, k, view)
            decidedX = fingerX
            decidedY = fingerY
            if (step != null) {
                beforeStep = live to side
                arrangement = step.arrangement
                side = step.side
                travel = 0f
                return HomeDragFrame.Step(step.arrangement, step.side)
            }
        }
        val edge = dragEdgeBand(edgePx, view.areaTop, view.areaBottom)
        if (edgeGuard != 0) {
            edgeGuardMs += dtSeconds * 1000f
            val inBand = dragEdgeOf(fingerY, view.areaTop, view.areaBottom, edge) == edgeGuard
            val holds = dragEdgeGuardHolds(edgeGuard, edgeGuardY, fingerY, edgeGuardMs, pushPx, HomeDragTokens.EdgeStartDwellMs)
            if (!inBand || !holds) {
                edgeGuard = 0
            } else {
                edgeScrolling = false
                return HomeDragFrame.None
            }
        }
        val speed = dragAutoScrollSpeed(fingerY, view.areaTop, view.areaBottom, edge, minSpeedPx, maxSpeedPx)
        if (speed == 0f) {
            edgeScrolling = false
        } else if (dtSeconds > 0f) {
            return HomeDragFrame.Scroll(speed * dtSeconds)
        }
        return HomeDragFrame.None
    }

    /** The list consumed [consumedPx] of a [HomeDragFrame.Scroll]: travel for the next change; at the list's end nothing moved. */
    fun scrolled(consumedPx: Float) {
        val moved = abs(consumedPx)
        travel += moved
        // At the list's end nothing moves: the finger pairs there as anywhere.
        edgeScrolling = moved > 0f
    }

    /**
     * The finger lifted. Not [cancelled]: its last moves, since the last frame decided, decide now against the
     * layout the list shows ([view]) — the live arrangement, or the one before the last change if that change
     * isn't laid out yet — with no travel wait. Returns the arrangement to write when it differs from
     * [origin] (null: nothing to write, the order goes back to how it was). The drag stays readable until [end].
     */
    fun release(cancelled: Boolean, view: HomeDragView): DragArrangement? {
        if (dragging == null) return null
        if (!cancelled) decideOnRelease(view)
        return arrangement?.takeIf { !cancelled && it != origin }
    }

    /**
     * An exit arrived mid-drag ([dragExitKeepsDrop]): Done and the app's own exits write the live arrangement
     * when it differs from [origin] (no re-decide: the finger may still be down); Back and a blank tap roll back
     * (null). Rotation, a new column count or a resize roll back too (null, as a cancelled [release]).
     */
    fun exit(reason: HomeEditExitReason): DragArrangement? =
        arrangement?.takeIf { dragging != null && dragExitKeepsDrop(reason) && it != origin }

    /** The drag is over: every decision value back at rest. */
    fun end() {
        dragging = null
        arrangement = null
        side = null
    }

    // A quick drag lets go within a frame or two of its last moves (moves batched with the up never reach a
    // frame at all), and the drop landed where the finger was a frame or more before (in list mode, a quick
    // drag up onto a full strip at 90% across swapped rows instead of pairing; a slow one paired).
    private fun decideOnRelease(view: HomeDragView) {
        val dragged = dragging ?: return
        val live = arrangement ?: return
        if (fingerX == decidedX && fingerY == decidedY) return
        val prior = beforeStep
        val (base, baseSide) = if (prior != null && laidOut != null && laidOut != live && laidOut == prior.first) prior else live to side
        val rows = rowsOf(base, view.twoColumn)
        val k = dragRowIndexOf(dragged, rows)
        if (k < 0) return
        if (!dragLayoutFollows(view.visible.map { it.key }, rows, rows[k].first)) return
        val step = decide(dragged, base, baseSide, rows, k, view)
        arrangement = step?.arrangement ?: base
        side = if (step != null) step.side else baseSide
        decidedX = fingerX
        decidedY = fingerY
    }

    private fun decide(
        dragged: HomeSection,
        live: DragArrangement,
        side: HomeDragLane?,
        rows: List<HomeFeedRow>,
        k: Int,
        view: HomeDragView,
    ): DragSideStep.Arrange? {
        val bands = view.visible.mapNotNull { item ->
            rows.firstOrNull { it.first == item.key }?.let { DragRowBand(it, item.top, item.bottom) }
        }
        if (view.twoColumn) {
            val rel = dragRel(fingerX, view.contentLeft, view.contentWidth)
            // While the edge scroll carries rows under the finger, it pairs with none of them.
            val step = dragSideStep(live.ids, live.pairs, dragged, side, rel, fingerY, bands, bufferPx, pairing = !edgeScrolling)
            when (step) {
                is DragSideStep.Arrange -> return step.takeIf { it.arrangement != live || it.side != side }
                DragSideStep.Stay -> return null
                DragSideStep.Vertical -> Unit
            }
        }
        val slot = bands.firstOrNull { it.row == rows[k] } ?: return null
        val prev = rows.getOrNull(k - 1)?.let { row -> bands.firstOrNull { it.row == row } }
        val next = rows.getOrNull(k + 1)?.let { row -> bands.firstOrNull { it.row == row } }
        val dir = dragSwapDir(fingerY - grabY, slot, prev, next)
        if (dir == 0) return null
        val ids = dragSwapRow(live.ids, live.pairs, dragged, dir, view.twoColumn)
        return DragSideStep.Arrange(DragArrangement(ids, live.pairs), side = null)
    }
}
