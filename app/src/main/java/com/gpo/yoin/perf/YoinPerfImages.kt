package com.gpo.yoin.perf

import android.os.SystemClock
import coil3.EventListener
import coil3.network.HttpException
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The debug-only Coil listener: `image.ok src=<DataSource> ms=…` per loaded
 * image and `image.error host=… code=… err=… ms=…` per failure. Never the URL
 * (Subsonic cover URLs carry auth in the query) — only its host. Null in
 * release, so the singleton loader is built without a listener there.
 */
object YoinPerfImages {
    fun listenerFactory(): EventListener.Factory? = if (YoinPerf.enabled) PerRequestFactory else null

    private object PerRequestFactory : EventListener.Factory {
        override fun create(request: ImageRequest): EventListener = PerRequestListener()
    }

    private class PerRequestListener : EventListener() {
        private var startMs = 0L

        override fun onStart(request: ImageRequest) {
            startMs = SystemClock.elapsedRealtime()
        }

        override fun onSuccess(request: ImageRequest, result: SuccessResult) {
            YoinPerf.line("image.ok", "src" to result.dataSource.name, "ms" to elapsed())
        }

        override fun onError(request: ImageRequest, result: ErrorResult) {
            val error = result.throwable
            val http = generateSequence(error) { it.cause }.filterIsInstance<HttpException>().firstOrNull()
            YoinPerf.line(
                "image.error",
                "host" to hostOf(request.data),
                "code" to http?.response?.code,
                "err" to error.javaClass.simpleName,
                "ms" to elapsed()
            )
        }

        private fun elapsed(): Long = if (startMs == 0L) -1L else SystemClock.elapsedRealtime() - startMs
    }

    /** The request's host for a network URL, else its data type (file, resource, …). */
    private fun hostOf(data: Any): String = data.toString().toHttpUrlOrNull()?.host ?: data.javaClass.simpleName
}
