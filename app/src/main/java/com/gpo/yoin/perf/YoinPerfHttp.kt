package com.gpo.yoin.perf

import android.os.Trace
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.Response

/**
 * The debug-only `http` mark for the provider clients (Spotify, Apple Music,
 * Subsonic): `builder.apply { YoinPerfHttp.interceptor()?.let(::addInterceptor) }`,
 * and the `net` line every client derived from
 * [com.gpo.yoin.data.remote.YoinNetworkCore] logs per call.
 * Both null in release, so nothing is added there.
 */
object YoinPerfHttp {
    fun interceptor(): Interceptor? = if (YoinPerf.enabled) YoinPerfHttpInterceptor else null

    /** Per-call `net` lines and [YoinPerfMetrics] counters for one traffic class (media, api, image). */
    fun eventListenerFactory(trafficClass: String): EventListener.Factory? =
        if (YoinPerf.enabled) EventListener.Factory { PerfCallListener(trafficClass) } else null
}

/**
 * Logs `net cls=… host=… bytes=… conn=new|reused ms=…` when a call ends
 * (`err=` when it fails). `bytes` counts the response body actually read,
 * so a stream the player stopped reading counts what it read, and `ms` runs
 * from the call's start to its end (the whole body for a cover; for a stream,
 * until the player closes it). Never the url's path or query.
 */
private class PerfCallListener(private val trafficClass: String) : EventListener() {
    private var startNs = 0L
    private var freshConnection = false
    private var bodyBytes = 0L

    override fun callStart(call: Call) {
        startNs = System.nanoTime()
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        freshConnection = true
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        YoinPerfMetrics.recordConnection(trafficClass, reused = !freshConnection)
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        bodyBytes += byteCount
    }

    override fun callEnd(call: Call) = finish(call, error = null)

    override fun callFailed(call: Call, ioe: IOException) = finish(call, error = ioe)

    private fun finish(call: Call, error: IOException?) {
        val ms = (System.nanoTime() - startNs) / 1_000_000
        YoinPerfMetrics.recordCall(trafficClass, bodyBytes, error != null)
        YoinPerf.line(
            "net",
            "cls" to trafficClass,
            "host" to call.request().url.host,
            "bytes" to bodyBytes,
            "conn" to if (freshConnection) "new" else "reused",
            "ms" to ms,
            "err" to error?.javaClass?.simpleName,
        )
    }
}

/**
 * Logs `http host=… path=… code=… cache=… ms=… thread=…` per call (or `err=`
 * instead of `code=`/`cache=` when the call throws). Only the host and a
 * templated path are logged: never the query (Subsonic's u/t/s auth lives
 * there), headers or bodies. `ms` runs to the response headers, not the body.
 */
internal object YoinPerfHttpInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        val host = url.host
        val path = templatePath(url.pathSegments)
        // OkHttp names async threads after the redacted URL; keep any query out regardless.
        val thread = Thread.currentThread().name.substringBefore('?')
        val start = System.nanoTime()
        Trace.beginSection("http")
        try {
            val response = chain.proceed(chain.request())
            YoinPerf.line(
                "http",
                "host" to host,
                "path" to path,
                "code" to response.code,
                "cache" to cacheOf(response),
                "ms" to (System.nanoTime() - start) / 1_000_000,
                "thread" to thread
            )
            return response
        } catch (e: Exception) {
            YoinPerf.line(
                "http",
                "host" to host,
                "path" to path,
                "ms" to (System.nanoTime() - start) / 1_000_000,
                "thread" to thread,
                "err" to e.javaClass.simpleName
            )
            throw e
        } finally {
            Trace.endSection()
        }
    }

    private fun cacheOf(response: Response): String = when {
        response.networkResponse == null && response.cacheResponse != null -> "hit"
        response.networkResponse != null && response.cacheResponse != null -> "cond"
        response.networkResponse != null -> "net"
        else -> "none"
    }
}

/** `/v1/albums/4aawyAB9vmqN3uQ7FjRGTy` → `/v1/albums/{id}`; endpoint names such as `getAlbumList2.view` stay. */
internal fun templatePath(segments: List<String>): String {
    val parts = segments.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return "/"
    return parts.joinToString(separator = "/", prefix = "/") { segment ->
        if (looksLikeId(segment)) "{id}" else segment
    }
}

internal fun looksLikeId(segment: String): Boolean = when {
    segment.all(Char::isDigit) -> true
    // Apple Music library / playlist ids: l.xxx, i.xxx, p.xxx, pl.xxx.
    appleMusicId.matches(segment) -> true
    // Subsonic REST endpoints: camelCase word (4+ letters), optional version digit, extension.
    endpointName.matches(segment) -> false
    // Spotify base62 ids, image hashes, UUIDs: long, letters mixed with digits.
    segment.length >= 8 && segment.any(Char::isDigit) && segment.any(Char::isLetter) -> true
    // A long mixed-case token without digits (rare base62 id); hyphenated words stay.
    segment.length >= 16 && '-' !in segment && segment.any(Char::isUpperCase) && segment.any(Char::isLowerCase) -> true
    else -> false
}

private val endpointName = Regex("^[a-z][A-Za-z]{3,}\\d*\\.[a-z]{2,5}$")
private val appleMusicId = Regex("^[a-z]{1,3}\\.[A-Za-z0-9._-]+$")
