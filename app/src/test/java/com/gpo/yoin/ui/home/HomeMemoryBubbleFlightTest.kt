package com.gpo.yoin.ui.home

import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bubble flying back into the arrow ([settle]). Device QA 2026-10-12
 * (HOM-28): on a Wide window the tap on an album card tucks it and opens the
 * detail column, and the column's first frames — Home re-laying out for it —
 * came ~115 ms apart; read by the wall clock, the whole fade fell between two
 * of them and read as a cut.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeMemoryBubbleFlightTest {

    private val scheme = MotionScheme.expressive()

    // As the overlay builds them.
    private val springs = MemoryBubbleSprings(
        out = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Expressive, expressiveScheme = scheme),
        back = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Standard, expressiveScheme = scheme),
        fly = YoinMotion.slowSpatialSpec(role = YoinMotionRole.Standard, expressiveScheme = scheme)
    )

    /** Where the bubble hangs when it is tucked. */
    private val departure = MemoryBubbleGeometry(
        body = Rect(16f, 40f, 280f, 100f),
        tailX = 200f,
        tailTop = 4f,
        markStart = Offset(38f, 60f),
        markEnd = Offset(260f, 90f)
    )

    /**
     * Frames at [frameTimesMs] — then on time, 60 Hz — each delayed to its
     * time on the test's clock; [drawn] sees what every frame leaves.
     */
    private class ReplayFrameClock(
        private val frameTimesMs: List<Long>,
        private val drawn: () -> Unit = {}
    ) : MonotonicFrameClock {
        private var frame = 0
        private var lastMs = 0L

        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            val ms = frameTimesMs.getOrElse(frame) { frameTimesMs.last() + (it - frameTimesMs.lastIndex) * ON_TIME_MS }
            frame++
            delay(ms - lastMs)
            lastMs = ms
            return onFrame(ms * NANOS_PER_MS).also { drawn() }
        }
    }

    private suspend fun spokenController() = MemoryBubbleController().apply {
        markIn.snapTo(1f)
        unroll.snapTo(1f)
        geometry = departure
    }

    /** The frames in which the bubble is seen part-way through its fade. */
    private fun fadingFrames(drawn: List<Float>) = drawn.count { memoryBubbleFlightAlpha(it) in 0.05f..0.95f }

    @Test
    fun should_fadeOverSeveralDrawnFrames_when_theTuckRunsThroughTheColumnsLateFrames() = runTest {
        val controller = spokenController()
        val drawn = mutableListOf<Float>()

        withContext(ReplayFrameClock(ColumnOpening) { drawn += controller.flyAway.value }) {
            controller.settle(speaking = false, reduced = false, springs = springs)
        }

        assertTrue("seen fading in only ${fadingFrames(drawn)} frames: $drawn", fadingFrames(drawn) >= 3)
        // Landed exactly, the title at rest.
        assertEquals(0f, controller.flyAway.value)
        assertEquals(0f, controller.unroll.value)
        assertEquals(0f, controller.markIn.value)
    }

    @Test
    fun should_crossTheFadeInOneFrame_when_theFlightReadsTheWallClock() = runTest {
        // What the device showed: the same spring on the frames' own times.
        val controller = spokenController()
        val drawn = mutableListOf<Float>()

        withContext(ReplayFrameClock(ColumnOpening) { drawn += controller.flyAway.value }) {
            controller.flyAway.animateTo(1f, springs.fly)
        }

        assertTrue("seen fading in ${fadingFrames(drawn)} frames: $drawn", fadingFrames(drawn) <= 1)
    }

    @Test
    fun should_flyOnTheSameSpring_when_everyFrameIsOnTime() = runTest {
        val onTime = List(60) { it * ON_TIME_MS }
        val steady = mutableListOf<Float>()
        val wall = mutableListOf<Float>()

        val tucked = spokenController()
        withContext(ReplayFrameClock(onTime) { steady += tucked.flyAway.value }) {
            tucked.settle(speaking = false, reduced = false, springs = springs)
        }
        val plain = spokenController()
        withContext(ReplayFrameClock(onTime) { wall += plain.flyAway.value }) {
            plain.flyAway.animateTo(1f, springs.fly)
        }

        assertEquals(wall, steady)
    }

    @Test
    fun should_flyIntoTheArrowItLeft_when_homeReLaysOutMidFlight() = runTest {
        val controller = spokenController()
        val held = mutableListOf<MemoryBubbleGeometry?>()

        withContext(
            ReplayFrameClock(ColumnOpening) {
                held += controller.flightGeometry
                // Home re-lays out for the column: the header now places it elsewhere.
                controller.geometry = departure.copy(body = departure.body.translate(44f, 0f), tailX = 210f)
            }
        ) {
            controller.settle(speaking = false, reduced = false, springs = springs)
        }

        assertTrue(held.isNotEmpty())
        held.forEach { assertSame(departure, it) }
        // Landed: the header places it again.
        assertNull(controller.flightGeometry)
    }

    @Test
    fun should_letTheHeaderPlaceIt_when_theAppStopsMidFlight() = runTest {
        val controller = spokenController()
        val flight = launch(ReplayFrameClock(ColumnOpening)) {
            controller.settle(speaking = false, reduced = false, springs = springs)
        }
        midFlight()
        assertSame(departure, controller.flightGeometry)
        assertTrue(controller.flyAway.value > 0f)

        // The app goes to the background: the overlay snaps it all to rest.
        controller.flyAway.snapTo(0f)
        controller.unroll.snapTo(0f)
        controller.markIn.snapTo(0f)
        runCurrent()

        assertTrue(flight.isCompleted)
        assertNull(controller.flightGeometry)
    }

    @Test
    fun should_startOverFromTheTitle_when_calledBackMidFlight() = runTest {
        val controller = spokenController()
        val flight = launch(ReplayFrameClock(ColumnOpening)) {
            controller.settle(speaking = false, reduced = false, springs = springs)
        }
        midFlight()
        assertTrue(controller.flyAway.value > 0f)

        // New news while it flies: the effect starts over, speaking.
        flight.cancel()
        withContext(ReplayFrameClock(List(200) { it * ON_TIME_MS })) {
            controller.settle(speaking = true, reduced = false, springs = springs)
        }

        assertNull(controller.flightGeometry)
        assertEquals(0f, controller.flyAway.value)
        assertEquals(1f, controller.unroll.value)
        assertEquals(1f, controller.markIn.value)
    }

    /** Past the column's first late frame. */
    private fun TestScope.midFlight() {
        advanceTimeBy(ColumnOpening[1] + 1L)
        runCurrent()
    }

    private companion object {
        /** The frames the tablet drew as the column opened (HOM-28), in ms from the flight's first. */
        val ColumnOpening = listOf(0L, 117L, 232L, 283L, 317L)
        const val ON_TIME_MS = 16L
        const val NANOS_PER_MS = 1_000_000L
    }
}
