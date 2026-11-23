package com.gpo.yoin.player.sing

import android.net.Uri
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.remote.SubsonicAuth
import com.gpo.yoin.data.remote.SubsonicInterceptor
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Caches `GET /api/v1/capabilities` Sing support per Lyra server URL so [SingProviderSelector]
 * knows non-blockingly whether the connected Subsonic server is an online Lyra instance with
 * Sing enabled, versus a plain Navidrome server or an unreachable Lyra host.
 */
class LyraSingCapabilityCache(
    private val httpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val positiveTtlMs: Long = DEFAULT_POSITIVE_TTL_MS,
    private val negativeTtlMs: Long = DEFAULT_NEGATIVE_TTL_MS,
) {
    private data class Entry(val enabled: Boolean, val checkedAtMs: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    private val probeClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** Non-blocking read of the last probed capability for [credentials]. */
    fun isSingEnabled(credentials: ServerCredentials?): Boolean {
        val key = normalizeKey(credentials?.serverUrl) ?: return false
        val entry = entries[key] ?: return false
        val ttl = if (entry.enabled) positiveTtlMs else negativeTtlMs
        if (clockMs() - entry.checkedAtMs > ttl) return false
        return entry.enabled
    }

    /** Marks [serverUrl] temporarily offline (e.g. after a 5xx or transport failure). */
    fun markOffline(serverUrl: String) {
        val key = normalizeKey(serverUrl) ?: return
        entries[key] = Entry(enabled = false, checkedAtMs = clockMs())
    }

    /**
     * Queries `GET /api/v1/capabilities` with Subsonic `u/t/s` auth and updates the cache.
     * Returns `true` only when the server responds 200 with `sing.enabled == true`.
     */
    suspend fun refresh(credentials: ServerCredentials, force: Boolean = false): Boolean {
        val key = normalizeKey(credentials.serverUrl) ?: return false
        if (!force) {
            val cached = entries[key]
            if (cached != null) {
                val ttl = if (cached.enabled) positiveTtlMs else negativeTtlMs
                if (clockMs() - cached.checkedAtMs <= ttl) return cached.enabled
            }
        }
        val url = LyraSingUrls.buildEndpointUrl(credentials, listOf("api", "v1", "capabilities"))
            ?: return false.also { markOffline(credentials.serverUrl) }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()
        val enabled = try {
            executeProbe(request)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        entries[key] = Entry(enabled = enabled, checkedAtMs = clockMs())
        return enabled
    }

    private suspend fun executeProbe(request: Request): Boolean = coroutineScope {
        val call = probeClient.newCall(request)
        val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            withContext(ioDispatcher) {
                call.execute().use { response ->
                    if (!response.isSuccessful) return@withContext false
                    val body = response.body.string()
                    val parsed = runCatching { SingJson.decodeFromString<CapabilitiesPayload>(body) }.getOrNull()
                    parsed?.sing?.enabled == true
                }
            }
        } finally {
            canceller.cancel()
        }
    }

    private fun normalizeKey(serverUrl: String?): String? =
        serverUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    @Serializable
    private data class CapabilitiesPayload(
        val sing: SingCapability? = null,
    )

    @Serializable
    private data class SingCapability(
        val enabled: Boolean = false,
    )

    companion object {
        const val DEFAULT_POSITIVE_TTL_MS = 60_000L
        const val DEFAULT_NEGATIVE_TTL_MS = 30_000L
        private const val PROBE_TIMEOUT_SECONDS = 5L
    }
}

/**
 * Lyra server Sing provider speaking the locked `/api/v1/sing/{song_id}/...` contract:
 * - `POST /api/v1/sing/{song_id}/prepare[?tier=quality|fast]`
 * - `GET  /api/v1/sing/{song_id}/status?stream=1` (SSE `event: sing`, with JSON poll fallback)
 * - `GET  /api/v1/sing/{song_id}/quad.m4a` (HTTP 206 Range with Subsonic `u/t/s` query auth)
 *
 * When [fallback] (typically [OnDeviceSingStreamProvider]) is supplied and the Lyra server is
 * unreachable, returns 5xx/404, or lacks credentials while [SingRequest.streamUri] is available,
 * this provider degrades transparently to on-device separation.
 */
class LyraSingStreamProvider(
    private val credentialsProvider: () -> ServerCredentials?,
    private val httpClient: OkHttpClient,
    private val fallback: SingStreamProvider? = null,
    private val tier: String? = null,
    private val capabilityCache: LyraSingCapabilityCache? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val maxPollAttempts: Int = DEFAULT_MAX_POLL_ATTEMPTS,
    private val sseReadTimeoutSeconds: Long = DEFAULT_SSE_READ_TIMEOUT_SECONDS,
) : SingStreamProvider {
    private val activeCalls = ConcurrentHashMap<String, CopyOnWriteArrayList<Call>>()
    private val cancelledKeys = ConcurrentHashMap.newKeySet<String>()

    private val sseClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .readTimeout(sseReadTimeoutSeconds, TimeUnit.SECONDS)
            .build()
    }

    override fun availability(): SingAvailability {
        val credentials = credentialsProvider()
        if (credentials == null || credentials.serverUrl.isBlank()) {
            return fallback?.availability() ?: SingAvailability.Unavailable(SingUnavailableReason.NoSource)
        }
        return SingAvailability.Available
    }

    override fun cancel(request: SingRequest) {
        cancelledKeys.add(request.key)
        activeCalls.remove(request.key)?.forEach { it.cancel() }
        fallback?.cancel(request)
    }

    override fun onPlayhead(request: SingRequest, positionMs: Long, playing: Boolean) {
        fallback?.onPlayhead(request, positionMs, playing)
    }

    override fun stream(request: SingRequest): Flow<SingStatus> = channelFlow {
        cancelledKeys.remove(request.key)
        val credentials = credentialsProvider()
        if (credentials == null || credentials.serverUrl.isBlank()) {
            if (canFallback(request)) {
                fallback!!.stream(request).collect { send(it) }
            } else {
                send(SingStatus.Unavailable(SingUnavailableReason.NoSource))
            }
            return@channelFlow
        }

        val songId = request.songId.rawId
        val extraQuery = tier?.takeIf { it.isNotBlank() }?.let { listOf("tier" to it) }.orEmpty()
        val prepareUrl = LyraSingUrls.buildEndpointUrl(
            credentials = credentials,
            pathSegments = listOf("api", "v1", "sing", songId, "prepare"),
            extraQuery = extraQuery,
        )
        if (prepareUrl == null) {
            send(SingStatus.Failed(SingFailure.Remote, "Invalid Lyra server URL"))
            return@channelFlow
        }

        val prepareReq = Request.Builder()
            .url(prepareUrl)
            .header("Accept", "application/json")
            .post(ByteArray(0).toRequestBody(null))
            .build()

        val prepareOutcome = try {
            executeCall(request.key, httpClient, prepareReq) { response ->
                val code = response.code
                val body = response.body.string()
                PrepareHttpResult.Response(code, body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (cancelledKeys.contains(request.key)) return@channelFlow
            PrepareHttpResult.NetworkError(e)
        }

        when (prepareOutcome) {
            is PrepareHttpResult.NetworkError -> {
                capabilityCache?.markOffline(credentials.serverUrl)
                if (canFallback(request)) {
                    fallback!!.stream(request).collect { send(it) }
                } else {
                    send(SingStatus.Failed(SingFailure.Remote, prepareOutcome.error.message))
                }
                return@channelFlow
            }
            is PrepareHttpResult.Response -> {
                val code = prepareOutcome.code
                when {
                    code == 404 || code == 409 -> {
                        if (canFallback(request)) {
                            fallback!!.stream(request).collect { send(it) }
                        } else {
                            send(SingStatus.Unavailable(SingUnavailableReason.NoSource))
                        }
                        return@channelFlow
                    }
                    code !in 200..299 -> {
                        if (code >= 500) capabilityCache?.markOffline(credentials.serverUrl)
                        if (code >= 500 && canFallback(request)) {
                            fallback!!.stream(request).collect { send(it) }
                        } else {
                            send(SingStatus.Failed(SingFailure.Remote, "HTTP $code"))
                        }
                        return@channelFlow
                    }
                }

                val payload = runCatching {
                    SingJson.decodeFromString<StatusPayload>(prepareOutcome.body)
                }.getOrNull()
                if (payload == null) {
                    send(SingStatus.Failed(SingFailure.Remote, "Invalid prepare response"))
                    return@channelFlow
                }

                // If another tier (e.g. fast) is already ready while the requested quality tier is
                // queued, hand it over immediately for zero-wait playback; the server still finishes
                // the quality tier in the background for subsequent plays.
                val available = payload.available
                if (effectiveState(payload) != "ready" &&
                    available != null &&
                    available.status.equals("ready", ignoreCase = true) &&
                    !available.streamUrl.isNullOrBlank()
                ) {
                    val uri = LyraSingUrls.buildQuadUri(
                        credentials = credentials,
                        songId = songId,
                        streamPath = available.streamUrl,
                        tier = available.tier,
                    )
                    if (uri != null) {
                        send(SingStatus.Ready(SingSource.Remote(uri)))
                        return@channelFlow
                    }
                }

                val initial = toSingStatus(credentials, songId, payload)
                send(initial.status)
                if (initial.terminal) return@channelFlow
            }
        }

        // Wait for server completion via SSE `/status?stream=1`, falling back to JSON polling if SSE
        // drops or is not supported by an intermediate proxy.
        val terminalFromSse = streamStatusSse(
            request = request,
            credentials = credentials,
            songId = songId,
            extraQuery = extraQuery,
            emitStatus = { send(it) },
        )
        if (terminalFromSse || cancelledKeys.contains(request.key)) return@channelFlow

        pollStatusJson(
            request = request,
            credentials = credentials,
            songId = songId,
            extraQuery = extraQuery,
            emitStatus = { send(it) },
        )
    }

    private suspend fun streamStatusSse(
        request: SingRequest,
        credentials: ServerCredentials,
        songId: String,
        extraQuery: List<Pair<String, String>>,
        emitStatus: suspend (SingStatus) -> Unit,
    ): Boolean {
        val sseUrl = LyraSingUrls.buildEndpointUrl(
            credentials = credentials,
            pathSegments = listOf("api", "v1", "sing", songId, "status"),
            extraQuery = extraQuery + ("stream" to "1"),
        ) ?: return false

        val sseReq = Request.Builder()
            .url(sseUrl)
            .header("Accept", "text/event-stream")
            .get()
            .build()

        var lastEmitted: SingStatus? = null
        return try {
            executeCall(request.key, sseClient, sseReq) { response ->
                if (!response.isSuccessful) return@executeCall false
                val contentType = response.header("Content-Type").orEmpty()
                if (!contentType.contains("text/event-stream", ignoreCase = true)) {
                    // Proxy or server returned plain JSON instead of SSE; parse it directly.
                    val body = response.body.string()
                    val payload = runCatching { SingJson.decodeFromString<StatusPayload>(body) }.getOrNull()
                        ?: return@executeCall false
                    val mapped = toSingStatus(credentials, songId, payload)
                    emitStatus(mapped.status)
                    return@executeCall mapped.terminal
                }

                val source = response.body.source()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith(":")) continue
                    if (!trimmed.startsWith("data:")) continue
                    val jsonText = trimmed.removePrefix("data:").trim()
                    if (jsonText.isEmpty()) continue
                    val payload = runCatching { SingJson.decodeFromString<StatusPayload>(jsonText) }.getOrNull()
                        ?: continue
                    val mapped = toSingStatus(credentials, songId, payload)
                    if (mapped.status != lastEmitted) {
                        lastEmitted = mapped.status
                        emitStatus(mapped.status)
                    }
                    if (mapped.terminal) return@executeCall true
                }
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            false
        }
    }

    private suspend fun pollStatusJson(
        request: SingRequest,
        credentials: ServerCredentials,
        songId: String,
        extraQuery: List<Pair<String, String>>,
        emitStatus: suspend (SingStatus) -> Unit,
    ) {
        val pollUrl = LyraSingUrls.buildEndpointUrl(
            credentials = credentials,
            pathSegments = listOf("api", "v1", "sing", songId, "status"),
            extraQuery = extraQuery,
        )
        if (pollUrl == null) {
            emitStatus(SingStatus.Failed(SingFailure.Remote, "Invalid status URL"))
            return
        }

        val pollReq = Request.Builder()
            .url(pollUrl)
            .header("Accept", "application/json")
            .get()
            .build()

        var lastEmitted: SingStatus? = null
        repeat(maxPollAttempts) { attempt ->
            currentCoroutineContext().ensureActive()
            if (cancelledKeys.contains(request.key)) return
            val result = try {
                executeCall(request.key, httpClient, pollReq) { response ->
                    if (!response.isSuccessful) {
                        PollStep.HttpError(response.code)
                    } else {
                        val body = response.body.string()
                        val payload = runCatching { SingJson.decodeFromString<StatusPayload>(body) }.getOrNull()
                        if (payload != null) PollStep.Payload(payload) else PollStep.Invalid
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (cancelledKeys.contains(request.key)) return
                capabilityCache?.markOffline(credentials.serverUrl)
                if (canFallback(request)) {
                    fallback!!.stream(request).collect { emitStatus(it) }
                } else {
                    emitStatus(SingStatus.Failed(SingFailure.Remote, e.message))
                }
                return
            }

            when (result) {
                is PollStep.Payload -> {
                    val mapped = toSingStatus(credentials, songId, result.payload)
                    if (mapped.status != lastEmitted) {
                        lastEmitted = mapped.status
                        emitStatus(mapped.status)
                    }
                    if (mapped.terminal) return
                }
                is PollStep.HttpError -> {
                    if (result.code >= 500) capabilityCache?.markOffline(credentials.serverUrl)
                    if (result.code >= 500 && canFallback(request)) {
                        fallback!!.stream(request).collect { emitStatus(it) }
                    } else {
                        emitStatus(SingStatus.Failed(SingFailure.Remote, "HTTP ${result.code}"))
                    }
                    return
                }
                PollStep.Invalid -> Unit
            }

            if (attempt + 1 < maxPollAttempts) {
                delay(pollIntervalMs)
            }
        }

        emitStatus(SingStatus.Failed(SingFailure.Remote, "Timed out waiting for Lyra Sing"))
    }

    private suspend fun <T> executeCall(
        requestKey: String,
        client: OkHttpClient,
        httpRequest: Request,
        block: suspend (Response) -> T,
    ): T = coroutineScope {
        val call = client.newCall(httpRequest)
        val list = activeCalls.getOrPut(requestKey) { CopyOnWriteArrayList() }
        list.add(call)
        val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            withContext(ioDispatcher) {
                call.execute().use { response ->
                    block(response)
                }
            }
        } finally {
            canceller.cancel()
            list.remove(call)
            if (list.isEmpty()) activeCalls.remove(requestKey, list)
        }
    }

    private fun canFallback(request: SingRequest): Boolean =
        fallback != null && !request.streamUri.isNullOrBlank()

    private fun effectiveState(payload: StatusPayload): String =
        (payload.state?.takeIf { it.isNotBlank() } ?: payload.status).lowercase()

    private fun toSingStatus(
        credentials: ServerCredentials,
        songId: String,
        payload: StatusPayload,
    ): MappedStatus {
        return when (effectiveState(payload)) {
            "ready" -> {
                val uri = LyraSingUrls.buildQuadUri(
                    credentials = credentials,
                    songId = songId,
                    streamPath = payload.streamUrl,
                    tier = payload.tier ?: tier,
                )
                if (uri != null) {
                    MappedStatus(SingStatus.Ready(SingSource.Remote(uri)), terminal = true)
                } else {
                    MappedStatus(SingStatus.Failed(SingFailure.Remote, "Invalid quad stream URL"), terminal = true)
                }
            }
            "failed", "error" -> {
                MappedStatus(
                    SingStatus.Failed(SingFailure.Remote, payload.error ?: "Server separation failed"),
                    terminal = true,
                )
            }
            "none" -> {
                MappedStatus(SingStatus.Unavailable(SingUnavailableReason.NoSource), terminal = true)
            }
            else -> {
                val stage = mapStage(payload)
                MappedStatus(SingStatus.Preparing(progress = null, stage = stage), terminal = false)
            }
        }
    }

    private fun mapStage(payload: StatusPayload): SingStatus.Preparing.Stage {
        val stageText = (payload.stage ?: payload.state ?: payload.status).lowercase()
        return when (stageText) {
            "queued", "decoding" -> SingStatus.Preparing.Stage.Reading
            "encoding" -> SingStatus.Preparing.Stage.Saving
            else -> SingStatus.Preparing.Stage.Working
        }
    }

    private data class MappedStatus(val status: SingStatus, val terminal: Boolean)

    private sealed interface PrepareHttpResult {
        data class Response(val code: Int, val body: String) : PrepareHttpResult
        data class NetworkError(val error: IOException) : PrepareHttpResult
    }

    private sealed interface PollStep {
        data class Payload(val payload: StatusPayload) : PollStep
        data class HttpError(val code: Int) : PollStep
        data object Invalid : PollStep
    }

    @Serializable
    private data class StatusPayload(
        val status: String,
        val state: String? = null,
        val stage: String? = null,
        val tier: String? = null,
        @SerialName("stream_url")
        val streamUrl: String? = null,
        @SerialName("eta_s")
        val etaS: Int? = null,
        val error: String? = null,
        val available: AvailableTier? = null,
    )

    @Serializable
    private data class AvailableTier(
        val status: String,
        val tier: String? = null,
        @SerialName("stream_url")
        val streamUrl: String? = null,
    )

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 1_000L
        const val DEFAULT_MAX_POLL_ATTEMPTS = 90
        const val DEFAULT_SSE_READ_TIMEOUT_SECONDS = 45L
    }
}

/** URL builder for Lyra `/api/v1/...` routes using Yoin's standard Subsonic `u/t/s` query auth. */
internal object LyraSingUrls {
    fun buildEndpointUrl(
        credentials: ServerCredentials,
        pathSegments: List<String>,
        extraQuery: List<Pair<String, String>> = emptyList(),
    ): HttpUrl? {
        val base = credentials.serverUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
        val (token, salt) = SubsonicAuth.stableToken(credentials)
        return base.newBuilder()
            .apply {
                pathSegments.forEach(::addPathSegment)
                addSubsonicAuth(credentials.username, token, salt)
                extraQuery.forEach { (key, value) -> addQueryParameter(key, value) }
            }
            .build()
    }

    fun buildQuadUri(
        credentials: ServerCredentials,
        songId: String,
        streamPath: String? = null,
        tier: String? = null,
    ): Uri? {
        val base = credentials.serverUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
        val (token, salt) = SubsonicAuth.stableToken(credentials)
        val url = if (!streamPath.isNullOrBlank()) {
            val normalizedStream = if (streamPath.startsWith("/")) streamPath else "/$streamPath"
            val basePath = base.encodedPath.trimEnd('/')
            val combinedPath = when {
                basePath.isEmpty() -> normalizedStream
                normalizedStream.startsWith("$basePath/") -> normalizedStream
                else -> basePath + normalizedStream
            }
            val parsedStream = base.newBuilder().encodedPath(combinedPath).build()
            parsedStream.newBuilder()
                .apply {
                    addSubsonicAuth(credentials.username, token, salt)
                    if (!tier.isNullOrBlank() && parsedStream.queryParameter("tier") == null) {
                        addQueryParameter("tier", tier)
                    }
                }
                .build()
        } else {
            val extra = tier?.takeIf { it.isNotBlank() }?.let { listOf("tier" to it) }.orEmpty()
            buildEndpointUrl(
                credentials = credentials,
                pathSegments = listOf("api", "v1", "sing", songId, "quad.m4a"),
                extraQuery = extra,
            ) ?: return null
        }
        return Uri.parse(url.toString())
    }

    private fun HttpUrl.Builder.addSubsonicAuth(username: String, token: String, salt: String) {
        addQueryParameter("u", username)
        addQueryParameter("t", token)
        addQueryParameter("s", salt)
        addQueryParameter("v", SubsonicInterceptor.API_VERSION)
        addQueryParameter("c", SubsonicInterceptor.CLIENT_NAME)
        addQueryParameter("f", SubsonicInterceptor.RESPONSE_FORMAT)
    }
}

private val SingJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}
