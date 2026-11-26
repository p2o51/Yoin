package com.gpo.yoin.ui.component

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.SuccessResult
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The cover read behind every backdrop's colour (Home, search, and since PR #343 Library's grid): the
 * palette pass never runs on the main thread, never more than two at once, and a cover read once is
 * never read again — what lets a big Library grid take its covers' colours without the 2026-04 jank.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpressiveBackdropPaletteLoadTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var originalLoader: ImageLoader? = null
    private var loader: ImageLoader? = null
    private val requests = AtomicInteger()

    @Before
    fun answerWithCovers() {
        ExpressiveBackdropPaletteCache.clearForTest()
        originalLoader = SingletonImageLoader.get(context)
        loader = ImageLoader.Builder(context).memoryCache(null).diskCache(null).components {
            add(
                Interceptor { chain ->
                    requests.incrementAndGet()
                    val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(0xFF2A4A9A.toInt())
                    SuccessResult(bitmap.asImage(), chain.request, DataSource.NETWORK)
                },
            )
        }.build().also(SingletonImageLoader::setUnsafe)
    }

    @After
    fun restore() {
        originalLoader?.let(SingletonImageLoader::setUnsafe)
        loader?.shutdown()
        ExpressiveBackdropPaletteCache.clearForTest()
    }

    @Test
    fun should_readOffTheMainThreadTwoAtATime_when_aScreenfulOfCoversAsksAtOnce() {
        val mainThread = Looper.getMainLooper().thread
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val threads = Collections.synchronizedList(mutableListOf<Thread>())
        val models = (1..12).map { "test://library/cover/$it" }

        val results = runBlocking {
            models.map { model ->
                async(Dispatchers.Default) {
                    loadBackdropColors(context, model) { bitmap ->
                        threads += Thread.currentThread()
                        val now = running.incrementAndGet()
                        peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                        Thread.sleep(25)
                        running.decrementAndGet()
                        extractBackdropColors(bitmap)
                    }
                }
            }.awaitAll()
        }

        assertEquals(12, threads.size)
        assertFalse("never on the main thread", threads.any { it == mainThread })
        assertTrue("at most two passes at once, was ${peak.get()}", peak.get() <= 2)
        results.forEach { assertNotNull(it) }
        models.forEach { assertNotNull("cached by cover", cachedBackdropColors(it)) }
    }

    @Test
    fun should_neverReadACoverTwice_when_itsColoursAreCached() {
        val model = "test://library/cover/again"
        var passes = 0
        runBlocking {
            repeat(3) {
                loadBackdropColors(context, model) { bitmap ->
                    passes++
                    extractBackdropColors(bitmap)
                }
            }
        }

        assertEquals(1, passes)
        assertEquals(1, requests.get())
    }
}
