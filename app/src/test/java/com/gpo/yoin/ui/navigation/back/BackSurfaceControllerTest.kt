package com.gpo.yoin.ui.navigation.back

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BackSurfaceControllerTest {

    private fun immediateController(): BackSurfaceController = BackSurfaceController { _, to, onValue ->
        onValue(to)
    }

    @Test
    fun should_clamp_drag_updates_within_fraction_bounds() {
        val controller = immediateController()

        controller.updateFromDrag(-0.5f)
        assertEquals(0f, controller.fraction, 0.0001f)

        controller.updateFromDrag(1.4f)
        assertEquals(1f, controller.fraction, 0.0001f)
    }

    @Test
    fun should_snap_fraction_within_bounds() {
        val controller = immediateController()

        controller.snapTo(1.4f)
        assertEquals(1f, controller.fraction, 0.0001f)

        controller.snapTo(-0.2f)
        assertEquals(0f, controller.fraction, 0.0001f)
    }

    @Test
    fun should_commit_only_once_until_reset() = runTest {
        val controller = immediateController()
        var commitCount = 0

        controller.updateFromDrag(0.42f)
        controller.animateCommit { commitCount++ }
        controller.animateCommit { commitCount++ }

        assertEquals(1, commitCount)
        assertEquals(1f, controller.fraction, 0.0001f)

        controller.reset()
        controller.updateFromDrag(0.18f)
        controller.animateCommit { commitCount++ }

        assertEquals(2, commitCount)
    }

    @Test
    fun should_commit_immediately_only_once_until_reset() = runTest {
        val controller = immediateController()
        var commitCount = 0

        controller.commitImmediately { commitCount++ }
        controller.commitImmediately { commitCount++ }

        assertEquals(1, commitCount)

        controller.reset()
        controller.commitImmediately { commitCount++ }

        assertEquals(2, commitCount)
    }

    @Test
    fun should_animate_cancel_back_to_zero() = runTest {
        val controller = immediateController()

        controller.updateFromDrag(0.67f)
        controller.animateCancel()

        assertEquals(0f, controller.fraction, 0.0001f)
    }
}
