package com.gpo.yoin.shared.home

import com.gpo.yoin.shared.home.HomeEditGestureAction.Abandon
import com.gpo.yoin.shared.home.HomeEditGestureAction.BlockTap
import com.gpo.yoin.shared.home.HomeEditGestureAction.CarryMove
import com.gpo.yoin.shared.home.HomeEditGestureAction.CarryRelease
import com.gpo.yoin.shared.home.HomeEditGestureAction.ConsumeOther
import com.gpo.yoin.shared.home.HomeEditGestureAction.ConsumeRest
import com.gpo.yoin.shared.home.HomeEditGestureAction.DragStartHaptic
import com.gpo.yoin.shared.home.HomeEditGestureAction.DropLift
import com.gpo.yoin.shared.home.HomeEditGestureAction.Enter
import com.gpo.yoin.shared.home.HomeEditGestureAction.ExitBlank
import com.gpo.yoin.shared.home.HomeEditGestureAction.LatchPlate
import com.gpo.yoin.shared.home.HomeEditGestureAction.Lift
import com.gpo.yoin.shared.home.HomeEditGestureAction.LongPressHaptic
import com.gpo.yoin.shared.home.HomeEditGestureAction.OpenPlaceholders
import com.gpo.yoin.shared.home.HomeEditGestureAction.PinchBy
import com.gpo.yoin.shared.home.HomeEditGestureAction.PinchEnd
import com.gpo.yoin.shared.home.HomeEditGestureAction.PinchStart
import com.gpo.yoin.shared.home.HomeEditGestureAction.PointerDown
import com.gpo.yoin.shared.home.HomeEditGestureAction.ReleaseCharge
import com.gpo.yoin.shared.home.HomeEditGestureAction.StartCarry
import com.gpo.yoin.shared.home.HomeEditGestureAction.StartCharge
import com.gpo.yoin.shared.home.HomeEditGestureAction.Touch
import com.gpo.yoin.shared.home.HomeEditGestureEvent.CarryEnded
import com.gpo.yoin.shared.home.HomeEditGestureEvent.EditEnded
import com.gpo.yoin.shared.home.HomeEditGestureEvent.OtherPointer
import com.gpo.yoin.shared.home.HomeEditGestureEvent.PinchDown
import com.gpo.yoin.shared.home.HomeEditGestureEvent.PinchMove
import com.gpo.yoin.shared.home.HomeEditGestureEvent.PinchUp
import com.gpo.yoin.shared.home.HomeEditGestureState.Blank
import com.gpo.yoin.shared.home.HomeEditGestureState.Carry
import com.gpo.yoin.shared.home.HomeEditGestureState.Done
import com.gpo.yoin.shared.home.HomeEditGestureState.HandleDown
import com.gpo.yoin.shared.home.HomeEditGestureState.Holding
import com.gpo.yoin.shared.home.HomeEditGestureState.Idle
import com.gpo.yoin.shared.home.HomeEditGestureState.Lifted
import com.gpo.yoin.shared.home.HomeEditGestureState.Passive
import com.gpo.yoin.shared.home.HomeEditGestureState.Pinch
import com.gpo.yoin.shared.home.HomeEditGestureState.Pressing
import kotlin.math.sqrt

// Home edit mode's gesture machine (port sheet §2.1, spec §2.1; Android PR #292), moved unchanged from
// Android's HomeEditGestures.kt for parity phase 4 with plain points and sizes in place of Compose's Offset
// and Size: fed one finger's events (pointer-event uptimes, ms) and its timers, it answers with actions the
// platform applies in order. Body hold = max(150ms, T/2); a press that stops a fling never lifts; moves
// stamped before a timer that arrive late undo the lift; a second finger before any lift starts a pinch; a
// mouse's secondary click enters edit mode. [HomeEditGestureTimeline] runs the timers on the events' own
// clock. Android's pointer modifier, iOS's touch recognizer and HarmonyOS's recognizer all drive it; the
// golden timelines are shared/src/commonTest/resources/golden/home-edit-gestures.json.

/** A point in px (a press position, a block-local offset). */
data class HomePoint(val x: Float, val y: Float) {
    operator fun plus(other: HomePoint): HomePoint = HomePoint(x + other.x, y + other.y)

    operator fun minus(other: HomePoint): HomePoint = HomePoint(x - other.x, y - other.y)

    /** The distance from the origin, as Compose's `Offset.getDistance()`. */
    fun distance(): Float = sqrt(x * x + y * y)

    companion object {
        val Zero: HomePoint = HomePoint(0f, 0f)
    }
}

/** A size in px. */
data class HomeSize(val width: Float, val height: Float) {
    companion object {
        val Zero: HomeSize = HomeSize(0f, 0f)
    }
}

/** Numbers of the gesture machine. */
object HomeEditGestureTokens {
    /** Edit mode's body hold never lifts sooner than this (ms); otherwise half the long-press timeout. */
    const val BodyHoldMinMs = 150L

    /** A tap's kick, and the one a lone lift puts down with. */
    const val KickTap = .5f
}

/** What a press lands on, resolved once at pointer-down. Positions are Box px. */
sealed interface HomeEditHit {
    /** A control the gestures leave alone: header icons, badges, the tray, the footer. */
    data object Excluded : HomeEditHit

    /** [section]'s drag handle (edit mode only). [local] and [size] as for [Block]. */
    data class Handle(val section: HomeSection, val local: HomePoint, val size: HomeSize = HomeSize.Zero) : HomeEditHit

    /** Inside [section]'s band. [local] is in block-content px; [size] is the block content's. */
    data class Block(val section: HomeSection, val local: HomePoint, val size: HomeSize = HomeSize.Zero) : HomeEditHit

    /** Off every block. [nearest] is the closest visible section, the entry ripple's origin. */
    data class Blank(val nearest: HomeSection?) : HomeEditHit
}

/** Where the tracked finger is in the gesture (port sheet §2.1). */
enum class HomeEditGestureState {
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

enum class HomeEditTimer {
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
data class HomeEditTimerAt(val kind: HomeEditTimer, val atMs: Long)

/** Input to [HomeEditGestureMachine]. Times are pointer-event uptimes (ms). */
sealed interface HomeEditGestureEvent {
    /** The tracked finger went down. */
    data class Down(
        val hit: HomeEditHit,
        val position: HomePoint,
        val editing: Boolean,
        /** The feed (or the list) is flinging, or stopped one a moment ago ([HomeEditFlingWatch]). */
        val flingInProgress: Boolean,
        val t: Long,
        /** A mouse or trackpad secondary-button press (desktop windowing). */
        val secondaryClick: Boolean = false,
    ) : HomeEditGestureEvent

    data class Move(val position: HomePoint, val t: Long) : HomeEditGestureEvent

    data class Up(val t: Long) : HomeEditGestureEvent

    /** The system or an ancestor took the finger. */
    data class Cancel(val t: Long) : HomeEditGestureEvent

    /** [kind] is due. [flinging]: the feed (or the list) is flinging as it fires, so a hold doesn't lift. */
    data class Timer(val kind: HomeEditTimer, val flinging: Boolean = false) : HomeEditGestureEvent

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
sealed interface HomeEditGestureAction {
    /** The press is given up: taps, clicks and scrolls go to the page. */
    data object Abandon : HomeEditGestureAction

    data class StartCharge(val section: HomeSection, val local: HomePoint) : HomeEditGestureAction

    data object ReleaseCharge : HomeEditGestureAction

    data object LongPressHaptic : HomeEditGestureAction

    data object DragStartHaptic : HomeEditGestureAction

    data class Enter(val origin: HomeSection?, val lifted: Boolean) : HomeEditGestureAction

    /** The entry plate's start, from the charge read before it is released. */
    data class LatchPlate(val section: HomeSection, val local: HomePoint, val size: HomeSize) : HomeEditGestureAction

    data class Lift(val section: HomeSection, val local: HomePoint) : HomeEditGestureAction

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
    data class BlockTap(val section: HomeSection, val local: HomePoint?) : HomeEditGestureAction

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
class HomeEditGestureMachine(private val longPressMs: Long, private val slopPx: Float) {
    var state: HomeEditGestureState = Idle
        private set

    private val bodyHoldMs = maxOf(HomeEditGestureTokens.BodyHoldMinMs, longPressMs / 2)
    private val timers = ArrayList<HomeEditTimerAt>(2)

    private var downAt = HomePoint.Zero
    private var section: HomeSection? = null
    private var local = HomePoint.Zero
    private var size = HomeSize.Zero
    private var nearest: HomeSection? = null

    // The finger belongs to an edit session: its up restarts the idle window and opens placeholders.
    private var editGesture = false
    private var charging = false
    private var flingStop = false
    private var blankHeld = false
    private var pinchSpan = 0f

    // The instant (event uptime) a hold lifted the block: a move from before it past the slop takes the lift back.
    private var liftAt = Long.MIN_VALUE

    /** The next timer to fire, or null. */
    val pendingTimer: HomeEditTimerAt? get() = timers.minByOrNull { it.atMs }

    fun handle(event: HomeEditGestureEvent): List<HomeEditGestureAction> = when (event) {
        is HomeEditGestureEvent.Down -> down(event)
        is HomeEditGestureEvent.Move -> move(event)
        is HomeEditGestureEvent.Up -> up(event.t)
        is HomeEditGestureEvent.Cancel -> cancel(event.t)
        is HomeEditGestureEvent.Timer -> timer(event.kind, event.flinging)
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
            // A press on a block that stops a fling (or lands just after one) is part of the
            // scroll: it never lifts, and its up kicks nothing.
            is HomeEditHit.Handle -> if (event.flingInProgress) Passive else HandleDown
            is HomeEditHit.Block -> if (event.flingInProgress) {
                Passive
            } else {
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
        // A press that stops a fling is the scroll's, not a long press: left to the page untouched.
        if (event.flingInProgress && !event.secondaryClick) return emptyList()
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
        val pastSlop = (event.position - downAt).distance() > slopPx
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
            // A move stamped before the lift that was already past the slop, handed over late (a long
            // frame held the input back past the hold's timer): the finger never held still that
            // long, so the lift is taken back and the rest of the finger swallowed. Never a carry:
            // a fling must not drag the block it started on.
            Lifted -> if (pastSlop && event.t < liftAt) {
                timers.clear()
                state = Done
                listOf(DropLift(0f))
            } else if (pastSlop) {
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
            Lifted -> listOf(DropLift(HomeEditGestureTokens.KickTap))
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

    private fun timer(kind: HomeEditTimer, flinging: Boolean): List<HomeEditGestureAction> {
        val at = timers.firstOrNull { it.kind == kind }?.atMs ?: return emptyList()
        timers.removeAll { it.kind == kind }
        val section = section
        // The feed began to fling under a resting finger: no lift, the press is the scroll's.
        if (flinging && kind == HomeEditTimer.LongPress && state == Pressing) return abandon(next = Idle)
        if (flinging && kind == HomeEditTimer.BodyHold && state == Holding) return abandon(next = Passive)
        return when (kind) {
            HomeEditTimer.HalfT -> if (state == Pressing && section != null) {
                charging = true
                listOf(StartCharge(section, local))
            } else {
                emptyList()
            }
            HomeEditTimer.LongPress -> if (state == Pressing) {
                liftAt = at
                threshold(section)
            } else {
                emptyList()
            }
            HomeEditTimer.BodyHold -> if (state == Holding && section != null) {
                liftAt = at
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

    private fun target(section: HomeSection, local: HomePoint, size: HomeSize) {
        this.section = section
        this.local = local
        this.size = size
    }

    private fun reset() {
        state = Idle
        timers.clear()
        section = null
        local = HomePoint.Zero
        size = HomeSize.Zero
        nearest = null
        editGesture = false
        charging = false
        flingStop = false
        blankHeld = false
        pinchSpan = 0f
        liftAt = Long.MIN_VALUE
    }
}

/**
 * Runs [machine]'s timers on the pointer events' own clock (their uptimes,
 * ms), never on when a wait happens to wake. Every sample of the finger is
 * handled at its stamp, and a timer due at or before a stamp fires first. So
 * a hold lifts only if the finger stayed within the slop up to the hold's
 * instant by the events themselves: a long frame that hands a fling's moves
 * over late (in one batch, after the wait for the hold already woke) can't
 * let the hold run ahead of them. A move from before the instant that comes
 * even later than that takes the lift back ([HomeEditGestureMachine]).
 * [flinging] is asked as a hold fires: no lift while the feed flings.
 */
class HomeEditGestureTimeline(
    private val machine: HomeEditGestureMachine,
    private val flinging: () -> Boolean,
    private val step: (HomeEditGestureEvent) -> List<HomeEditGestureAction>,
) {
    /** The event clock: the latest instant handled, a sample's stamp or a timer's. */
    var now: Long = 0L
        private set

    /** The gesture starts at its down's stamp [t]. */
    fun start(t: Long) {
        now = t
    }

    /** How long (ms, from [now]) input may take before the next timer is due; null with none pending. */
    fun untilDue(): Long? = machine.pendingTimer?.let { (it.atMs - now).coerceAtLeast(0L) }

    /**
     * The wait for the next timer woke at [wallMs] (uptime) with no input:
     * how much longer to wait for input held back from before the timer's
     * instant. Only a wait that woke late (a long frame) has any to wait for.
     */
    fun graceAfter(wallMs: Long): Long {
        val timer = machine.pendingTimer ?: return 0L
        return if (wallMs - timer.atMs > LateTimerMs) StalledInputGraceMs else 0L
    }

    /** No input came by the next timer's instant (and its grace): it fires. */
    fun timeout() {
        machine.pendingTimer?.let(::fire)
    }

    /** Timers due at or before [t] fire, oldest first; then the clock is at [t]. */
    fun advanceTo(t: Long) {
        while (true) {
            val timer = machine.pendingTimer ?: break
            if (timer.atMs > t) break
            fire(timer)
        }
        now = maxOf(now, t)
    }

    /** A sample of the tracked finger, handled at its own stamp: whatever was due before it goes first. */
    fun sample(event: HomeEditGestureEvent): List<HomeEditGestureAction> {
        val t = when (event) {
            is HomeEditGestureEvent.Move -> event.t
            is HomeEditGestureEvent.Up -> event.t
            is HomeEditGestureEvent.Cancel -> event.t
            else -> now
        }
        advanceTo(t)
        return step(event)
    }

    private fun fire(timer: HomeEditTimerAt) {
        now = maxOf(now, timer.atMs)
        val lifts = timer.kind == HomeEditTimer.BodyHold || timer.kind == HomeEditTimer.LongPress
        step(HomeEditGestureEvent.Timer(timer.kind, flinging = lifts && flinging()))
    }
}

// A timer that wakes this late (ms) may have pointer events from before it still queued…
const val LateTimerMs = 8L

// …which reach the detector with the first frame the main thread runs after it (edit mode has
// 100-300ms frames on the tablet; 48ms was seen to fall short). A resting finger lifts this late at most.
const val StalledInputGraceMs = 120L
