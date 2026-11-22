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
import androidx.compose.ui.geometry.Offset
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

    // ── Drag ──────────────────────────────────────────────────────────────

    /** The feed's rows. Set by Home while composed. */
    var host: HomeDragHost? = null

    /** The pinched list's rows. Set by Home while composed. */
    var listHost: HomeDragHost? = null

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
    private var frameJob: Job? = null
    private var settleJob: Job? = null
    private var pendingExit: HomeEditExitReason? = null

    private val travelPx = with(density) { HomeDragTokens.ChangeTravel.toPx() }
    private val edgePx = with(density) { HomeDragTokens.AutoScrollEdge.toPx() }
    private val maxSpeedPx = with(density) { HomeDragTokens.AutoScrollMax.toPx() }

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
     * [fingerX]). In two columns a half stays beside its partner while the
     * finger is still on its own side.
     */
    fun start(fingerY: Float, uptimeMs: Long) {
        // A drag that doesn't start stays the gesture's: its release puts the lift down.
        letGo = false
        val dragged = liftSection ?: return
        if (dragging != null) return
        val host = (if (list.on) listHost else host) ?: return
        val ids = host.order()
        if (dragged !in ids) return
        val twoColumn = host.twoColumn()
        val pairs = if (twoColumn) host.pairs().filter { (l, r) -> l in ids && r in ids } else emptyMap()
        val before = DragArrangement(ids, pairs)
        val at = layoutTopLeft(host, before, dragged) ?: return
        val lane = dragLaneOf(dragged, rowsOf(before, twoColumn))
        val x = fingerX.takeUnless { it.isNaN() } ?: at.x
        val zone = if (twoColumn) {
            dragEdgeZone(x, host.contentLeft(), host.contentWidth(), lane.takeIf { it != HomeDragLane.Full })
        } else {
            null
        }
        val keep = lane != HomeDragLane.Full && zone == lane
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
        side = if (keep) lane else null
        origin = before
        travel = 0f
        pendingExit = null
        val live = if (keep || lane == HomeDragLane.Full) before else DragArrangement(ids, dragUnpair(pairs, dragged))
        if (live != before) host.holdScroll()
        arrangement = live
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
     * The finger lifted (or the drag was cancelled): a changed arrangement
     * is written now, and the block glides from where it was let go into its
     * slot as the lift falls. A drag that never started puts the lift down.
     */
    fun release(cancelled: Boolean, uptimeMs: Long) {
        val dragged = dragging
        if (dragged == null) {
            dropLift(0f)
            return
        }
        val before = origin ?: DragArrangement(emptyList(), emptyMap())
        val live = arrangement ?: before
        val from = translation(dragged, activeList)
        frameJob?.cancel()
        frameJob = null
        // Written before the live arrangement goes, so the feed never shows the order it started from.
        val changed = live != before && commit(live)
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

    /** A new width class or a resize mid-drag: let go where it is. */
    fun cancel() {
        if (dragging == null) return
        letGo = true
        release(cancelled = true, uptimeMs = uptimeMs())
    }

    /**
     * An exit arrived mid-drag: the drag is released as cancelled (a changed
     * arrangement still writes) and the exit runs right after. With no drag
     * there is nothing to wait for.
     */
    fun deferExit(reason: HomeEditExitReason) {
        if (dragging == null) {
            finishDeferredExit(reason)
            return
        }
        pendingExit = reason
        letGo = true
        release(cancelled = true, uptimeMs = uptimeMs())
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
                arrangement = step.arrangement
                side = step.side
                travel = 0f
                feedback.segmentTick()
                return
            }
        }
        val area = host.scrollArea()
        val speed = dragAutoScrollSpeed(finger.y, area.top, area.bottom, edgePx, maxSpeedPx)
        if (speed != 0f && dt > 0f) travel += abs(host.scrollBy(speed * dt))
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
            val zone = dragEdgeZone(finger.x, host.contentLeft(), host.contentWidth(), side)
            when (val step = dragSideStep(live.ids, live.pairs, dragged, side, zone, finger.y, bands)) {
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

    /** …faster the closer it gets, up to this many dp/s at the edge. */
    val AutoScrollMax = 1400.dp

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

/** A `zIndex` read in the placement pass, so a change re-places the item without recomposing it. */
internal fun Modifier.dragZIndex(zIndex: () -> Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) { placeable.place(0, 0, zIndex = zIndex()) }
}

// ── Host ──────────────────────────────────────────────────────────────────

/**
 * [HomeDragHost] over a LazyColumn at the page Box's origin whose section
 * rows are keyed [homeSectionItemKey] by their first section: the feed, or
 * the pinched list ([isList]).
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
) : HomeDragHost {
    override fun order(): List<HomeSection> = order.invoke()

    override fun twoColumn(): Boolean = laidOutPairs() != null

    override fun pairs(): Map<HomeSection, HomeSection> = laidOutPairs().orEmpty()

    override fun visibleRows(): List<DragItemBand> {
        val info = listState.layoutInfo
        return info.visibleItemsInfo.mapNotNull { item ->
            val section = homeSectionOfKey(item.key) ?: return@mapNotNull null
            val top = (item.offset - info.viewportStartOffset).toFloat()
            DragItemBand(section, top, top + item.size)
        }
    }

    override fun rowBand(key: HomeSection): DragItemBand? {
        val info = listState.layoutInfo
        val itemKey = homeSectionItemKey(key)
        val item = info.visibleItemsInfo.firstOrNull { it.key == itemKey } ?: return null
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
): LazyListDragHost {
    val currentOrder by rememberUpdatedState(order)
    val currentPairs by rememberUpdatedState(pairs)
    val currentLeft by rememberUpdatedState(contentLeft)
    val currentWidth by rememberUpdatedState(contentWidth)
    val currentArea by rememberUpdatedState(scrollArea)
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
        )
    }
}

/** The item key of [section]'s row (the row it is the first of), in the feed and in the pinched list. */
internal fun homeSectionItemKey(section: HomeSection): String = SectionKeyPrefix + section.id

/** The section a row item key belongs to, or null for the header, tray and footer. */
internal fun homeSectionOfKey(key: Any): HomeSection? {
    val id = (key as? String)?.takeIf { it.startsWith(SectionKeyPrefix) }?.removePrefix(SectionKeyPrefix) ?: return null
    return HomeSection.fromId(id)
}

private const val SectionKeyPrefix = "section-"
