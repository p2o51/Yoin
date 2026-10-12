package com.gpo.yoin.ui.component

import com.gpo.yoin.testutil.imageDecoderFailure
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * [ArtworkRetryPolicy] on a device API level, where Coil decodes a disk-cache file with
 * ImageDecoder and its failure is an IOException (ArtworkRetryTest runs without Android).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkRetryDecoderTest {

    @Test
    fun should_classifyAsUndecodable_when_imageDecoderRejectsTheBytes() {
        val failure = imageDecoderFailure()
        assertTrue(failure is IOException)
        listOf(failure, RuntimeException(failure)).forEach { error ->
            assertEquals(error.toString(), ArtworkFailureKind.Undecodable, ArtworkRetryPolicy.classify(error))
            // Only a signal retries it: no backoff re-reads the same stored bytes.
            assertNull(ArtworkRetryState(requestSignal = 0L).failed(error).backoffMillis())
        }
    }

    @Test
    fun should_stayTransient_when_ioFailureIsNotADecode() {
        listOf(IOException("unexpected end of stream"), SocketTimeoutException()).forEach { error ->
            assertEquals(error.toString(), ArtworkFailureKind.Transient, ArtworkRetryPolicy.classify(error))
        }
    }
}
