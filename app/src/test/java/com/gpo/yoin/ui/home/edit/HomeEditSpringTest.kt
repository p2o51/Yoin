package com.gpo.yoin.ui.home.edit

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.snap
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.unit.IntOffset
import com.gpo.yoin.ui.theme.YoinMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The port sheet §8 spring vectors, run on the real resolved specs. */
class HomeEditSpringTest {

    private val specs = HomeEditSpecs.create(MotionScheme.expressive(), reduced = false)

    @Test
    fun should_peakAtAmplitude_when_kickedWithGain31_6() {
        for (amplitude in listOf(1f, .5f)) {
            val velocity = kickInitialVelocity(amplitude, sign = 1, running = false, currentVelocity = 0f)
            val samples = sample(specs.kick, from = 0f, to = 0f, velocity = velocity, untilMs = 400.0)
            val peak = samples.maxBy { it.second }
            // Analytic first peak for ζ .3 / k 450: 1.0003·a at 62.57ms.
            assertEquals(amplitude, peak.second, .003f * amplitude)
            assertEquals(62.6, peak.first, 1.0)
            val trough = samples.filter { it.first > peak.first }.minBy { it.second }
            assertEquals(-.372f, trough.second / peak.second, .005f)
        }
    }

    @Test
    fun should_pulseTo1_1993_when_fastSpatialV11_3() {
        val samples = sample(specs.pulse, from = 1f, to = 1f, velocity = HomeEditTokens.PulseVelocity, untilMs = 200.0)
        val peak = samples.maxBy { it.second }
        assertEquals(1.1993f, peak.second, .0005f)
        assertEquals(41.0, peak.first, 1.0)
    }

    @Test
    fun should_reach0_8755_when_chargeRuns200ms() {
        val charge = valueAt(specs.chargeUp, from = 0f, to = 1f, velocity = 0f, ms = 200.0)
        assertEquals(.8755f, charge, .001f)
        assertEquals(.3829f, chargePlateAlpha(charge), .001f)
        assertEquals(.98949f, chargeScale(charge), .0001f)
    }

    @Test
    fun should_cross085At108ms_when_stageSettle() {
        val samples = sample(specs.stage, from = 0f, to = 1f, velocity = 0f, untilMs = 400.0)
        assertEquals(108.0, firstCrossing(samples, .85f), 1.0)
        assertEquals(1.0063f, samples.maxOf { it.second }, .0005f)
    }

    @Test
    fun should_hitFoldMilestones_when_defaultSpatial() {
        val samples = sample(specs.fold, from = 0f, to = 1f, velocity = 0f, untilMs = 500.0)
        assertEquals(71.0, firstCrossing(samples, .45f), 1.0)
        assertEquals(84.0, firstCrossing(samples, .55f), 1.0)
        assertEquals(214.0, firstCrossing(samples, 1f), 1.0)
        assertEquals(1.0152f, samples.maxOf { it.second }, .0005f)
    }

    @Test
    fun should_carryOwnThreshold_when_resolved() {
        val fold = specs.fold as SpringSpec<Float>
        assertEquals(.001f, fold.visibilityThreshold)
        // Expressive defaultSpatial whatever role the caller runs under.
        assertEquals(.8f, fold.dampingRatio, 0f)
        assertEquals(380f, fold.stiffness, 0f)
        assertEquals(.0005f, (specs.stage as SpringSpec<Float>).visibilityThreshold)
        assertEquals(.01f, (specs.kick as SpringSpec<Float>).visibilityThreshold)
        assertEquals(.3f, (specs.holePx as SpringSpec<Float>).visibilityThreshold)
    }

    @Test
    fun should_useFastEffects_when_reducedMotion() {
        val reduced = HomeEditSpecs.create(MotionScheme.expressive(), reduced = true)
        val spatial = with(reduced) { listOf(stage, fold, chargeUp, liftUp, envUp, pulse, kick) }
        for (spec in spatial) {
            val spring = spec as SpringSpec<Float>
            assertEquals(1f, spring.dampingRatio, 0f)
            assertEquals(3800f, spring.stiffness, 0f)
        }
        assertEquals(.001f, (reduced.fold as SpringSpec<Float>).visibilityThreshold)
        assertSame(homeEditStageSpec(reduced = true), reduced.stage)
    }

    @Test
    fun should_passThrough_when_specIsNotASpring() {
        val notASpring = snap<Float>()
        assertSame(notASpring, notASpring.withThreshold(.002f))

        val spring = YoinMotion.homeEditKickSpring() as SpringSpec<Float>
        assertEquals(.3f, spring.dampingRatio, 0f)
        assertEquals(450f, spring.stiffness, 0f)
        val retuned = spring.withThreshold(.002f) as SpringSpec<Float>
        assertEquals(.002f, retuned.visibilityThreshold)
        assertEquals(spring.dampingRatio, retuned.dampingRatio, 0f)
        assertEquals(spring.stiffness, retuned.stiffness, 0f)
    }

    @Test
    fun should_keepFeedFadeSpring_when_itemsFadeOut() {
        // Errata 10: feed fade-out stays on the effects spring the feed already uses.
        for (spec in listOf(specs.effectsIn, specs.effectsOut)) {
            val spring = spec as SpringSpec<Float>
            assertEquals(1f, spring.dampingRatio, 0f)
            assertEquals(1600f, spring.stiffness, 0f)
        }
    }

    private fun valueAt(spec: FiniteAnimationSpec<Float>, from: Float, to: Float, velocity: Float, ms: Double): Float =
        spec.vectorize(Float.VectorConverter).getValueFromNanos(
            playTimeNanos = (ms * 1_000_000).toLong(),
            initialValue = AnimationVector1D(from),
            targetValue = AnimationVector1D(to),
            initialVelocity = AnimationVector1D(velocity),
        ).value

    /** (ms, value) every 0.05ms. */
    private fun sample(
        spec: FiniteAnimationSpec<Float>,
        from: Float,
        to: Float,
        velocity: Float,
        untilMs: Double,
    ): List<Pair<Double, Float>> = (0..(untilMs * 20).toInt()).map { step ->
        val ms = step / 20.0
        ms to valueAt(spec, from, to, velocity, ms)
    }

    private fun firstCrossing(samples: List<Pair<Double, Float>>, level: Float): Double =
        samples.first { it.second >= level }.first

    @Test
    fun should_landAtOnce_when_placementGateIsShut() {
        var shut = false
        val spring = HomeEditSpecs.create(MotionScheme.expressive(), reduced = false).placement
        val gated = GatedPlacementSpec(spring) { shut }
        val converter = IntOffset.VectorConverter
        val from = AnimationVector2D(0f, 0f)
        val to = AnimationVector2D(0f, 300f)
        val still = AnimationVector2D(0f, 0f)

        // Open: the feed's spring, read when the move starts.
        val open = gated.vectorize(converter).getDurationNanos(from, to, still)
        assertEquals(spring.vectorize(converter).getDurationNanos(from, to, still), open)
        assertTrue(open > 0L)

        // Folding or carrying: the item lands at once.
        shut = true
        val snapped = gated.vectorize(converter)
        assertEquals(0L, snapped.getDurationNanos(from, to, still))
        assertEquals(to, snapped.getValueFromNanos(0L, from, to, still))
    }
}
