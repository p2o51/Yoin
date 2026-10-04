package com.gpo.yoin.ui.experience

import androidx.compose.animation.core.spring
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TestMonotonicFrameClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dp dismiss rule (twostate4 `releaseQ`), in px at density 1 so the
 * numbers read as dp: card body 112 / 600, top bar 56 / 450, flick back 350.
 */
class RevealStateDismissRuleTest {

    private val body = DismissRule(commitPx = 112f, flingPxPerSec = 600f, flickBackPxPerSec = 350f)
    private val bar = DismissRule(commitPx = 56f, flingPxPerSec = 450f, flickBackPxPerSec = 350f)

    private fun target(rule: DismissRule, dismissedPx: Float, dismissVelocity: Float): Float = chooseDismissTarget(
        dismissedPx = dismissedPx,
        dismissVelocityPxPerSec = dismissVelocity,
        commitPx = rule.commitPx,
        flingPxPerSec = rule.flingPxPerSec,
        flickBackPxPerSec = rule.flickBackPxPerSec,
    )

    @Test
    fun should_commit_when_pushed_past_commit_distance() {
        assertEquals(1f, target(body, dismissedPx = 112f, dismissVelocity = 0f))
        assertEquals(1f, target(body, dismissedPx = 140f, dismissVelocity = -100f))
        assertEquals(1f, target(bar, dismissedPx = 56f, dismissVelocity = 0f))
    }

    @Test
    fun should_commit_when_fling_exceeds_threshold_below_distance() {
        assertEquals(1f, target(body, dismissedPx = 20f, dismissVelocity = 600f))
        assertEquals(0f, target(body, dismissedPx = 20f, dismissVelocity = 599f))
        // The bar is a shorter handle with a gentler fling.
        assertEquals(1f, target(bar, dismissedPx = 10f, dismissVelocity = 450f))
    }

    @Test
    fun should_return_when_flicked_back_past_threshold() {
        // Pushed well past 112, then flicked back down: the flick wins.
        assertEquals(0f, target(body, dismissedPx = 300f, dismissVelocity = -350f))
        assertEquals(0f, target(bar, dismissedPx = 120f, dismissVelocity = -900f))
        // A slower drift back down past the threshold still commits.
        assertEquals(1f, target(body, dismissedPx = 300f, dismissVelocity = -349f))
    }

    @Test
    fun should_return_when_released_short_without_speed() {
        assertEquals(0f, target(body, dismissedPx = 100f, dismissVelocity = 0f))
        assertEquals(0f, target(body, dismissedPx = 111.9f, dismissVelocity = 200f))
        assertEquals(0f, target(bar, dismissedPx = 50f, dismissVelocity = 100f))
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun should_land_closed_and_report_commit_when_settle_dismiss_commits() = runTest {
        val state = revealState(initialFraction = 0f)
        state.dragBy(deltaPx = -120f, containerPx = 1000f)
        var committedAt = -1f
        val result = withContext(TestMonotonicFrameClock(this)) {
            // Drag convention: negative = finger moving up.
            state.settleDismiss(velocityPxPerSec = -50f, containerPx = 1000f, rule = body) {
                committedAt = state.fraction
            }
        }
        assertEquals(1f, result)
        assertEquals(1f, state.fraction, 0.001f)
        // onCommit fires at release, before the spring moves the page.
        assertEquals(0.12f, committedAt, 0.0001f)
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun should_spring_open_without_commit_when_settle_dismiss_returns() = runTest {
        val state = revealState(initialFraction = 0f)
        state.dragBy(deltaPx = -80f, containerPx = 1000f)
        var committed = false
        val result = withContext(TestMonotonicFrameClock(this)) {
            state.settleDismiss(velocityPxPerSec = 0f, containerPx = 1000f, rule = body) { committed = true }
        }
        assertEquals(0f, result)
        assertEquals(0f, state.fraction, 0.001f)
        assertFalse(committed)
        assertTrue(state.isFullyExpanded)
    }

    private fun revealState(initialFraction: Float) = RevealState(
        initialFraction = initialFraction,
        rubberBandCoefficient = 0.3f,
        velocityThresholdFractionPerSec = 1.6f,
        positionThreshold = 0.5f,
        settleSpec = spring(),
    )
}
