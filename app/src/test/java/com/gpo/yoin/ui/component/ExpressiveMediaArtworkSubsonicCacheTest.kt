package com.gpo.yoin.ui.component

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.EventListener
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.gpo.yoin.buildYoinImageLoader
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.remote.SubsonicApiFactory
import com.gpo.yoin.data.remote.SubsonicCacheKey
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.testutil.pngBytes
import com.gpo.yoin.testutil.signedWithSalt
import com.gpo.yoin.ui.theme.YoinTheme
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * A Subsonic cover shown by [ExpressiveMediaArtwork] through the app's loader: Compose's request,
 * with its target and size resolver, is stored under [SubsonicCacheKey], and the next process,
 * whose url has a new salt, reads it back from the disk. Native graphics: Coil decodes the stored
 * file with ImageDecoder.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpressiveMediaArtworkSubsonicCacheTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val results = CopyOnWriteArrayList<ImageResult>()
    private var originalLoader: ImageLoader? = null
    private lateinit var loader: ImageLoader

    @Before
    fun setup() {
        server.start()
        originalLoader = SingletonImageLoader.get(context)
        // The app's loader as built; the listener only records how each request ended.
        loader = buildYoinImageLoader(context).newBuilder().eventListener(
            object : EventListener() {
                override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                    results += result
                }

                override fun onError(request: ImageRequest, result: ErrorResult) {
                    results += result
                }
            }
        ).build().also(SingletonImageLoader::setUnsafe)
    }

    @After
    fun cleanup() {
        originalLoader?.let(SingletonImageLoader::setUnsafe)
        loader.shutdown()
        loader.diskCache?.clear()
        server.shutdown()
    }

    private fun cover(salt: String): String {
        val credentials = ServerCredentials(serverUrl = server.url("/").toString(), username = "qa", password = "pw")
        return signedWithSalt(SubsonicApiFactory.buildCoverArtUrl(credentials, "al-1", size = 300), "pw", salt)
    }

    private fun awaitResults(count: Int) {
        rule.waitUntil(10_000) {
            rule.mainClock.advanceTimeByFrame()
            results.size >= count
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    private fun fallbackIcon() = rule.onNodeWithTag(ARTWORK_FALLBACK_TAG, useUnmergedTree = true)

    private fun loaded(index: Int): SuccessResult = when (val result = results[index]) {
        is SuccessResult -> result
        is ErrorResult -> throw AssertionError("load ${index + 1} failed", result.throwable)
    }

    @Test
    fun should_readTheStoredCover_when_theNextProcessShowsItAtLayoutSize() {
        assertTheNextProcessReadsTheStoredCover(requestSizePx = null)
    }

    @Test
    fun should_readTheStoredCover_when_theNextProcessShowsItAtAFixedSize() {
        assertTheNextProcessReadsTheStoredCover(requestSizePx = 300)
    }

    private fun assertTheNextProcessReadsTheStoredCover(requestSizePx: Int?) {
        val cover = Buffer().write(pngBytes())
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png").setBody(cover))
        var salt by mutableStateOf("aaaa1111")
        rule.mainClock.autoAdvance = false
        rule.setContent {
            YoinTheme {
                ExpressiveMediaArtwork(
                    model = cover(salt),
                    contentDescription = "Cover",
                    fallbackIcon = YoinSymbols.Album,
                    modifier = Modifier.size(120.dp),
                    requestSizePx = requestSizePx
                )
            }
        }

        awaitResults(1)
        // The key rewrite kept Compose's request whole: the cover loaded, no failure stood the icon in.
        val first = loaded(0)
        assertEquals(DataSource.NETWORK, first.dataSource)
        assertEquals(SubsonicCacheKey.of(cover("aaaa1111")), first.diskCacheKey)
        fallbackIcon().assertDoesNotExist()
        rule.onNodeWithContentDescription("Cover").assertExists()

        // The next process: same cover, new salt, nothing in memory — only the disk can answer.
        loader.memoryCache?.clear()
        salt = "bbbb2222"
        Snapshot.sendApplyNotifications()
        awaitResults(2)
        val next = loaded(1)
        assertEquals(DataSource.DISK, next.dataSource)
        assertEquals(1, server.requestCount)
        fallbackIcon().assertDoesNotExist()
        rule.onNodeWithContentDescription("Cover").assertExists()
    }
}
