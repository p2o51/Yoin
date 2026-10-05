package com.gpo.yoin.ui.home.edit

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.gpo.yoin.ui.experience.smoothstep
import com.gpo.yoin.ui.home.HomeLayout
import com.gpo.yoin.ui.home.HomeRowPreset
import com.gpo.yoin.ui.home.HomeRowStop
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.presetFrom
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Home edit mode's row resize (D1 option ②, owner 2026-10-05, H5: a
// bottom-right handle shaped like a rounded ⌟, "像 iOS 那样"). Each section
// that takes presets (Activities, Jump Back In) has ONE driver, its content
// height: the handle drag snaps it 1:1 to the finger (rubber-band past the
// smallest and largest stop), a release springs it to a stop with the release
// velocity, and the keyboard, TalkBack, Undo and Reset spring it from wherever
// it is. While it is between two stops the section composes both and every
// card interpolates between its two positions (HomeRowsBody / homeRowsCard):
// cards both stops show move; cards only the larger one shows fade and scale
// in, staggered top to bottom; a card that changes kind (a strip growing into
// a wide card) fades through. The stop's preset is written (with Undo) the
// moment the finger lifts. Every per-frame value is read in layout, placement
// or draw.

/** Numbers of the resize (d1-rows.md §3.2). */
internal object HomeRowsTokens {
    /** A stop changes this far past the midpoint between two stops. */
    val Hysteresis = 6.dp

    /** A release this fast travels one more stop when its short projection crosses the next midpoint. */
    val FlingVelocity = 650.dp
    const val ProjectSeconds = .15f

    /** Released past the smallest or largest stop, the spring takes this share of the finger's velocity. */
    const val OverVelocityShare = .25f

    /** The feed scrolls while the finger is this close to the bar (or the status tide), up to [AutoScrollMax]/s. */
    val AutoScrollEdge = 64.dp
    val AutoScrollMax = 900.dp

    /** Cards arriving with the larger stop grow from this scale. */
    const val IncomingScale = .88f

    /** The handle: the ⌟ glyph's box, stroke, inset from the plate's corner, touch box. */
    val HandleGlyph = 24.dp
    val HandleStroke = 3.dp
    val HandleInset = 6.dp
    val HandleTouch = 48.dp

    /**
     * The glyph's corner sits this far into the touch box from its top-left: the box's
     * bottom-right is the plate's own corner, so the whole 48dp target stays on the plate
     * (in landscape the plate ends ~8dp short of the screen edge — a box past it caught
     * edge swipes meant to scroll the feed).
     */
    val HandleCornerInTouch = HandleTouch - HandleInset

    /** Held: the glyph grows by this much (on top of the badge scale and the tick pulse). */
    const val HeldScale = .12f

    /** Hovered (mouse, trackpad): the handle's held look, this much of it. */
    const val HoverHot = .6f

    // Card transition windows over the stop-to-stop progress p (d1-rows.md §3.0).
    const val KindOutStart = .08f
    const val KindOutEnd = .42f
    const val KindInStart = .48f
    const val KindInEnd = .86f
    const val IncomingFirst = .08f
    const val IncomingSpread = .32f
    const val IncomingSpan = .55f
    const val IncomingLone = .15f
}

/** Scrolls the feed under a held handle near the screen's edges (d1-rows.md §3.2). Root px. */
internal interface HomeRowsAutoScroll {
    /** Scroll speed (px/s, + = content up) for a finger at [fingerRootY], before the ladder's limits. */
    fun speed(fingerRootY: Float): Float

    /** Scrolls by [px]; returns what the list consumed. */
    fun scrollBy(px: Float): Float
}

/** A card's rect in its layer and what it renders as there. */
private class RowsCardRect(val rect: Rect, val kind: Any)

/** Composed stops of a section: [a] ≤ [b]; [a] == [b] is one stop. */
internal data class HomeRowsPair(val a: Int, val b: Int)

/**
 * The stop pair that brackets content height [h] given the measured stop
 * [heights] (index → px) of a [count]-stop ladder: the highest measured stop at
 * or under [h] and the one above it — composed even before it is measured, so
 * its height arrives the next frame. Past the largest measured stop it is that
 * stop alone (the rubber band); under the smallest, the one below it if any.
 * [fallback] when nothing is measured. Pure.
 */
internal fun homeRowsPair(h: Float, count: Int, heights: Map<Int, Float>, fallback: Int): HomeRowsPair {
    if (count <= 0) return HomeRowsPair(0, 0)
    val top = heights[count - 1]
    if (top != null && h >= top) return HomeRowsPair(count - 1, count - 1)
    for (i in count - 2 downTo 0) {
        val hi = heights[i] ?: continue
        if (h >= hi) return HomeRowsPair(i, i + 1)
    }
    val lowest = (0 until count).firstOrNull { heights[it] != null } ?: return HomeRowsPair(fallback, fallback)
    return if (lowest > 0) HomeRowsPair(lowest - 1, lowest) else HomeRowsPair(0, 0)
}

/**
 * The stop [h] has reached from [current], measured [heights] only: it moves
 * a stop on once [h] is [hysteresis] past the midpoint to it. Pure.
 */
internal fun homeRowsNearest(h: Float, current: Int, count: Int, heights: Map<Int, Float>, hysteresis: Float): Int {
    var n = current.coerceIn(0, (count - 1).coerceAtLeast(0))
    while (n < count - 1) {
        val here = heights[n] ?: break
        val next = heights[n + 1] ?: break
        if (h > (here + next) / 2f + hysteresis) n++ else break
    }
    while (n > 0) {
        val here = heights[n] ?: break
        val below = heights[n - 1] ?: break
        if (h < (below + here) / 2f - hysteresis) n-- else break
    }
    return n
}

/**
 * Where a release lands: [nearest], or one stop further in the direction of
 * [velocity] (px/s, + = taller) when it is at least [flingVelocity] and its
 * [projectSeconds] projection from [h] crosses the midpoint to that stop. Pure.
 */
internal fun homeRowsReleaseStop(
    h: Float,
    nearest: Int,
    count: Int,
    heights: Map<Int, Float>,
    velocity: Float,
    flingVelocity: Float,
    projectSeconds: Float = HomeRowsTokens.ProjectSeconds,
): Int {
    if (abs(velocity) < flingVelocity) return nearest
    val dir = if (velocity > 0f) 1 else -1
    val next = (nearest + dir).coerceIn(0, count - 1)
    if (next == nearest) return nearest
    val here = heights[nearest] ?: return nearest
    val there = heights[next] ?: return nearest
    val midpoint = (here + there) / 2f
    val projected = h + velocity * projectSeconds
    return if (if (dir > 0) projected > midpoint else projected < midpoint) next else nearest
}

/**
 * One section's resize: its driver ([height], valid while [active]), the
 * ladder its body reported, each stop's measured height and card rects, and
 * the handle's visuals. Written by [HomeRowsEngine] (gestures, settles) and
 * by the body (ladder, heights, rects).
 */
@Stable
internal class HomeRowsTrack(val section: HomeSection) {
    /** Content height in px while [active]: the one driver. */
    val height = Animatable(0f)

    /** Between stops, or springing to one: the body composes the pair and reports [height]. */
    var active: Boolean by mutableStateOf(false)
        internal set

    /** The handle is held. */
    var dragging: Boolean by mutableStateOf(false)
        internal set

    /** The stop a settle is heading for, composed (hidden) until measured. */
    var target: Int? by mutableStateOf(null)
        internal set

    /** The handle's held look (0 rest, 1 held). Layer and draw only. */
    val hot = Animatable(0f)

    /** The handle's scale pulse on each stop crossing: 1 at rest. Layer only. */
    val pulse = Animatable(1f)

    /** This block's card wiggle gain: 0 while its handle is held. Draw only. */
    val wiggleGain = Animatable(1f)

    // ── Ladder (the body's, written after composition) ────────────────────

    var stopPresets: List<List<HomeRowPreset>> by mutableStateOf(emptyList())
        private set
    var rowCounts: List<Int> by mutableStateOf(emptyList())
        private set

    /** The stop of the section's current preset. */
    var restStop: Int by mutableIntStateOf(0)
        private set

    val stopCount: Int get() = stopPresets.size

    private var ladderKey: Any? = null
    private var heightsWidth = -1

    /** Measured height of each stop (px), at [heightsWidth]. */
    val heights = mutableStateMapOf<Int, Float>()

    private val rects = HashMap<Int, HashMap<Any, RowsCardRect>>()

    /** Bumped whenever a card rect changes: card layers re-read. */
    var rectVersion: Int by mutableIntStateOf(0)
        private set

    private val pairState = derivedStateOf {
        if (!active) {
            HomeRowsPair(restStop, restStop)
        } else {
            homeRowsPair(height.value, stopCount, heights, fallback = restStop)
        }
    }

    /** The two stops shown now (the rest stop alone when not [active]). */
    val pair: HomeRowsPair get() = pairState.value

    // Gesture bookkeeping (the engine's).
    internal var settleToken = 0
    internal var settleJob: Job? = null
    internal var nearest = 0

    /**
     * The body's ladder. A new [key] (other cards, another screen) forgets
     * every measured height and rect; [reset] then puts the track at rest.
     */
    internal fun setLadder(key: Any, stops: List<List<HomeRowPreset>>, rows: List<Int>, rest: Int, reset: () -> Unit) {
        if (key != ladderKey) {
            ladderKey = key
            heights.clear()
            rects.clear()
            if (active) reset()
        }
        if (stopPresets != stops) stopPresets = stops
        if (rowCounts != rows) rowCounts = rows
        if (restStop != rest) restStop = rest
    }

    /** The stop [preset] resolves to here. */
    fun stopOf(preset: HomeRowPreset): Int = stopPresets.indexOfFirst { preset in it }.coerceAtLeast(0)

    internal fun recordHeight(stop: Int, width: Int, px: Int) {
        if (width != heightsWidth) {
            heightsWidth = width
            heights.clear()
        }
        val value = px.toFloat()
        if (heights[stop] != value) heights[stop] = value
    }

    internal fun recordRect(stop: Int, key: Any, kind: Any, rect: Rect) {
        val map = rects.getOrPut(stop) { HashMap() }
        val old = map[key]
        if (old != null && old.rect == rect && old.kind == kind) return
        map[key] = RowsCardRect(rect, kind)
        rectVersion++
    }

    internal fun forgetRect(stop: Int, key: Any, rect: Rect) {
        val map = rects[stop] ?: return
        if (map[key]?.rect == rect) map.remove(key)
    }

    // ── Card transforms (layer blocks) ────────────────────────────────────

    private var rankKey: Triple<Int, Int, Int>? = null
    private var rankCache: Map<Any, Float> = emptyMap()

    /** Stop-to-stop progress of [pair] at the current height. */
    private fun progress(pair: HomeRowsPair): Float {
        if (pair.a == pair.b) return 0f
        val from = heights[pair.a] ?: return 0f
        val to = heights[pair.b] ?: return 0f
        if (to - from < 1f) return if (height.value >= to) 1f else 0f
        return ((height.value - from) / (to - from)).coerceIn(0f, 1f)
    }

    /** Where an incoming card's fade starts: staggered top to bottom, then left to right. */
    private fun incomingStart(pair: HomeRowsPair, key: Any, version: Int): Float {
        val cacheKey = Triple(pair.a, pair.b, version)
        if (rankKey != cacheKey) {
            val lower = rects[pair.a].orEmpty()
            val incoming = rects[pair.b].orEmpty().filterKeys { it !in lower }.entries
                .sortedWith(compareBy({ it.value.rect.top }, { it.value.rect.left }))
            val n = incoming.size
            rankCache = incoming.withIndex().associate { (k, entry) ->
                entry.key to if (n > 1) {
                    HomeRowsTokens.IncomingFirst + HomeRowsTokens.IncomingSpread * k / (n - 1)
                } else {
                    HomeRowsTokens.IncomingLone
                }
            }
            rankKey = cacheKey
        }
        return rankCache[key] ?: HomeRowsTokens.IncomingLone
    }

    /**
     * Card [key] of stop [stop]'s layer this frame: its offset toward its
     * place in the other stop, its alpha and its scale. Identity at rest.
     */
    internal fun applyCard(scope: GraphicsLayerScope, stop: Int, key: Any, reduced: Boolean) = with(scope) {
        translationX = 0f
        translationY = 0f
        alpha = 1f
        scaleX = 1f
        scaleY = 1f
        if (!active) return@with
        // Read first: the layer re-runs when a rect arrives.
        val version = rectVersion
        val pair = pair
        if (stop != pair.a && stop != pair.b) {
            // The settle target, composed only to be measured.
            alpha = 0f
            return@with
        }
        if (pair.a == pair.b) return@with
        val p = progress(pair)
        val lower = rects[pair.a]?.get(key)
        val upper = rects[pair.b]?.get(key)
        val sameLook = lower != null && upper != null && lower.kind == upper.kind &&
            abs(lower.rect.width - upper.rect.width) < 1f && abs(lower.rect.height - upper.rect.height) < 1f
        if (stop == pair.b) {
            when {
                lower != null && upper != null -> {
                    translationX = (lower.rect.left - upper.rect.left) * (1f - p)
                    translationY = (lower.rect.top - upper.rect.top) * (1f - p)
                    if (!sameLook) alpha = smoothstep(HomeRowsTokens.KindInStart, HomeRowsTokens.KindInEnd, p)
                }
                else -> {
                    // New in the larger stop (or not placed yet: shown once it is).
                    val start = incomingStart(pair, key, version)
                    val q = smoothstep(start, minOf(.98f, start + HomeRowsTokens.IncomingSpan), p)
                    alpha = q
                    if (!reduced) {
                        val s = lerp(HomeRowsTokens.IncomingScale, 1f, q)
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin.Center
                    }
                }
            }
        } else {
            when {
                upper == null -> {
                    // Only in the smaller stop (presets nest, so a card not placed yet).
                    alpha = if (lower == null) 0f else 1f - p
                }
                sameLook -> alpha = 0f
                else -> {
                    translationX = (upper.rect.left - lower!!.rect.left) * p
                    translationY = (upper.rect.top - lower.rect.top) * p
                    alpha = 1f - smoothstep(HomeRowsTokens.KindOutStart, HomeRowsTokens.KindOutEnd, p)
                }
            }
        }
    }
}

/**
 * Every section's [HomeRowsTrack] and the gestures and settles that drive
 * them. One per Home; [commit] writes a preset through the edit controller
 * (persisted, with Undo) and answers whether it changed anything.
 */
@Stable
internal class HomeRowsEngine(
    private val scope: CoroutineScope,
    private val specs: HomeEditSpecs,
    private val feedback: HomeEditFeedback,
    private val density: Density,
    private val editing: () -> Boolean,
    /** A carry is in progress: the handle doesn't grab. */
    private val carrying: () -> Boolean,
    private val currentPreset: (HomeSection) -> HomeRowPreset,
    private val commit: (HomeSection, HomeRowPreset) -> Boolean,
    private val uptimeMs: () -> Long = { SystemClock.uptimeMillis() },
) {
    private val tracks = HomeSection.entries.filter { it.supportsRows }.associateWith { HomeRowsTrack(it) }

    /** The feed under a held handle; Home sets it. */
    var autoScroll: HomeRowsAutoScroll? = null

    fun track(section: HomeSection): HomeRowsTrack? = tracks[section]

    private val resizingState = derivedStateOf { tracks.values.any { it.active } }

    /** Some block is between stops: the feed places 1:1 and votes the high frame rate. */
    val resizing: Boolean get() = resizingState.value

    private val draggingState = derivedStateOf { tracks.values.any { it.dragging } }

    /** Some handle is held (the carry stays out). */
    val dragging: Boolean get() = draggingState.value

    // ── The handle ────────────────────────────────────────────────────────

    private var drag: Drag? = null

    private class Drag(
        val track: HomeRowsTrack,
        val startStop: Int,
        val h0: Float,
        val y0: Float,
        var lastY: Float,
        var scrolled: Float = 0f,
        var raw: Float = 0f,
        var overMax: Boolean = false,
        var overMin: Boolean = false,
        val velocity: HomeEditVelocity,
        var autoScroll: Job? = null,
    )

    /**
     * The finger went down on [section]'s handle at [fingerRootY]. Starts the
     * resize unless another handle or a carry has the page, or there is only
     * one stop. A settle in flight is caught where it is.
     */
    fun grab(section: HomeSection, fingerRootY: Float, uptime: Long = uptimeMs()): Boolean {
        val track = tracks[section] ?: return false
        if (!editing() || carrying() || drag != null || track.stopCount < 2) return false
        val h0 = if (track.active) {
            track.height.value
        } else {
            track.heights[track.restStop] ?: return false
        }
        track.settleJob?.cancel()
        track.settleToken++
        scope.launchNow { track.height.snapTo(h0) }
        track.target = null
        track.active = true
        track.dragging = true
        track.nearest = homeRowsNearest(h0, track.restStop, track.stopCount, track.heights, 0f)
        val maxV = with(density) { HomeEditTokens.MaxV.toPx() }
        val d = Drag(
            track = track,
            startStop = track.restStop,
            h0 = h0,
            y0 = fingerRootY,
            lastY = fingerRootY,
            raw = h0,
            velocity = HomeEditVelocity(maxV).apply { add(uptime, h0) },
        )
        drag = d
        scope.launchNow { track.hot.animateTo(1f, specs.liftTint) }
        scope.launchNow { track.wiggleGain.animateTo(0f, specs.liftUp) }
        return true
    }

    /** The held finger is at [fingerRootY]. */
    fun dragTo(fingerRootY: Float, uptime: Long = uptimeMs()) {
        val d = drag ?: return
        d.lastY = fingerRootY
        update(d, uptime)
        // Frames only while the finger sits in an edge zone with room to go.
        if (d.autoScroll?.isActive != true && autoScrollSpeed(d) != 0f) {
            d.autoScroll = scope.launch { runAutoScroll(d) }
        }
    }

    /** The feed's scroll speed for [d] now (px/s): 0 outside the edge zones or with no room left to resize. */
    private fun autoScrollSpeed(d: Drag): Float {
        val scroller = autoScroll ?: return 0f
        val track = d.track
        val speed = scroller.speed(d.lastY)
        val hi = track.heights[track.stopCount - 1]
        val lo = track.heights[0]
        val h = track.height.value
        return when {
            speed > 0f && (hi == null || h >= hi) -> 0f
            speed < 0f && (lo == null || h <= lo) -> 0f
            else -> speed
        }
    }

    private fun update(d: Drag, uptime: Long) {
        val track = d.track
        val count = track.stopCount
        val raw = d.h0 + (d.lastY - d.y0) + d.scrolled
        d.raw = raw
        val lo = track.heights[0]
        val hi = track.heights[count - 1]
        val shown = when {
            hi != null && raw > hi -> {
                if (!d.overMax) {
                    d.overMax = true
                    feedback.threshold()
                }
                hi + rubber(raw - hi, density)
            }
            lo != null && raw < lo -> {
                if (!d.overMin) {
                    d.overMin = true
                    feedback.threshold()
                }
                lo - rubber(lo - raw, density)
            }
            else -> raw
        }
        scope.launchNow { track.height.snapTo(shown.coerceAtLeast(0f)) }
        d.velocity.add(uptime, raw)
        val hysteresis = with(density) { HomeRowsTokens.Hysteresis.toPx() }
        val next = homeRowsNearest(shown, track.nearest, count, track.heights, hysteresis)
        if (next != track.nearest) {
            track.nearest = next
            feedback.segmentTick()
            pulse(track)
        }
    }

    /** The finger lifted ([cancelled]: taken by the system). Lands on a stop and writes its preset. */
    fun release(cancelled: Boolean = false, uptime: Long = uptimeMs()) {
        val d = drag ?: return
        drag = null
        d.autoScroll?.cancel()
        val track = d.track
        val count = track.stopCount
        val shown = track.height.value
        val velocity = if (cancelled) 0f else d.velocity.velocity(uptime)
        val flingV = with(density) { HomeRowsTokens.FlingVelocity.toPx() }
        val stop = if (cancelled) {
            d.startStop
        } else {
            homeRowsReleaseStop(shown, track.nearest, count, track.heights, velocity, flingV)
        }
        if (stop != track.nearest) {
            track.nearest = stop
            feedback.segmentTick()
            pulse(track)
        }
        val lo = track.heights[0] ?: shown
        val hi = track.heights[count - 1] ?: shown
        val outside = shown > hi || shown < lo
        track.dragging = false
        scope.launchNow { track.hot.animateTo(0f, specs.trayIn) }
        scope.launchNow { track.wiggleGain.animateTo(1f, specs.liftDown) }
        settle(track, stop, if (outside) velocity * HomeRowsTokens.OverVelocityShare else velocity)
        if (stop != d.startStop) {
            val from = currentPreset(track.section)
            val preset = track.stopPresets.getOrNull(stop)?.presetFrom(from)
            if (preset != null && commit(track.section, preset)) feedback.confirm()
        }
    }

    private suspend fun runAutoScroll(d: Drag) {
        var last = -1L
        var running = true
        while (running) {
            withFrameNanos { now ->
                val dt = if (last < 0L) 0f else ((now - last) / 1e9f).coerceAtMost(1f / 24f)
                last = now
                val speed = autoScrollSpeed(d)
                val scroller = autoScroll
                if (speed == 0f || scroller == null) {
                    running = false
                    return@withFrameNanos
                }
                if (dt == 0f) return@withFrameNanos
                val consumed = scroller.scrollBy(speed * dt)
                if (consumed == 0f) {
                    // The list can't move further: stop until the finger moves again.
                    running = false
                    return@withFrameNanos
                }
                d.scrolled += consumed
                update(d, uptimeMs())
            }
        }
    }

    // ── Keyboard, TalkBack ────────────────────────────────────────────────

    /** True when [section] has a stop [delta] away from its current one (+1 more rows, −1 fewer). */
    fun canStep(section: HomeSection, delta: Int): Boolean {
        val track = tracks[section] ?: return false
        return (track.restStop + delta) in 0 until track.stopCount
    }

    /** One stop more (+1) or fewer (−1) rows: writes the preset; the settle follows it ([onLayoutChange]). */
    fun step(section: HomeSection, delta: Int): Boolean {
        val track = tracks[section] ?: return false
        if (!editing() || drag != null || carrying()) return false
        val stop = track.restStop + delta
        val presets = track.stopPresets.getOrNull(stop) ?: return false
        val preset = presets.presetFrom(currentPreset(section))
        if (!commit(section, preset)) return false
        feedback.segmentTick()
        pulse(track)
        return true
    }

    // ── Layout changes (drop, step, Undo, Reset) ──────────────────────────

    /**
     * An applied edit. A section whose preset changed springs from where it
     * shows now to its new stop — unless its release is already heading there.
     */
    fun onLayoutChange(previous: HomeLayout, next: HomeLayout) {
        if (!editing()) return
        for ((section, track) in tracks) {
            val from = previous.rowsOf(section)
            val to = next.rowsOf(section)
            if (from == to || track.stopCount == 0) continue
            val stop = track.stopOf(to)
            if (track.active && track.target == stop) continue
            if (!track.active && stop == track.restStop) continue
            val start = if (track.active) {
                track.height.value
            } else {
                track.heights[track.restStop] ?: continue
            }
            if (track.dragging) continue
            scope.launchNow { track.height.snapTo(start) }
            track.active = true
            settle(track, stop, velocity = 0f)
        }
    }

    private fun settle(track: HomeRowsTrack, stop: Int, velocity: Float) {
        track.settleJob?.cancel()
        val token = ++track.settleToken
        track.target = stop
        track.settleJob = scope.launchNow {
            try {
                // A stop never shown yet is composed (hidden) and measured first.
                val to = track.heights[stop] ?: snapshotFlow { track.heights[stop] }.filterNotNull().first()
                track.height.animateTo(to, specs.settlePx, initialVelocity = velocity)
            } finally {
                if (track.settleToken == token && !track.dragging) {
                    track.active = false
                    track.target = null
                }
            }
        }
    }

    private fun pulse(track: HomeRowsTrack) {
        if (specs.reduced) return
        scope.launchNow {
            track.pulse.snapTo(1f)
            track.pulse.animateTo(1f, specs.pulse, initialVelocity = HomeEditTokens.PulseVelocity)
        }
    }

    // ── Session ───────────────────────────────────────────────────────────

    /** Edit mode is ending: a held handle lets go where it is. */
    fun onExit() {
        if (drag != null) release(cancelled = true)
    }

    /** Everything at rest now (a snap exit, a carry starting, a profile switch). */
    fun snapAll() {
        drag?.autoScroll?.cancel()
        drag = null
        tracks.values.forEach(::snapTrack)
    }

    private fun snapTrack(track: HomeRowsTrack) {
        track.settleJob?.cancel()
        track.settleToken++
        track.dragging = false
        track.active = false
        track.target = null
        scope.launchNow { track.hot.snapTo(0f) }
        scope.launchNow { track.wiggleGain.snapTo(1f) }
    }

    /** A track's ladder changed under it: back to rest. */
    internal fun resetTrack(track: HomeRowsTrack) {
        if (drag?.track === track) {
            drag?.autoScroll?.cancel()
            drag = null
        }
        snapTrack(track)
    }
}

// ── The body: one layer per composed stop ─────────────────────────────────

/** The layer a card is composed in: its stop and the root its rect is measured from. */
@Stable
internal class HomeRowsLayer(val track: HomeRowsTrack, val stop: Int, val reduced: () -> Boolean) {
    var root: LayoutCoordinates? = null
}

internal val LocalHomeRowsLayer = compositionLocalOf<HomeRowsLayer?> { null }

/**
 * A section's cards at its row preset. With a [track] (Home, in or out of edit
 * mode) the stop of [preset] renders in a layer of its own; while the track
 * is [HomeRowsTrack.active] the stops around its height render together,
 * their cards interpolating (put [homeRowsCard] on every card), and the body
 * is as tall as the driver, clipped to the plate below it. Without a track
 * (previews) it is just [content] at [preset]'s stop.
 *
 * [ladder] is the section's ladder on this screen (HomeRowPresets.kt) and the
 * key of everything measured: pass it remembered. Only the [owner] body
 * drives the track — a composition fading out under a width crossfade passes
 * false and stays its static self.
 */
@Composable
internal fun <T> HomeRowsBody(
    track: HomeRowsTrack?,
    engine: HomeRowsEngine?,
    ladder: List<HomeRowStop<T>>,
    preset: HomeRowPreset,
    reduced: Boolean,
    modifier: Modifier = Modifier,
    owner: Boolean = true,
    content: @Composable (HomeRowStop<T>) -> Unit,
) {
    val rest = ladder.indexOfFirst { preset in it.presets }.coerceAtLeast(0)
    if (track == null || engine == null || ladder.isEmpty()) {
        ladder.getOrNull(rest)?.let { stop -> Box(modifier) { content(stop) } }
        return
    }
    val stops = remember(ladder) { ladder.map { it.presets } }
    val rows = remember(ladder) { ladder.map { it.rowCount } }
    if (owner) SideEffect { track.setLadder(ladder, stops, rows, rest) { engine.resetTrack(track) } }
    val live = owner && track.active
    val pair = if (owner) track.pair else HomeRowsPair(rest, rest)
    val target = if (owner) track.target else null
    // The pair is the rest stop alone at rest; while active it follows the
    // driver (a composition only when it crosses a stop), plus a settle target.
    val composed = if (!live) {
        listOf(rest)
    } else {
        listOfNotNull(pair.a, pair.b, target).distinct().sorted().filter { it in ladder.indices }
    }
    val currentReduced = rememberUpdatedState(reduced)
    Layout(
        content = {
            composed.forEach { index ->
                key(index) {
                    val layer = remember(track, index) { HomeRowsLayer(track, index) { currentReduced.value } }
                    Box(Modifier.onPlaced { layer.root = it }) {
                        CompositionLocalProvider(LocalHomeRowsLayer provides layer.takeIf { owner }) {
                            content(ladder[index])
                        }
                    }
                }
            }
        },
        modifier = modifier.drawWithContent {
            if (!owner || !track.active) {
                drawContent()
            } else {
                val slack = HomePlateTrial.V1OutsetV.toPx()
                val big = size.width
                clipRect(left = -big, top = -size.height, right = size.width + big, bottom = size.height + slack) {
                    this@drawWithContent.drawContent()
                }
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val placeables = measurables.map { it.measure(loose) }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeables.maxOfOrNull { it.width } ?: 0
        if (owner) {
            placeables.forEachIndexed { i, placeable ->
                composed.getOrNull(i)?.let { stop -> track.recordHeight(stop, width, placeable.height) }
            }
        }
        val height = if (owner && track.active) {
            track.height.value.roundToInt().coerceAtLeast(0)
        } else {
            placeables.firstOrNull()?.height ?: 0
        }
        layout(width, constraints.constrainHeight(height)) {
            placeables.forEach { it.place(0, 0) }
        }
    }
}

/**
 * Card [key] of a row-preset section, rendering as [kind] (a card that is
 * another kind in the neighbouring stop fades through). Records where the
 * card sits in its stop and, while the section resizes, moves, fades and
 * scales it between stops in its placement layer. Put it first on the card's
 * modifier. A no-op outside [HomeRowsBody].
 */
internal fun Modifier.homeRowsCard(key: Any, kind: Any): Modifier = this then HomeRowsCardElement(key, kind)

private data class HomeRowsCardElement(val key: Any, val kind: Any) : ModifierNodeElement<HomeRowsCardNode>() {
    override fun create() = HomeRowsCardNode(key, kind)

    override fun update(node: HomeRowsCardNode) {
        node.update(key, kind)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "homeRowsCard"
        properties["key"] = key
    }
}

private class HomeRowsCardNode(private var key: Any, private var kind: Any) :
    Modifier.Node(),
    LayoutModifierNode,
    LayoutAwareModifierNode,
    CompositionLocalConsumerModifierNode {

    private var recorded: Rect? = null
    private var recordedIn: HomeRowsLayer? = null

    // The layer this card is composed in, resolved at measure.
    private var layer: HomeRowsLayer? = null

    private val layerBlock: GraphicsLayerScope.() -> Unit = {
        layer?.let { it.track.applyCard(this, it.stop, key, it.reduced()) }
    }

    fun update(key: Any, kind: Any) {
        if (key == this.key && kind == this.kind) return
        forget()
        this.key = key
        this.kind = kind
    }

    override fun onDetach() {
        forget()
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        val layer = currentValueOf(LocalHomeRowsLayer)
        this@HomeRowsCardNode.layer = layer
        return layout(placeable.width, placeable.height) {
            if (layer == null) placeable.place(0, 0) else placeable.placeWithLayer(0, 0, layerBlock = layerBlock)
        }
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        val layer = currentValueOf(LocalHomeRowsLayer) ?: return
        val root = layer.root?.takeIf { it.isAttached } ?: return
        val size = Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
        val rect = Rect(root.localPositionOf(coordinates, Offset.Zero), size)
        if (recordedIn !== layer) forget()
        recordedIn = layer
        recorded = rect
        layer.track.recordRect(layer.stop, key, kind, rect)
    }

    private fun forget() {
        val rect = recorded ?: return
        recordedIn?.let { it.track.forgetRect(it.stop, key, rect) }
        recorded = null
        recordedIn = null
    }
}

/** The auto-scroll's ramp over the edge zone: 0 at its inner edge, 1 at the screen's, eased in. */
internal fun homeRowsEdgeRamp(depth: Float): Float {
    val x = depth.coerceIn(0f, 1f)
    return x * x
}
