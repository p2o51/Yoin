package com.gpo.yoin.ui.component

import coil3.network.HttpException
import coil3.network.NetworkResponse
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArtworkRetryTest {

    private fun http(code: Int) = HttpException(NetworkResponse(code = code))

    @Test
    fun should_classifyAsTransient_when_errorIsIOExceptionOr5xx() {
        val transient = listOf(
            IOException(),
            SocketTimeoutException(),
            UnknownHostException(),
            RuntimeException(IOException()),
            http(500),
            http(503),
            // Coil's only-if-cached miss while offline.
            http(504)
        )
        transient.forEach { error ->
            assertEquals(error.toString(), ArtworkFailureKind.Transient, ArtworkRetryPolicy.classify(error))
        }
    }

    @Test
    fun should_waitForSignal_when_responseIs404Or410OrUnknown() {
        val awaitSignal = listOf(http(404), http(410), http(403), IllegalStateException("undecodable"))
        awaitSignal.forEach { error ->
            assertEquals(error.toString(), ArtworkFailureKind.AwaitSignal, ArtworkRetryPolicy.classify(error))
        }
    }

    @Test
    fun should_backOff3s15s60s_when_failuresAreTransient() = runTest {
        val signal = MutableStateFlow(0L)
        var state = ArtworkRetryState(requestSignal = 0L)
        val waits = mutableListOf<Long>()
        repeat(3) {
            state = state.failed(IOException())
            val start = currentTime
            state = awaitArtworkRetry(state, signal)
            waits += currentTime - start
            assertTrue(state.requesting)
        }
        assertEquals(listOf(3_000L, 15_000L, 60_000L), waits)
        assertEquals(3, state.backoffRetries)
    }

    @Test
    fun should_waitForSignal_when_threeBackoffRetriesAreSpent() = runTest {
        val signal = MutableStateFlow(0L)
        val spent = ArtworkRetryState(requestSignal = 0L, backoffRetries = 3).failed(http(503))
        assertNull(spent.backoffMillis())

        val retry = async { awaitArtworkRetry(spent, signal) }
        advanceTimeBy(60 * 60_000L)
        assertFalse(retry.isCompleted)

        signal.value = 1L
        runCurrent()
        val next = retry.await()
        assertTrue(next.requesting)
        assertEquals(1L, next.requestSignal)
        assertEquals(3, next.backoffRetries)
    }

    @Test
    fun should_retryOnlyOnSignal_when_responseIs404() = runTest {
        val signal = MutableStateFlow(0L)
        val failed = ArtworkRetryState(requestSignal = 0L).failed(http(404))
        assertNull(failed.backoffMillis())

        val retry = async { awaitArtworkRetry(failed, signal) }
        advanceTimeBy(10 * 60_000L)
        assertFalse(retry.isCompleted)

        signal.value = 1L
        runCurrent()
        val next = retry.await()
        assertTrue(next.requesting)
        assertEquals(0, next.backoffRetries)
    }

    @Test
    fun should_retryBeforeTheBackoff_when_signalArrives() = runTest {
        val signal = MutableStateFlow(0L)
        val failed = ArtworkRetryState(requestSignal = 0L).failed(IOException())

        val retry = async { awaitArtworkRetry(failed, signal) }
        advanceTimeBy(1_000L)
        signal.value = 1L
        runCurrent()
        val next = retry.await()
        assertEquals(1_000L, currentTime)
        // A signal retry leaves the backoff budget untouched.
        assertEquals(0, next.backoffRetries)
        assertEquals(1L, next.requestSignal)
    }

    @Test
    fun should_retryAtOnce_when_signalMovedWhileTheRequestWasOut() = runTest {
        // The request went out at generation 0; the network came back before it failed.
        val signal = MutableStateFlow(1L)
        val failed = ArtworkRetryState(requestSignal = 0L).failed(http(404))

        val next = awaitArtworkRetry(failed, signal)
        assertEquals(0L, currentTime)
        assertEquals(1L, next.requestSignal)
    }

    @Test
    fun should_keepFallbackIconUnder_when_retryIsInFlight() {
        val failed = ArtworkRetryState(requestSignal = 0L).failed(IOException())
        assertFalse(failed.requesting)
        assertTrue(failed.fallbackUnder)

        val retrying = failed.retried(signal = 0L, byBackoff = true)
        assertTrue(retrying.requesting)
        assertTrue(retrying.fallbackUnder)
    }

    @Test
    fun should_dropFallbackIcon_when_retriedImageHasRevealed() {
        val revealed = ArtworkRetryState(requestSignal = 0L)
            .failed(IOException())
            .retried(signal = 0L, byBackoff = true)
            .revealed()
        assertTrue(revealed.requesting)
        assertFalse(revealed.fallbackUnder)
    }

    @Test
    fun should_neitherShowIconNorWait_when_artworkHasNotFailed() {
        val fresh = ArtworkRetryState(requestSignal = 0L)
        assertTrue(fresh.requesting)
        assertFalse(fresh.fallbackUnder)
        assertNull(fresh.backoffMillis())
    }
}
