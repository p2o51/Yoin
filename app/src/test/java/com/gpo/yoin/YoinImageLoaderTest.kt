package com.gpo.yoin

import android.content.Context
import android.os.Looper
import coil3.annotation.ExperimentalCoilApi
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.fetch.SourceFetchResult
import coil3.network.CacheStrategy
import coil3.network.ConcurrentRequestStrategy
import coil3.network.ConnectivityChecker
import coil3.network.HttpException
import coil3.network.NetworkFetcher
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.okhttp.asNetworkClient
import coil3.request.Options
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoilApi::class)
class YoinImageLoaderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val options = Options(mockk<Context>(relaxed = true))
    private val request = NetworkRequest(url = "https://images.example/cover.jpg")
    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun cleanup() {
        server.shutdown()
    }

    @Test
    fun should_notWriteDiskCache_when_responseIs404() = runTest {
        val result = SuccessOnlyCacheStrategy.write(null, request, NetworkResponse(code = 404), options)
        assertSame(CacheStrategy.WriteResult.DISABLED, result)
    }

    @Test
    fun should_notWriteDiskCache_when_responseIsAnyOtherError() = runTest {
        // Coil's default stores the first seven; none of them may reach the disk.
        listOf(300, 301, 404, 405, 410, 414, 501, 500, 503, 504).forEach { code ->
            val result = SuccessOnlyCacheStrategy.write(null, request, NetworkResponse(code = code), options)
            assertSame("$code", CacheStrategy.WriteResult.DISABLED, result)
        }
        val default404 = CacheStrategy.DEFAULT.write(null, request, NetworkResponse(code = 404), options)
        assertNotEquals(CacheStrategy.WriteResult.DISABLED, default404)
    }

    @Test
    fun should_writeDiskCache_when_responseIs200() = runTest {
        val ok = NetworkResponse(code = 200, headers = NetworkHeaders.Builder().set("ETag", "\"a\"").build())
        val result = SuccessOnlyCacheStrategy.write(null, request, ok, options)
        assertEquals(ok, result.response)
    }

    @Test
    fun should_delegateToDefault_when_responseIs304() = runTest {
        val cached = NetworkResponse(code = 200, headers = NetworkHeaders.Builder().set("ETag", "\"a\"").build())
        val notModified = NetworkResponse(
            code = 304,
            headers = NetworkHeaders.Builder().set("Cache-Control", "max-age=60").build()
        )
        val result = SuccessOnlyCacheStrategy.write(cached, request, notModified, options)
        assertEquals(CacheStrategy.DEFAULT.write(cached, request, notModified, options), result)
        // Coil merges the revalidation's headers into the stored entry.
        val headers = result.response?.headers
        assertEquals("\"a\"", headers?.get("ETag"))
        assertEquals("max-age=60", headers?.get("Cache-Control"))
    }

    @Test
    fun should_bypassCache_when_cachedResponseIsError() = runTest {
        listOf(301, 404, 410, 501).forEach { code ->
            val result = SuccessOnlyCacheStrategy.read(NetworkResponse(code = code), request, options)
            assertNull("$code", result.response)
            assertEquals("$code", request, result.request)
        }
    }

    @Test
    fun should_reuseCache_when_cachedResponseIs200OrRevalidated() = runTest {
        listOf(200, 304).forEach { code ->
            val cached = NetworkResponse(code = code)
            val result = SuccessOnlyCacheStrategy.read(cached, request, options)
            assertEquals("$code", cached, result.response)
            assertNull("$code", result.request)
        }
    }

    @Test
    fun should_fetchFromNetworkAgain_when_theLastResponseWas404() = runBlocking {
        // Coil refuses to fetch on the main looper; JVM stubs report null for both.
        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk(relaxed = true)
        try {
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "image/jpeg").setBody("cover"))
            val url = server.url("/cover.jpg").toString()
            val diskCache = DiskCache.Builder()
                .directory(temp.newFolder("images").toOkioPath())
                .maxSizeBytes(1_000_000L)
                .build()
            val client = OkHttpClient()
            fun fetcher() = NetworkFetcher(
                url = url,
                options = options,
                networkClient = lazy { client.asNetworkClient() },
                diskCache = lazy { diskCache },
                cacheStrategy = lazy { SuccessOnlyCacheStrategy },
                connectivityChecker = lazy { ConnectivityChecker.ONLINE },
                concurrentRequestStrategy = lazy { ConcurrentRequestStrategy.UNCOORDINATED }
            )

            val missing = runCatching { fetcher().fetch() }.exceptionOrNull()
            assertEquals(404, (missing as? HttpException)?.response?.code)
            assertNull(diskCache.openSnapshot(url))

            val found = fetcher().fetch() as SourceFetchResult
            found.source.close()
            assertEquals(DataSource.NETWORK, found.dataSource)
            assertEquals(2, server.requestCount)
            diskCache.openSnapshot(url).use { assertNotNull(it) }
        } finally {
            unmockkStatic(Looper::class)
        }
    }

    @Test
    fun should_deleteOnlyTheLegacyDirectory_when_retiringTheOldCache() {
        val cacheDir = temp.newFolder("cache")
        val legacy = File(cacheDir, LEGACY_IMAGE_DISK_CACHE_DIRECTORY).apply { mkdirs() }
        File(legacy, "journal").writeText("404")
        val current = File(cacheDir, IMAGE_DISK_CACHE_DIRECTORY).apply { mkdirs() }
        File(current, "journal").writeText("200")

        deleteLegacyImageDiskCache(cacheDir)
        assertFalse(legacy.exists())
        assertTrue(File(current, "journal").exists())

        // Nothing left to delete on later launches.
        deleteLegacyImageDiskCache(cacheDir)
        assertTrue(current.exists())
    }
}
