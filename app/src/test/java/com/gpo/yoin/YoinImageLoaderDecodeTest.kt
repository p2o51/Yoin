package com.gpo.yoin

import android.content.Context
import android.graphics.ImageDecoder
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.remote.SubsonicApiFactory
import com.gpo.yoin.data.remote.SubsonicCacheKey
import com.gpo.yoin.testutil.pngBytes
import com.gpo.yoin.testutil.undecodableImageBytes
import com.gpo.yoin.ui.component.ArtworkFailureKind
import com.gpo.yoin.ui.component.ArtworkRetryPolicy
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The app's loader against bytes that make no image, with real decoders (native graphics):
 * the path a device takes, where Coil stores the body first and decodes the stored file.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class YoinImageLoaderDecodeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: MockWebServer
    private lateinit var loader: ImageLoader

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        loader = buildYoinImageLoader(context)
    }

    @After
    fun cleanup() {
        loader.shutdown()
        loader.diskCache?.clear()
        server.shutdown()
    }

    private fun garbageCover() = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "image/jpeg")
        .setBody(Buffer().write(undecodableImageBytes()))

    private fun load(url: String) =
        ImageRequest.Builder(context).data(url).memoryCachePolicy(CachePolicy.DISABLED).build()

    private fun realCover() = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "image/png")
        .setBody(Buffer().write(pngBytes()))

    // What the palette reads ask for: a small software bitmap of the same url.
    private fun paletteRead(url: String) = ImageRequest.Builder(context).data(url)
        .size(Size(64, 64))
        .allowHardware(false)
        .memoryCachePolicy(CachePolicy.DISABLED)
        .build()

    @Test
    fun should_failAsUndecodable_when_imageJpegBodyMakesNoImage() = runBlocking {
        server.enqueue(garbageCover())
        val url = server.url("/cover.jpg").toString()

        val failed = loader.execute(load(url)) as ErrorResult

        // ImageDecoder's failure, an IOException: what the device logged as err=DecodeException.
        assertTrue(failed.throwable.toString(), failed.throwable is ImageDecoder.DecodeException)
        assertEquals(ArtworkFailureKind.Undecodable, ArtworkRetryPolicy.classify(failed.throwable))
    }

    @Test
    fun should_dropTheStoredCopy_when_imageJpegBodyMakesNoImage() = runBlocking {
        server.enqueue(garbageCover())
        server.enqueue(realCover())
        val url = server.url("/cover.jpg").toString()
        val diskCache = checkNotNull(loader.diskCache)

        assertTrue(loader.execute(load(url)) is ErrorResult)
        assertNull(diskCache.openSnapshot(url))

        // The retry asks the network, which serves the real cover by now.
        val healed = loader.execute(load(url)) as SuccessResult
        assertEquals(DataSource.NETWORK, healed.dataSource)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun should_dropTheStoredCopy_when_aSubsonicCoverMakesNoImage() = runBlocking {
        server.enqueue(garbageCover())
        server.enqueue(realCover())
        val credentials = ServerCredentials(serverUrl = server.url("/").toString(), username = "qa", password = "pw")
        val url = SubsonicApiFactory.buildCoverArtUrl(credentials, "al-1", size = 300)
        val key = checkNotNull(SubsonicCacheKey.of(url))
        val diskCache = checkNotNull(loader.diskCache)

        assertTrue(loader.execute(load(url)) is ErrorResult)
        // Gone from under the key the next process looks up, not only this process's url.
        assertNull(diskCache.openSnapshot(key))
        assertNull(diskCache.openSnapshot(url))

        val healed = loader.execute(load(url)) as SuccessResult
        assertEquals(DataSource.NETWORK, healed.dataSource)
        diskCache.openSnapshot(key).use { assertNotNull(it) }
    }

    @Test
    fun should_dropTheStoredCopy_when_aPaletteReadFindsNoImage() = runBlocking {
        server.enqueue(garbageCover())
        server.enqueue(garbageCover())
        server.enqueue(realCover())
        val url = server.url("/cover.jpg").toString()
        val diskCache = checkNotNull(loader.diskCache)

        // The artwork fails and its copy goes; the palette read then fetches and stores the
        // same bytes again, and its failure takes them out too.
        assertTrue(loader.execute(load(url)) is ErrorResult)
        assertTrue(loader.execute(paletteRead(url)) is ErrorResult)
        assertNull(diskCache.openSnapshot(url))

        // So the artwork's retry is not served the palette read's bytes from the disk.
        val healed = loader.execute(load(url)) as SuccessResult
        assertEquals(DataSource.NETWORK, healed.dataSource)
        assertEquals(3, server.requestCount)
    }
}
