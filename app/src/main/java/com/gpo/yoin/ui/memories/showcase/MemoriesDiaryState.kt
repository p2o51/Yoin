package com.gpo.yoin.ui.memories.showcase

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.navigation.back.BackMotionTokens
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Memories' inner controller **p**: the card ⇄ diary morph. It is an in-page
 * state machine inside the ShellOverlayUp (the outer **q**, the retreat to
 * Home, is [com.gpo.yoin.ui.experience.RevealState]); every gesture is handed
 * to exactly one of the two the moment it passes slop, never both.
 *
 *   fraction = 0f → card
 *   fraction = 1f → diary
 *   fraction < 0f → the card's own pull-down rubber band
 *                   ([rubberBand]×, floored at [rubberBandFloorPx] of morph travel)
 *
 * Diary scroll is NOT part of p: the diary's ScrollState owns it, and only
 * the overflow of a pull past the top of the text reaches [pullBy].
 *
 * Settle semantics copy RevealState: ONE settle owner. Any new input
 * ([startPull], [pullBy], [snapTo], [stop]) cancels the running spring first,
 * and a suspend settle interrupted that way cancels its caller. Every spring —
 * open, close, release, back commit, back cancel — rides the same [morphSpec]
 * (AGENTS: cancel and commit share one token family).
 *
 * Inputs: the Diary button and the bar cover / ⌄ (launchAnimateTo), the
 * card's pull-down (rubber band, capped at the card), the bar as the diary's
 * handle (1:1), a pull past the top of the diary text (MemoriesDiaryDeck's
 * nested scroll, banded) and system back at the diary level (stop + snapTo,
 * then launchAnimateTo). [isSettling] tells the award when p has landed.
 */
@Stable
class MemoriesDiaryState internal constructor(
    initialFraction: Float,
    morphDistancePx: Float,
    pullBandPx: Float,
    flickPxPerSec: Float,
    private val rubberBand: Float,
    rubberBandFloorPx: Float,
    morphSpec: AnimationSpec<Float>,
) {
    // The px metrics of the dp tokens. A density change updates them in place ([updateMetrics]): p itself is
    // density-free, so the controller — and everything keyed on it (the router, the deck, the bar) — survives.
    private var morphDistancePx = morphDistancePx
    private var pullBandPx = pullBandPx
    private var flickPxPerSec = flickPxPerSec
    private var rubberBandFloorPx = rubberBandFloorPx

    private var _fraction by mutableFloatStateOf(initialFraction.coerceIn(0f, 1f))

    /**
     * The one spring every p move rides. Swapped (not re-keyed) when reduced motion toggles, so a live
     * diary never resets: under reduced motion it is the critically damped effects spring (alpha only).
     */
    internal var morphSpec: AnimationSpec<Float> = morphSpec

    /** A spring is moving p (open, close, release, back commit / cancel). Snapshot state: award gates read it. */
    var isSettling: Boolean by mutableStateOf(false)
        private set

    private var settleJob: Job? = null

    // The live pull, in finger-linear morph px ("w" in the prototype). p is a
    // function of it, so the half-speed band and the rubber band never drift.
    private var pullTravelPx = 0f
    private var pullBanded = false
    private var pullFromScrolled = false
    private var pullCeiling = 1f

    private val minFraction: Float
        get() = if (morphDistancePx > 0f) -rubberBandFloorPx / morphDistancePx else 0f

    val fraction: Float get() = _fraction

    /** System back steps the diary (diary → card) from here up; below it back goes to q. */
    val isDiaryLevel: Boolean get() = _fraction >= 0.5f

    /** New px for the same dp tokens (the display density changed); p and any running spring are untouched. */
    internal fun updateMetrics(
        morphDistancePx: Float,
        pullBandPx: Float,
        flickPxPerSec: Float,
        rubberBandFloorPx: Float,
    ) {
        this.morphDistancePx = morphDistancePx
        this.pullBandPx = pullBandPx
        this.flickPxPerSec = flickPxPerSec
        this.rubberBandFloorPx = rubberBandFloorPx
    }

    /** Cancel any running spring and return where p stopped (a back gesture's p0). */
    fun stop(): Float {
        settleJob?.cancel()
        settleJob = null
        isSettling = false
        return _fraction
    }

    /** Snap without animation (a back preview frame). Cancels any running spring. */
    fun snapTo(target: Float) {
        stop()
        _fraction = target.coerceIn(minFraction, 1f)
    }

    /**
     * A finger takes p: cancels the running spring (it is caught where it is,
     * never jumped) and starts a pull. [banded] = the pull came out of the
     * diary text (its first [pullBandPx] past the top run at half speed);
     * [fromScrolled] = that text was scrolled when the finger went down (its
     * release inside the band stays in the diary — see [chooseDiaryReleaseTarget]).
     * [ceiling] caps p for this pull (never below the p it caught): the
     * card's own pull-down passes 0f, so a finger that turns back up stops
     * at the card and never opens the diary (that is the Diary button's job).
     */
    fun startPull(banded: Boolean, fromScrolled: Boolean, ceiling: Float = 1f) {
        stop()
        pullBanded = banded
        pullFromScrolled = banded && fromScrolled
        // never below where p already is: a pull that catches a spring above the ceiling must not jump
        pullCeiling = maxOf(ceiling.coerceIn(0f, 1f), _fraction.coerceAtMost(1f))
        pullTravelPx = diaryMorphToPull(
            morphPx = _fraction * morphDistancePx,
            distancePx = morphDistancePx,
            bandPx = pullBandPx,
            banded = banded,
            rubberBand = rubberBand,
        )
    }

    /**
     * Apply a finger delta (positive = finger moving down = toward the card),
     * 1:1 through the band and rubber band. Returns the part of [deltaPx] p
     * took; the rest belongs to the diary scroll (p already 1) or nobody
     * (rubber band floor) — the nested-scroll caller reports it as consumed.
     */
    fun pullBy(deltaPx: Float): Float {
        settleJob?.cancel()
        settleJob = null
        isSettling = false
        val maxTravel = diaryMorphToPull(
            morphPx = pullCeiling * morphDistancePx,
            distancePx = morphDistancePx,
            bandPx = pullBandPx,
            banded = pullBanded,
            rubberBand = rubberBand,
        )
        val minTravel = diaryMorphToPull(
            morphPx = minFraction * morphDistancePx,
            distancePx = morphDistancePx,
            bandPx = pullBandPx,
            banded = pullBanded,
            rubberBand = rubberBand,
        )
        val next = (pullTravelPx - deltaPx).coerceIn(minTravel, maxTravel)
        val consumed = pullTravelPx - next
        pullTravelPx = next
        val morphPx = diaryPullToMorph(
            travelPx = next,
            distancePx = morphDistancePx,
            bandPx = pullBandPx,
            banded = pullBanded,
            rubberBand = rubberBand,
        )
        _fraction = if (morphDistancePx > 0f) (morphPx / morphDistancePx).coerceIn(minFraction, 1f) else 0f
        return consumed
    }

    /**
     * Release the pull. [velocityPxPerSec]: positive = finger moving down
     * (RevealState.settle's convention). Springs to the endpoint chosen by
     * [chooseDiaryReleaseTarget] with the finger's velocity (none when a
     * fling "to the top" is held in the diary). Interruptible; returns the
     * endpoint (0f card, 1f diary).
     */
    suspend fun releasePull(velocityPxPerSec: Float): Float {
        val openVelocityPxPerSec = -velocityPxPerSec
        val target = chooseDiaryReleaseTarget(
            fraction = _fraction,
            openVelocityPxPerSec = openVelocityPxPerSec,
            distancePx = morphDistancePx,
            bandPx = pullBandPx,
            flickPxPerSec = flickPxPerSec,
            fromScrolled = pullFromScrolled,
        )
        val heldAtTop = pullFromScrolled && target >= 1f && _fraction < 1f &&
            _fraction * morphDistancePx >= morphDistancePx - pullBandPx / 2f
        val velocity = when {
            heldAtTop || morphDistancePx <= 0f -> 0f
            else -> openVelocityPxPerSec / morphDistancePx
        }
        if (_fraction == target) return target
        animateInternal(target, velocity)
        return target
    }

    /** Programmatic open (1f) / close (0f), e.g. the Diary button. Interruptible. */
    suspend fun animateTo(target: Float) {
        animateInternal(target.coerceIn(0f, 1f), initialVelocity = 0f)
    }

    /**
     * Fire-and-forget spring on [scope] — the back handler's commit (→ 0) and
     * cancel (→ 1) and state-driven toggles. Same spec as every other p move.
     */
    fun launchAnimateTo(scope: CoroutineScope, target: Float) {
        settleJob?.cancel()
        isSettling = true
        // Lazy, so settleJob already names this job when its first frame runs.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            try {
                animate(
                    initialValue = _fraction,
                    targetValue = target.coerceIn(0f, 1f),
                    initialVelocity = 0f,
                    animationSpec = morphSpec,
                ) { value, _ -> _fraction = value }
            } catch (_: CancellationException) {
                // A finger or a newer settle took p; leave it where it is.
            } finally {
                if (settleJob === self) {
                    settleJob = null
                    isSettling = false
                }
            }
        }
        settleJob = job
        job.start()
    }

    private suspend fun animateInternal(target: Float, initialVelocity: Float) {
        settleJob?.cancel()
        val owner = coroutineContext[Job]
        settleJob = owner
        isSettling = true
        try {
            animate(
                initialValue = _fraction,
                targetValue = target,
                initialVelocity = initialVelocity,
                animationSpec = morphSpec,
            ) { value, _ -> _fraction = value }
        } finally {
            if (settleJob === owner) {
                settleJob = null
                isSettling = false
            }
        }
    }
}

/**
 * Finger travel → morph travel for a diary pull (prototype `wToV`), both in
 * px of the morph axis, increasing toward the diary (0 = card, [distancePx] =
 * diary at the top of its text). [banded]: the first [bandPx] below the top
 * run at half speed; past the band the pull is shifted by the half band it
 * lost, so the mapping is continuous. Below 0 the card's rubber band applies.
 */
internal fun diaryPullToMorph(
    travelPx: Float,
    distancePx: Float,
    bandPx: Float,
    banded: Boolean,
    rubberBand: Float = DiaryRubberBand,
): Float {
    val morph = when {
        !banded || travelPx >= distancePx -> travelPx
        travelPx >= distancePx - bandPx -> distancePx - (distancePx - travelPx) * 0.5f
        else -> travelPx + bandPx / 2f
    }
    return if (morph < 0f) morph * rubberBand else morph
}

/** Inverse of [diaryPullToMorph] (prototype `vToW`): where a pull resumes from the current p. */
internal fun diaryMorphToPull(
    morphPx: Float,
    distancePx: Float,
    bandPx: Float,
    banded: Boolean,
    rubberBand: Float = DiaryRubberBand,
): Float {
    val morph = if (morphPx < 0f && rubberBand > 0f) morphPx / rubberBand else morphPx
    return when {
        !banded || morph >= distancePx -> morph
        morph >= distancePx - bandPx / 2f -> distancePx - (distancePx - morph) * 2f
        else -> morph - bandPx / 2f
    }
}

/**
 * Where a released diary pull settles (prototype `releaseV`). [openVelocityPxPerSec]
 * is positive toward the diary (finger moving up).
 * - Below the card (rubber band) → 0f.
 * - At or past the diary (p ≥ 1, the scroll owns the rest) → 1f.
 * - A pull that started in scrolled text and is still inside the first half
 *   of the band stays in the diary however fast it was flung: a fling "to the
 *   top" is reading, only a fresh pull from the top (or one past the whole
 *   band) can close it by speed.
 * - Otherwise a flick of [flickPxPerSec] either way decides, then p > 0.5.
 */
internal fun chooseDiaryReleaseTarget(
    fraction: Float,
    openVelocityPxPerSec: Float,
    distancePx: Float,
    bandPx: Float,
    flickPxPerSec: Float,
    fromScrolled: Boolean,
): Float = when {
    fraction < 0f -> 0f
    fraction >= 1f || distancePx <= 0f -> 1f
    fromScrolled && fraction * distancePx >= distancePx - bandPx / 2f -> 1f
    openVelocityPxPerSec > flickPxPerSec -> 1f
    openVelocityPxPerSec < -flickPxPerSec -> 0f
    fraction > 0.5f -> 1f
    else -> 0f
}

/** Linear rubber band of the card's pull below p = 0 (prototype `wToV`'s 0.3×). */
internal const val DiaryRubberBand = 0.3f

/** How far below the card p may be pulled, in morph travel (prototype clamp −90). */
private val DiaryRubberBandFloor: Dp = 90.dp

@Composable
fun rememberMemoriesDiaryState(initialFraction: Float = 0f, reducedMotion: Boolean = false): MemoriesDiaryState {
    val density = LocalDensity.current
    val morphDistancePx = with(density) { BackMotionTokens.MemoriesDiaryMorphDistance.toPx() }
    val pullBandPx = with(density) { BackMotionTokens.MemoriesDiaryPullBand.toPx() }
    val flickPxPerSec = with(density) { BackMotionTokens.MemoriesFlickBack.toPx() }
    val floorPx = with(density) { DiaryRubberBandFloor.toPx() }
    // The prototype's morph spring (340 / .82) maps to the expressive default
    // spatial spring (PLAN Q12); every p move uses it. Reduced motion: the
    // prototype's EFFECTS spring (200 / 1) → slowEffectsSpec, alpha only.
    val morphSpec: AnimationSpec<Float> = if (reducedMotion) {
        YoinMotion.slowEffectsSpec(role = YoinMotionRole.Expressive)
    } else {
        YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Expressive)
    }
    // Not keyed on the px metrics: a display-size change must not rebuild p (it closed an open diary and left
    // anything holding the old controller reading a dead one); the new px are handed in below instead.
    val state = rememberSaveable(
        saver = Saver(
            save = { it.fraction.coerceIn(0f, 1f) },
            restore = { saved ->
                MemoriesDiaryState(
                    initialFraction = saved,
                    morphDistancePx = morphDistancePx,
                    pullBandPx = pullBandPx,
                    flickPxPerSec = flickPxPerSec,
                    rubberBand = DiaryRubberBand,
                    rubberBandFloorPx = floorPx,
                    morphSpec = morphSpec,
                )
            },
        ),
    ) {
        MemoriesDiaryState(
            initialFraction = initialFraction,
            morphDistancePx = morphDistancePx,
            pullBandPx = pullBandPx,
            flickPxPerSec = flickPxPerSec,
            rubberBand = DiaryRubberBand,
            rubberBandFloorPx = floorPx,
            morphSpec = morphSpec,
        )
    }
    SideEffect {
        state.morphSpec = morphSpec
        state.updateMetrics(morphDistancePx, pullBandPx, flickPxPerSec, floorPx)
    }
    return state
}
