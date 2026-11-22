package com.gpo.yoin.lyra.data

import com.gpo.yoin.data.remote.SubsonicAuth
import com.gpo.yoin.data.sync.GoogleAccountInfo
import com.gpo.yoin.data.sync.RemoteFile
import com.gpo.yoin.data.sync.SyncFileProps
import com.gpo.yoin.data.sync.SyncTransport
import com.gpo.yoin.data.sync.SyncTransportException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Native Lyra backend for Yoin's [SyncTransport] (`CloudSyncManager` + `SyncEngine`).
 *
 * Replaces Google Drive `appDataFolder` with Lyra's `/api/v1/sync` endpoints, authenticated
 * via the active Subsonic/Lyra profile's `u/t/s` (`v1Auth`) — preserving Yoin's exact per-platform
 * and per-account scope isolation (`s-<sha256>[:24]`) and gzip envelope wire format so Lyra's
 * `go/internal/yoinsync` projects Memories, ratings, and notes immediately on write.
 */
class LyraSyncTransport(
    private val authProvider: suspend () -> LyraServerAuth?,
    private val http: OkHttpClient = defaultHttpClient(),
) : SyncTransport {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun about(): GoogleAccountInfo = withContext(Dispatchers.IO) {
        val auth = requireAuth()
        val url = syncUrl(auth, listOf("about"))
        send(requestBuilder(url, auth).get().build()) { response ->
            val dto = decode<LyraSyncAboutResponse>(response)
            val id = dto.permissionId.takeIf { it.isNotBlank() }
                ?: throw SyncTransportException.Server(200, "Lyra sync/about returned no permissionId")
            GoogleAccountInfo(
                permissionId = id,
                email = dto.email?.takeIf { it.isNotBlank() },
                displayName = dto.displayName?.takeIf { it.isNotBlank() } ?: "Lyra",
            )
        }
    }

    override suspend fun list(): List<RemoteFile> = withContext(Dispatchers.IO) {
        val auth = requireAuth()
        val url = syncUrl(auth, listOf("files"))
        send(requestBuilder(url, auth).get().build()) { response ->
            decode<LyraSyncFilesResponse>(response).files.map { it.toRemoteFile() }
        }
    }

    override suspend fun download(fileId: String): ByteArray? = withContext(Dispatchers.IO) {
        val auth = requireAuth()
        val url = syncUrl(auth, listOf("files", fileId))
        send(requestBuilder(url, auth).get().build(), accept = setOf(404)) { response ->
            if (response.code == 404) null else response.body.bytes()
        }
    }

    override suspend fun generateIds(count: Int): List<String> = withContext(Dispatchers.IO) {
        require(count in 1..MAX_GENERATED_IDS) { "count must be in 1..$MAX_GENERATED_IDS" }
        val auth = requireAuth()
        val url = syncUrl(
            auth = auth,
            segments = listOf("files", "generate-ids"),
            queryParams = mapOf("count" to count.toString()),
        )
        send(requestBuilder(url, auth).post(EMPTY_BODY).build()) { response ->
            val ids = decode<LyraSyncGeneratedIdsResponse>(response).ids.filter { it.isNotBlank() }
            if (ids.size < count) {
                throw SyncTransportException.Server(200, "Lyra returned ${ids.size} of $count requested file ids")
            }
            ids.take(count)
        }
    }

    override suspend fun create(
        fileId: String,
        name: String,
        appProperties: Map<String, String>,
        mimeType: String,
        bytes: ByteArray,
    ): RemoteFile = withContext(Dispatchers.IO) {
        val auth = requireAuth()
        val params = buildMap {
            put("name", name)
            PROP_QUERY_KEYS.forEach { key ->
                appProperties[key]?.takeIf { it.isNotBlank() }?.let { put(key, it) }
            }
        }
        val url = syncUrl(auth, listOf("files", fileId), queryParams = params)
        val body = bytes.toRequestBody(mimeType.toMediaType())
        send(requestBuilder(url, auth).post(body).build(), accept = setOf(409)) { response ->
            if (response.code == 409) {
                throw SyncTransportException.AlreadyExists("Lyra sync file $fileId already exists")
            }
            decode<LyraSyncFileDto>(response).toRemoteFile()
        }
    }

    override suspend fun update(fileId: String, bytes: ByteArray): RemoteFile? = withContext(Dispatchers.IO) {
        val auth = requireAuth()
        val url = syncUrl(auth, listOf("files", fileId))
        val body = bytes.toRequestBody(sniffMediaType(bytes))
        send(requestBuilder(url, auth).put(body).build(), accept = setOf(404)) { response ->
            if (response.code == 404) null else decode<LyraSyncFileDto>(response).toRemoteFile()
        }
    }

    override suspend fun delete(fileId: String): Boolean = withContext(Dispatchers.IO) {
        val auth = requireAuth()
        val url = syncUrl(auth, listOf("files", fileId))
        send(requestBuilder(url, auth).delete().build(), accept = setOf(404)) { true }
    }

    // ── Internals ────────────────────────────────────────────────────────

    private suspend fun requireAuth(): LyraServerAuth =
        authProvider() ?: throw SyncTransportException.Unauthorized("No Lyra server configured")

    private fun <T> send(
        request: Request,
        accept: Set<Int> = emptySet(),
        handle: (Response) -> T,
    ): T {
        val response = try {
            http.newCall(request).execute()
        } catch (error: IOException) {
            throw SyncTransportException.Network("Could not reach Lyra sync server", error)
        }
        return response.use { res ->
            if (res.isSuccessful || res.code in accept) {
                handle(res)
            } else {
                throw mapFailure(res)
            }
        }
    }

    private fun mapFailure(response: Response): SyncTransportException {
        val code = response.code
        val body = runCatching { response.body.string() }.getOrDefault("").take(300)
        return when (code) {
            401, 403 -> SyncTransportException.Unauthorized("Lyra sync unauthorized ($code): $body")
            429 -> {
                val retrySec = response.header("Retry-After")?.toLongOrNull()
                SyncTransportException.RateLimited(
                    message = "Lyra sync rate-limited",
                    retryAfterMs = retrySec?.times(1000L),
                )
            }
            507 -> SyncTransportException.StorageFull("Lyra server storage full")
            else -> SyncTransportException.Server(code, "Lyra sync HTTP $code: $body")
        }
    }

    private inline fun <reified T> decode(response: Response): T {
        val raw = response.body.string()
        return try {
            json.decodeFromString(raw)
        } catch (error: Exception) {
            throw SyncTransportException.Server(response.code, "Malformed Lyra sync JSON: ${error.message}")
        }
    }

    private fun requestBuilder(url: HttpUrl, auth: LyraServerAuth): Request.Builder =
        Request.Builder()
            .url(url)
            .apply {
                val bearer = auth.adminToken?.trim().orEmpty()
                if (bearer.isNotEmpty()) header("Authorization", "Bearer $bearer")
            }

    private fun syncUrl(
        auth: LyraServerAuth,
        segments: List<String>,
        queryParams: Map<String, String> = emptyMap(),
    ): HttpUrl {
        val base = LyraApiClient.normalizeServerBase(auth.serverUrl).toHttpUrl()
        val (token, salt) = SubsonicAuth.stableToken(auth.subsonicCredentials)
        return base.newBuilder()
            .addPathSegment("api")
            .addPathSegment("v1")
            .addPathSegment("sync")
            .apply {
                segments.forEach { addPathSegment(it) }
                queryParams.forEach { (k, v) -> addQueryParameter(k, v) }
                if (auth.username.isNotBlank()) {
                    addQueryParameter("u", auth.username)
                    addQueryParameter("t", token)
                    addQueryParameter("s", salt)
                    addQueryParameter("v", "1.16.1")
                    addQueryParameter("c", "LyraAndroid")
                    addQueryParameter("f", "json")
                }
            }
            .build()
    }

    private fun LyraSyncFileDto.toRemoteFile(): RemoteFile = RemoteFile(
        id = id,
        name = name,
        version = version,
        modifiedTime = modifiedTime,
        size = size,
        appProperties = appProperties,
    )

    private fun sniffMediaType(bytes: ByteArray) =
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIP_MEDIA_TYPE
        } else {
            JSON_MEDIA_TYPE
        }

    companion object {
        private const val MAX_GENERATED_IDS = 20
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val GZIP_MEDIA_TYPE = "application/gzip".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        private val PROP_QUERY_KEYS = listOf(
            SyncFileProps.KIND,
            SyncFileProps.DEVICE_ID,
            SyncFileProps.SHARD,
            SyncFileProps.SCHEMA,
            SyncFileProps.EPOCH,
        )

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}
