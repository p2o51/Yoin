package com.gpo.yoin.ui.home.edit

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.home.HomeFeedRow
import com.gpo.yoin.ui.home.HomeLayout
import com.gpo.yoin.ui.home.HomeSection
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** A laid-out row item, keyed by its first section, and its vertical extent in Box px. */
@Immutable
internal data class DragItemBand(val key: HomeSection, val top: Float, val bottom: Float)

/**
 * A list of section rows a block can be dragged in: the feed, or the pinched
 * list. Implemented over a LazyColumn at the page Box's origin ([LazyListDragHost]).
 */
internal interface HomeDragHost {
    /**
     * Enabled sections in the draft's order, without those this device never
     * shows. From data, never from what is rendered: a placeholder held back
     * above the lifted block still counts, or the commit would not be a full
     * permutation.
     */
    fun order(): List<HomeSection>

    /** The rows lay out in two columns: a drag may pair blocks side by side. */
    fun twoColumn(): Boolean = false

    /** Two columns: the pairs laid out side by side now, left → right. */
    fun pairs(): Map<HomeSection, HomeSection> = emptyMap()

    /** The row items laid out now, top to bottom. */
    fun visibleRows(): List<DragItemBand>

    /** The row item keyed [key] if it is laid out. */
    fun rowBand(key: HomeSection): DragItemBand? = visibleRows().firstOrNull { it.key == key }

    fun contentLeft(): Float

    fun contentWidth(): Float

    /** Two columns: the gap between the halves of a row (px). */
    fun columnGap(): Float = 0f

    /** Where the list shows rows, between the status tide and the bar: its ends scroll a held block. */
    fun scrollArea(): HomeEditSafeArea

    /** Scrolls by [px] (+ = toward the end); returns what the list consumed. */
    fun scrollBy(px: Float): Float

    /** The next reorder keeps the list where it is (its first visible index and offset), not by key. */
    fun holdScroll() = Unit

    /** The pinched list's rows, not the feed's. */
    val isList: Boolean get() = false
}

/**
 * Lift and the in-place drag (owner 2026-11-22). Lift and its tint are one
 * shared pair, so only one block is ever lifted. A drag reorders the live
 * [arrangement] the feed (or the pinched [list]) renders while the finger
 * moves: the dragged block follows the finger in its layer, every other block
 * makes way as the list lays the new order out, and a finger near the top or
 * bottom scrolls. The drop writes the arrangement once (one Undo step) and
 * the block settles into its slot.
 *
 * Frame cost: a move writes only [finger], read by the dragged block's layer;
 * an arrangement change (a slot change) is the only composition a drag makes.
 */
@Stable
internal class HomeDragEngine(
    private val scope: CoroutineScope,
    private val specs: HomeEditSpecs,
    private val motion: HomeEditMotion,
    private val feedback: HomeEditFeedback,
    private val density: Density,
    private val commitOrder: (List<HomeSection>) -> Boolean,
    private val finishDeferredExit: (HomeEditExitReason) -> Unit,
    private val uptimeMs: () -> Long = { SystemClock.uptimeMillis() },
    /** Two columns: a drop writes the order and the pairs together (one Undo step). */
    private val commitArrangement: ((List<HomeSection>, Map<HomeSection, HomeSection>) -> Boolean)? = null,
) {
    // ── Lift (§2.3 step 5, §2.4) ──────────────────────────────────────────

    /** Scale 1 + .02·lift and a 6dp shadow on the lifted block. Layer only. */
    val lift = Animatable(0f)

    /** Plate colour toward surfaceContainerHighest. Draw only. */
    val liftTint = Animatable(0f)

    var liftSection: HomeSection? by mutableStateOf(null)
        private set

    /** The press point in block-local px: the lift's pivot. */
    var liftOrigin: Offset by mutableStateOf(Offset.Zero)
        private set

    private var liftJob: Job? = null
    private var tintJob: Job? = null

    /** Picks [section] up at [origin]: its kicks stop, a block still lifted elsewhere is let go at once. */
    fun liftBlock(section: HomeSection, origin: Offset) {
        // A span or rows handle in use owns the edit: no block lifts under it.
        if (resizeActive()) return
        val retire = liftSection != null && liftSection != section
        liftSection = section
        liftOrigin = origin
        liftJob = scope.launchNow {
            if (retire) lift.snapTo(0f)
            lift.animateTo(1f, specs.liftUp)
        }
        tintJob = scope.launchNow {
            if (retire) liftTint.snapTo(0f)
            liftTint.animateTo(1f, specs.liftTint)
        }
        motion.stopKicks(section)
    }

    /** Puts the lifted block down where it is; [kickA] > 0 kicks it, showing as the lift falls. */
    fun dropLift(kickA: Float) {
        val section = liftSection ?: return
        liftJob = scope.launchNow {
            lift.animateTo(0f, specs.liftDown)
            if (liftSection == section && dragging == null) liftSection = null
        }
        tintJob = scope.launchNow { liftTint.animateTo(0f, specs.liftTint) }
        if (kickA > 0f) motion.impulse(section, kickA)
    }

    private fun snapLiftToRest() {
        liftJob?.cancel()
        tintJob?.cancel()
        liftJob = scope.launchNow { lift.snapTo(0f) }
        tintJob = scope.launchNow { liftTint.snapTo(0f) }
        liftSection = null
    }

    // ── Exits (port sheet §5.5, critique 4) ───────────────────────────────

    /**
     * The Home layer's animated exit: the motion settles, a block still
     * lifted drops where it is with no kick, the press lets go and the list
     * folds back into the feed.
     */
    fun onExit(reason: HomeEditExitReason, press: HomeEditPressState) {
        motion.onExit(reason)
        dropLift(0f)
        press.release(scope, specs)
        list.close()
    }

    /** The Home layer's snap exit: motion, lift, press and list at rest now. */
    fun onSnapExit(press: HomeEditPressState) {
        motion.onSnapExit()
        snapLiftToRest()
        press.snapOff(scope)
        list.snapOff()
    }

    // ── The pinched list ──────────────────────────────────────────────────

    /** Edit mode's compact list: a pinch in shows it over the feed, a pinch out folds it back. */
    val list = HomeEditListMode(scope, specs, feedback)

    /**
     * The list covers the feed: its blocks and tray draw at alpha 0, so nothing
     * in them needs a frame (their wiggle stops reading the clock). Layer and draw only.
     */
    val feedHidden: Boolean get() = list.covers

    /** The edit header's switch between the list and the full layout: ignored while a block is lifted or dragged. */
    fun toggleList() {
        if (dragging != null || liftSection != null || resizeActive()) return
        list.toggle()
    }

    // ── Drag ──────────────────────────────────────────────────────────────

    /** The feed's rows. Set by Home while composed. */
    var host: HomeDragHost? = null

    /** The pinched list's rows. Set by Home while composed. */
    var listHost: HomeDragHost? = null

    /** A span, units or rows handle is being dragged: lifts and drags wait for it. Set by Home. */
    var resizeActive: () -> Boolean = { false }

    /** The block being dragged; null between drags. */
    var dragging: HomeSection? by mutableStateOf(null)
        private set

    /** The live arrangement while dragging, rendered in place of the draft's; null between drags. */
    var arrangement: DragArrangement? by mutableStateOf(null)
        private set

    /** The dropped block gliding from where it was let go into its slot. */
    var settling: HomeSection? by mutableStateOf(null)
        private set

    /** The settle's offset from the slot (px). Layer only. */
    val settleX = Animatable(0f)
    val settleY = Animatable(0f)

    /** The finger's x in Box px, set by the gesture before each event (two-column drops read it). */
    var fingerX: Float = Float.NaN

    /**
     * A drag was let go from outside (an exit, a resize) while its finger is
     * still down: the gesture swallows the rest of it. Cleared by the next drag.
     */
    var letGo: Boolean = false
        private set

    /** A drag is on: Undo and the other edits wait for its drop. */
    val isCarrying: Boolean get() = dragging != null

    // The finger in Box px: written by every move, read by the dragged block's layer alone.
    private var finger by mutableStateOf(Offset.Zero)

    // The finger's offset from the dragged block's top-left at the start.
    private var grab = Offset.Zero
    private var origin: DragArrangement? = null
    private var active: HomeDragHost? = null
    private var activeList by mutableStateOf(false)
    private var side: HomeDragLane? = null

    // How far the finger (or the scroll under it) went since the last change: a change waits for a
    // little of it, so a layout that moved under a resting finger never decides the next one alone.
    private var travel = 0f

    // The start-in-edge guard: the band the finger was lifted in (±1, 0 none), where, and how long it has rested there.
    private var edgeGuard = 0
    private var edgeGuardY = 0f
    private var edgeGuardMs = 0f
    private var frameJob: Job? = null
    private var settleJob: Job? = null
    private var pendingExit: HomeEditExitReason? = null

    private val travelPx = with(density) { HomeDragTokens.ChangeTravel.toPx() }
    private val edgePx = with(density) { HomeDragTokens.AutoScrollEdge.toPx() }
    private val minSpeedPx = with(density) { HomeDragTokens.AutoScrollMin.toPx() }
    private val maxSpeedPx = with(density) { HomeDragTokens.AutoScrollMax.toPx() }
    private val pushPx = with(density) { HomeDragTokens.EdgeStartPush.toPx() }
    private val bufferPx = with(density) { HomeDragRuleTokens.RowBuffer.toPx() }

    private var rowsFor: Pair<DragArrangement, Boolean>? = null
    private var rowsCache: List<HomeFeedRow> = emptyList()

    private fun rowsOf(arrangement: DragArrangement, twoColumn: Boolean): List<HomeFeedRow> {
        val key = arrangement to twoColumn
        if (rowsFor != key) {
            rowsCache = dragRows(arrangement.ids, arrangement.pairs, twoColumn)
            rowsFor = key
        }
        return rowsCache
    }

    /**
     * [section]'s slot is marked where the drop will land (rule 4): it is being
     * dragged, or gliding back into the slot, in the feed or ([inList]) the
     * pinched list. Draw only.
     */
    fun slotShown(section: HomeSection, inList: Boolean): Boolean =
        (dragging == section || settling == section) && inList == activeList

    /** [row] holds the dragged block. Read at placement. */
    fun carries(row: HomeFeedRow): Boolean = dragging?.let { it in row.sections() } == true

    /**
     * [section]'s offset from its laid-out place: under the finger while
     * dragged, gliding home while it settles. [inList]: the pinched list's
     * strip asks, not the feed's block. Layer only.
     */
    fun translation(section: HomeSection, inList: Boolean): Offset {
        if (inList != activeList) return Offset.Zero
        if (dragging == section) {
            val host = active ?: return Offset.Zero
            val live = arrangement ?: return Offset.Zero
            val at = layoutTopLeft(host, live, section) ?: return Offset.Zero
            return finger - grab - at
        }
        if (settling == section) return Offset(settleX.value, settleY.value)
        return Offset.Zero
    }

    /**
     * Starts dragging the lifted block with the finger at [fingerY] (and
     * [fingerX]). In two columns a half stays beside its partner: the finger
     * is in its own row.
     */
    fun start(fingerY: Float, uptimeMs: Long) {
        // A drag that doesn't start stays the gesture's: its release puts the lift down.
        letGo = false
        val dragged = liftSection ?: return
        if (dragging != null || resizeActive()) return
        val host = (if (list.on) listHost else host) ?: return
        val ids = host.order()
        if (dragged !in ids) return
        val twoColumn = host.twoColumn()
        val pairs = if (twoColumn) host.pairs().filter { (l, r) -> l in ids && r in ids } else emptyMap()
        val before = DragArrangement(ids, pairs)
        val at = layoutTopLeft(host, before, dragged) ?: return
        val lane = dragLaneOf(dragged, rowsOf(before, twoColumn))
        val x = fingerX.takeUnless { it.isNaN() } ?: at.x
        val shown = at + if (settling == dragged) Offset(settleX.value, settleY.value) else Offset.Zero
        settleJob?.cancel()
        settleJob = null
        settling = null
        scope.launchNow { settleX.snapTo(0f) }
        scope.launchNow { settleY.snapTo(0f) }
        active = host
        activeList = host.isList
        finger = Offset(x, fingerY)
        grab = finger - shown
        side = lane.takeIf { it != HomeDragLane.Full }
        origin = before
        travel = 0f
        pendingExit = null
        val area = host.scrollArea()
        edgeGuard = dragEdgeOf(fingerY, area.top, area.bottom, edgePx)
        edgeGuardY = fingerY
        edgeGuardMs = 0f
        arrangement = before
        dragging = dragged
        motion.carryActive = true
        motion.placeholdersAboveOpen = true
        frameJob?.cancel()
        frameJob = scope.launch { runFrames() }
    }

    /** The finger moved to [y] (and [fingerX]). The next frame decides on it. */
    fun move(y: Float, uptimeMs: Long) {
        if (dragging == null) return
        val next = Offset(if (fingerX.isNaN()) finger.x else fingerX, y)
        travel += (next - finger).getDistance()
        finger = next
    }

    /**
     * The finger lifted: a changed arrangement is written now, and the block
     * glides from where it was let go into its slot as the lift falls.
     * [cancelled] (the system took the finger): nothing is written, the
     * order goes back to how it was before the lift. A drag that never
     * started puts the lift down.
     */
    fun release(cancelled: Boolean, uptimeMs: Long) {
        if (dragging == null) {
            dropLift(0f)
            return
        }
        finish(keep = !cancelled, glide = true)
    }

    /**
     * Rotation, a new column count or a resize mid-drag (rule 5): the drag's
     * geometry is gone, so it rolls back to before the lift, nothing written.
     */
    fun cancel() {
        if (dragging == null) return
        letGo = true
        finish(keep = false, glide = false)
    }

    /**
     * An exit arrived mid-drag: the drag ends at once and the exit runs right
     * after. Done (and the app's own exits) write the live arrangement; Back
     * rolls it back to before the lift ([dragExitKeepsDrop]). With no drag
     * there is nothing to wait for.
     */
    fun deferExit(reason: HomeEditExitReason) {
        if (dragging == null) {
            finishDeferredExit(reason)
            return
        }
        pendingExit = reason
        letGo = true
        finish(keep = dragExitKeepsDrop(reason), glide = true)
    }

    /** A snap exit mid-drag: a changed arrangement writes now, then every drag value is at rest. */
    fun abortNow() {
        val live = arrangement
        if (dragging != null) {
            letGo = true
            if (live != null && live != origin) commit(live)
        }
        frameJob?.cancel()
        frameJob = null
        settleJob?.cancel()
        settleJob = null
        dragging = null
        arrangement = null
        settling = null
        side = null
        pendingExit = null
        scope.launchNow { settleX.snapTo(0f) }
        scope.launchNow { settleY.snapTo(0f) }
        snapLiftToRest()
        motion.carryActive = false
    }

    // Ends the drag: [keep] writes a changed arrangement, else the order before the lift comes back.
    // [glide]: the block glides from where it was let go into its slot.
    private fun finish(keep: Boolean, glide: Boolean) {
        val dragged = dragging ?: return
        val before = origin ?: DragArrangement(emptyList(), emptyMap())
        val live = arrangement ?: before
        val from = when {
            !glide -> Offset.Zero
            keep -> translation(dragged, activeList)
            // Back to its slot before the lift, as near as this layout tells.
            else -> active?.let { host -> layoutTopLeft(host, before, dragged)?.let { finger - grab - it } }
                ?: translation(dragged, activeList)
        }
        frameJob?.cancel()
        frameJob = null
        // Written before the live arrangement goes, so the feed never shows the order it started from.
        val changed = keep && live != before && commit(live)
        dragging = null
        arrangement = null
        side = null
        motion.carryActive = false
        settle(dragged, from, if (changed) before.ids.filterIndexed { index, s -> live.ids.getOrNull(index) != s } else emptyList())
        liftJob = scope.launchNow {
            lift.animateTo(0f, specs.liftDown)
            if (liftSection == dragged && dragging == null) liftSection = null
        }
        tintJob = scope.launchNow { liftTint.animateTo(0f, specs.liftTint) }
        pendingExit?.let { reason ->
            pendingExit = null
            finishDeferredExit(reason)
        }
    }

    // One decision and the edge scroll per frame, against the layout of the frame before.
    private suspend fun runFrames() {
        var last = -1L
        while (dragging != null) {
            withFrameNanos { now ->
                val dt = if (last < 0L) 0f else ((now - last) / 1e9f).coerceAtMost(MaxFrameSeconds)
                last = now
                frame(dt)
            }
        }
    }

    private fun frame(dt: Float) {
        val host = active ?: return
        val dragged = dragging ?: return
        val live = arrangement ?: return
        val twoColumn = host.twoColumn()
        val rows = rowsOf(live, twoColumn)
        val k = dragRowIndexOf(dragged, rows)
        if (k < 0) return
        val visible = host.visibleRows()
        if (travel >= travelPx && dragLayoutFollows(visible.map { it.key }, rows, rows[k].first)) {
            val step = decide(host, dragged, live, rows, k, visible, twoColumn)
            if (step != null) {
                // A frame that reorders doesn't scroll as well: the held position is this layout's.
                host.holdScroll()
                if (twoColumn) rescaleGrab(host, dragged, rows, rowsOf(step.arrangement, twoColumn))
                arrangement = step.arrangement
                side = step.side
                travel = 0f
                feedback.segmentTick()
                return
            }
        }
        val area = host.scrollArea()
        if (edgeGuard != 0) {
            edgeGuardMs += dt * 1000f
            val inBand = dragEdgeOf(finger.y, area.top, area.bottom, edgePx) == edgeGuard
            val holds = dragEdgeGuardHolds(edgeGuard, edgeGuardY, finger.y, edgeGuardMs, pushPx, HomeDragTokens.EdgeStartDwellMs)
            if (!inBand || !holds) edgeGuard = 0 else return
        }
        val speed = dragAutoScrollSpeed(finger.y, area.top, area.bottom, edgePx, minSpeedPx, maxSpeedPx)
        if (speed != 0f && dt > 0f) travel += abs(host.scrollBy(speed * dt))
    }

    // Rule 4: the dragged block's slot turned half or full: the finger keeps its place across the block.
    private fun rescaleGrab(host: HomeDragHost, dragged: HomeSection, rows: List<HomeFeedRow>, next: List<HomeFeedRow>) {
        val width = host.contentWidth()
        val gap = host.columnGap()
        val old = dragLaneWidth(dragLaneOf(dragged, rows), width, gap)
        val new = dragLaneWidth(dragLaneOf(dragged, next), width, gap)
        if (old != new) grab = Offset(dragRescaleGrabX(grab.x, old, new), grab.y)
    }

    private fun decide(
        host: HomeDragHost,
        dragged: HomeSection,
        live: DragArrangement,
        rows: List<HomeFeedRow>,
        k: Int,
        visible: List<DragItemBand>,
        twoColumn: Boolean,
    ): DragSideStep.Arrange? {
        val bands = visible.mapNotNull { item ->
            rows.firstOrNull { it.first == item.key }?.let { DragRowBand(it, item.top, item.bottom) }
        }
        if (twoColumn) {
            val rel = dragRel(finger.x, host.contentLeft(), host.contentWidth())
            when (val step = dragSideStep(live.ids, live.pairs, dragged, side, rel, finger.y, bands, bufferPx)) {
                is DragSideStep.Arrange -> return step.takeIf { it.arrangement != live || it.side != side }
                DragSideStep.Stay -> return null
                DragSideStep.Vertical -> Unit
            }
        }
        val slot = bands.firstOrNull { it.row == rows[k] } ?: return null
        val prev = rows.getOrNull(k - 1)?.let { row -> bands.firstOrNull { it.row == row } }
        val next = rows.getOrNull(k + 1)?.let { row -> bands.firstOrNull { it.row == row } }
        val dir = dragSwapDir(finger.y - grab.y, slot, prev, next)
        if (dir == 0) return null
        val ids = dragSwapRow(live.ids, live.pairs, dragged, dir, twoColumn)
        return DragSideStep.Arrange(DragArrangement(ids, live.pairs), side = null)
    }

    // The dropped block glides home, then it and the blocks it [moved] are kicked.
    private fun settle(section: HomeSection, from: Offset, moved: List<HomeSection>) {
        settling = section
        settleJob = scope.launchNow {
            settleX.snapTo(from.x)
            settleY.snapTo(from.y)
            coroutineScope {
                launch { settleX.animateTo(0f, specs.settlePx) }
                settleY.animateTo(0f, specs.settlePx)
            }
            if (settling == section) settling = null
            motion.touch()
            motion.impulse(section, HomeEditTokens.KickDrop)
            moved.forEach { if (it != section) motion.impulse(it, HomeEditTokens.KickNeighbour) }
        }
    }

    private fun commit(arrangement: DragArrangement): Boolean {
        val write = commitArrangement
        val twoColumn = active?.twoColumn() == true
        return if (twoColumn && write != null) write(arrangement.ids, arrangement.pairs) else commitOrder(arrangement.ids)
    }

    // [section]'s laid-out top-left in Box px under [arrangement]: its row item's top, its lane's left.
    private fun layoutTopLeft(host: HomeDragHost, arrangement: DragArrangement, section: HomeSection): Offset? {
        val rows = rowsOf(arrangement, host.twoColumn())
        val row = rows.firstOrNull { section in it.sections() } ?: return null
        val band = host.rowBand(row.first) ?: return null
        val left = host.contentLeft()
        val x = when (dragLaneOf(section, rows)) {
            HomeDragLane.Full, HomeDragLane.Left -> left
            HomeDragLane.Right -> left + (host.contentWidth() - host.columnGap()) / 2f + host.columnGap()
        }
        return Offset(x, band.top)
    }

    private companion object {
        // A frame after a stall scrolls no more than this far in time.
        const val MaxFrameSeconds = 1f / 24f
    }
}

/** Numbers of the drag. */
internal object HomeDragTokens {
    /** The list scrolls while the finger is this close to the bar (or the status tide)… */
    val AutoScrollEdge = 72.dp

    /** …from this many dp/s where the band starts… */
    val AutoScrollMin = 120.dp

    /** …easing in (depth squared) to this many at the edge. */
    val AutoScrollMax = 900.dp

    /** Lifted with the finger already in a band, it scrolls once the finger pushes this much further toward the edge… */
    val EdgeStartPush = 8.dp

    /** …or rests there this long. */
    const val EdgeStartDwellMs = 180f

    /** The drop slot's frame: its stroke, over the lifted plate colour at [SlotFillAlpha]. */
    val SlotStroke = 2.dp
    const val SlotFillAlpha = .55f

    /** A change waits for this much finger (or scroll) travel since the last one. */
    val ChangeTravel = 12.dp
}

/** [this] layout with a drag's live [arrangement]: how the controller will write it on the drop. */
internal fun HomeLayout.withDrag(
    arrangement: DragArrangement,
    twoColumn: Boolean,
    unseen: Set<HomeSection>,
): HomeLayout = if (twoColumn) {
    withArrangement(arrangement.ids, arrangement.pairs, unseen)
} else {
    withEnabledOrder(arrangement.ids, unseen)
}

/**
 * Rule 4: a light frame where the dragged [section] will land, at its slot's
 * width and place, while the block itself follows the finger (and as it
 * glides back in). [fill] is the lifted plate colour, [stroke] the frame's.
 * Put it before the block's own layer, so it stays in the slot. Draw only.
 */
internal fun Modifier.dragSlotMark(
    engine: HomeDragEngine,
    section: HomeSection,
    inList: Boolean,
    fill: Color,
    stroke: Color,
): Modifier = drawBehind {
    if (!engine.slotShown(section, inList)) return@drawBehind
    val radius = HomeEditTokens.PlateRadius.toPx()
    drawRoundRect(fill, cornerRadius = CornerRadius(radius), alpha = HomeDragTokens.SlotFillAlpha)
    val w = HomeDragTokens.SlotStroke.toPx()
    drawRoundRect(
        color = stroke,
        topLeft = Offset(w / 2f, w / 2f),
        size = Size((size.width - w).coerceAtLeast(0f), (size.height - w).coerceAtLeast(0f)),
        cornerRadius = CornerRadius((radius - w / 2f).coerceAtLeast(0f)),
        style = Stroke(w),
    )
}

/** A `zIndex` read in the placement pass, so a change re-places the item without recomposing it. */
internal fun Modifier.dragZIndex(zIndex: () -> Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) { placeable.place(0, 0, zIndex = zIndex()) }
}

// ── Host ──────────────────────────────────────────────────────────────────

/**
 * [HomeDragHost] over a LazyColumn at the page Box's origin whose section
 * rows are keyed [homeSectionItemKey] as [rowKeys] assigns (by their first
 * section, or the key a swapped pair kept): the feed, or the pinched list ([isList]).
 */
@Stable
internal class LazyListDragHost(
    private val listState: LazyListState,
    private val order: () -> List<HomeSection>,
    /** Two columns: the pairs laid out side by side, left → right; null in one column. */
    private val laidOutPairs: () -> Map<HomeSection, HomeSection>?,
    private val contentLeft: () -> Float,
    private val contentWidth: () -> Float,
    private val columnGapPx: Float,
    private val scrollArea: () -> HomeEditSafeArea,
    override val isList: Boolean = false,
    /** The rows' item keys, as last composed. */
    private val rowKeys: () -> HomeRowKeys = { HomeRowKeys.Initial },
) : HomeDragHost {
    override fun order(): List<HomeSection> = order.invoke()

    override fun twoColumn(): Boolean = laidOutPairs() != null

    override fun pairs(): Map<HomeSection, HomeSection> = laidOutPairs().orEmpty()

    override fun visibleRows(): List<DragItemBand> {
        val info = listState.layoutInfo
        val keys = rowKeys()
        return info.visibleItemsInfo.mapNotNull { item ->
            val section = keys.firstOf(item.key) ?: return@mapNotNull null
            val top = (item.offset - info.viewportStartOffset).toFloat()
            DragItemBand(section, top, top + item.size)
        }
    }

    override fun rowBand(key: HomeSection): DragItemBand? {
        val info = listState.layoutInfo
        val keys = rowKeys()
        val item = info.visibleItemsInfo.firstOrNull { keys.firstOf(it.key) == key } ?: return null
        val top = (item.offset - info.viewportStartOffset).toFloat()
        return DragItemBand(key, top, top + item.size)
    }

    override fun contentLeft(): Float = contentLeft.invoke()

    override fun contentWidth(): Float = contentWidth.invoke()

    override fun columnGap(): Float = columnGapPx

    override fun scrollArea(): HomeEditSafeArea = scrollArea.invoke()

    override fun scrollBy(px: Float): Float = listState.dispatchRawDelta(px)

    override fun holdScroll() {
        listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
    }
}

/** A [LazyListDragHost] that reads the latest of its lambdas. Hand it to the engine as `host` or `listHost`. */
@Composable
internal fun rememberLazyListDragHost(
    listState: LazyListState,
    order: () -> List<HomeSection>,
    pairs: () -> Map<HomeSection, HomeSection>?,
    contentLeft: () -> Float,
    contentWidth: () -> Float,
    columnGapPx: Float,
    scrollArea: () -> HomeEditSafeArea,
    isList: Boolean = false,
    rowKeys: () -> HomeRowKeys = { HomeRowKeys.Initial },
): LazyListDragHost {
    val currentOrder by rememberUpdatedState(order)
    val currentPairs by rememberUpdatedState(pairs)
    val currentLeft by rememberUpdatedState(contentLeft)
    val currentWidth by rememberUpdatedState(contentWidth)
    val currentArea by rememberUpdatedState(scrollArea)
    val currentRowKeys by rememberUpdatedState(rowKeys)
    return remember(listState, columnGapPx, isList) {
        LazyListDragHost(
            listState = listState,
            order = { currentOrder() },
            laidOutPairs = { currentPairs() },
            contentLeft = { currentLeft() },
            contentWidth = { currentWidth() },
            columnGapPx = columnGapPx,
            scrollArea = { currentArea() },
            isList = isList,
            rowKeys = { currentRowKeys() },
        )
    }
}

/** The item key of [section]'s row (the row it is the first of, unless [HomeRowKeys] kept another), in the feed and in the pinched list. */
internal fun homeSectionItemKey(section: HomeSection): String = SectionKeyPrefix + section.id

/** The section a row item key belongs to, or null for the header, tray and footer. */
internal fun homeSectionOfKey(key: Any): HomeSection? {
    val id = (key as? String)?.takeIf { it.startsWith(SectionKeyPrefix) }?.removePrefix(SectionKeyPrefix) ?: return null
    return HomeSection.fromId(id)
}

/**
 * The item key each feed row carries: [homeSectionItemKey] of its first
 * section, except that a row made of the same sections as before keeps the
 * key it had. A pair whose halves trade sides changes its first section, and
 * a new key would make the LazyColumn dispose the row and compose both of
 * its sections anew (covers re-requested, every text measured again: one
 * ~170ms frame on the tablet, each time a drag swaps a pair). With the key
 * kept, the cells just move inside the one item. Read the items back through
 * [firstOf], never by parsing keys: an item's key section may be its right half.
 */
@Immutable
internal class HomeRowKeys private constructor(
    /** Row first section → the section whose key the row's item carries. */
    private val byFirst: Map<HomeSection, HomeSection>,
    private val bySections: Map<Set<HomeSection>, HomeSection>,
) {
    /** The item key of the row [first] is the first section of. */
    fun itemKey(first: HomeSection): String = homeSectionItemKey(byFirst[first] ?: first)

    /** The first section of the row whose item is keyed [key]; null for the header, tray and footer. */
    fun firstOf(key: Any): HomeSection? {
        val keyed = homeSectionOfKey(key) ?: return null
        if (byFirst[keyed] == keyed) return keyed
        return byFirst.entries.firstOrNull { it.value == keyed }?.key ?: keyed
    }

    /** The keys of [rows]: a row of the same sections as one here keeps its key, any other its first's. Pure. */
    fun next(rows: List<HomeFeedRow>): HomeRowKeys {
        val byFirst = HashMap<HomeSection, HomeSection>(rows.size)
        val bySections = HashMap<Set<HomeSection>, HomeSection>(rows.size)
        rows.forEach { row ->
            val sections = row.sections().toSet()
            val keyed = this.bySections[sections] ?: row.first
            byFirst[row.first] = keyed
            bySections[sections] = keyed
        }
        return HomeRowKeys(byFirst, bySections)
    }

    companion object {
        /** No rows yet: every row is keyed by its first section. */
        val Initial = HomeRowKeys(emptyMap(), emptyMap())
    }
}

/** [rows]' item keys, each row keeping its key for as long as it holds the same sections ([HomeRowKeys]). */
@Composable
internal fun rememberHomeRowKeys(rows: List<HomeFeedRow>): HomeRowKeys {
    // The last assignment, a plain holder: the next rows are matched against it, nothing observes it.
    val last = remember { arrayOf(HomeRowKeys.Initial) }
    return remember(rows) { last[0].next(rows).also { last[0] = it } }
}

private const val SectionKeyPrefix = "section-"
