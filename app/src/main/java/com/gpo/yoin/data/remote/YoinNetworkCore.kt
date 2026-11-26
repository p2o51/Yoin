package com.gpo.yoin.data.remote

import com.gpo.yoin.perf.YoinPerfHttp
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/**
 * The process's HTTP transport: one [ConnectionPool] shared by every client
 * derived from here, and one request queue ([Dispatcher]) per traffic class.
 *
 * Why one pool: playback, cover art and the Subsonic API usually reach the
 * same server. Sharing idle connections means a stream or a cover request can
 * reuse the TCP + TLS connection the last API call opened instead of
 * handshaking again (HTTP/2 servers multiplex them on one connection).
 *
 * Why a queue per class: OkHttp's Dispatcher limits concurrent *enqueued*
 * calls per host, and Media3's OkHttpDataSource, Coil's fetcher and Retrofit's
 * suspend calls all enqueue. On one shared queue a Library grid's 20–48
 * covers held the stream's slot, so tap-to-audio waited for the covers
 * (review: 2 ms → 1.9 s). The queue is not part of OkHttp's connection
 * sharing, so connection reuse is the same with separate queues. A call only
 * holds its slot until the response headers arrive; streaming the body does
 * not.
 *
 * - [mediaClient]: ExoPlayer streams, the Sing prebuffer (same data source),
 *   offline downloads. OkHttp's default 10 s timeouts, as `OkHttpClient()` had.
 * - [newApiBuilder]: Subsonic, Spotify, Apple Music, lyrics, MusicBrainz,
 *   NeoDB, Gemini. Each caller keeps its own timeouts, cache and interceptors.
 * - [imageClient]: Coil. 16 per host so a cover burst isn't serialized 5 at a
 *   time; it only competes with other covers.
 *
 * No DNS cache: Android's resolver already caches per network, and an app
 * level cache kept split-DNS / VPN addresses alive across network changes.
 */
object YoinNetworkCore {
    private const val MAX_IDLE_CONNECTIONS = 16
    private const val KEEP_ALIVE_MINUTES = 5L
    internal const val IMAGE_MAX_REQUESTS_PER_HOST = 16

    /**
     * Playback + Sing prebuffer + 2 downloads + a traits probe, which had two
     * separate clients (5 per host each) before.
     */
    internal const val MEDIA_MAX_REQUESTS_PER_HOST = 10

    val connectionPool: ConnectionPool = ConnectionPool(
        maxIdleConnections = MAX_IDLE_CONNECTIONS,
        keepAliveDuration = KEEP_ALIVE_MINUTES,
        timeUnit = TimeUnit.MINUTES,
    )

    internal val mediaDispatcher: Dispatcher by lazy {
        Dispatcher().apply { maxRequestsPerHost = MEDIA_MAX_REQUESTS_PER_HOST }
    }

    /** OkHttp's defaults (64, 5 per host): what each provider client had on its own. */
    internal val apiDispatcher: Dispatcher by lazy { Dispatcher() }

    internal val imageDispatcher: Dispatcher by lazy {
        Dispatcher().apply { maxRequestsPerHost = IMAGE_MAX_REQUESTS_PER_HOST }
    }

    private fun builder(dispatcher: Dispatcher, trafficClass: String): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .connectionPool(connectionPool)
            .dispatcher(dispatcher)
            // Debug-only per-call bytes / connection reuse lines; null (nothing added) in release.
            .apply { YoinPerfHttp.eventListenerFactory(trafficClass)?.let(::eventListenerFactory) }

    private val apiRoot: OkHttpClient by lazy { builder(apiDispatcher, "api").build() }

    /** ExoPlayer streaming, the Sing prebuffer and offline downloads. No HTTP cache. */
    val mediaClient: OkHttpClient by lazy { builder(mediaDispatcher, "media").build() }

    /** Coil's fetcher. No OkHttp HTTP cache: Coil's disk cache owns the bytes. */
    val imageClient: OkHttpClient by lazy { builder(imageDispatcher, "image").build() }

    /** The API root itself, for callers that derive their own builder (Apple Music's transport). */
    val apiClient: OkHttpClient get() = apiRoot

    /** A builder for an API client: shared pool and API queue, OkHttp's default timeouts. */
    fun newApiBuilder(): OkHttpClient.Builder = apiRoot.newBuilder()
}
