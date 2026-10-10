package com.gpo.yoin.ui.component

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.network.NetworkResponse
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.theme.YoinTheme
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A failed [ExpressiveMediaArtwork] asks again: on a backoff for transient errors, on the signal for 404. */
@RunWith(RobolectricTestRunner::class)
class ExpressiveMediaArtworkRetryTest {

    @get:Rule
    val rule = createComposeRule()

    private val attempts = AtomicInteger(0)
    private var originalLoader: ImageLoader? = null
    private var loader: ImageLoader? = null

    /** Every request is answered by [outcome] for its 1-based attempt: an error, or null for a cover. */
    private fun answerWith(outcome: (attempt: Int) -> Throwable?) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        originalLoader = SingletonImageLoader.get(context)
        loader = ImageLoader.Builder(context).memoryCache(null).diskCache(null).components {
            add(
                Interceptor { chain ->
                    val error = outcome(attempts.incrementAndGet())
                    if (error == null) {
                        SuccessResult(ColorImage(Color.Red.toArgb()), chain.request, DataSource.NETWORK)
                    } else {
                        ErrorResult(null, chain.request, error)
                    }
                }
            )
        }.build().also(SingletonImageLoader::setUnsafe)
    }

    @After
    fun restore() {
        originalLoader?.let(SingletonImageLoader::setUnsafe)
        loader?.shutdown()
    }

    private fun showArtwork() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            YoinTheme {
                ExpressiveMediaArtwork(
                    model = "https://images.example/cover.jpg",
                    contentDescription = "Cover",
                    fallbackIcon = YoinSymbols.Album,
                    modifier = Modifier.size(120.dp)
                )
            }
        }
    }

    private fun awaitAttempts(count: Int) {
        rule.waitUntil(5_000) {
            rule.mainClock.advanceTimeByFrame()
            attempts.get() >= count
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    @Test
    fun should_retryOnTheSignalOnly_when_responseIs404() {
        answerWith { attempt -> if (attempt == 1) HttpException(NetworkResponse(code = 404)) else null }
        showArtwork()

        awaitAttempts(1)
        rule.onNodeWithContentDescription("Cover").assertDoesNotExist()

        rule.mainClock.advanceTimeBy(120_000)
        rule.waitForIdle()
        assertEquals(1, attempts.get())

        ArtworkRetrySignal.bump()
        awaitAttempts(2)
        rule.onNodeWithContentDescription("Cover").assertExists()
    }

    @Test
    fun should_retryAfterThreeSeconds_when_loadFailedTransiently() {
        answerWith { attempt -> if (attempt == 1) IOException("offline") else null }
        showArtwork()

        awaitAttempts(1)
        rule.onNodeWithContentDescription("Cover").assertDoesNotExist()

        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        assertEquals(1, attempts.get())

        rule.mainClock.advanceTimeBy(1_500)
        awaitAttempts(2)
        rule.onNodeWithContentDescription("Cover").assertExists()
    }
}
