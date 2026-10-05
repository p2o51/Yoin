package com.gpo.yoin.ui.nowplaying

import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TestMonotonicFrameClock
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave-2 gate fix for I-2: the letter-spacing fallback (a held last line that
 * nearly fills its row) used to snap from its spacing straight back to 0 at
 * the hand-over. It now lets go on the same default spatial spring as the
 * scale mode, by cross-fading its frozen spaced copy into the resting copy —
 * no relayout per frame.
 */
class LastLineReleaseTest {

    private val scheme = MotionScheme.expressive()
    private val role = YoinMotionRole.Expressive
    private val widen = YoinMotion.slowSpatialSpec<Float>(role = role, expressiveScheme = scheme)
    private val release = YoinMotion.defaultSpatialSpec<Float>(role = role, expressiveScheme = scheme)

    private data class Frame(val progress: Float, val heldStep: Float)

    /** Holds [motion] at [held] in [letterSpacing] mode, then lets go; returns the release, frame by frame. */
    @OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
    private fun holdThenRelease(motion: HeldLineMotion, held: Float, letterSpacing: Boolean): List<Frame> {
        val frames = mutableListOf<Frame>()
        runTest {
            withContext(TestMonotonicFrameClock(this)) {
                motion.follow(held, letterSpacing, widen, release)
                val letGo = launch { motion.follow(0f, letterSpacing, widen, release) }
                val sampler = launch {
                    while (true) {
                        withFrameNanos { frames += Frame(motion.progress.value, motion.heldStep) }
                    }
                }
                letGo.join()
                sampler.cancel()
            }
        }
        return frames
    }

    @Test
    fun should_layOutTheSpacingPerStep_when_aFullRowLineWidens() {
        val held = HeldLineMotion()
        runTest { held.follow(0.25f, letterSpacing = true, widen = widen, release = release) }

        // Snapped to the quantized step: the spaced copy is laid out at it, whole.
        assertEquals(0.25f, held.heldStep, 0f)
        assertEquals(0.25f, held.progress.value, 0f)
        assertEquals(1f, heldLineSpacedWeight(held.progress.value, held.heldStep), 0f)
    }

    @Test
    fun should_springBackInsteadOfSnapping_when_theSpacedLineLetsGo() {
        val motion = HeldLineMotion()

        // Let go from the top of the stretch, as at the hand-over.
        val frames = holdThenRelease(motion, held = 1f, letterSpacing = true)
        val weights = frames.map { heldLineSpacedWeight(it.progress, it.heldStep) }

        // Many frames, starting near whole: not a one-frame drop to 0.
        assertTrue(frames.size > 10)
        assertTrue(weights.first() > 0.9f)
        assertTrue(weights.zipWithNext().all { (a, b) -> a - b < 0.35f })
        // The spaced copy stays laid out at its last step (no relayout) while the spring moves…
        assertTrue(frames.filter { it.progress != 0f }.all { it.heldStep == 1f })
        // …and the spring's rebound reaches the resting copy as a hair under its width.
        val deepest = weights.minOrNull()!!
        assertTrue(deepest < 0f)
        assertTrue(heldLineRestScaleX(deepest, ratio = 0.95f) < 1f)
        // Settled: no spaced copy left, home at rest.
        assertEquals(0f, motion.heldStep, 0f)
        assertEquals(0f, motion.progress.value, 0f)
    }

    @Test
    fun should_releaseOnTheScalesOwnSpring_when_theSpacingLetsGo() {
        val spaced = holdThenRelease(HeldLineMotion(), held = 0.5f, letterSpacing = true)
        val scaled = holdThenRelease(HeldLineMotion(), held = 0.5f, letterSpacing = false)

        // One spring family: the cross-fade weight traces the scale's release exactly.
        val n = minOf(spaced.size, scaled.size)
        assertTrue(n > 10)
        for (i in 0 until n) {
            assertEquals(
                scaled[i].progress / 0.5f,
                heldLineSpacedWeight(spaced[i].progress, spaced[i].heldStep),
                1e-4f,
            )
        }
    }

    @Test
    fun should_morphTheTwoWidths_when_bothCopiesWrapAlike() {
        val ratio = heldLineMorphRatio(restWidth = 900f, restLines = 1, spacedWidth = 950f, spacedLines = 1)
        assertEquals(900f / 950f, ratio, 1e-6f)

        // Held: the resting copy (hidden) already matches the spaced width.
        assertEquals(950f / 900f, heldLineRestScaleX(weight = 1f, ratio = ratio), 1e-5f)
        assertEquals(1f, heldLineSpacedScaleX(weight = 1f, ratio = ratio), 0f)
        // Home: the spaced copy (gone) squeezed onto the resting width, rest at 1.
        assertEquals(1f, heldLineRestScaleX(weight = 0f, ratio = ratio), 0f)
        assertEquals(ratio, heldLineSpacedScaleX(weight = 0f, ratio = ratio), 1e-6f)
        // Mid-release both copies draw the same width.
        val mid = 0.4f
        assertEquals(
            900f * heldLineRestScaleX(mid, ratio),
            950f * heldLineSpacedScaleX(mid, ratio),
            2f,
        )
    }

    @Test
    fun should_plainCrossFade_when_theSpacingReWrapsTheLine() {
        assertEquals(1f, heldLineMorphRatio(restWidth = 980f, restLines = 1, spacedWidth = 620f, spacedLines = 2), 0f)
        assertEquals(1f, heldLineMorphRatio(restWidth = 0f, restLines = 0, spacedWidth = 950f, spacedLines = 1), 0f)
        assertEquals(1f, heldLineRestScaleX(weight = 0.6f, ratio = 1f), 0f)
        assertEquals(1f, heldLineSpacedScaleX(weight = 0.6f, ratio = 1f), 0f)
    }

    @Test
    fun should_endWithoutAVisibleSnap_when_releasedFromTheFirstStep() {
        val frames = holdThenRelease(HeldLineMotion(), held = 1f / 24f, letterSpacing = true)
        val lastMoving = frames.last { it.heldStep > 0f }

        // The last drawn cross-fade weight before the copy goes is all but gone.
        assertTrue(kotlin.math.abs(heldLineSpacedWeight(lastMoving.progress, lastMoving.heldStep)) < 0.03f)
    }

    @Test
    fun should_neverDrawASpacedCopy_when_nothingIsHeld() {
        assertEquals(0f, heldLineSpacedWeight(progress = 0.3f, heldStep = 0f), 0f)
        assertEquals(1f, heldLineSpacedWeight(progress = 0.3f, heldStep = 0.3f), 0f)
    }
}
