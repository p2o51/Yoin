package com.gpo.yoin.ui.memories

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Past the deck's last (or first) card an ordinary swipe or flick turns the deck (owner, 2026-10-05). */
class MemoriesDeckEdgeTest {
    private val fling = 1_200f

    @Test
    fun should_advance_deck_when_release_is_half_way_to_the_trigger() {
        assertTrue(shouldAdvanceDeckOnRelease(pullProgress = 0.5f, outwardVelocity = 0f, flingVelocity = fling))
        assertTrue(shouldAdvanceDeckOnRelease(pullProgress = 0.8f, outwardVelocity = -500f, flingVelocity = fling))
    }

    @Test
    fun should_advance_deck_when_a_short_pull_is_flung_outward() {
        assertTrue(shouldAdvanceDeckOnRelease(pullProgress = 0.1f, outwardVelocity = 1_500f, flingVelocity = fling))
    }

    @Test
    fun should_stay_when_the_pull_is_short_and_slow_or_flung_back() {
        assertFalse(shouldAdvanceDeckOnRelease(pullProgress = 0.2f, outwardVelocity = 300f, flingVelocity = fling))
        assertFalse(shouldAdvanceDeckOnRelease(pullProgress = 0.2f, outwardVelocity = -2_000f, flingVelocity = fling))
        // no pull past the edge at all (a fling between pages): never
        assertFalse(shouldAdvanceDeckOnRelease(pullProgress = 0f, outwardVelocity = 5_000f, flingVelocity = fling))
    }
}
