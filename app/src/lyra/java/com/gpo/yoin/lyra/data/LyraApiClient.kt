package com.gpo.yoin.lyra.data

import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.remote.SubsonicAuth
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Active Lyra server connection credentials (Subsonic v1Auth + optional admin Bearer token). */
data class LyraServerAuth(
    val serverUrl: String,
    val username: String,
    val password: String,
    val adminToken: String? = null,
) {
    val subsonicCredentials: ServerCredentials
        get() = ServerCredentials(serverUrl = serverUrl, username = username, password = password)
}

class LyraApiException(
    val code: Int,
    message: String,
    val apiCode: String? = null,
) : IOException(message)

/**
 * Native client for Lyra's `/api/v1` contract (`Lyra/go/internal/api/`).
 *
 * Authenticates every `v1Auth` route automatically using the active Subsonic account's `u/t/s`
 * query parameters (`SubsonicAuth.stableToken`, zero second pairing). When an optional
 * `LYRA_TOKEN` admin bearer is configured, sends `Authorization: Bearer <token>` as well so
 * admin-gated routes (Inbox uploads, Listen Later, Share Pass pulls) work seamlessly.
 */
class LyraApiClient(
    private val http: OkHttpClient = defaultHttpClient(),
    private val sseHttp: OkHttpClient = defaultSseHttpClient(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    suspend fun capabilities(auth: LyraServerAuth): LyraCapabilitiesResponse = withContext(Dispatchers.IO) {
        val url = buildUrl(auth, listOf("api", "v1", "capabilities"), includeSubsonicAuth = true)
        executeJson(requestBuilder(url, auth).get().build())
    }

    suspend fun listSources(auth: LyraServerAuth): List<LyraSourceInfo> = withContext(Dispatchers.IO) {
        val url = buildUrl(auth, listOf("api", "v1", "sources"), includeSubsonicAuth = true)
        executeJson<LyraSourcesResponse>(requestBuilder(url, auth).get().build()).sources
    }

    suspend fun searchSource(
        auth: LyraServerAuth,
        sourceId: String,
        query: String,
        type: String = "song",
        limit: Int = 30,
    ): LyraSourceSearchResponse = withContext(Dispatchers.IO) {
        val url = buildUrl(
            auth = auth,
            segments = listOf("api", "v1", "sources", sourceId, "search"),
            includeSubsonicAuth = true,
            queryParams = mapOf(
                "q" to query,
                "type" to type,
                "limit" to limit.coerceIn(1, 50).toString(),
            ),
        )
        executeJson(requestBuilder(url, auth).get().build())
    }

    suspend fun getSourceAlbum(
        auth: LyraServerAuth,
        sourceId: String,
        albumMid: String,
    ): LyraSourceAlbumResponse = withContext(Dispatchers.IO) {
        val url = buildUrl(
            auth = auth,
            segments = listOf("api", "v1", "sources", sourceId, "albums", albumMid),
            includeSubsonicAuth = true,
        )
        executeJson(requestBuilder(url, auth).get().build())
    }

    suspend fun enqueueSourceDownload(
        auth: LyraServerAuth,
        sourceId: String,
        request: LyraDownloadRequest,
    ): LyraDownloadResponse = withContext(Dispatchers.IO) {
        val url = buildUrl(
            auth = auth,
            segments = listOf("api", "v1", "sources", sourceId, "download"),
            includeSubsonicAuth = true,
        )
        val body = json.encodeToString(LyraDownloadRequest.serializer(), request).toRequestBody(JSON_MEDIA_TYPE)
        executeJson(requestBuilder(url, auth).post(body).build())
    }

    suspend fun listJobs(
        auth: LyraServerAuth,
        status: String? = null,
        kind: String? = null,
        limit: Int = 50,
    ): List<LyraJob> = withContext(Dispatchers.IO) {
        val params = buildMap {
            if (!status.isNullOrBlank()) put("status", status)
            if (!kind.isNullOrBlank()) put("kind", kind)
            put("limit", limit.toString())
        }
        val url = buildUrl(auth, listOf("api", "v1", "jobs"), includeSubsonicAuth = true, queryParams = params)
        executeJson<LyraJobsResponse>(requestBuilder(url, auth).get().build()).jobs
    }

    suspend fun getJob(auth: LyraServerAuth, jobId: Long): LyraJob = withContext(Dispatchers.IO) {
        val url = buildUrl(auth, listOf("api", "v1", "jobs", jobId.toString()), includeSubsonicAuth = true)
        executeJson(requestBuilder(url, auth).get().build())
    }

    suspend fun issueEventsTicket(auth: LyraServerAuth): LyraEventTicketResponse = withContext(Dispatchers.IO) {
        val url = buildUrl(auth, listOf("api", "v1", "events", "ticket"), includeSubsonicAuth = true)
        executeJson(requestBuilder(url, auth).post("{}".toRequestBody(JSON_MEDIA_TYPE)).build())
    }

    /**
     * Opens a long-lived SSE stream to `/api/v1/events` using a short-lived ticket and optional
     * `Last-Event-ID` resume cursor. Returns when the stream closes or the coroutine is cancelled.
     */
    suspend fun streamEvents(
        auth: LyraServerAuth,
        ticket: String,
        topics: String = "job,library,inbox,share",
        lastEventId: Long? = null,
        onEvent: suspend (LyraEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val params = buildMap {
            put("ticket", ticket)
            if (topics.isNotBlank()) put("topics", topics)
            if (lastEventId != null && lastEventId >= 0) put("after", lastEventId.toString())
        }
        val url = buildUrl(auth, listOf("api", "v1", "events"), includeSubsonicAuth = false, queryParams = params)
        val req = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .apply { if (lastEventId != null && lastEventId >= 0) header("Last-Event-ID", lastEventId.toString()) }
            .get()
            .build()
        sseHttp.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                throw parseApiError(response.code, response.body.string())
            }
            val reader = BufferedReader(InputStreamReader(response.body.byteStream(), Charsets.UTF_8))
            val dataLines = StringBuilder()
            while (currentCoroutineContext().isActive) {
                val line = reader.readLine() ?: break
                when {
                    line.isEmpty() -> {
                        if (dataLines.isNotEmpty()) {
                            val raw = dataLines.toString().trim()
                            dataLines.clear()
                            runCatching { json.decodeFromString(LyraEvent.serializer(), raw) }
                                .getOrNull()
                                ?.let { onEvent(it) }
                        }
                    }
                    line.startsWith("data:") -> {
                        if (dataLines.isNotEmpty()) dataLines.append('\n')
                        dataLines.append(line.removePrefix("data:").trimStart())
                    }
                }
            }
        }
    }

    // ── Admin-authenticated Inbox, Listen Later & Share Passes ───────────

    suspend fun listInbox(auth: LyraServerAuth, all: Boolean = false): List<LyraInboxItem> = withContext(Dispatchers.IO) {
        val params = if (all) mapOf("all" to "1") else emptyMap()
        val url = buildUrl(auth, listOf("api", "v1", "inbox"), includeSubsonicAuth = false, queryParams = params)
        executeJson<LyraInboxListResponse>(requestBuilder(url, auth).get().build()).items
    }

    suspend fun addInboxUrl(auth: LyraServerAuth, rawUrl: String): LyraInboxItem = withContext(Dispatchers.IO) {
        val url = buildUrl(auth, listOf("api", "v1", "inbox", "url"), includeSubsonicAuth = false)
        val payload = buildJsonObject { put("url", rawUrl) }
        executeJson(requestBuilder(url, auth).post(payload.toString().toRequestBody(JSON_MEDIA_TYPE)).build())
    }

    suspend fun uploadInboxFiles(
        auth: LyraServerAuth,
        files: List<Pair<String, ByteArray>>,
    ): Unit = withContext(Dispatchers.IO) {
        val url = buildUrl(auth, listOf("api", "v1", "inbox", "files"), includeSubsonicAuth = false)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .apply {
                files.forEach { (filename, bytes) ->
                    addFormDataPart("file", filename, bytes.toRequestBody(OCTET_STREAM))
                }
            }
            .build()
        executeUnit(requestBuilder(url, auth).post(body).build())
    }

    suspend fun promoteInboxItem(auth: LyraServerAuth, id: String): Unit = withContext(Dispatchers.IO) {
        val url = buildUrl(auth, listOf("api", "v1", "inbox", id, "promote"), includeSubsonicAuth = false)
        executeUnit(requestBuilder(url, auth).post("{}".toRequestBody(JSON_MEDIA_TYPE)).build())
    }

    suspend fun discardInboxItem(auth: LyraServerAuth, id: String): Unit = withContext(Dispatchers.IO) {
        val url = buildUrl(auth, listOf("api", "v1", "inbox", id), includeSubsonicAuth = false)
        executeUnit(requestBuilder(url, auth).delete().build())
    }

    suspend fun listLater(auth: LyraServerAuth): List<LyraListenLaterItem> = withContext(Dispatchers.IO) {
        val url = buildUrl(auth, listOf("api", "v1", "later"), includeSubsonicAuth = false)
        executeJson<LyraListenLaterListResponse>(requestBuilder(url, auth).get().build()).items
    }

    suspend fun listShares(auth: LyraServerAuth): List<LyraShareView> = withContext(Dispatchers.IO) {
        val url = buildUrl(auth, listOf("api", "v1", "shares"), includeSubsonicAuth = false)
        executeJson<LyraSharesListResponse>(requestBuilder(url, auth).get().build()).shares
    }

    suspend fun revokeShare(auth: LyraServerAuth, token: String): Unit = withContext(Dispatchers.IO) {
        val url = buildUrl(auth, listOf("api", "v1", "shares", token), includeSubsonicAuth = false)
        executeUnit(requestBuilder(url, auth).delete().build())
    }

    suspend fun listPulls(auth: LyraServerAuth): List<LyraIncomingPull> = withContext(Dispatchers.IO) {
        val url = buildUrl(auth, listOf("api", "v1", "shares", "pulls"), includeSubsonicAuth = false)
        executeJson<LyraIncomingPullsResponse>(requestBuilder(url, auth).get().build()).pulls
    }

    suspend fun enqueuePull(
        auth: LyraServerAuth,
        manifestUrl: String,
        target: String = "library",
    ): LyraIncomingPull = withContext(Dispatchers.IO) {
        val url = buildUrl(auth, listOf("api", "v1", "shares", "pull"), includeSubsonicAuth = false)
        val payload = buildJsonObject {
            put("manifest_url", manifestUrl)
            put("target", target)
        }
        executeJson(requestBuilder(url, auth).post(payload.toString().toRequestBody(JSON_MEDIA_TYPE)).build())
    }

    // ── Internals ────────────────────────────────────────────────────────

    private inline fun <reified T> executeJson(request: Request): T {
        http.newCall(request).execute().use { response ->
            val raw = response.body.string()
            if (!response.isSuccessful) {
                throw parseApiError(response.code, raw)
            }
            return json.decodeFromString(raw)
        }
    }

    private fun executeUnit(request: Request) {
        http.newCall(request).execute().use { response ->
            val raw = response.body.string()
            if (!response.isSuccessful) {
                throw parseApiError(response.code, raw)
            }
        }
    }

    private fun parseApiError(code: Int, rawBody: String): LyraApiException {
        val parsed = runCatching { json.decodeFromString(JsonObject.serializer(), rawBody) }.getOrNull()
        val msg = parsed?.get("error")?.toString()?.trim('"')?.takeIf { it.isNotBlank() }
            ?: "HTTP $code"
        val apiCode = parsed?.get("code")?.toString()?.trim('"')?.takeIf { it.isNotBlank() }
        return LyraApiException(code = code, message = msg, apiCode = apiCode)
    }

    private fun requestBuilder(url: HttpUrl, auth: LyraServerAuth): Request.Builder =
        Request.Builder()
            .url(url)
            .apply {
                val bearer = auth.adminToken?.trim().orEmpty()
                if (bearer.isNotEmpty()) header("Authorization", "Bearer $bearer")
            }

    internal fun buildUrl(
        auth: LyraServerAuth,
        segments: List<String>,
        includeSubsonicAuth: Boolean,
        queryParams: Map<String, String> = emptyMap(),
    ): HttpUrl {
        val base = normalizeServerBase(auth.serverUrl).toHttpUrl()
        return base.newBuilder()
            .apply {
                segments.forEach { addPathSegment(it) }
                queryParams.forEach { (k, v) -> addQueryParameter(k, v) }
                if (includeSubsonicAuth && auth.username.isNotBlank()) {
                    val (token, salt) = SubsonicAuth.stableToken(auth.subsonicCredentials)
                    addQueryParameter("u", auth.username)
                    addQueryParameter("t", token)
                    addQueryParameter("s", salt)
                    addQueryParameter("v", SUBSONIC_API_VERSION)
                    addQueryParameter("c", SUBSONIC_CLIENT_NAME)
                    addQueryParameter("f", "json")
                }
            }
            .build()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private const val SUBSONIC_API_VERSION = "1.16.1"
        private const val SUBSONIC_CLIENT_NAME = "LyraAndroid"

        fun normalizeServerBase(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "https://$trimmed"
            }
            return "$withScheme/"
        }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        fun defaultSseHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
