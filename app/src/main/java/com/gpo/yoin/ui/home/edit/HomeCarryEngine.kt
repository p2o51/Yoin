package com.gpo.yoin.ui.home.edit

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import com.gpo.yoin.ui.home.HomeSection
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

internal enum class CarryPhase { Drag, Settle, Unfold }

/** The feed as the strip carry sees it. Implemented over the LazyColumn (`LazyListCarryHost`). */
internal interface CarryHost {
    /**
     * Enabled sections in the draft's order. From data, never from what is
     * rendered: a placeholder held back above the lifted block still counts,
     * or the commit would not be a full permutation (critique 1).
     */
    fun displayedOrder(): List<HomeSection>

    /** The section's plate in Box px; null if it is not laid out. */
    fun plateRect(section: HomeSection): Rect?

    /** Which side an off-screen section left by, for its B_i. */
    fun isAbove(section: HomeSection): Boolean

    fun safeArea(): HomeEditSafeArea

    fun contentLeft(): Float

    fun contentWidth(): Float

    /** Scrolls the dropped block under its slot, then waits for a layout in [order]. */
    suspend fun anchorAndAwaitLayout(dropped: HomeSection, order: List<HomeSection>, slotTop: Float)
}

/**
 * One strip carry (port sheet §4.14). [carried], [orig] and [metrics] are
 * fixed at fold start. Draw reads the snapshot fields; the finger's raw
 * offset, last y and end-of-stack flag are the engine's alone.
 */
@Stable
internal class CarrySession(
    val carried: HomeSection,
    /** The order at fold start: the drop commits only if [ids] differs. */
    val orig: List<HomeSection>,
    val metrics: StripMetrics,
    starts: Map<HomeSection, StripStart>,
    lastY: Float,
) {
    /** The live order; the carried strip sits at [k]. */
    var ids: List<HomeSection> by mutableStateOf(orig)

    var k: Int by mutableIntStateOf(orig.indexOf(carried))

    /** B_i: where each strip flies from, re-read from the feed after the anchor. */
    var starts: Map<HomeSection, StripStart> by mutableStateOf(starts)

    /** The carried strip's offset from slot [k] while dragged, rubber-banded past the ends. */
    var display: Float by mutableFloatStateOf(0f)

    var phase: CarryPhase by mutableStateOf(CarryPhase.Drag)

    internal var dragOffset = 0f
    internal var lastY = lastY
    internal var wasOver = false

    val count: Int get() = orig.size

    val changed: Boolean get() = ids != orig

    /** [offset] from slot [k] runs past the first or last slot. */
    internal fun isOver(offset: Float): Boolean = (k == 0 && offset < 0f) || (k == count - 1 && offset > 0f)
}

/**
 * Lift and the strip carry (port sheet §2.3–2.4, §4). Lift and its tint are
 * one shared pair, so only one block is ever lifted; the carry folds every
 * section into strips, reorders them under the finger and unfolds into the
 * committed order. The strip half is the prototype's, number for number.
 */
@Stable
internal class HomeCarryEngine(
    private val scope: CoroutineScope,
    private val specs: HomeEditSpecs,
    private val motion: HomeEditMotion,
    private val feedback: HomeEditFeedback,
    private val density: Density,
    private val commitOrder: (List<HomeSection>) -> Boolean,
    private val finishDeferredExit: (HomeEditExitReason) -> Unit,
    private val uptimeMs: () -> Long = { SystemClock.uptimeMillis() },
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
            if (liftSection == section && session == null) liftSection = null
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
     * lifted drops where it is with no kick, and the press lets go. The
     * layer's `onExit` is this, so a finger held through Done or back never
     * leaves a block lifted in normal mode.
     */
    fun onExit(reason: HomeEditExitReason, press: HomeEditPressState) {
        motion.onExit(reason)
        dropLift(0f)
        press.release(scope, specs)
    }

    /** The Home layer's snap exit: motion, lift and press at rest now. */
    fun onSnapExit(press: HomeEditPressState) {
        motion.onSnapExit()
        snapLiftToRest()
        press.snapOff(scope)
    }

    // ── Strips (§4) ───────────────────────────────────────────────────────

    /** Set by Home while composed. */
    var host: CarryHost? = null

    /** Feed ⇄ strips, overshooting on purpose (§4.4). Draw and placement. */
    val fold = Animatable(0f)

    /** The drop hole's top in Box px. Draw only. */
    val holeY = Animatable(0f)

    /** The released strip's offset from its slot while it settles. */
    val settleY = Animatable(0f)

    // Make-way offsets. Plain state, not Animatables: a swap's snap must land
    // in the move event itself, even while the neighbour is still in flight.
    private val stripOffsets: Map<HomeSection, MutableFloatState> =
        HomeSection.entries.associateWith { mutableFloatStateOf(0f) }
    private val stripJobs = HashMap<HomeSection, Job>()

    /** A neighbour's make-way offset (§4.6). Draw and placement. */
    fun stripOffset(section: HomeSection): Float = stripOffsets.getValue(section).floatValue

    var session: CarrySession? by mutableStateOf(null)
        private set

    /** A carry is in Drag, Settle or Unfold. */
    val isCarrying: Boolean get() = session != null

    /** Settling or unfolding: presses only re-grab (§4.12). */
    val isBusy: Boolean
        get() = session?.phase.let { it == CarryPhase.Settle || it == CarryPhase.Unfold }

    private val velocity = HomeEditVelocity(maxVelocity = with(density) { HomeEditTokens.MaxV.toPx() })
    private val flingVelocity = with(density) { HomeEditTokens.FlingV.toPx() }
    private val overHapticPx = with(density) { HomeEditTokens.OverHaptic.toPx() }
    private val regrabSlopPx = with(density) { HomeEditTokens.RegrabSlop.toPx() }

    private var foldJob: Job? = null
    private var holeJob: Job? = null

    // Settle, then commit, anchor, unfold and finish. Cancelled by a re-grab, so the commit never runs.
    private var settleJob: Job? = null

    // An exit that arrived mid-carry; it runs at finish (§4.13).
    private var pendingExit: HomeEditExitReason? = null

    /**
     * Folds every section into a strip with the lifted block's strip centred
     * on [fingerY] (§4.1–4.3). The order is the draft's, B_i the feed plates
     * as they stand now.
     */
    fun start(fingerY: Float, uptimeMs: Long) {
        val host = host ?: return
        val carried = liftSection ?: return
        if (session != null) return
        val ids = host.displayedOrder()
        val k = ids.indexOf(carried)
        if (k < 0) return
        val safe = host.safeArea()
        val metrics = stripMetrics(
            count = ids.size,
            carriedIndex = k,
            fingerY = fingerY,
            safeTop = safe.top,
            safeBottom = safe.bottom,
            contentLeft = host.contentLeft(),
            contentWidth = host.contentWidth(),
            density = density,
        )
        ids.forEach(::settleStrip)
        holeJob?.cancel()
        holeJob = scope.launchNow { holeY.snapTo(metrics.slotY(k)) }
        velocity.reset()
        velocity.add(uptimeMs, fingerY)
        pendingExit = null
        session = CarrySession(carried, ids, metrics, startsFor(host, ids, metrics), lastY = fingerY)
        foldJob = scope.launchNow { fold.animateTo(1f, specs.fold) }
        motion.carryActive = true
        motion.placeholdersAboveOpen = true
    }

    /**
     * The strips' metrics if [count] sections folded now, slot top aside: what
     * the strip stack sizes its labels by before a carry. Null without a host.
     */
    fun provisionalMetrics(count: Int): StripMetrics? {
        val host = host ?: return null
        val safe = host.safeArea()
        return stripMetrics(
            count = count,
            carriedIndex = 0,
            fingerY = safe.top,
            safeTop = safe.top,
            safeBottom = safe.bottom,
            contentLeft = host.contentLeft(),
            contentWidth = host.contentWidth(),
            density = density,
        )
    }

    /** 1:1 in y, a swap each time the finger passes half a pitch, a rubber band past the ends (§4.6). */
    fun move(y: Float, uptimeMs: Long) {
        val s = session ?: return
        if (s.phase != CarryPhase.Drag) return
        s.dragOffset += y - s.lastY
        s.lastY = y
        velocity.add(uptimeMs, y)
        val half = s.metrics.pitch / 2f
        while (s.dragOffset > half && s.k < s.count - 1) swap(s, 1)
        while (s.dragOffset < -half && s.k > 0) swap(s, -1)
        val over = s.isOver(s.dragOffset)
        s.display = if (over) sign(s.dragOffset) * rubber(abs(s.dragOffset), density) else s.dragOffset
        val overNow = over && abs(s.dragOffset) > overHapticPx
        if (overNow && !s.wasOver) feedback.threshold()
        s.wasOver = overNow
    }

    /**
     * The finger lifted (or the carry was cancelled: no fling). The strip
     * settles into its slot, a fast release projecting it one slot at most,
     * then the order commits and the stack unfolds (§4.8–4.11).
     */
    fun release(cancelled: Boolean, uptimeMs: Long) {
        val s = session
        if (s == null) {
            // The carry never started: put the block down where it is.
            dropLift(0f)
            return
        }
        if (s.phase != CarryPhase.Drag) return
        s.phase = CarryPhase.Settle
        val v = if (cancelled) 0f else velocity.velocity(uptimeMs)
        // Where the strip shows now, read before a fling moves its slot.
        val shownY = s.metrics.slotY(s.k) + s.display
        if (abs(v) > flingVelocity) {
            val dir = if (v > 0f) 1 else -1
            if ((dir > 0 && s.k < s.count - 1) || (dir < 0 && s.k > 0)) swap(s, dir)
        }
        val from = shownY - s.metrics.slotY(s.k)
        settleJob = scope.launchNow {
            settleY.snapTo(from)
            settleY.animateTo(0f, specs.settlePx, initialVelocity = v)
            commitAndUnfold(s)
        }
    }

    /**
     * Catches the strip while it settles (§4.12): a press on it, ±8dp, takes
     * it back where it is and the drag goes on. Never while unfolding.
     */
    fun tryRegrab(position: Offset, uptimeMs: Long): Boolean {
        val s = session ?: return false
        if (s.phase != CarryPhase.Settle) return false
        val m = s.metrics
        val settle = settleY.value
        val top = m.slotY(s.k) + settle
        val inside = position.x >= m.left && position.x <= m.left + m.width &&
            position.y >= top - regrabSlopPx && position.y <= top + m.height + regrabSlopPx
        if (!inside) return false
        settleJob?.cancel()
        settleJob = null
        val over = s.isOver(settle)
        s.dragOffset = if (over) sign(settle) * unrubber(abs(settle), density) else settle
        s.display = settle
        s.lastY = position.y
        s.wasOver = over && abs(s.dragOffset) > overHapticPx
        s.phase = CarryPhase.Drag
        velocity.reset()
        velocity.add(uptimeMs, position.y)
        animateHole(m.slotY(s.k))
        feedback.dragStart()
        return true
    }

    /**
     * An exit arrived mid-carry (§4.13): a drag is released as cancelled, a
     * changed order still commits, and the exit runs at finish. With no carry
     * there is nothing to wait for.
     */
    fun deferExit(reason: HomeEditExitReason) {
        val s = session
        if (s == null) {
            finishDeferredExit(reason)
            return
        }
        pendingExit = reason
        if (s.phase == CarryPhase.Drag) release(cancelled = true, uptimeMs = uptimeMs())
    }

    /** A snap exit mid-carry: a changed order commits now, then every carry value is at rest (§4.13). */
    fun abortNow() {
        val s = session ?: return
        // In Unfold the order is already committed.
        if (s.phase != CarryPhase.Unfold && s.changed) commitOrder(s.ids)
        settleJob?.cancel()
        settleJob = null
        foldJob?.cancel()
        foldJob = null
        holeJob?.cancel()
        holeJob = null
        HomeSection.entries.forEach(::settleStrip)
        pendingExit = null
        session = null
        scope.launchNow { fold.snapTo(0f) }
        scope.launchNow { settleY.snapTo(0f) }
        scope.launchNow { holeY.snapTo(0f) }
        snapLiftToRest()
        motion.carryActive = false
    }

    /** The strip frame of [section] at the current fold (§4.4). Draw and placement only. */
    fun frameFor(section: HomeSection): StripFrame? {
        val s = session ?: return null
        val index = s.ids.indexOf(section)
        val start = s.starts[section]
        if (index < 0 || start == null) return null
        val carried = section == s.carried
        val offset = when {
            !carried -> stripOffset(section)
            s.phase == CarryPhase.Drag -> s.display
            else -> settleY.value
        }
        return stripFrame(
            start = start,
            slotTop = s.metrics.slotY(index) + offset,
            metrics = s.metrics,
            fold = fold.value,
            carried = carried,
            lift = lift.value,
            density = density,
        )
    }

    // The neighbour takes the carried strip's slot but keeps its place on
    // screen, then makes way (critique 6); the hole follows the carried strip.
    private fun swap(s: CarrySession, dir: Int) {
        val k = s.k
        val j = k + dir
        val ids = s.ids.toMutableList()
        val neighbour = ids[j]
        ids[j] = ids[k]
        ids[k] = neighbour
        s.ids = ids
        makeWay(neighbour, dir * s.metrics.pitch)
        s.k = j
        s.dragOffset -= dir * s.metrics.pitch
        animateHole(s.metrics.slotY(j))
        feedback.segmentTick()
    }

    private fun makeWay(section: HomeSection, by: Float) {
        val offset = stripOffsets.getValue(section)
        stripJobs.remove(section)?.cancel()
        val from = offset.floatValue + by
        offset.floatValue = from
        stripJobs[section] = scope.launchNow {
            animate(from, 0f, animationSpec = specs.neighbourPx) { value, _ -> offset.floatValue = value }
        }
    }

    private fun settleStrip(section: HomeSection) {
        stripJobs.remove(section)?.cancel()
        stripOffsets.getValue(section).floatValue = 0f
    }

    // From its current motion, as the prototype's retargets do.
    private fun animateHole(y: Float) {
        holeJob = scope.launchNow { holeY.animateTo(y, specs.holePx) }
    }

    private fun startsFor(
        host: CarryHost,
        ids: List<HomeSection>,
        metrics: StripMetrics,
    ): Map<HomeSection, StripStart> = ids.associateWith { stripStart(host.plateRect(it), metrics, host.isAbove(it)) }

    // §4.9–4.10: commit, anchor the dropped block under its slot, re-read
    // B_i from the new layout, and unfold with the lift falling alongside.
    private suspend fun commitAndUnfold(s: CarrySession) {
        s.phase = CarryPhase.Unfold
        val changed = s.changed && commitOrder(s.ids)
        host?.let { host ->
            host.anchorAndAwaitLayout(s.carried, s.ids, s.metrics.slotY(s.k))
            s.starts = startsFor(host, s.ids, s.metrics)
        }
        liftJob = scope.launchNow { lift.animateTo(0f, specs.liftDown) }
        tintJob = scope.launchNow { liftTint.animateTo(0f, specs.liftTint) }
        fold.animateTo(0f, specs.fold)
        finish(s, changed)
    }

    // §4.11: the overlay goes, the sway comes back to 1, the dropped block
    // and every block that moved get a kick, then a deferred exit runs.
    private fun finish(s: CarrySession, changed: Boolean) {
        settleJob = null
        session = null
        snapLiftToRest()
        motion.carryActive = false
        motion.touch()
        motion.impulse(s.carried, HomeEditTokens.KickDrop)
        if (changed) {
            s.ids.forEachIndexed { index, section ->
                if (section != s.carried && s.orig.indexOf(section) != index) {
                    motion.impulse(section, HomeEditTokens.KickNeighbour)
                }
            }
        }
        pendingExit?.let { reason ->
            pendingExit = null
            finishDeferredExit(reason)
        }
    }
}
