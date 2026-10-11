package com.gpo.yoin

import android.content.Context
import android.graphics.ImageDecoder
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import com.gpo.yoin.testutil.undecodableImageBytes
import com.gpo.yoin.ui.component.ArtworkFailureKind
import com.gpo.yoin.ui.component.ArtworkRetryPolicy
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Test
    fun should_failAsUndecodable_when_imageJpegBodyMakesNoImage() = runBlocking {
        server.enqueue(garbageCover())
        val url = server.url("/cover.jpg").toString()

        val failed = loader.execute(load(url)) as ErrorResult

        // ImageDecoder's failure, an IOException: what the device logged as err=DecodeException.
        assertTrue(failed.throwable.toString(), failed.throwable is ImageDecoder.DecodeException)
        assertEquals(ArtworkFailureKind.Undecodable, ArtworkRetryPolicy.classify(failed.throwable))
    }
}
