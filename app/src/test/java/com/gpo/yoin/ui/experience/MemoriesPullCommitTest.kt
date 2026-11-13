package com.gpo.yoin.ui.experience

import androidx.compose.animation.core.spring
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TestMonotonicFrameClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home's pull to Memories (four quick pulls on the tablet, 2026-11-13): the
 * commit happens when a release decides to open, an interrupted settle never
 * undoes it, and a reveal that a drag carries the rest of the way open is
 * reconciled to the Memories surface — so the back resolver always sees it.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class MemoriesPullCommitTest {

    private val containerPx = 1000f

    @Test
    fun should_commit_at_release_and_keep_it_when_settle_is_interrupted_and_drag_finishes_opening() = runTest {
        val state = revealState()
        var surface = HomeSurface.Feed
        var commits = 0
        var committedAt = -1f
        state.dragBy(deltaPx = 600f, containerPx = containerPx)

        val settle = startSettle(state) {
            commits++
            committedAt = state.fraction
            surface = HomeSurface.Memories
        }
        advanceTimeBy(32)
        runCurrent()
        assertEquals(0f, state.settleTarget)
        assertNull(state.restingEndpoint)

        // The next pull's touch takes the reveal mid-spring and carries it open.
        state.dragBy(deltaPx = 50f, containerPx = containerPx)
        runCurrent()
        assertTrue(settle.isCancelled)
        state.dragBy(deltaPx = containerPx, containerPx = containerPx)

        assertEquals(1, commits)
        assertEquals(0.4f, committedAt, 0.0001f)
        assertEquals(HomeSurface.Memories, surface)
        assertEquals(0f, state.restingEndpoint)
        assertNull(reconcileMemoriesSurface(surface, state.restingEndpoint!!))
    }

    @Test
    fun should_reconcile_to_memories_when_drag_finishes_opening_after_closing_settle_was_interrupted() = runTest {
        val state = revealState()
        var commits = 0
        state.dragBy(deltaPx = 300f, containerPx = containerPx)

        startSettle(state) { commits++ }
        advanceTimeBy(32)
        runCurrent()
        assertEquals(1f, state.settleTarget)

        state.dragBy(deltaPx = containerPx, containerPx = containerPx)
        runCurrent()

        // The release decided closed, so nothing committed — the resting
        // reveal is what brings the surface along.
        assertEquals(0, commits)
        assertEquals(0f, state.restingEndpoint)
        assertEquals(HomeSurface.Memories, reconcileMemoriesSurface(HomeSurface.Feed, 0f))
    }

    @Test
    fun should_not_commit_when_settle_returns_closed() = runTest {
        val state = revealState()
        var commits = 0
        state.dragBy(deltaPx = 200f, containerPx = containerPx)

        val target = withContext(TestMonotonicFrameClock(this)) {
            state.settle(velocityPxPerSec = 0f, containerPx = containerPx, onOpen = { commits++ })
        }

        assertEquals(1f, target)
        assertEquals(0, commits)
        assertNull(state.settleTarget)
        assertEquals(1f, state.restingEndpoint)
        assertNull(reconcileMemoriesSurface(HomeSurface.Feed, 1f))
    }

    @Test
    fun should_rest_open_with_no_target_when_settle_lands_open() = runTest {
        val state = revealState()
        var commits = 0
        state.dragBy(deltaPx = 700f, containerPx = containerPx)

        val target = withContext(TestMonotonicFrameClock(this)) {
            state.settle(velocityPxPerSec = 0f, containerPx = containerPx, onOpen = { commits++ })
        }

        assertEquals(0f, target)
        assertEquals(1, commits)
        assertNull(state.settleTarget)
        assertEquals(0f, state.restingEndpoint)
    }

    @Test
    fun should_reconcile_only_mismatched_resting_states() {
        assertEquals(HomeSurface.Memories, reconcileMemoriesSurface(HomeSurface.Feed, restedAt = 0f))
        assertEquals(HomeSurface.Feed, reconcileMemoriesSurface(HomeSurface.Memories, restedAt = 1f))
        assertNull(reconcileMemoriesSurface(HomeSurface.Memories, restedAt = 0f))
        assertNull(reconcileMemoriesSurface(HomeSurface.Feed, restedAt = 1f))
        // Memories never opens over edit mode.
        assertNull(reconcileMemoriesSurface(HomeSurface.Edit, restedAt = 0f))
        assertNull(reconcileMemoriesSurface(HomeSurface.Edit, restedAt = 1f))
    }

    @Test
    fun should_not_rest_while_partly_open() {
        val state = revealState()
        assertEquals(1f, state.restingEndpoint)
        state.dragBy(deltaPx = 300f, containerPx = containerPx)
        assertNull(state.restingEndpoint)
        assertFalse(state.isFullyExpanded)
    }

    private fun TestScope.startSettle(state: RevealState, onOpen: () -> Unit) =
        launch(TestMonotonicFrameClock(this)) {
            state.settle(velocityPxPerSec = 0f, containerPx = containerPx, onOpen = onOpen)
        }

    private fun revealState() = RevealState(
        initialFraction = 1f,
        rubberBandCoefficient = 0.3f,
        velocityThresholdFractionPerSec = 1.6f,
        positionThreshold = 0.5f,
        settleSpec = spring(),
    )
}
