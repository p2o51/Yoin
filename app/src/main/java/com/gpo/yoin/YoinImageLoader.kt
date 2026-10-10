package com.gpo.yoin

import android.content.Context
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.network.CacheStrategy
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.Options
import com.gpo.yoin.perf.YoinPerfImages
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
internal fun buildYoinImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
    .components {
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
    .diskCache {
        DiskCache.Builder()
            .directory(File(context.cacheDir, IMAGE_DISK_CACHE_DIRECTORY).toOkioPath())
            .build()
    }
    .apply { YoinPerfImages.listenerFactory()?.let(::eventListenerFactory) }
    .build()

/**
 * Caches only what can be shown again: a 2xx, or a 304 revalidating an entry
 * (Coil's default merges its headers into the stored one). Any other status is
 * neither written nor, should an old entry hold one, read back — the request
 * goes to the network instead.
 */
@OptIn(ExperimentalCoilApi::class)
internal object SuccessOnlyCacheStrategy : CacheStrategy {
    override suspend fun read(
        cacheResponse: NetworkResponse,
        networkRequest: NetworkRequest,
        options: Options
    ): CacheStrategy.ReadResult = if (cacheResponse.code.isReusable()) {
        CacheStrategy.DEFAULT.read(cacheResponse, networkRequest, options)
    } else {
        CacheStrategy.ReadResult(networkRequest)
    }

    override suspend fun write(
        cacheResponse: NetworkResponse?,
        networkRequest: NetworkRequest,
        networkResponse: NetworkResponse,
        options: Options
    ): CacheStrategy.WriteResult = if (networkResponse.code.isReusable()) {
        CacheStrategy.DEFAULT.write(cacheResponse, networkRequest, networkResponse, options)
    } else {
        CacheStrategy.WriteResult.DISABLED
    }

    // A stored entry's code is 304 once a revalidation has rewritten its headers.
    private fun Int.isReusable(): Boolean = this in 200..299 || this == 304
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
