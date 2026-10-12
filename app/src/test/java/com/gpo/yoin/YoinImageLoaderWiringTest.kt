package com.gpo.yoin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.network.HttpException
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.remote.SubsonicApiFactory
import com.gpo.yoin.data.remote.SubsonicAuth
import com.gpo.yoin.data.remote.SubsonicCacheKey
import com.gpo.yoin.testutil.pngBytes
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The app's loader as built — its disk cache directory and its cache strategy — not the strategy alone.
 * Native graphics: Coil decodes a stored body with ImageDecoder, which the legacy shadows cannot run.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class YoinImageLoaderWiringTest {

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

    /** A Subsonic cover's url as a process signs it: SubsonicAuth draws the salt once per process. */
    private fun subsonicCover(salt: String): String {
        val credentials = ServerCredentials(serverUrl = server.url("/").toString(), username = "qa", password = "pw")
        return SubsonicApiFactory.buildCoverArtUrl(credentials, "al-1", size = 300).toHttpUrl().newBuilder()
            .setQueryParameter("t", SubsonicAuth.md5("pw$salt"))
            .setQueryParameter("s", salt)
            .build()
            .toString()
    }

    private fun load(url: String) =
        ImageRequest.Builder(context).data(url).memoryCachePolicy(CachePolicy.DISABLED).build()

    @Test
    fun should_readTheStoredCover_when_anotherProcessSignsItsUrlWithANewSalt() = runBlocking {
        val cover = Buffer().write(pngBytes())
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png").setBody(cover))

        val first = loader.execute(load(subsonicCover(salt = "aaaa1111"))) as SuccessResult
        assertEquals(DataSource.NETWORK, first.dataSource)

        // The next process: same cover, new salt, no memory cache — only the disk can answer.
        val next = loader.execute(load(subsonicCover(salt = "bbbb2222"))) as SuccessResult
        assertEquals(DataSource.DISK, next.dataSource)
        assertEquals(1, server.requestCount)
        // Stored under the key that leaves the signature out.
        assertEquals(SubsonicCacheKey.of(subsonicCover(salt = "aaaa1111")), next.diskCacheKey)
    }

    @Test
    fun should_storeImagesInTheV2Directory_when_loaderIsBuilt() {
        val directory = loader.diskCache?.directory?.toFile()
        assertEquals(File(context.cacheDir, IMAGE_DISK_CACHE_DIRECTORY), directory)
    }

    @Test
    fun should_notStoreErrorResponses_when_loadingThroughTheAppLoader() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("{}"))
        val cover = Buffer().write(pngBytes())
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png").setBody(cover))
        val url = server.url("/cover.png").toString()
        val diskCache = checkNotNull(loader.diskCache)

        // A real image: the loader drops a stored body that does not decode. Keep each load off the memory cache.
        fun load() = ImageRequest.Builder(context).data(url).memoryCachePolicy(CachePolicy.DISABLED).build()

        val missing = loader.execute(load()) as ErrorResult
        assertEquals(missing.throwable.toString(), 404, (missing.throwable as? HttpException)?.response?.code)
        assertNull(diskCache.openSnapshot(url))

        loader.execute(load())
        assertNull(diskCache.openSnapshot(url))

        loader.execute(load())
        assertEquals(3, server.requestCount)
        diskCache.openSnapshot(url).use { assertNotNull(it) }
    }
}
