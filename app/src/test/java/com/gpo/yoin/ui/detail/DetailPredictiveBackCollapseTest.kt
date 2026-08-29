package com.gpo.yoin.ui.detail

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailPredictiveBackCollapseTest {
    @Test
    fun should_cancelOldSettleBeforeButtonCommit_andFinishOnce() = runTest {
        val guard = DetailBackOperationGuard()
        val events = mutableListOf<String>()
        val settleStarted = CompletableDeferred<Unit>()

        val cancelledOwner = guard.beginOperation()
        guard.launchCancelSettle(
            scope = this,
            owner = cancelledOwner,
            settle = {
                try {
                    settleStarted.complete(Unit)
                    awaitCancellation()
                } finally {
                    events += "cancelSettleStopped"
                }
            },
            onSettled = { events += "idle" },
        )
        settleStarted.await()

        guard.beginOperation()
        events += "buttonCommit"
        guard.markCommitted()
        guard.dispatchFinishOnce { events += "finish" }
        guard.dispatchFinishOnce { events += "duplicateFinish" }

        assertEquals(
            listOf("cancelSettleStopped", "buttonCommit", "finish"),
            events,
        )
    }

    @Test
    fun should_finishWithoutResettingIdle_when_committedOperationIsCancelled() {
        val guard = DetailBackOperationGuard()
        var finishCount = 0
        var idleReset = false

        guard.markCommitted()
        guard.recoverCancellation(
            onCommittedCancellation = {
                guard.dispatchFinishOnce { finishCount += 1 }
            },
            onGestureCancellation = { idleReset = true },
        )
        // A second cancellation must not dispatch finish twice either.
        guard.recoverCancellation(
            onCommittedCancellation = {
                guard.dispatchFinishOnce { finishCount += 1 }
            },
            onGestureCancellation = { idleReset = true },
        )

        assertEquals(1, finishCount)
        assertFalse(idleReset)
    }

    @Test
    fun should_notPublishIdle_when_staleSettleIgnoresCancellation() = runTest {
        val guard = DetailBackOperationGuard()
        val settleStarted = CompletableDeferred<Unit>()
        val releaseSettle = CompletableDeferred<Unit>()
        var idleReset = false

        val cancelledOwner = guard.beginOperation()
        guard.launchCancelSettle(
            scope = this,
            owner = cancelledOwner,
            settle = {
                settleStarted.complete(Unit)
                // Models a platform/animation cleanup section that cannot stop
                // synchronously when the next back invalidates this owner.
                withContext(NonCancellable) { releaseSettle.await() }
            },
            onSettled = { idleReset = true },
        )
        settleStarted.await()

        val nextOperation = async { guard.beginOperation() }
        yield()
        assertFalse(nextOperation.isCompleted)
        releaseSettle.complete(Unit)
        nextOperation.await()

        assertTrue(nextOperation.isCompleted)
        assertFalse(idleReset)
    }
}
