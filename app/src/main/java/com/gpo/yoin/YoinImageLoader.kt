package com.gpo.yoin

import android.content.Context
import coil3.ImageLoader
import coil3.Uri
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.network.CacheStrategy
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.Options
import com.gpo.yoin.data.remote.SubsonicCacheKey
import com.gpo.yoin.perf.YoinPerfImages
import com.gpo.yoin.ui.component.ArtworkFailureKind
import com.gpo.yoin.ui.component.ArtworkRetryPolicy
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/**
 * Coil's disk cache, under `cacheDir`. Coil 3.4's default strategy stored
 * 300/301/404/405/410/414/501 responses next to the images, and its fetcher
 * rejects a cached error before any strategy is asked — one 404 replayed
 * forever, across restarts. A fresh directory drops every such entry at once;
 * [SuccessOnlyCacheStrategy] keeps new ones out.
 */
internal const val IMAGE_DISK_CACHE_DIRECTORY = "coil3_disk_cache_v2"

/** Coil's default directory (`coil3/disk/utils.kt`), retired with the entries above. */
internal const val LEGACY_IMAGE_DISK_CACHE_DIRECTORY = "coil3_disk_cache"

/**
 * The app's one Coil loader (see [YoinApplication.newImageLoader]). The disk
 * cache keeps Coil's default size — 2% of the free space when it opens,
 * clamped to 10–250 MB — and only its directory moves.
 */
@OptIn(ExperimentalCoilApi::class)
internal fun buildYoinImageLoader(context: Context): ImageLoader {
    val diskCache = lazy {
        DiskCache.Builder()
            .directory(File(context.cacheDir, IMAGE_DISK_CACHE_DIRECTORY).toOkioPath())
            .build()
    }
    return ImageLoader.Builder(context)
        .components {
            add(ImageDiskCacheInterceptor { diskCache.value })
            add(
                OkHttpNetworkFetcherFactory(
                    // Its own client with OkHttp's default timeouts. Not the Spotify or
                    // Subsonic client: both carry an OkHttp HTTP cache, which would store
                    // every image a second time beside Coil's disk cache.
                    callFactory = { OkHttpClient() },
                    cacheStrategy = { SuccessOnlyCacheStrategy }
                )
            )
        }
        .diskCache { diskCache.value }
        .apply { YoinPerfImages.listenerFactory()?.let(::eventListenerFactory) }
        .build()
}

/**
 * Owns a network image's entry in [diskCache], for every request the loader
 * runs — the artwork, the palette reads, Now Playing, the widgets.
 *
 * Its key: a Subsonic url is stored under [SubsonicCacheKey] instead of
 * itself, so a cover one process stored is found by the next (the url carries
 * a salt drawn per process). Any other url keeps Coil's key, the url.
 *
 * Bytes that made no image: Coil stores a body before it decodes it, and
 * serves a stored entry without asking the network, so the next request for
 * that url, this one's retry or a sibling's read, would decode the same bytes
 * again, in this process or the next. On an [ArtworkFailureKind.Undecodable]
 * failure the entry goes before the result is returned; any other failure
 * leaves it alone.
 */
internal class ImageDiskCacheInterceptor(private val diskCache: () -> DiskCache?) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = networkUrlOf(request.data)
        val named = request.diskCacheKey
        val key = named ?: url?.let { SubsonicCacheKey.of(it) ?: it } ?: return chain.proceed()
        val keyed = if (named == null && key != url) {
            chain.withRequest(request.newBuilder().diskCacheKey(key).build())
        } else {
            chain
        }
        val result = keyed.proceed()
        if (result is ErrorResult && ArtworkRetryPolicy.classify(result.throwable) == ArtworkFailureKind.Undecodable) {
            // Even when the request is cancelled as it fails (scrolled off, a widget's
            // timeout, a skip): a cancellable hop would skip the removal, and the bytes
            // would stay under a key that outlives the process.
            withContext(NonCancellable + Dispatchers.IO) { diskCache()?.remove(key) }
        }
        return result
    }

    /** The key Coil stores a network url under when the request names none: the url itself. */
    private fun networkUrlOf(data: Any): String? = when (data) {
        is String -> data
        is Uri -> data.toString()
        else -> null
    }?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
}

/**
 * Caches only what can be shown again: a 2xx that carries an image, or a 304
 * revalidating an entry (Coil's default merges its headers into the stored
 * one). Anything else is neither written nor, should an old entry hold one,
 * read back — the request goes to the network instead. Coil's fetcher already
 * rejects a stored non-2xx code before [read] is asked; a stored 2xx that is
 * not an image only this strategy keeps out.
 */
@OptIn(ExperimentalCoilApi::class)
internal object SuccessOnlyCacheStrategy : CacheStrategy {
    override suspend fun read(
        cacheResponse: NetworkResponse,
        networkRequest: NetworkRequest,
        options: Options
    ): CacheStrategy.ReadResult = if (cacheResponse.isShowable()) {
        CacheStrategy.DEFAULT.read(cacheResponse, networkRequest, options)
    } else {
        CacheStrategy.ReadResult(networkRequest)
    }

    override suspend fun write(
        cacheResponse: NetworkResponse?,
        networkRequest: NetworkRequest,
        networkResponse: NetworkResponse,
        options: Options
    ): CacheStrategy.WriteResult = if (networkResponse.isShowable()) {
        CacheStrategy.DEFAULT.write(cacheResponse, networkRequest, networkResponse, options)
    } else {
        CacheStrategy.WriteResult.DISABLED
    }

    // A stored entry's code is 304 once a revalidation has rewritten its headers.
    private fun NetworkResponse.isShowable(): Boolean = when (code) {
        HTTP_NOT_MODIFIED -> true
        HTTP_NO_CONTENT -> false
        in 200..299 -> hasImageBody()
        else -> false
    }

    /**
     * A 2xx can still carry no image: Subsonic answers a failed `getCoverArt`
     * with HTTP 200 and its JSON (`f=json`) or XML error, a captive portal with
     * its HTML page. Stored, that body would fail to decode on every retry.
     * A missing or generic type (octet-stream, text/plain) stays cacheable —
     * servers send images under those too.
     */
    private fun NetworkResponse.hasImageBody(): Boolean {
        if (headers[CONTENT_LENGTH]?.trim() == "0") return false
        val type = headers[CONTENT_TYPE]?.substringBefore(';')?.trim()?.lowercase() ?: return true
        return type.startsWith("image/") || NOT_IMAGE_TYPE_MARKERS.none { it in type }
    }

    private const val HTTP_NO_CONTENT = 204
    private const val HTTP_NOT_MODIFIED = 304
    private const val CONTENT_TYPE = "Content-Type"
    private const val CONTENT_LENGTH = "Content-Length"
    private val NOT_IMAGE_TYPE_MARKERS = listOf("json", "xml", "html")
}

/**
 * Deletes [LEGACY_IMAGE_DISK_CACHE_DIRECTORY] off the main thread. Nothing opens it
 * any more, so it goes once, on the first launch of this version; later
 * launches find nothing to delete.
 */
internal fun retireLegacyImageDiskCache(cacheDir: File) {
    CoroutineScope(Dispatchers.IO).launch { deleteLegacyImageDiskCache(cacheDir) }
}

internal fun deleteLegacyImageDiskCache(cacheDir: File) {
    File(cacheDir, LEGACY_IMAGE_DISK_CACHE_DIRECTORY).deleteRecursively()
}
