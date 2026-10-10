package com.gpo.yoin.ui.component

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.network.NetworkResponse
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.theme.YoinTheme
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A failed [ExpressiveMediaArtwork] asks again: on a backoff for transient errors, on the signal for 404
 * and undecodable bytes; the recovered cover reveals over the fallback icon, which then goes.
 */
@RunWith(RobolectricTestRunner::class)
class ExpressiveMediaArtworkRetryTest {

    @get:Rule
    val rule = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private val attempts = AtomicInteger(0)
    private var originalLoader: ImageLoader? = null
    private var loader: ImageLoader? = null

    /** Every request is answered by [outcome] for its 1-based attempt: an error, or null for a cover. */
    private fun answerWith(diskCache: DiskCache? = null, outcome: (attempt: Int) -> Throwable?) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        originalLoader = SingletonImageLoader.get(context)
        loader = ImageLoader.Builder(context).memoryCache(null).diskCache(diskCache).components {
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

    private fun advanceFrames(count: Int) {
        repeat(count) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
    }

    private fun fallbackIcon() = rule.onNodeWithTag(ARTWORK_FALLBACK_TAG, useUnmergedTree = true)

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

    @Test
    fun should_dropTheIconAfterTheReveal_when_retrySucceeds() {
        answerWith { attempt -> if (attempt == 1) IOException("offline") else null }
        showArtwork()
        awaitAttempts(1)
        fallbackIcon().assertExists()

        // Exactly the backoff: any further time would also run the reveal out.
        rule.mainClock.advanceTimeBy(3_000)
        awaitAttempts(2)
        // The recovered cover reveals over the icon instead of cutting to it.
        rule.onNodeWithContentDescription("Cover").assertExists()
        fallbackIcon().assertExists()

        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        fallbackIcon().assertDoesNotExist()
        rule.onNodeWithContentDescription("Cover").assertExists()

        // Dropping the icon leaves the shown cover's request alone.
        rule.mainClock.advanceTimeBy(120_000)
        rule.waitForIdle()
        assertEquals(2, attempts.get())
    }

    @Test
    fun should_neverShowTheIcon_when_firstLoadSucceeds() {
        answerWith { null }
        showArtwork()
        awaitAttempts(1)
        fallbackIcon().assertDoesNotExist()
        rule.onNodeWithContentDescription("Cover").assertExists()
    }

    @Test
    fun should_snapTheCoverIn_when_noLoadHasFailed() {
        answerWith { null }
        var revealed = false
        showSwap(revealFirstLoad = false) { revealed = true }
        awaitAttempts(1)
        advanceFrames(2)
        // No spring ran: the cover was in place on the frame it arrived.
        assertTrue(revealed)
    }

    @Test
    fun should_revealTheCoverOnASpring_when_loadIsARetry() {
        answerWith { null }
        var revealed = false
        showSwap(revealFirstLoad = true) { revealed = true }
        awaitAttempts(1)
        advanceFrames(2)
        assertFalse(revealed)

        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        assertTrue(revealed)
    }

    @Test
    fun should_dropTheStoredCopy_when_coverDidNotDecode() {
        val url = "https://images.example/cover.jpg"
        val diskCache = DiskCache.Builder().directory(temp.newFolder().toOkioPath()).maxSizeBytes(1_000_000L).build()
        // What a 200 error body would leave behind: bytes under the cover's url that make no image.
        diskCache.openEditor(url)!!.apply {
            diskCache.fileSystem.write(data) { writeUtf8("{\"subsonic-response\":{\"status\":\"failed\"}}") }
        }.commit()
        answerWith(diskCache) { attempt ->
            if (attempt == 1) IllegalStateException("BitmapFactory returned a null bitmap.") else null
        }
        showArtwork()
        awaitAttempts(1)

        rule.waitUntil(5_000) {
            rule.mainClock.advanceTimeByFrame()
            diskCache.openSnapshot(url)?.close() == null
        }
        // Only a signal retries it.
        rule.mainClock.advanceTimeBy(120_000)
        rule.waitForIdle()
        assertEquals(1, attempts.get())
        ArtworkRetrySignal.bump()
        awaitAttempts(2)
        rule.onNodeWithContentDescription("Cover").assertExists()
    }

    private fun showSwap(revealFirstLoad: Boolean, onRevealed: () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            YoinTheme {
                Box(Modifier.size(120.dp)) {
                    ArtworkSwap(
                        model = "https://images.example/cover.jpg",
                        contentDescription = "Cover",
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.Low,
                        requestSizePx = null,
                        reveal = ArtworkReveal.Crossfade,
                        direction = 1,
                        onError = {},
                        revealFirstLoad = revealFirstLoad,
                        onRevealed = onRevealed
                    )
                }
            }
        }
    }
}
