package com.gpo.yoin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.network.NetworkResponse
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Size
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Which failures cost a url its stored copy: only bytes that made no image. */
@RunWith(RobolectricTestRunner::class)
class ImageDiskCacheInterceptorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val url = "https://images.example/cover.jpg"
    private val loaders = mutableListOf<ImageLoader>()

    @After
    fun cleanup() {
        loaders.forEach(ImageLoader::shutdown)
    }

    /** A disk cache holding a copy of [url], and a loader whose every request ends in [failure]. */
    private fun storedCopyFailingWith(failure: Throwable): Pair<DiskCache, ImageLoader> {
        val diskCache = DiskCache.Builder().directory(temp.newFolder().toOkioPath()).maxSizeBytes(1_000_000L).build()
        diskCache.openEditor(url)!!.apply { diskCache.fileSystem.write(data) { writeUtf8("stored") } }.commit()
        val loader = ImageLoader.Builder(context).memoryCache(null).diskCache(diskCache).components {
            add(ImageDiskCacheInterceptor { diskCache })
            add(Interceptor { chain -> ErrorResult(null, chain.request, failure) })
        }.build()
        loaders += loader
        return diskCache to loader
    }

    @Test
    fun should_dropTheStoredCopy_when_bytesMadeNoImage() = runBlocking {
        val (diskCache, loader) = storedCopyFailingWith(IllegalStateException("BitmapFactory returned a null bitmap."))
        loader.execute(ImageRequest.Builder(context).data(url).build())
        assertNull(diskCache.openSnapshot(url))
    }

    @Test
    fun should_dropTheStoredCopy_when_requestIsCancelledAsItFails() = runBlocking {
        val diskCache = DiskCache.Builder().directory(temp.newFolder().toOkioPath()).maxSizeBytes(1_000_000L).build()
        diskCache.openEditor(url)!!.apply { diskCache.fileSystem.write(data) { writeUtf8("stored") } }.commit()
        val request = ImageRequest.Builder(context).data(url).build()
        val failure = IllegalStateException("BitmapFactory returned a null bitmap.")

        // Scrolled off, a widget's timeout, a skip: the request's job goes as the decode fails.
        launch { ImageDiskCacheInterceptor { diskCache }.intercept(CancelledAsItFails(request, failure)) }.join()

        assertNull(diskCache.openSnapshot(url))
    }

    /** A chain whose request fails with [failure] while its job is being cancelled. */
    private class CancelledAsItFails(
        override val request: ImageRequest,
        private val failure: Throwable
    ) : Interceptor.Chain {
        override val size: Size = Size.ORIGINAL

        override fun withRequest(request: ImageRequest): Interceptor.Chain = CancelledAsItFails(request, failure)

        override fun withSize(size: Size): Interceptor.Chain = this

        override suspend fun proceed(): ImageResult {
            currentCoroutineContext().job.cancel()
            return ErrorResult(null, request, failure)
        }
    }

    @Test
    fun should_keepTheStoredCopy_when_failureIsTheNetworkOrHttp() = runBlocking {
        val failures = listOf(
            IOException("offline"),
            HttpException(NetworkResponse(code = 404)),
            HttpException(NetworkResponse(code = 503))
        )
        failures.forEach { failure ->
            val (diskCache, loader) = storedCopyFailingWith(failure)
            loader.execute(ImageRequest.Builder(context).data(url).build())
            diskCache.openSnapshot(url).use { assertNotNull(failure.toString(), it) }
        }
    }

    @Test
    fun should_keepTheStoredCopy_when_loadSucceeds() = runBlocking {
        val diskCache = DiskCache.Builder().directory(temp.newFolder().toOkioPath()).maxSizeBytes(1_000_000L).build()
        diskCache.openEditor(url)!!.apply { diskCache.fileSystem.write(data) { writeUtf8("stored") } }.commit()
        val loader = ImageLoader.Builder(context).memoryCache(null).diskCache(diskCache).components {
            add(ImageDiskCacheInterceptor { diskCache })
            add(Interceptor { chain -> SuccessResult(ColorImage(0), chain.request, DataSource.DISK) })
        }.build()
        loaders += loader
        loader.execute(ImageRequest.Builder(context).data(url).build())
        diskCache.openSnapshot(url).use { assertNotNull(it) }
    }
}
