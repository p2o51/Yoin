package com.gpo.yoin.ui.memories.award

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.gpo.yoin.ui.memories.emblem.GrooveAwardMode
import com.gpo.yoin.ui.memories.emblem.GrooveAwardState
import com.gpo.yoin.ui.memories.emblem.GrooveBeatMode
import com.gpo.yoin.ui.memories.emblem.awardBeats
import com.gpo.yoin.ui.memories.emblem.strongestBeat
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/*
 * The groove award's lifecycle in the deck (twostate4 `awardDue` / `awardOk` / `startAward` /
 * `interruptAward` / `pauseAward` / `syncOne`):
 *
 *  · Each card gets its tiered award ONCE per Memories open, the first time it is fully on show: on open
 *    once the reveal is 85% in (q ≤ .15), when a page settle comes within .15 of it with the finger off the
 *    glass, and (P5b) when the diary closes back onto it. The first beat lands ≥ 120ms after the finger
 *    lifts. A new open is a new lifecycle: Memories unmounts when it closes, so every open re-awards.
 *  · A card not yet awarded waits in the award's first frame (nothing cut), never the finished disc.
 *  · Any drag pauses the beats (the picture keeps playing); coming back to the same card resumes them.
 *  · Really leaving a card before its climax beat un-awards it: once the pager has settled elsewhere it
 *    goes back to waiting and earns the whole award next time. Past the climax it counts.
 *  · Unrated cards have no award and never wait.
 */

/** Award timing constants (not back thresholds: they gate a ceremony, they move nothing). */
object MemoriesAwardTokens {
    /** The first beat lands at least this long after the finger lifts. */
    const val FirstBeatAfterLiftMs = 120L

    /** On open: the award starts once the reveal is 85% of the way in. */
    const val OpenRevealFraction = 0.15f

    /** On a page settle: the award starts once the pager is this close to the card. */
    const val PagerSettleDistance = 0.15f

    /** p within this of 0 counts as the card state (not the rubber band, not the diary). */
    const val CardStateEpsilon = 0.002f
}

/** One card's award, as the lifecycle drives it: a [GrooveAwardState] in the app, a fake in tests. */
interface MemoriesAwardTarget {
    /** Tier > 0: the card has a ceremony. Unrated cards never wait and never play. */
    val hasAward: Boolean

    /** The waiting pose: the choreography's first frame, nothing cut. */
    fun showPending()

    /** Plays the first award from its first frame. */
    fun playFirst(): MemoriesAwardRun
}

/** A playing award. */
interface MemoriesAwardRun {
    /** The strongest beat's time has passed: an interruption no longer un-awards the card. */
    val climaxed: Boolean

    /** Beats stop at once; the picture settles to rest from where it is. */
    fun interrupt()

    fun pauseBeats()

    fun resumeBeats()
}

/** What decides whether an award is due, read together from the deck. */
@Immutable
data class MemoriesAwardInputs(
    /** The card the pager is on, or heading to once the finger is up; null without a deck. */
    val cardKey: String?,
    val fingerDown: Boolean,
    /** The pager is within [MemoriesAwardTokens.PagerSettleDistance] of that card. */
    val nearCard: Boolean,
    /** The reveal is at least 85% in. */
    val revealIn: Boolean,
    /** p is at the card: not rubber-banding, not in or toward the diary. */
    val cardState: Boolean,
)

/** Pure: how long after [nowMs] an award may start so its first beat is ≥ 120ms past the lift at [liftMs]. */
internal fun awardStartDelayMs(liftMs: Long, nowMs: Long): Long =
    max(0L, liftMs + MemoriesAwardTokens.FirstBeatAfterLiftMs - nowMs)

/** Pure: the open award's gate on the reveal (q = 1 closed, 0 open). */
internal fun revealInForAward(revealFraction: Float): Boolean = revealFraction <= MemoriesAwardTokens.OpenRevealFraction

/** Pure: the settle award's gate on the pager's continuous [position]. */
internal fun pagerNearCard(position: Float, card: Int): Boolean =
    abs(position - card) < MemoriesAwardTokens.PagerSettleDistance

internal fun pInCardState(p: Float): Boolean = abs(p) <= MemoriesAwardTokens.CardStateEpsilon

/** The award bookkeeping of ONE Memories open. Not thread-safe: call on the main thread. */
@Stable
class MemoriesAwardLifecycle {
    private class Running(val key: String, val run: MemoriesAwardRun, val first: Boolean)

    private val targets = HashMap<String, MemoriesAwardTarget>()
    private val awarded = HashSet<String>()
    private val unaward = HashSet<String>()
    private var running: Running? = null
    private var liftMs = Long.MIN_VALUE / 2

    /** Debug builds' haptic trace: one line per lifecycle step (start, pause, resume, interrupt, reset). */
    var debugLog: ((String) -> Unit)? = null

    /** A finger is on the page (snapshot state: the award gate reads it). */
    var fingerDown: Boolean by mutableStateOf(false)
        private set

    fun isAwarded(key: String): Boolean = key in awarded

    /** A card's award came on screen; it waits unless it already had its award this open. */
    fun register(key: String, target: MemoriesAwardTarget) {
        targets[key] = target
        if (target.hasAward && key !in awarded) target.showPending()
    }

    fun unregister(key: String, target: MemoriesAwardTarget) {
        if (targets[key] === target) targets.remove(key)
    }

    fun onFingerDown() {
        fingerDown = true
    }

    fun onFingerUp(nowMs: Long) {
        fingerDown = false
        liftMs = nowMs
    }

    /** A drag started (either axis): beats pause, the picture plays on. */
    fun onDragStart() {
        val r = running ?: return
        r.run.pauseBeats()
        debugLog?.invoke("pause beats · ${r.key}")
    }

    /**
     * The pager is on, or heading to, [key] with the finger off (a settle, a fling, a dot tap). The same
     * card: the beats go on. Another card: the award stops, and a first award cut off before its climax did
     * not happen (it waits again once off screen, see [onPagerSettled]).
     */
    fun onCardTargeted(key: String?) {
        val r = running ?: return
        if (r.key == key) {
            r.run.resumeBeats()
            return
        }
        val cutShort = r.first && !r.run.climaxed
        if (cutShort) unaward += r.key
        r.run.interrupt()
        running = null
        debugLog?.invoke("interrupt · ${r.key}" + if (cutShort) " · before climax: un-award" else "")
    }

    /** The pager came to rest on [key]: cards cut off before their climax go back to waiting, off screen. */
    fun onPagerSettled(key: String?) {
        if (unaward.isEmpty()) return
        unaward.forEach { k ->
            if (k != key) {
                awarded -= k
                targets[k]?.showPending()
                debugLog?.invoke("waiting again · $k")
            }
        }
        unaward.clear()
    }

    /** The retreat to Home committed: the award stops (it counts, the card was seen). */
    fun onDismissCommitted() {
        val r = running ?: return
        r.run.interrupt()
        running = null
        debugLog?.invoke("interrupt · ${r.key} · Home")
    }

    /** Whether the card in [inputs] is due its award now (before the lift delay). */
    fun isDue(inputs: MemoriesAwardInputs): Boolean {
        val key = inputs.cardKey ?: return false
        if (inputs.fingerDown || !inputs.nearCard || !inputs.revealIn || !inputs.cardState) return false
        if (key in awarded) return false
        return targets[key]?.hasAward == true
    }

    /** Null when nothing is due; else how long to wait so the first beat is ≥ 120ms after the lift. */
    fun startDelayMs(inputs: MemoriesAwardInputs, nowMs: Long): Long? =
        if (isDue(inputs)) awardStartDelayMs(liftMs, nowMs) else null

    /** Starts the due award of [inputs] (call after [startDelayMs]); false when it is no longer due. */
    fun start(inputs: MemoriesAwardInputs): Boolean {
        if (!isDue(inputs)) return false
        val key = inputs.cardKey ?: return false
        val target = targets[key] ?: return false
        running?.run?.interrupt()
        awarded += key
        running = Running(key, target.playFirst(), first = true)
        debugLog?.invoke("award · $key")
        return true
    }
}

@Composable
fun rememberMemoriesAwardLifecycle(): MemoriesAwardLifecycle = remember { MemoriesAwardLifecycle() }

/**
 * Drives [lifecycle] from the deck: [inputs] is read in a snapshot flow (never in composition), so the
 * pager's per-frame position costs no recomposition. A change of inputs cancels a pending lift delay
 * (collectLatest), which is exactly the prototype's re-check when its timer fires. [settledKey] is the
 * card the pager rests on, null while it moves.
 */
@Composable
fun MemoriesAwardEffects(
    lifecycle: MemoriesAwardLifecycle,
    inputs: () -> MemoriesAwardInputs,
    settledKey: () -> String?,
) {
    val currentInputs by rememberUpdatedState(inputs)
    val currentSettled by rememberUpdatedState(settledKey)
    LaunchedEffect(lifecycle) {
        snapshotFlow { currentInputs() }
            .distinctUntilChanged()
            .collectLatest { input ->
                if (!input.fingerDown) lifecycle.onCardTargeted(input.cardKey)
                val wait = lifecycle.startDelayMs(input, SystemClock.uptimeMillis()) ?: return@collectLatest
                if (wait > 0L) delay(wait)
                lifecycle.start(input)
            }
    }
    LaunchedEffect(lifecycle) {
        snapshotFlow { currentSettled() }
            .distinctUntilChanged()
            .collect { key -> if (key != null) lifecycle.onPagerSettled(key) }
    }
}

/**
 * A card's [GrooveAwardState] as an award target. "Climaxed" is measured from the play call against the
 * first award's strongest beat ([strongestBeat]), not from the haptic handle (which only exists from the
 * run's first frame, and is absent without haptics).
 */
class GrooveAwardTarget(
    private val state: GrooveAwardState,
    private val clock: () -> Long = SystemClock::uptimeMillis,
) : MemoriesAwardTarget {
    override val hasAward: Boolean get() = state.tier > 0

    override fun showPending() = state.setPending(true)

    override fun playFirst(): MemoriesAwardRun {
        state.play(GrooveAwardMode.First)
        val beats = awardBeats(state.model, GrooveBeatMode.First, state.size, state.reducedMotion)
        val climaxMs = if (beats.isEmpty()) 0L else strongestBeat(beats).atMs.toLong()
        val startedAt = clock()
        return object : MemoriesAwardRun {
            override val climaxed: Boolean get() = clock() - startedAt >= climaxMs

            override fun interrupt() = state.interrupt()

            override fun pauseBeats() {
                state.pauseBeats()
            }

            override fun resumeBeats() {
                state.resumeBeats()
            }
        }
    }
}
