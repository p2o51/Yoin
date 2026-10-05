package com.gpo.yoin.ui.home.edit

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import com.gpo.yoin.ui.experience.smoothstep
import com.gpo.yoin.ui.home.HomeSection
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/** A card edit-mode taps can find ([HomeEditMotion.cardAt]): its index in its section and its rect now. */
internal interface HomeEditCardSpot {
    val cardIndex: Int

    /** The card's rect in its block's px at this moment; null when it can't be resolved. Events only. */
    fun rectInBlock(): Rect?
}

/** The page's safe area in Box px: below the status tide, above the bar (port sheet §3.4). */
@Immutable
internal data class HomeEditSafeArea(val top: Float, val bottom: Float)

/**
 * Where the entry block's plate grows from: R_press⁺ in block-local px, and
 * the charge it had at the threshold ([latch], dropped once P passes
 * [HomeEditTokens.LatchClearP]).
 */
@Immutable
internal data class PlateFrom(val section: HomeSection, val rect: Rect, val latch: Float)

/**
 * The normal-mode press charge (port sheet §2.2): from T/2 to T the pressed
 * block sinks about the finger and its plate pre-shows around it. The
 * gesture machine is the only writer; blocks read it in layer and draw.
 */
@Stable
internal class HomeEditPressState {
    val charge = Animatable(0f)

    /** The pressed block. Kept through the release, so the charge eases out where it was. */
    var section: HomeSection? by mutableStateOf(null)
        private set

    /** The press point in block-local px: the scale's pivot. */
    var origin: Offset by mutableStateOf(Offset.Zero)
        private set

    /** A charge is building or easing out: edit chrome may compose ahead of a long-press entry. */
    val isCharging: Boolean get() = charge.isRunning || charge.value > 0f

    fun start(scope: CoroutineScope, section: HomeSection, origin: Offset, specs: HomeEditSpecs) {
        // No charge and no plate latch under reduced motion (§1.2).
        if (specs.reduced) return
        this.section = section
        this.origin = origin
        scope.launchNow { charge.animateTo(1f, specs.chargeUp) }
    }

    fun release(scope: CoroutineScope, specs: HomeEditSpecs) {
        if (charge.value == 0f && !charge.isRunning) return
        scope.launchNow { charge.animateTo(0f, specs.chargeDown) }
    }

    fun snapOff(scope: CoroutineScope) {
        scope.launchNow { charge.snapTo(0f) }
    }
}

/**
 * Bounds the gesture detector must leave alone (header icons, badges, the
 * tray, the footer, the bubble) and the drag handles. Registered from
 * `onPlaced` and resolved against the page Box only at pointer-down.
 */
@Stable
internal class HomeEditTargets {
    private var box: LayoutCoordinates? = null
    private val registrations = LinkedHashMap<Any, HomeEditTargetNode>()

    fun attachBox(coords: LayoutCoordinates) {
        box = coords
    }

    fun isExcluded(positionInBox: Offset): Boolean =
        registrations.any { (key, node) -> key !is HandleKey && node.contains(positionInBox) }

    fun handleAt(positionInBox: Offset): HomeSection? =
        registrations.entries.firstOrNull { (key, node) -> key is HandleKey && node.contains(positionInBox) }
            ?.let { (it.key as HandleKey).section }

    internal fun register(key: Any, node: HomeEditTargetNode) {
        registrations[key] = node
    }

    internal fun unregister(key: Any, node: HomeEditTargetNode) {
        if (registrations[key] === node) registrations.remove(key)
    }

    private fun HomeEditTargetNode.contains(positionInBox: Offset): Boolean {
        val box = box?.takeIf { it.isAttached } ?: return false
        val coordinates = coordinates?.takeIf { isAttached && it.isAttached } ?: return false
        if (!active()) return false
        return box.localBoundingBoxOf(coordinates).contains(positionInBox)
    }

    private data class HandleKey(val section: HomeSection)

    companion object {
        internal fun handleKey(section: HomeSection): Any = HandleKey(section)
    }
}

/** A button or other control the edit gestures never claim. [active] is read at pointer-down. */
internal fun Modifier.homeEditExclusion(
    targets: HomeEditTargets,
    key: Any,
    active: () -> Boolean = { true },
): Modifier = this then HomeEditTargetElement(targets, key, active)

/** [section]'s drag handle: a slop past it lifts and carries at once. */
internal fun Modifier.homeEditHandle(targets: HomeEditTargets, section: HomeSection): Modifier =
    this then HomeEditTargetElement(targets, HomeEditTargets.handleKey(section)) { true }

private data class HomeEditTargetElement(
    val targets: HomeEditTargets,
    val key: Any,
    val active: () -> Boolean,
) : ModifierNodeElement<HomeEditTargetNode>() {
    override fun create() = HomeEditTargetNode(targets, key, active)

    override fun update(node: HomeEditTargetNode) {
        node.update(targets, key, active)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "homeEditTarget"
    }
}

internal class HomeEditTargetNode(
    private var targets: HomeEditTargets,
    private var key: Any,
    var active: () -> Boolean,
) : Modifier.Node(), LayoutAwareModifierNode {
    var coordinates: LayoutCoordinates? = null
        private set

    fun update(targets: HomeEditTargets, key: Any, active: () -> Boolean) {
        this.active = active
        if (targets === this.targets && key == this.key) return
        this.targets.unregister(this.key, this)
        this.targets = targets
        this.key = key
        if (coordinates != null) targets.register(key, this)
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        targets.register(key, this)
    }

    override fun onDetach() {
        targets.unregister(key, this)
        coordinates = null
    }
}

/**
 * Every Home-layer edit value that is not the carry's (port sheet §2.5, §3,
 * §5): the sway envelope E and its clock, the entry ripple, kicks and the
 * handle pulse, the entry plate latch, hide/show fades and the tray and
 * footer cross-fade. Each value has one writer, here; Home reads them in draw,
 * layer and layout only. Composition reads only the discrete flags
 * ([hiding], [heldSection], [placeholdersAboveOpen], the mount flags).
 *
 * [editing] and [progress] are the shell controller's; [uptimeMs] is
 * injectable because `SystemClock` is 0 in JVM tests.
 */
@Stable
internal class HomeEditMotion(
    private val scope: CoroutineScope,
    val specs: HomeEditSpecs,
    val style: HomeWiggleStyle,
    private val reduced: () -> Boolean,
    private val progress: () -> Float,
    private val editing: () -> Boolean,
    val feedback: HomeEditFeedback,
    private val uptimeMs: () -> Long = { SystemClock.uptimeMillis() },
    /** P is still on its way into edit mode (see [inputEditing]). */
    private val entering: () -> Boolean = { false },
) {
    // ── Envelope and clock (§3.5–3.6) ─────────────────────────────────────

    /** The one wiggle clock t (seconds); only [runClock] writes it. Draw only. */
    val swayT: MutableFloatState = mutableFloatStateOf(0f)

    /** Sway envelope E: rises on fastSpatial (overshooting to ~1.095), falls on defaultSpatial. */
    val envelope = Animatable(0f)

    // The target last asked for: the Animatable's own only updates once its coroutine runs.
    private var envelopeTarget = 0f
    private var lastTouch = 0L

    /** A finger is down on the page; the idle rule waits for it. Set by the gestures. */
    var pointerDown: Boolean = false
        set(value) {
            // The 6s window restarts when the finger lifts (proto fingerLifted).
            if (field && !value && editing()) lastTouch = uptimeMs()
            field = value
        }

    /** A block is carried as a strip: the others sway at [HomeEditTokens.DragEnvelope]. Set by the engine. */
    var carryActive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            setEnvelope()
        }

    /** Home is resumed, selected and not under Now Playing. */
    var visible: Boolean by mutableStateOf(true)

    private val inputEditingState = derivedStateOf { editing() && !entering() }

    /**
     * Edit mode as the cards' clicks and the badges' enabled state see it:
     * on once entering has settled. Nothing visible follows it, so the
     * recomposition it costs every card lands after the entry's frames.
     * Presses meanwhile are the edit gestures' (an edit-mode tap is
     * consumed on its way up), so no card opens.
     */
    val inputEditing: Boolean get() = inputEditingState.value

    val reducedMotion: Boolean get() = reduced()

    private val clockState = derivedStateOf {
        !reduced() && visible && (editing() || progress() > ProgressRest) &&
            (envelope.value > HomeEditTokens.SwayFloor || envelope.isRunning)
    }

    /** The clock runs only while a sway can show (port sheet §3.6). */
    val clockShouldRun: Boolean get() = clockState.value

    /**
     * The envelope's only writer besides the idle rule: 0 outside edit mode,
     * under reduced motion or in the Kick-only form; otherwise 1, or
     * [HomeEditTokens.DragEnvelope] while a block is carried.
     */
    fun setEnvelope() {
        if (!editing() || reduced() || style.mode == HomeWiggleMode.Kick) {
            if (envelopeTarget != 0f || (!envelope.isRunning && envelope.value != 0f)) {
                animateEnvelope(0f, specs.envDown)
            }
            return
        }
        val target = if (carryActive) HomeEditTokens.DragEnvelope else 1f
        if (envelopeTarget != target || (!envelope.isRunning && envelope.value != target)) {
            animateEnvelope(target, specs.envUp)
        }
    }

    /** Any press on the page or a bar click while editing: E back up, idle timer restarted. */
    fun touch(nowMs: Long = uptimeMs()) {
        lastTouch = nowMs
        setEnvelope()
    }

    /** IdleSettle: six seconds without a touch, no finger down and no carry, and the sway settles. */
    fun idleTick(nowMs: Long) {
        if (style.mode != HomeWiggleMode.IdleSettle || !editing() || carryActive || pointerDown) return
        if (envelopeTarget > 0f && nowMs - lastTouch > HomeEditTokens.IdleMs) animateEnvelope(0f, specs.envDown)
    }

    private fun animateEnvelope(target: Float, spec: FiniteAnimationSpec<Float>) {
        envelopeTarget = target
        scope.launchNow { envelope.animateTo(target, spec) }
    }

    /**
     * Advances [swayT] every frame, by the frame time capped at 1/24s (1/60s
     * on the first frame after a resume), and runs the idle rule. Runs until
     * cancelled; [HomeEditClock] runs it while [clockShouldRun].
     */
    internal suspend fun runClock() {
        var last = -1L
        while (true) {
            withFrameNanos { now ->
                val seconds = if (last < 0L) 1f / 60f else min((now - last) / 1e9f, 1f / 24f)
                last = now
                swayT.floatValue += seconds
            }
            idleTick(uptimeMs())
        }
    }

    // ── Ripple and entry (§2.5) ───────────────────────────────────────────

    /** The sections Home renders, in render order. Written by Home in a `SideEffect`. */
    var displayed: List<HomeSection> by mutableStateOf(emptyList())

    // The entry section, not its index: placeholders inserted on entry shift the indices.
    private var originSection: HomeSection? by mutableStateOf(null)

    /** Index of the entry block in [displayed] (0 when it has none): the ripple's i0. */
    val rippleOrigin: Int get() = displayed.indexOf(originSection).coerceAtLeast(0)

    private val kicked = HashSet<HomeSection>()

    /** Ripple progress p_i at the current P. Draw only. A section not displayed ripples as the origin. */
    fun ripple(section: HomeSection): Float {
        val order = displayed
        val origin = rippleOrigin
        val index = order.indexOf(section)
        return rippleProgress(progress(), if (index < 0) origin else index, origin, order.size)
    }

    /** The normalised sway of [section] (and all its cards) at envelope [envelope]. Draw only. */
    fun sway(section: HomeSection, envelope: Float): Float {
        if (envelope <= HomeEditTokens.SwayFloor) return 0f
        return swayValue(envelope, smoothstep(0f, 1f, ripple(section)), swayT.floatValue, sectionSway.getValue(section))
    }

    private val sectionSway = HomeSection.entries.associateWith { swayParams(it.id) }

    /** The entry plate: R_press⁺ and its latch. Draw only. */
    var plateFrom: PlateFrom? by mutableStateOf(null)

    /** Latches the entry plate at the threshold, from the charge read before it is released. */
    fun latchPlate(section: HomeSection, pressRect: Rect, charge: Float) {
        plateFrom = if (reduced()) null else PlateFrom(section, pressRect, charge.coerceIn(0f, 1f))
    }

    /**
     * Entry effects for as long as Home is composed (Home's `LaunchedEffect`):
     * each block's entry kick once its p_i reaches [HomeEditTokens.EntryKickAt]
     * (the lifted block is marked, not kicked), the latch cleared at P ≥ .6
     * and the entry plate dropped once grown or once P is back at rest.
     * [displayed] is watched too, as the prototype checks every frame: a
     * placeholder held back above the lifted block joins after P has settled
     * and still gets its one entry kick.
     */
    suspend fun runEntryEffects() {
        snapshotFlow { progress() to displayed }.collect { (p, _) -> entryTick(p) }
    }

    private fun entryTick(p: Float) {
        val editing = editing()
        if (editing) {
            val order = displayed
            val origin = rippleOrigin
            order.forEachIndexed { index, section ->
                if (section !in kicked && rippleProgress(p, index, origin, order.size) >= HomeEditTokens.EntryKickAt) {
                    kicked += section
                    if (section != heldSection) impulse(section, HomeEditTokens.KickEntry)
                }
            }
        }
        val from = plateFrom ?: return
        val grown = editing && smoothstep(0f, 1f, ripple(from.section)) >= PlateGrown
        plateFrom = when {
            grown || (!editing && p <= ProgressRest) -> null
            p >= HomeEditTokens.LatchClearP && from.latch != 0f -> from.copy(latch = 0f)
            else -> from
        }
    }

    // ── Kicks and the handle pulse (§2.7, §3.7) ───────────────────────────

    private val blockKicks = HomeSection.entries.associateWith { Animatable(0f) }

    // Card kicks appear as cards are tapped; a state map so a draw that found none is told.
    private val cardKicks = mutableStateMapOf<Int, Animatable<Float, AnimationVector1D>>()
    private val pulses = HomeSection.entries.associateWith { Animatable(1f) }

    // Per section, every card node that edit-mode taps can find. Keyed by the
    // node, not its index: a row resize composes two stops whose cards share
    // indices, and the stop that leaves must take only its own cards along.
    private val cardSpots = HashMap<HomeSection, LinkedHashSet<HomeEditCardSpot>>()

    /** Normalised block kick (first peak = its amplitude). Draw only. */
    fun blockKick(section: HomeSection): Float = blockKicks.getValue(section).value

    fun cardKick(section: HomeSection, card: Int): Float = cardKicks[cardKey(section, card)]?.value ?: 0f

    /** Kicks the whole block (every card follows, alternating). Skipped under reduced motion. */
    fun impulse(section: HomeSection, a: Float) {
        if (reduced()) return
        kick(blockKicks.getValue(section), a, swaySign(section.id))
    }

    /** Kicks one card only; outside the card-level form the block takes it. */
    fun impulseCard(section: HomeSection, card: Int, a: Float) {
        if (reduced()) return
        if (style.target != HomeWiggleTarget.Card) {
            impulse(section, a)
            return
        }
        val anim = cardKicks.getOrPut(cardKey(section, card)) { Animatable(0f) }
        kick(anim, a, swaySign(section.id) * cardAlt(card))
    }

    // From the current value with no snap: a block still ringing takes the kick along its motion.
    private fun kick(anim: Animatable<Float, AnimationVector1D>, a: Float, sign: Int) {
        val velocity = kickInitialVelocity(a, sign, anim.isRunning, anim.velocity)
        scope.launchNow { anim.animateTo(0f, specs.kick, initialVelocity = velocity) }
    }

    /** Snaps the block's kick and its cards' to 0 (a lift stills the block). */
    fun stopKicks(section: HomeSection) {
        val cards = cardKicks.filterKeys { it shr CardKeyShift == section.ordinal }.values
        (cards + blockKicks.getValue(section)).forEach { scope.launchNow { it.snapTo(0f) } }
    }

    /** The handle's scale multiplier: 1 at rest. Layer only. */
    fun handlePulse(section: HomeSection): Float = pulses.getValue(section).value

    /** One underdamped bounce 1 → 1.2 → 1: "drag from here". */
    fun pulseHandle(section: HomeSection) {
        if (reduced()) return
        val pulse = pulses.getValue(section)
        scope.launchNow {
            pulse.snapTo(1f)
            pulse.animateTo(1f, specs.pulse, initialVelocity = HomeEditTokens.PulseVelocity)
        }
    }

    /** A card of [section] taps can find (from `onPlaced`). A plain set: read at events only. */
    fun registerCard(section: HomeSection, spot: HomeEditCardSpot) {
        cardSpots.getOrPut(section) { LinkedHashSet() } += spot
    }

    fun unregisterCard(section: HomeSection, spot: HomeEditCardSpot) {
        cardSpots[section]?.remove(spot)
    }

    /**
     * The card under [posInBlock], for an edit-mode tap. Each card's rect is
     * resolved now, not remembered from its last placement: a card that
     * settled under a row-resize layer was placed mid-transform, and its
     * layer moved it since without placing it again.
     */
    fun cardAt(section: HomeSection, posInBlock: Offset): Int? =
        cardSpots[section]?.firstOrNull { spot -> spot.rectInBlock()?.contains(posInBlock) == true }?.cardIndex

    // ── Hide and show (§5.1–5.3) ──────────────────────────────────────────

    private val hideScales = HomeSection.entries.associateWith { Animatable(1f) }
    private val hideAlphas = HomeSection.entries.associateWith { Animatable(1f) }
    private val rowAlphas = HomeSection.entries.associateWith { Animatable(1f) }

    // Bumped by every change and snap: a fade-out removes its blocks only if nothing came after it.
    private var layoutGeneration = 0

    /** Disabled sections still rendered while they fade out. Composition reads it. */
    var hiding: Set<HomeSection> by mutableStateOf(emptySet())
        private set

    /**
     * Sections switched on (Show, Reset, Undo) whose fade-in [hideAlpha] owns.
     * Home gives their inserted item no fade of its own (critique 10); every
     * other insert keeps it. Composition reads it.
     */
    var showing: Set<HomeSection> by mutableStateOf(emptySet())
        private set

    // A show's fade-in leaves [showing] only if no later show of the section replaced it.
    private val showTokens = HashMap<HomeSection, Int>()

    fun hideScale(s: HomeSection): Float = hideScales.getValue(s).value

    fun hideAlpha(s: HomeSection): Float = hideAlphas.getValue(s).value

    /**
     * Animates one applied edit. Newly disabled sections scale and fade out,
     * then leave [hiding]; newly enabled ones fade and scale in, kicked only
     * for a Show in the viewport; one switched back on mid-fade turns round
     * from where it is. New tray rows fade in; rows that leave are removed.
     */
    fun onLayoutChange(change: HomeEditChange, isInViewport: (HomeSection) -> Boolean) {
        val generation = ++layoutGeneration
        val wasEnabled = change.previous.enabledSections.toSet()
        val enabled = change.next.enabledSections.toSet()

        change.next.hiddenSections.filter { it in wasEnabled }.forEach { section ->
            val row = rowAlphas.getValue(section)
            scope.launchNow {
                row.snapTo(0f)
                row.animateTo(1f, specs.trayIn)
            }
        }

        // Back on before its fade-out ended: no snap and no kick (proto commitDom).
        val revived = hiding.filterTo(HashSet()) { it in enabled }
        revived.forEach { section ->
            scope.launchNow { hideAlphas.getValue(section).animateTo(1f, specs.showAlphaIn) }
            scope.launchNow { hideScales.getValue(section).animateTo(1f, specs.showScaleIn) }
        }

        val appeared = (enabled - wasEnabled) - revived
        if (appeared.isNotEmpty()) showing = showing + appeared
        appeared.forEach { section ->
            // Past the entry ripple: no entry kick on top of the show kick.
            kicked += section
            val alpha = hideAlphas.getValue(section)
            val scale = hideScales.getValue(section)
            val token = (showTokens[section] ?: 0) + 1
            showTokens[section] = token
            scope.launchNow { blockKicks.getValue(section).snapTo(0f) }
            scope.launchNow {
                try {
                    alpha.snapTo(0f)
                    alpha.animateTo(1f, specs.showAlphaIn)
                } finally {
                    if (showTokens[section] == token) showing = showing - section
                }
            }
            scope.launchNow {
                scale.snapTo(HomeEditTokens.HideScale)
                scale.animateTo(1f, specs.showScaleIn)
            }
        }
        if (change.kind == HomeEditChangeKind.Show && appeared.isNotEmpty()) {
            scope.launch {
                // One frame to compose the insert, one to lay it out.
                repeat(2) { withFrameNanos { } }
                if (generation != layoutGeneration) return@launch
                appeared.filter(isInViewport).forEach { impulse(it, HomeEditTokens.KickNeighbour) }
            }
        }

        // Earlier fade-outs restart with this generation, so they all leave together.
        val fading = (hiding - revived) + (wasEnabled - enabled)
        hiding = fading
        if (showing.any { it in fading }) showing = showing - fading
        if (fading.isEmpty()) return
        val fades = fading.map { section ->
            scope.launchNow { hideScales.getValue(section).animateTo(HomeEditTokens.HideScale, specs.hideScaleOut) }
            scope.launchNow { hideAlphas.getValue(section).animateTo(0f, specs.hideAlphaOut) }
        }
        scope.launch {
            fades.joinAll()
            if (generation == layoutGeneration) hiding = hiding - fading
        }
    }

    // ── Tray and footer (§5.4) ────────────────────────────────────────────

    val trayAlpha = Animatable(0f)
    val footerAlpha = Animatable(1f)
    private var trayJob: Job? = null

    /** A tray row's own fade (on top of [trayAlpha]). Layer only. */
    fun rowAlpha(section: HomeSection): Float = rowAlphas.getValue(section).value

    private val trayMountedState = derivedStateOf { editing() || trayAlpha.value > HomeEditTokens.UnitThreshold }

    /** The tray is in the list: while editing and through its fade-out. */
    val trayMounted: Boolean get() = trayMountedState.value

    /** The footer entry replaces the tray once it has faded out. */
    val footerMounted: Boolean get() = !trayMountedState.value

    // ── Session (SPEC §2.1.4-7) ───────────────────────────────────────────

    /** The block lifted at entry. Placeholders above it wait, so it isn't pushed from under the finger. */
    var heldSection: HomeSection? by mutableStateOf(null)

    /** Opens on the first finger-up or when a carry starts. */
    var placeholdersAboveOpen: Boolean by mutableStateOf(true)

    /** Edit mode started: fresh ripple from [origin], footer out and tray in, sway up. */
    fun onEnter(origin: HomeSection?, lifted: Boolean) {
        trayJob?.cancel()
        trayJob = scope.launchNow {
            footerAlpha.snapTo(0f)
            trayAlpha.animateTo(1f, specs.trayIn)
        }
        kicked.clear()
        plateFrom = null
        originSection = origin
        heldSection = if (lifted) origin else null
        placeholdersAboveOpen = !lifted
        touch()
    }

    /**
     * An animated exit started ([reason] is for symmetry; Done's haptic is the
     * controller's): the plate retreats with P instead of holding its latch,
     * E falls, and the tray fades out before the footer fades in. Home layers
     * call [HomeCarryEngine.onExit], which also drops a lift and the press.
     */
    @Suppress("UNUSED_PARAMETER")
    fun onExit(reason: HomeEditExitReason) {
        plateFrom = plateFrom?.copy(latch = 0f)
        heldSection = null
        placeholdersAboveOpen = true
        setEnvelope()
        trayJob?.cancel()
        trayJob = scope.launchNow {
            trayAlpha.animateTo(0f, specs.trayOut)
            if (editing()) return@launchNow
            footerAlpha.snapTo(0f)
            footerAlpha.animateTo(1f, specs.trayIn)
        }
    }

    /** Every value at rest now. Ringing kicks may finish; nothing else moves. */
    fun onSnapExit() {
        trayJob?.cancel()
        trayJob = null
        layoutGeneration++
        hiding = emptySet()
        showing = emptySet()
        plateFrom = null
        heldSection = null
        placeholdersAboveOpen = true
        envelopeTarget = 0f
        scope.launchNow { envelope.snapTo(0f) }
        scope.launchNow { trayAlpha.snapTo(0f) }
        scope.launchNow { footerAlpha.snapTo(1f) }
        (hideAlphas.values + hideScales.values).forEach { scope.launchNow { it.snapTo(1f) } }
    }

    private fun cardKey(section: HomeSection, card: Int): Int = section.ordinal shl CardKeyShift or card
}

/**
 * Launches undispatched: a snap or the start of an animation lands in the
 * calling event, as the prototype's do, unless another animation of the same
 * value still has to unwind.
 */
internal fun CoroutineScope.launchNow(block: suspend CoroutineScope.() -> Unit): Job =
    launch(start = CoroutineStart.UNDISPATCHED, block = block)

// P at or below this counts as at rest (port sheet §2.5, §3.6).
private const val ProgressRest = .001f

// The entry plate is fully grown: the continuous Panel takes over.
private const val PlateGrown = .9999f
private const val CardKeyShift = 16
