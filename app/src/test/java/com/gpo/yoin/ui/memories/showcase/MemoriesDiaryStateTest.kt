package com.gpo.yoin.ui.memories.showcase

import androidx.compose.animation.core.spring
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TestMonotonicFrameClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The diary's inner p (twostate4 `wToV` / `vToW` / `releaseV`), in px at
 * density 1 so the numbers read as dp: morph 320, band 24, flick 350.
 */
class MemoriesDiaryStateTest {

    private val distance = 320f
    private val band = 24f
    private val flick = 350f

    private fun diary(initialFraction: Float) = MemoriesDiaryState(
        initialFraction = initialFraction,
        morphDistancePx = distance,
        pullBandPx = band,
        flickPxPerSec = flick,
        rubberBand = DiaryRubberBand,
        rubberBandFloorPx = 90f,
        morphSpec = spring(),
    )

    private fun release(fraction: Float, openVelocity: Float, fromScrolled: Boolean) = chooseDiaryReleaseTarget(
        fraction = fraction,
        openVelocityPxPerSec = openVelocity,
        distancePx = distance,
        bandPx = band,
        flickPxPerSec = flick,
        fromScrolled = fromScrolled,
    )

    @Test
    fun should_stay_in_diary_when_fling_to_top_started_in_text() {
        // Started in scrolled text, still inside the first half of the band,
        // flung hard toward the card: reading, not closing.
        assertEquals(1f, release(fraction = (distance - 10f) / distance, openVelocity = -3000f, fromScrolled = true))
        assertEquals(1f, release(fraction = (distance - 11.5f) / distance, openVelocity = -3000f, fromScrolled = true))
        // The same fling from a pull that started at the top closes.
        assertEquals(0f, release(fraction = (distance - 10f) / distance, openVelocity = -3000f, fromScrolled = false))
        // Past the whole band the speed decides again.
        assertEquals(0f, release(fraction = (distance - 13f) / distance, openVelocity = -3000f, fromScrolled = true))

        // Through the state: a 20dp pull out of scrolled text moves p 10dp.
        val state = diary(initialFraction = 1f)
        state.startPull(banded = true, fromScrolled = true)
        state.pullBy(20f)
        assertEquals(distance - 10f, state.fraction * distance, 1e-3f)
    }

    @Test
    fun should_close_when_pull_from_top_passes_half() {
        assertEquals(0f, release(fraction = 0.49f, openVelocity = 0f, fromScrolled = false))
        assertEquals(1f, release(fraction = 0.51f, openVelocity = 0f, fromScrolled = false))
        // A flick (350dp/s) beats the half rule either way.
        assertEquals(1f, release(fraction = 0.2f, openVelocity = 351f, fromScrolled = false))
        assertEquals(0f, release(fraction = 0.8f, openVelocity = -351f, fromScrolled = false))
        // Below the card (rubber band) always returns to the card.
        assertEquals(0f, release(fraction = -0.1f, openVelocity = 2000f, fromScrolled = false))
    }

    @Test
    fun should_halve_pull_in_first_band_when_started_scrolled() {
        val state = diary(initialFraction = 1f)
        state.startPull(banded = true, fromScrolled = true)
        // The first 24dp past the top move the morph 12dp...
        state.pullBy(24f)
        assertEquals(distance - 12f, state.fraction * distance, 1e-3f)
        // ...then 1:1 again, continuous at the band's edge.
        state.pullBy(24f)
        assertEquals(distance - 36f, state.fraction * distance, 1e-3f)
        // Back up through the band: the mapping is its own inverse, no drift.
        state.pullBy(-48f)
        assertEquals(1f, state.fraction, 1e-6f)

        // A pull not from the diary text (the top bar) is 1:1 from the start.
        val bar = diary(initialFraction = 1f)
        bar.startPull(banded = false, fromScrolled = false)
        bar.pullBy(24f)
        assertEquals(distance - 24f, bar.fraction * distance, 1e-3f)
    }

    @Test
    fun should_hand_overflow_back_to_scroll_when_pull_reaches_the_diary() {
        val state = diary(initialFraction = 1f)
        state.startPull(banded = true, fromScrolled = false)
        // Finger up at p = 1: nothing for p, the diary scroll takes it all.
        assertEquals(0f, state.pullBy(-30f), 1e-6f)
        assertEquals(1f, state.fraction, 1e-6f)
        // Down 10, then up 30: p takes the 10 back, the rest is the scroll's.
        assertEquals(10f, state.pullBy(10f), 1e-6f)
        assertEquals(-10f, state.pullBy(-30f), 1e-6f)
        assertEquals(1f, state.fraction, 1e-6f)
    }

    @Test
    fun should_rubber_band_below_card_with_floor() {
        val state = diary(initialFraction = 0f)
        state.startPull(banded = false, fromScrolled = false)
        state.pullBy(100f)
        assertEquals(-30f, state.fraction * distance, 1e-3f)
        state.pullBy(10_000f)
        assertEquals(-90f, state.fraction * distance, 1e-3f)
    }

    @Test
    fun should_round_trip_pull_and_morph_mappings() {
        listOf(-200f, -12f, 0f, 100f, distance - band - 1f, distance - band, distance - 5f, distance, distance + 40f)
            .forEach { travel ->
                val morph = diaryPullToMorph(travel, distance, band, banded = true)
                assertEquals("travel $travel", travel, diaryMorphToPull(morph, distance, band, banded = true), 1e-3f)
            }
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun should_spring_to_card_when_released_past_half_toward_card() = runTest {
        val state = diary(initialFraction = 1f)
        state.startPull(banded = false, fromScrolled = false)
        state.pullBy(200f)
        val target = withContext(TestMonotonicFrameClock(this)) { state.releasePull(velocityPxPerSec = 0f) }
        assertEquals(0f, target)
        assertEquals(0f, state.fraction, 1e-3f)
    }
}
