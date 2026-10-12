package com.gpo.yoin.ui.component

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArtworkForegroundRetryTest {

    @Test
    fun should_notRetry_when_processResumesForTheFirstTime() {
        val owner = TestOwner()
        var retries = 0
        owner.registry.addObserver(ArtworkForegroundRetry { retries++ })

        owner.registry.currentState = Lifecycle.State.RESUMED

        assertEquals(0, retries)
    }

    @Test
    fun should_retryOnce_when_processResumesWithoutStopping() {
        val owner = TestOwner()
        var retries = 0
        owner.registry.addObserver(ArtworkForegroundRetry { retries++ })
        owner.registry.currentState = Lifecycle.State.RESUMED

        // HOME can pause the process while the Activity remains started/STOPPING.
        owner.registry.currentState = Lifecycle.State.STARTED
        assertEquals(0, retries)
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertEquals(1, retries)

        owner.registry.currentState = Lifecycle.State.RESUMED
        assertEquals(1, retries)
    }

    @Test
    fun should_retryOnlyOnResume_when_processStopsAndStartsAgain() {
        val owner = TestOwner()
        var retries = 0
        owner.registry.addObserver(ArtworkForegroundRetry { retries++ })
        owner.registry.currentState = Lifecycle.State.RESUMED

        owner.registry.currentState = Lifecycle.State.CREATED
        assertEquals(0, retries)
        owner.registry.currentState = Lifecycle.State.STARTED
        assertEquals(0, retries)
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertEquals(1, retries)

        // A later genuine return is a new opportunity, not a repeated retry loop.
        owner.registry.currentState = Lifecycle.State.STARTED
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertEquals(2, retries)
    }

    @Test
    fun should_notRetry_when_observerRegistersOnAnAlreadyResumedProcess() {
        val owner = TestOwner()
        owner.registry.currentState = Lifecycle.State.RESUMED
        var retries = 0

        owner.registry.addObserver(ArtworkForegroundRetry { retries++ })

        assertEquals(0, retries)
        owner.registry.currentState = Lifecycle.State.STARTED
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertEquals(1, retries)
    }

    @Test
    fun should_releaseUndecodableArtworkWait_when_pausedProcessResumesWithoutStopping() = runTest {
        val owner = TestOwner()
        val signal = MutableStateFlow(0L)
        owner.registry.addObserver(ArtworkForegroundRetry { signal.value += 1 })
        owner.registry.currentState = Lifecycle.State.RESUMED
        val failed = ArtworkRetryState(requestSignal = signal.value)
            .failed(IllegalStateException("BitmapFactory returned a null bitmap."))
        assertEquals(ArtworkFailureKind.Undecodable, failed.failure)
        val retry = async { awaitArtworkRetry(failed, signal) }
        runCurrent()

        advanceTimeBy(60_000L)
        assertFalse(retry.isCompleted)
        owner.registry.currentState = Lifecycle.State.STARTED
        runCurrent()
        assertFalse(retry.isCompleted)

        owner.registry.currentState = Lifecycle.State.RESUMED
        runCurrent()
        val requesting = retry.await()
        assertTrue(requesting.requesting)
        assertTrue(requesting.fallbackUnder)
        assertEquals(1L, requesting.requestSignal)
        assertEquals(0, requesting.backoffRetries)

        // If the returned bytes still fail, time alone must not start an endless retry.
        val stillFailed = requesting.failed(IllegalStateException("Still undecodable"))
        val nextRetry = async { awaitArtworkRetry(stillFailed, signal) }
        runCurrent()
        advanceTimeBy(60_000L)
        assertFalse(nextRetry.isCompleted)
        owner.registry.currentState = Lifecycle.State.STARTED
        owner.registry.currentState = Lifecycle.State.RESUMED
        runCurrent()
        assertEquals(2L, nextRetry.await().requestSignal)
    }

    private class TestOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
