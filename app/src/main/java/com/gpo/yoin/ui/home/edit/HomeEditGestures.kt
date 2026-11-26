package com.gpo.yoin.ui.home.edit

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
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
import com.gpo.yoin.shared.home.HomeEditGestureAction
import com.gpo.yoin.shared.home.HomeEditGestureEvent
import com.gpo.yoin.shared.home.HomeEditGestureMachine
import com.gpo.yoin.shared.home.HomeEditGestureTimeline
import com.gpo.yoin.shared.home.HomeEditHit
import com.gpo.yoin.shared.home.HomePoint
import com.gpo.yoin.shared.home.HomeSize
import com.gpo.yoin.ui.home.HomeSection
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
import kotlinx.coroutines.CoroutineScope

// Home edit mode's one gesture detector (port sheet §2.1, spec §2.1): a pure
// state machine, the executor that applies its actions, the page hit tester,
// and the Initial-pass pointer modifier on Home's root Box that drives them.
// It drives the feed and the pinched list alike (owner 2026-11-22): a second
// finger turns an edit-mode press into a pinch, in or out of the list.

// ── Hit testing ───────────────────────────────────────────────────────────

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
            local = HomePoint(position.x - left, position.y - top),
            size = HomeSize((right - left).coerceAtLeast(0f), bottom - top),
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
                is StartCharge -> press.start(scope, action.section, action.local.toOffset(), specs)
                ReleaseCharge -> press.release(scope, specs)
                LongPressHaptic -> feedback.longPress()
                DragStartHaptic -> feedback.dragStart()
                is Enter -> controller.enter(action.origin, action.lifted)
                is LatchPlate -> {
                    // Read now, before the release that follows starts moving it.
                    val charge = press.charge.value
                    motion.latchPlate(action.section, pressRect(action.local.toOffset(), action.size.toSize(), charge, density), charge)
                }
                is Lift -> engine.liftBlock(action.section, action.local.toOffset())
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
        val card = action.local?.let { motion.cardAt(action.section, it.toOffset()) }
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
        fun step(event: HomeEditGestureEvent): List<HomeEditGestureAction> {
            val actions = machine.handle(event)
            if (ConsumeRest in actions) consuming = true
            executor.run(actions, machine)
            return actions
        }
        // Event time, so timers follow the events' clock (and a test's).
        val timeline = HomeEditGestureTimeline(machine, deps.isFlingInProgress, ::step)
        timeline.start(down.uptimeMillis)

        // The drag reads the finger's x for two-column drops.
        deps.engine.fingerX = down.position.x
        step(
            HomeEditGestureEvent.Down(
                hit = deps.hitTester.hitTest(down.position),
                position = down.position.toHomePoint(),
                editing = deps.controller.isEditing,
                flingInProgress = deps.isFlingInProgress(),
                t = down.uptimeMillis,
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
                val wait = timeline.untilDue()
                var event = when (wait) {
                    null -> awaitPointerEvent(PointerEventPass.Initial)
                    0L -> null
                    else -> withTimeoutOrNull(wait) { awaitPointerEvent(PointerEventPass.Initial) }
                }
                // Woken late at a timer's instant: a long frame held the main thread past it, and the
                // finger's moves from before it may still wait for the next frame's input. Give them
                // a moment to arrive; they are then handled at their own stamps, ahead of the timer.
                if (event == null && wait != null) {
                    val grace = timeline.graceAfter(SystemClock.uptimeMillis())
                    if (grace > 0L) event = withTimeoutOrNull(grace) { awaitPointerEvent(PointerEventPass.Initial) }
                }
                // Edit mode can end, or an exit let the drag go, while the finger rests. A drag that never
                // started stays: the engine's release puts a lone lift down.
                if (!deps.controller.isEditing) step(EditEnded)
                if (machine.state == Carry && deps.engine.letGo) step(CarryEnded)
                if (event == null) {
                    timeline.timeout()
                    continue
                }
                for (change in event.changes) {
                    if (change.id != down.id) {
                        // Any finger's event moves the clock on, or a resting finger's timer would run late.
                        timeline.advanceTo(change.uptimeMillis)
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
                    // A batch's earlier samples (a long frame held them back), each at its own stamp:
                    // a timer due between two of them fires between them.
                    if (!change.isConsumed && machine.state != Pinch) {
                        for (sample in change.historical) {
                            deps.engine.fingerX = sample.position.x
                            primary = sample.position
                            timeline.sample(HomeEditGestureEvent.Move(sample.position.toHomePoint(), sample.uptimeMillis))
                        }
                    }
                    val t = change.uptimeMillis
                    when {
                        // Consumed before the Initial pass reached us: a system cancel, or an ancestor took it.
                        change.isConsumed -> timeline.sample(HomeEditGestureEvent.Cancel(t))
                        !change.pressed -> timeline.sample(HomeEditGestureEvent.Up(t))
                        change.positionChanged() -> {
                            primary = change.position
                            deps.engine.fingerX = change.position.x
                            if (machine.state == Pinch && second != null) {
                                timeline.advanceTo(t)
                                step(PinchMove((secondAt - primary).getDistance()))
                            } else {
                                timeline.sample(HomeEditGestureEvent.Move(change.position.toHomePoint(), t))
                            }
                        }
                        else -> timeline.advanceTo(t)
                    }
                    if (consuming) change.consume()
                }
            }
        } finally {
            // The detector itself was cancelled mid-gesture: a drag releases as cancelled, back to before the lift.
            if (machine.state != Idle) step(HomeEditGestureEvent.Cancel(timeline.now))
        }
    }
}

/**
 * Whether the feed (or the pinched list) is flinging, or stopped one less
 * than [windowMs] ago: a press then belongs to the scroll and never lifts.
 * [inProgress] is read live; [onScrolling] is told each change of it.
 */
internal class HomeEditFlingWatch(
    private val inProgress: () -> Boolean,
    private val windowMs: Long = HomeEditTokens.FlingStopMs,
    private val uptimeMs: () -> Long = { SystemClock.uptimeMillis() },
) {
    private var scrolling = false
    private var stoppedAt = Long.MIN_VALUE

    fun onScrolling(scrolling: Boolean) {
        if (this.scrolling && !scrolling) stoppedAt = uptimeMs()
        this.scrolling = scrolling
    }

    fun flinging(): Boolean =
        inProgress() || scrolling || (stoppedAt != Long.MIN_VALUE && uptimeMs() - stoppedAt < windowMs)
}

// Full-width shelves: a press in the page margin beside them lands on the shelf.
private val BleedingSections = setOf(HomeSection.RecentlyAdded, HomeSection.Rediscover)

// The machine's plain points and sizes ⇄ Compose's.
internal fun Offset.toHomePoint(): HomePoint = HomePoint(x, y)

internal fun HomePoint.toOffset(): Offset = Offset(x, y)

internal fun HomeSize.toSize(): Size = Size(width, height)
