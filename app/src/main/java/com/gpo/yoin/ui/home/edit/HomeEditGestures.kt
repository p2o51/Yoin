package com.gpo.yoin.ui.home.edit

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputEventHandler
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.Abandon
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.BlockTap
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.CarryMove
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.CarryRelease
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.ConsumeOther
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.ConsumeRest
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.DragStartHaptic
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.DropLift
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.Enter
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.ExitBlank
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.LatchPlate
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.Lift
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.LongPressHaptic
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.OpenPlaceholders
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.PinchBy
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.PinchEnd
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.PinchStart
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.PointerDown
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.ReleaseCharge
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.StartCarry
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.StartCharge
import com.gpo.yoin.ui.home.edit.HomeEditGestureAction.Touch
import com.gpo.yoin.ui.home.edit.HomeEditGestureEvent.CarryEnded
import com.gpo.yoin.ui.home.edit.HomeEditGestureEvent.EditEnded
import com.gpo.yoin.ui.home.edit.HomeEditGestureEvent.OtherPointer
import com.gpo.yoin.ui.home.edit.HomeEditGestureEvent.PinchDown
import com.gpo.yoin.ui.home.edit.HomeEditGestureEvent.PinchMove
import com.gpo.yoin.ui.home.edit.HomeEditGestureEvent.PinchUp
import com.gpo.yoin.ui.home.edit.HomeEditGestureState.Blank
import com.gpo.yoin.ui.home.edit.HomeEditGestureState.Carry
import com.gpo.yoin.ui.home.edit.HomeEditGestureState.Done
import com.gpo.yoin.ui.home.edit.HomeEditGestureState.HandleDown
import com.gpo.yoin.ui.home.edit.HomeEditGestureState.Holding
import com.gpo.yoin.ui.home.edit.HomeEditGestureState.Idle
import com.gpo.yoin.ui.home.edit.HomeEditGestureState.Lifted
import com.gpo.yoin.ui.home.edit.HomeEditGestureState.Passive
import com.gpo.yoin.ui.home.edit.HomeEditGestureState.Pinch
import com.gpo.yoin.ui.home.edit.HomeEditGestureState.Pressing
import kotlinx.coroutines.CoroutineScope

// Home edit mode's one gesture detector (port sheet §2.1, spec §2.1): a pure
// state machine, the executor that applies its actions, the page hit tester,
// and the Initial-pass pointer modifier on Home's root Box that drives them.
// It drives the feed and the pinched list alike (owner 2026-11-22): a second
// finger turns an edit-mode press into a pinch, in or out of the list.

// ── Hit testing ───────────────────────────────────────────────────────────

/** What a press lands on, resolved once at pointer-down. Positions are Box px. */
internal sealed interface HomeEditHit {
    /** A control the gestures leave alone: header icons, badges, the tray, the footer. */
    data object Excluded : HomeEditHit

    /** [section]'s drag handle (edit mode only). [local] and [size] as for [Block]. */
    data class Handle(val section: HomeSection, val local: Offset, val size: Size = Size.Zero) : HomeEditHit

    /** Inside [section]'s band. [local] is in block-content px; [size] is the block content's. */
    data class Block(val section: HomeSection, val local: Offset, val size: Size = Size.Zero) : HomeEditHit

    /** Off every block. [nearest] is the closest visible section, the entry ripple's origin. */
    data class Blank(val nearest: HomeSection?) : HomeEditHit
}

/** Resolves a press at pointer-down: the feed's [HomeEditHitTester], or the pinched list's while it shows. */
internal fun interface HomeEditHitTest {
    fun hitTest(positionInBox: Offset): HomeEditHit
}

/**
 * Resolves presses against the feed (spec §2.1.2): exclusions first, then a
 * drag handle, then the section bands of the list's visible items, else
 * blank. Everything is read at pointer-down only. Sections fading out after
 * Hide ([isHiding]) are blank: they are leaving and take no lift (critique 17).
 */
internal class HomeEditHitTester(
    private val listState: LazyListState,
    private val targets: HomeEditTargets,
    /** The feed's content x-range in Box px (the frame's live margins). */
    private val contentBounds: () -> ClosedFloatingPointRange<Float>,
    /** The plate outset (horizontal, vertical) in px; part of the block while editing. */
    private val plateOutsetPx: () -> Size,
    private val editing: () -> Boolean,
    private val isHiding: (HomeSection) -> Boolean = { false },
    /** Two columns: the pairs laid out side by side (left → right) and the gap between them, px. */
    private val pairs: () -> Map<HomeSection, HomeSection> = { emptyMap() },
    private val columnGapPx: Float = 0f,
    /** The rows' item keys, as last composed. */
    private val rowKeys: () -> HomeRowKeys = { HomeRowKeys.Initial },
) : HomeEditHitTest {
    override fun hitTest(positionInBox: Offset): HomeEditHit {
        if (targets.isExcluded(positionInBox)) return HomeEditHit.Excluded
        val layoutInfo = listState.layoutInfo
        val content = contentBounds()
        val bands = splitPairBands(
            homeEditSectionBands(layoutInfo.visibleItemsInfo, layoutInfo.viewportStartOffset, rowKeys(), isHiding),
            pairs(),
            content.start,
            content.endInclusive,
            columnGapPx,
        )
        val editing = editing()
        return resolveHomeEditHit(
            position = positionInBox,
            bands = bands,
            contentLeft = content.start,
            contentRight = content.endInclusive,
            outset = plateOutsetPx(),
            editing = editing,
            handle = if (editing) targets.handleAt(positionInBox)?.takeUnless(isHiding) else null,
        )
    }
}

/**
 * The bands of the visible `section-<id>` items (each its row's first
 * section, through [rowKeys]), top to bottom, in Box px
 * (the list sits at the Box origin): top = offset − viewportStartOffset.
 * Other items (header, tray, footer) are not sections.
 */
internal fun homeEditSectionBands(
    items: List<LazyListItemInfo>,
    viewportStartOffset: Int,
    rowKeys: HomeRowKeys = HomeRowKeys.Initial,
    isHiding: (HomeSection) -> Boolean = { false },
): List<SectionBand> = items.mapNotNull { info ->
    // The row's first section: a swapped pair's item keeps the key of its right half (HomeRowKeys).
    val section = rowKeys.firstOf(info.key) ?: return@mapNotNull null
    if (isHiding(section)) return@mapNotNull null
    val top = (info.offset - viewportStartOffset).toFloat()
    SectionBand(section, top, top + info.size, bleeds = section in BleedingSections)
}

/**
 * The hit at [position] once exclusions are ruled out: [handle] wins (edit
 * mode), then the band under the finger (plate outsets count while
 * [editing]; bleeding shelves take the margins), else blank with the
 * nearest band.
 */
internal fun resolveHomeEditHit(
    position: Offset,
    bands: List<SectionBand>,
    contentLeft: Float,
    contentRight: Float,
    outset: Size,
    editing: Boolean,
    handle: HomeSection? = null,
): HomeEditHit {
    fun SectionBand.block(): HomeEditHit.Block {
        val left = left ?: contentLeft
        val right = right ?: contentRight
        return HomeEditHit.Block(
            section = id,
            local = Offset(position.x - left, position.y - top),
            size = Size((right - left).coerceAtLeast(0f), bottom - top),
        )
    }
    if (handle != null) {
        bands.firstOrNull { it.id == handle }?.block()?.let { return HomeEditHit.Handle(handle, it.local, it.size) }
    }
    val band = resolveSectionAt(
        x = position.x,
        y = position.y,
        bands = bands,
        contentLeft = contentLeft,
        contentRight = contentRight,
        outsetH = outset.width,
        outsetV = outset.height,
        editing = editing,
    )
    if (band != null) return band.block()
    return HomeEditHit.Blank(bands.getOrNull(nearestSectionIndex(position.y, bands))?.id)
}

// ── Gesture machine ───────────────────────────────────────────────────────

/** Where the tracked finger is in the gesture (port sheet §2.1). */
internal enum class HomeEditGestureState {
    /** No finger tracked. */
    Idle,

    /** Normal mode before the long-press threshold: watching only, so taps and scrolls go through. */
    Pressing,

    /** A block is lifted under the finger; a slop from the down point starts the carry. */
    Lifted,

    /** Edit mode on a block's body (or plate outset): lifts after the body hold. */
    Holding,

    /** Edit mode on a drag handle: the first slop lifts and carries at once. */
    HandleDown,

    /** Edit mode off every plate: a tap exits. */
    Blank,

    /** The finger drags the lifted block. */
    Carry,

    /** A second finger came down in edit mode: the two pinch the list in or out. */
    Pinch,

    /** The rest of the finger is swallowed. */
    Done,

    /** An edit-mode press given up to the list (a scroll); watched only for its up. */
    Passive,
}

internal enum class HomeEditTimer {
    /** T/2: the press charge starts (blocks only). */
    HalfT,

    /** T: enter edit mode. */
    LongPress,

    /** Edit mode, max(150ms, T/2) on a block body: lift. */
    BodyHold,

    /** Edit mode, T on blank: its up no longer exits. */
    BlankHold,
}

/** A pending timer and its uptime deadline. */
@Immutable
internal data class HomeEditTimerAt(val kind: HomeEditTimer, val atMs: Long)

/** Input to [HomeEditGestureMachine]. Times are pointer-event uptimes (ms). */
internal sealed interface HomeEditGestureEvent {
    /** The tracked finger went down. */
    data class Down(
        val hit: HomeEditHit,
        val position: Offset,
        val editing: Boolean,
        val flingInProgress: Boolean,
        val t: Long,
        /** A mouse or trackpad secondary-button press (desktop windowing). */
        val secondaryClick: Boolean = false,
    ) : HomeEditGestureEvent

    data class Move(val position: Offset, val t: Long) : HomeEditGestureEvent

    data class Up(val t: Long) : HomeEditGestureEvent

    /** The system or an ancestor took the finger. */
    data class Cancel(val t: Long) : HomeEditGestureEvent

    data class Timer(val kind: HomeEditTimer) : HomeEditGestureEvent

    /** Edit mode ended under the finger (critique 4). */
    data object EditEnded : HomeEditGestureEvent

    /** The drag was let go from outside: an exit or a resize dropped it (proto forceDrop). */
    data object CarryEnded : HomeEditGestureEvent

    /** A change of any other finger. */
    data object OtherPointer : HomeEditGestureEvent

    /** A second finger went down [span] px from the tracked one. */
    data class PinchDown(val span: Float) : HomeEditGestureEvent

    /** A finger of the pinch moved: the two are [span] px apart. */
    data class PinchMove(val span: Float) : HomeEditGestureEvent

    /** The pinch's second finger lifted. */
    data object PinchUp : HomeEditGestureEvent
}

/** Output of [HomeEditGestureMachine], applied in order by [HomeEditGestureExecutor]. */
internal sealed interface HomeEditGestureAction {
    /** The press is given up: taps, clicks and scrolls go to the page. */
    data object Abandon : HomeEditGestureAction

    data class StartCharge(val section: HomeSection, val local: Offset) : HomeEditGestureAction

    data object ReleaseCharge : HomeEditGestureAction

    data object LongPressHaptic : HomeEditGestureAction

    data object DragStartHaptic : HomeEditGestureAction

    data class Enter(val origin: HomeSection?, val lifted: Boolean) : HomeEditGestureAction

    /** The entry plate's start, from the charge read before it is released. */
    data class LatchPlate(val section: HomeSection, val local: Offset, val size: Size) : HomeEditGestureAction

    data class Lift(val section: HomeSection, val local: Offset) : HomeEditGestureAction

    data class DropLift(val kick: Float) : HomeEditGestureAction

    /** Placeholders held back above the lifted block may come in (spec §2.1.4-7). */
    data object OpenPlaceholders : HomeEditGestureAction

    data class StartCarry(val y: Float, val t: Long) : HomeEditGestureAction

    data class CarryMove(val y: Float, val t: Long) : HomeEditGestureAction

    data class CarryRelease(val cancelled: Boolean, val t: Long) : HomeEditGestureAction

    /** Two fingers down in edit mode: the list follows their span from here. */
    data object PinchStart : HomeEditGestureAction

    /** The fingers' span over the one they started at. */
    data class PinchBy(val scale: Float) : HomeEditGestureAction

    /** The pinch let go: the list lands where it was left. */
    data object PinchEnd : HomeEditGestureAction

    /** An edit-mode tap: kick the card at [local] (or the block, for null or no card) and pulse the handle. */
    data class BlockTap(val section: HomeSection, val local: Offset?) : HomeEditGestureAction

    data object ExitBlank : HomeEditGestureAction

    /** Sway envelope back up, idle window restarted. */
    data object Touch : HomeEditGestureAction

    data class PointerDown(val down: Boolean) : HomeEditGestureAction

    /** Consume this change and every later one of the finger. */
    data object ConsumeRest : HomeEditGestureAction

    /** Consume the other finger's change: it is ignored, but must not scroll the list (spec ⑫). */
    data object ConsumeOther : HomeEditGestureAction
}

/**
 * The detector as a pure machine (port sheet §2.1, spec §2.1.2, §2.6): fed
 * one finger's events and its timers, it answers with actions. In normal mode
 * it only watches until the long-press threshold, so a tap still opens the
 * card and a slop still scrolls; from the threshold on the finger is owned.
 * In edit mode a body hold or a handle drag lifts, a tap kicks, a blank
 * tap exits, and a second finger before any lift pinches the list in or out.
 * [longPressMs] is T, [slopPx] the touch slop.
 */
internal class HomeEditGestureMachine(private val longPressMs: Long, private val slopPx: Float) {
    var state: HomeEditGestureState = Idle
        private set

    private val bodyHoldMs = maxOf(HomeEditTokens.BodyHoldMinMs, longPressMs / 2)
    private val timers = ArrayList<HomeEditTimerAt>(2)

    private var downAt = Offset.Zero
    private var section: HomeSection? = null
    private var local = Offset.Zero
    private var size = Size.Zero
    private var nearest: HomeSection? = null

    // The finger belongs to an edit session: its up restarts the idle window and opens placeholders.
    private var editGesture = false
    private var charging = false
    private var flingStop = false
    private var blankHeld = false
    private var pinchSpan = 0f

    /** The next timer to fire, or null. */
    val pendingTimer: HomeEditTimerAt? get() = timers.minByOrNull { it.atMs }

    fun handle(event: HomeEditGestureEvent): List<HomeEditGestureAction> = when (event) {
        is HomeEditGestureEvent.Down -> down(event)
        is HomeEditGestureEvent.Move -> move(event)
        is HomeEditGestureEvent.Up -> up(event.t)
        is HomeEditGestureEvent.Cancel -> cancel(event.t)
        is HomeEditGestureEvent.Timer -> timer(event.kind)
        EditEnded -> editEnded()
        CarryEnded -> carryEnded()
        OtherPointer -> otherPointer()
        is PinchDown -> pinchDown(event.span)
        is PinchMove -> if (state == Pinch && pinchSpan > 0f) listOf(PinchBy(event.span / pinchSpan)) else emptyList()
        PinchUp -> if (state == Pinch) {
            state = Done
            listOf(PinchEnd)
        } else {
            emptyList()
        }
    }

    private fun otherPointer(): List<HomeEditGestureAction> =
        if (state != Idle && state != Passive) listOf(ConsumeOther) else emptyList()

    // Before a lift, an edit-mode press (a scroll included) becomes a pinch; otherwise the finger is ignored.
    private fun pinchDown(span: Float): List<HomeEditGestureAction> {
        val canPinch = editGesture && span > 0f && (state == Holding || state == HandleDown || state == Blank || state == Passive)
        if (!canPinch) return otherPointer()
        timers.clear()
        pinchSpan = span
        state = Pinch
        return listOf(PinchStart, ConsumeRest, ConsumeOther)
    }

    private fun down(event: HomeEditGestureEvent.Down): List<HomeEditGestureAction> {
        if (state != Idle) return emptyList()
        reset()
        downAt = event.position
        val hit = event.hit
        when (hit) {
            is HomeEditHit.Block -> target(hit.section, hit.local, hit.size)
            is HomeEditHit.Handle -> target(hit.section, hit.local, hit.size)
            is HomeEditHit.Blank -> nearest = hit.nearest
            HomeEditHit.Excluded -> Unit
        }
        return if (event.editing) editDown(event) else normalDown(event)
    }

    private fun editDown(event: HomeEditGestureEvent.Down): List<HomeEditGestureAction> {
        // Only primary presses edit; the mouse's secondary button does nothing here.
        if (event.secondaryClick) return emptyList()
        if (event.hit == HomeEditHit.Excluded) return listOf(Touch)
        editGesture = true
        state = when (event.hit) {
            is HomeEditHit.Handle -> HandleDown
            is HomeEditHit.Block -> {
                timers += HomeEditTimerAt(HomeEditTimer.BodyHold, event.t + bodyHoldMs)
                Holding
            }
            else -> {
                flingStop = event.flingInProgress
                timers += HomeEditTimerAt(HomeEditTimer.BlankHold, event.t + longPressMs)
                Blank
            }
        }
        return listOf(Touch, PointerDown(true))
    }

    private fun normalDown(event: HomeEditGestureEvent.Down): List<HomeEditGestureAction> {
        if (event.hit == HomeEditHit.Excluded) return emptyList()
        if (event.secondaryClick) {
            editGesture = true
            state = Done
            return listOf(Enter(section ?: nearest, lifted = false), ConsumeRest, PointerDown(true))
        }
        state = Pressing
        if (section != null) timers += HomeEditTimerAt(HomeEditTimer.HalfT, event.t + longPressMs / 2)
        timers += HomeEditTimerAt(HomeEditTimer.LongPress, event.t + longPressMs)
        return emptyList()
    }

    private fun move(event: HomeEditGestureEvent.Move): List<HomeEditGestureAction> {
        val pastSlop = (event.position - downAt).getDistance() > slopPx
        val section = section
        return when (state) {
            Pressing -> if (pastSlop) abandon(next = Idle) else emptyList()
            Holding, Blank -> if (pastSlop) abandon(next = Passive) else emptyList()
            HandleDown -> if (pastSlop && section != null) {
                state = Carry
                listOf(DragStartHaptic, Lift(section, local), StartCarry(event.position.y, event.t), ConsumeRest)
            } else {
                emptyList()
            }
            Lifted -> if (pastSlop) {
                state = Carry
                listOf(StartCarry(event.position.y, event.t))
            } else {
                emptyList()
            }
            Carry -> listOf(CarryMove(event.position.y, event.t))
            Idle, Done, Passive, Pinch -> emptyList()
        }
    }

    private fun up(t: Long): List<HomeEditGestureAction> {
        val section = section
        val body = when (state) {
            Pressing -> releaseCharge() + Abandon
            Lifted -> listOf(DropLift(HomeEditTokens.KickTap))
            // A handle has no card under it: the block kicks.
            Holding, HandleDown -> if (section == null) {
                emptyList()
            } else {
                listOf(BlockTap(section, local.takeIf { state == Holding }), ConsumeRest)
            }
            Blank -> if (flingStop || blankHeld) emptyList() else listOf(ExitBlank, ConsumeRest)
            Carry -> listOf(CarryRelease(cancelled = false, t))
            Pinch -> listOf(PinchEnd)
            Idle, Done, Passive -> emptyList()
        }
        return finish(body)
    }

    private fun cancel(t: Long): List<HomeEditGestureAction> {
        val body = when (state) {
            Pressing -> releaseCharge() + Abandon
            Lifted -> listOf(DropLift(0f))
            Carry -> listOf(CarryRelease(cancelled = true, t))
            Pinch -> listOf(PinchEnd)
            else -> emptyList()
        }
        return finish(body)
    }

    // An edit-session finger restarts the idle window as it lifts and opens held-back placeholders (critique 27).
    private fun finish(body: List<HomeEditGestureAction>): List<HomeEditGestureAction> {
        val edit = editGesture
        reset()
        if (!edit) return body
        return listOf(PointerDown(false), Touch) + body + OpenPlaceholders
    }

    private fun timer(kind: HomeEditTimer): List<HomeEditGestureAction> {
        if (!timers.removeAll { it.kind == kind }) return emptyList()
        val section = section
        return when (kind) {
            HomeEditTimer.HalfT -> if (state == Pressing && section != null) {
                charging = true
                listOf(StartCharge(section, local))
            } else {
                emptyList()
            }
            HomeEditTimer.LongPress -> if (state == Pressing) threshold(section) else emptyList()
            HomeEditTimer.BodyHold -> if (state == Holding && section != null) {
                state = Lifted
                listOf(DragStartHaptic, Lift(section, local), ConsumeRest)
            } else {
                emptyList()
            }
            HomeEditTimer.BlankHold -> {
                if (state == Blank) blankHeld = true
                emptyList()
            }
        }
    }

    // The threshold frame (port sheet §2.3); the order matters: the plate
    // latches the charge before it is released, and the lift needs edit mode.
    private fun threshold(section: HomeSection?): List<HomeEditGestureAction> {
        timers.clear()
        charging = false
        editGesture = true
        if (section == null) {
            state = Done
            return listOf(
                LongPressHaptic,
                ReleaseCharge,
                Enter(nearest, lifted = false),
                ConsumeRest,
                PointerDown(true),
            )
        }
        state = Lifted
        return listOf(
            LongPressHaptic,
            Enter(section, lifted = true),
            LatchPlate(section, local, size),
            ReleaseCharge,
            Lift(section, local),
            ConsumeRest,
            PointerDown(true),
        )
    }

    // proto disownGesture: a press still waiting on edit mode must not lift or exit in normal mode.
    private fun editEnded(): List<HomeEditGestureAction> = when (state) {
        Lifted, Holding, HandleDown, Blank -> {
            timers.clear()
            state = Done
            listOf(ConsumeRest)
        }
        else -> emptyList()
    }

    private fun carryEnded(): List<HomeEditGestureAction> {
        if (state == Carry) state = Done
        return emptyList()
    }

    private fun abandon(next: HomeEditGestureState): List<HomeEditGestureAction> {
        timers.clear()
        val release = releaseCharge()
        state = next
        return release + Abandon
    }

    private fun releaseCharge(): List<HomeEditGestureAction> {
        if (!charging) return emptyList()
        charging = false
        return listOf(ReleaseCharge)
    }

    private fun target(section: HomeSection, local: Offset, size: Size) {
        this.section = section
        this.local = local
        this.size = size
    }

    private fun reset() {
        state = Idle
        timers.clear()
        section = null
        local = Offset.Zero
        size = Size.Zero
        nearest = null
        editGesture = false
        charging = false
        flingStop = false
        blankHeld = false
        pinchSpan = 0f
    }
}

// ── Executor ──────────────────────────────────────────────────────────────

/**
 * Applies machine actions to the controller, the motion, the press and the
 * carry, synchronously and in order. Actions that only make sense in edit
 * mode are dropped once it has ended (critique 4): a body hold that fires
 * after Done never lifts in normal mode, and a blank tap never exits twice.
 * [scope] runs the press charge's animations.
 */
internal class HomeEditGestureExecutor(
    private val controller: HomeEditController,
    private val motion: HomeEditMotion,
    private val press: HomeEditPressState,
    private val engine: HomeDragEngine,
    private val feedback: HomeEditFeedback,
    private val specs: HomeEditSpecs,
    private val density: Density,
    private val scope: CoroutineScope,
) {
    fun run(actions: List<HomeEditGestureAction>, machine: HomeEditGestureMachine) {
        for (action in actions) {
            if (action.editOnly && !controller.isEditing) continue
            when (action) {
                is StartCharge -> press.start(scope, action.section, action.local, specs)
                ReleaseCharge -> press.release(scope, specs)
                LongPressHaptic -> feedback.longPress()
                DragStartHaptic -> feedback.dragStart()
                is Enter -> controller.enter(action.origin, action.lifted)
                is LatchPlate -> {
                    // Read now, before the release that follows starts moving it.
                    val charge = press.charge.value
                    motion.latchPlate(action.section, pressRect(action.local, action.size, charge, density), charge)
                }
                is Lift -> engine.liftBlock(action.section, action.local)
                is DropLift -> engine.dropLift(action.kick)
                OpenPlaceholders -> motion.placeholdersAboveOpen = true
                is StartCarry -> engine.start(action.y, action.t)
                is CarryMove -> engine.move(action.y, action.t)
                is CarryRelease -> engine.release(action.cancelled, action.t)
                PinchStart -> engine.list.pinchStart()
                is PinchBy -> engine.list.pinchBy(action.scale)
                PinchEnd -> engine.list.pinchEnd()
                is BlockTap -> blockTap(action)
                ExitBlank -> controller.commitAndExit(HomeEditExitReason.Blank)
                Touch -> motion.touch()
                is PointerDown -> motion.pointerDown = action.down
                Abandon, ConsumeRest, ConsumeOther -> Unit
            }
        }
    }

    // Card level: the tapped card kicks (and is noted, for a section that answers it), else
    // the block; the handle always pulses. Never exits.
    private fun blockTap(action: BlockTap) {
        val card = action.local?.let { motion.cardAt(action.section, it) }
        if (card != null) {
            motion.impulseCard(action.section, card, HomeEditTokens.KickTap)
            motion.noteCardTap(action.section, card)
        } else {
            motion.impulse(action.section, HomeEditTokens.KickTap)
        }
        motion.pulseHandle(action.section)
    }

    private val HomeEditGestureAction.editOnly: Boolean
        get() = this is Lift || this is LatchPlate || this is StartCarry || this is BlockTap || this == ExitBlank ||
            this == PinchStart || this is PinchBy
}

// ── Pointer modifier ──────────────────────────────────────────────────────

/**
 * Home edit mode's detector over the whole page, margins included (spec
 * §2.1.2). It reads only the Initial pass, before the cards and the list:
 * in normal mode it watches without consuming until the long-press
 * threshold, then owns the finger; in edit mode it lifts, drags, kicks,
 * pinches and exits. A down already consumed (the memory bubble or arrow) is left alone.
 * The dependencies are swapped in place, so a recomposition never restarts a
 * gesture mid-carry.
 */
internal fun Modifier.homeEditGestures(
    controller: HomeEditController,
    motion: HomeEditMotion,
    press: HomeEditPressState,
    engine: HomeDragEngine,
    hitTester: HomeEditHitTest,
    feedback: HomeEditFeedback,
    isFlingInProgress: () -> Boolean,
    specs: HomeEditSpecs,
): Modifier = this then HomeEditGesturesElement(
    controller = controller,
    motion = motion,
    press = press,
    engine = engine,
    hitTester = hitTester,
    feedback = feedback,
    isFlingInProgress = isFlingInProgress,
    specs = specs,
)

private data class HomeEditGesturesElement(
    val controller: HomeEditController,
    val motion: HomeEditMotion,
    val press: HomeEditPressState,
    val engine: HomeDragEngine,
    val hitTester: HomeEditHitTest,
    val feedback: HomeEditFeedback,
    val isFlingInProgress: () -> Boolean,
    val specs: HomeEditSpecs,
) : ModifierNodeElement<HomeEditGesturesNode>() {
    override fun create() = HomeEditGesturesNode(this)

    override fun update(node: HomeEditGesturesNode) {
        node.deps = this
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "homeEditGestures"
    }
}

private class HomeEditGesturesNode(var deps: HomeEditGesturesElement) : DelegatingNode(), PointerInputModifierNode {
    private val pointerInput = delegate(SuspendingPointerInputModifierNode(PointerInputEventHandler { detect() }))

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        pointerInput.onPointerEvent(pointerEvent, pass, bounds)
    }

    override fun onCancelPointerInput() {
        pointerInput.onCancelPointerInput()
    }

    private suspend fun PointerInputScope.detect() = awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // The memory bubble or arrow claimed it.
        if (down.isConsumed) return@awaitEachGesture
        val buttons = currentEvent.buttons
        val mouse = down.type == PointerType.Mouse
        // A mouse presses with its primary button; its secondary one enters edit mode.
        if (mouse && !buttons.isPrimaryPressed && !buttons.isSecondaryPressed) return@awaitEachGesture
        // One gesture keeps the dependencies it started with.
        val deps = deps
        val machine = HomeEditGestureMachine(viewConfiguration.longPressTimeoutMillis, viewConfiguration.touchSlop)
        val executor = HomeEditGestureExecutor(
            controller = deps.controller,
            motion = deps.motion,
            press = deps.press,
            engine = deps.engine,
            feedback = deps.feedback,
            specs = deps.specs,
            density = this@detect,
            scope = this@HomeEditGesturesNode.coroutineScope,
        )
        var consuming = false
        // Event time, so timers follow the events' clock (and a test's).
        var now = down.uptimeMillis
        fun step(event: HomeEditGestureEvent): List<HomeEditGestureAction> {
            val actions = machine.handle(event)
            if (ConsumeRest in actions) consuming = true
            executor.run(actions, machine)
            return actions
        }

        // The drag reads the finger's x for two-column drops.
        deps.engine.fingerX = down.position.x
        step(
            HomeEditGestureEvent.Down(
                hit = deps.hitTester.hitTest(down.position),
                position = down.position,
                editing = deps.controller.isEditing,
                flingInProgress = deps.isFlingInProgress(),
                t = now,
                secondaryClick = mouse && !buttons.isPrimaryPressed,
            ),
        )
        if (consuming) down.consume()
        // A pinch's two fingers: the tracked one and the second one down.
        var primary = down.position
        var second: PointerId? = null
        var secondAt = Offset.Zero
        try {
            while (machine.state != Idle) {
                val timer = machine.pendingTimer
                val event = when {
                    timer == null -> awaitPointerEvent(PointerEventPass.Initial)
                    timer.atMs <= now -> null
                    else -> withTimeoutOrNull(timer.atMs - now) { awaitPointerEvent(PointerEventPass.Initial) }
                }
                // Edit mode can end, or an exit let the drag go, while the finger rests. A drag that never
                // started stays: the engine's release puts a lone lift down.
                if (!deps.controller.isEditing) step(EditEnded)
                if (machine.state == Carry && deps.engine.letGo) step(CarryEnded)
                if (event == null) {
                    if (timer != null) {
                        now = maxOf(now, timer.atMs)
                        step(HomeEditGestureEvent.Timer(timer.kind))
                    }
                    continue
                }
                // Any finger's event moves the clock on, or a resting finger's timer would run late.
                now = maxOf(now, event.changes.maxOfOrNull { it.uptimeMillis } ?: now)
                for (change in event.changes) {
                    if (change.id != down.id) {
                        when {
                            second == null && change.changedToDown() -> {
                                val actions = step(PinchDown((change.position - primary).getDistance()))
                                if (machine.state == Pinch) {
                                    second = change.id
                                    secondAt = change.position
                                }
                                if (ConsumeOther in actions) change.consume()
                            }
                            change.id == second -> {
                                if (!change.pressed) {
                                    second = null
                                    step(PinchUp)
                                } else if (change.positionChanged()) {
                                    secondAt = change.position
                                    step(PinchMove((secondAt - primary).getDistance()))
                                }
                                change.consume()
                            }
                            else -> if (ConsumeOther in machine.handle(OtherPointer)) change.consume()
                        }
                        continue
                    }
                    val t = change.uptimeMillis
                    when {
                        // Consumed before the Initial pass reached us: a system cancel, or an ancestor took it.
                        change.isConsumed -> step(HomeEditGestureEvent.Cancel(t))
                        !change.pressed -> step(HomeEditGestureEvent.Up(t))
                        change.positionChanged() -> {
                            primary = change.position
                            deps.engine.fingerX = change.position.x
                            if (machine.state == Pinch && second != null) {
                                step(PinchMove((secondAt - primary).getDistance()))
                            } else {
                                step(HomeEditGestureEvent.Move(change.position, t))
                            }
                        }
                    }
                    if (consuming) change.consume()
                }
            }
        } finally {
            // The detector itself was cancelled mid-gesture: a drag releases as cancelled, back to before the lift.
            if (machine.state != Idle) step(HomeEditGestureEvent.Cancel(now))
        }
    }
}

// Full-width shelves: a press in the page margin beside them lands on the shelf.
private val BleedingSections = setOf(HomeSection.RecentlyAdded, HomeSection.Rediscover)
