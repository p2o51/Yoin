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
 * I-2's held last line moves on one spring, as a whole-line scale: it lets go
 * smoothly (no snap to rest) and, with no relayout, the drawn width changes a
 * little every frame instead of jumping in steps (owner 2026-10-05: the old
 * letter-spacing fallback juddered on nearly full Latin lines).
 */
class LastLineReleaseTest {

    private val scheme = MotionScheme.expressive()
    private val role = YoinMotionRole.Expressive
    private val widen = YoinMotion.slowSpatialSpec<Float>(role = role, expressiveScheme = scheme)
    private val release = YoinMotion.defaultSpatialSpec<Float>(role = role, expressiveScheme = scheme)

    /** Runs [motion] to [target] from wherever it is; returns the progress, frame by frame. */
    @OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
    private fun frames(motion: HeldLineMotion, vararg targets: Float): List<Float> {
        val frames = mutableListOf<Float>()
        runTest {
            withContext(TestMonotonicFrameClock(this)) {
                val sampler = launch {
                    while (true) {
                        withFrameNanos { frames += motion.progress.value }
                    }
                }
                targets.forEach { motion.follow(it, widen, release) }
                sampler.cancel()
            }
        }
        return frames
    }

    @Test
    fun should_springBackInsteadOfSnapping_when_theLineLetsGo() {
        val motion = HeldLineMotion()

        val release = frames(motion, 1f, 0f).dropWhile { it < 1f - 1e-3f }

        assertTrue(release.size > 10)
        assertTrue(release.zipWithNext().all { (a, b) -> a - b < 0.35f })
        // The default spatial spring's rebound: a hair past rest, then home.
        assertTrue(release.minOrNull()!! < 0f)
        assertEquals(0f, motion.progress.value, 0f)
    }

    @Test
    fun should_changeTheDrawnWidthInSmallSteps_when_aFullRowLineWidens() {
        val usage = 0.98f
        val widths = frames(HeldLineMotion(), 0.5f, 1f).map { lastLineStretchScale(it, usage) * usage }

        // Every frame a small change — no step jumps from a relayout.
        assertTrue(widths.size > 10)
        assertTrue(widths.zipWithNext().all { (a, b) -> kotlin.math.abs(b - a) < 0.01f })
        // A line that fills its row still widens, into the gutter, by at most the gutter room.
        assertEquals(1f + LastLineStretchGutterRoom, lastLineStretchScale(1f, usage), 1e-6f)
    }
}
