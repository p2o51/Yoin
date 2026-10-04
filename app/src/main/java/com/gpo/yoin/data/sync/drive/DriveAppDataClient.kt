package com.gpo.yoin.data.sync.drive

import com.gpo.yoin.data.sync.GoogleAccountInfo
import com.gpo.yoin.data.sync.RemoteFile
import com.gpo.yoin.data.sync.SyncTokenProvider
import com.gpo.yoin.data.sync.SyncTransport
import com.gpo.yoin.data.sync.SyncTransportException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Drive v3 REST client scoped to the hidden appDataFolder (scope drive.appdata).
 *
 * Every request carries a Bearer token from [tokens]; a 401 invalidates that
 * token and retries exactly once with a fresh one. HTTP failures are mapped to
 * [SyncTransportException] subtypes so the sync manager can pick a user-visible
 * state without parsing Drive errors itself.
 */
class DriveAppDataClient(
    private val http: OkHttpClient,
    private val tokens: SyncTokenProvider,
    apiBase: String = DEFAULT_API_BASE,
    uploadBase: String = DEFAULT_UPLOAD_BASE,
) : SyncTransport {
    private val apiBase: HttpUrl = apiBase.asBaseUrl()
    private val uploadBase: HttpUrl = uploadBase.asBaseUrl()

    override suspend fun list(): List<RemoteFile> {
        val files = mutableListOf<RemoteFile>()
        val seenTokens = mutableSetOf<String>()
        var pageToken: String? = null
        do {
            val url = apiBase.newBuilder()
                .addPathSegment("files")
                .addQueryParameter("spaces", APP_DATA_FOLDER)
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("fields", "nextPageToken,files($FILE_FIELDS)")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val page = send(Request.Builder().url(url).get()) { decode<DriveFileList>(it) }
            page.files.mapTo(files) { it.toRemoteFile() }
            pageToken = page.nextPageToken?.takeIf { it.isNotEmpty() }
            if (pageToken != null && !seenTokens.add(pageToken)) {
                throw SyncTransportException.Server(200, "Drive repeated a page token")
            }
        } while (pageToken != null)
        return files
    }

    override suspend fun download(fileId: String): ByteArray? {
        val url = apiBase.newBuilder()
            .addPathSegment("files")
            .addPathSegment(fileId)
            .addQueryParameter("alt", "media")
            .build()
        return send(Request.Builder().url(url).get(), accept = setOf(404)) { response ->
            if (response.code == 404) null else response.body.bytes()
        }
    }

    override suspend fun create(
        fileId: String,
        name: String,
        appProperties: Map<String, String>,
        mimeType: String,
        bytes: ByteArray,
    ): RemoteFile {
        val url = uploadBase.newBuilder()
            .addPathSegment("files")
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", FILE_FIELDS)
            .build()
        val metadata = json.encodeToString(
            DriveCreateMetadata.serializer(),
            DriveCreateMetadata(
                id = fileId,
                name = name,
                parents = listOf(APP_DATA_FOLDER),
                mimeType = mimeType,
                appProperties = appProperties,
            ),
        )
        // Part Content-Types come from the bodies; OkHttp 5 rejects Content-Type in part headers.
        val body = MultipartBody.Builder()
            .setType(MULTIPART_RELATED)
            .addPart(metadata.toRequestBody(JSON_UTF8))
            .addPart(bytes.toRequestBody(mimeType.toMediaType()))
            .build()
        return send(Request.Builder().url(url).post(body), accept = setOf(409)) { response ->
            if (response.code == 409) {
                throw SyncTransportException.AlreadyExists("Drive file $fileId already exists")
            }
            decode<DriveFile>(response).toRemoteFile()
        }
    }

    override suspend fun update(fileId: String, bytes: ByteArray): RemoteFile? {
        val url = uploadBase.newBuilder()
            .addPathSegment("files")
            .addPathSegment(fileId)
            .addQueryParameter("uploadType", "media")
            .addQueryParameter("fields", FILE_FIELDS)
            .build()
        val body = bytes.toRequestBody(sniffMediaType(bytes))
        return send(Request.Builder().url(url).patch(body), accept = setOf(404)) { response ->
            if (response.code == 404) null else decode<DriveFile>(response).toRemoteFile()
        }
    }

    override suspend fun delete(fileId: String): Boolean {
        val url = apiBase.newBuilder()
            .addPathSegment("files")
            .addPathSegment(fileId)
            .build()
        return send(Request.Builder().url(url).delete(), accept = setOf(404)) { true }
    }

    override suspend fun generateIds(count: Int): List<String> {
        require(count in 1..MAX_GENERATED_IDS) { "count must be in 1..$MAX_GENERATED_IDS" }
        val url = apiBase.newBuilder()
            .addPathSegments("files/generateIds")
            .addQueryParameter("space", APP_DATA_FOLDER)
            .addQueryParameter("count", count.toString())
            .build()
        val ids = send(Request.Builder().url(url).get()) { decode<DriveGeneratedIds>(it).ids }
            .filter { it.isNotBlank() }
        // Callers index into the result (generateIds(1).first()); a short list is a malformed response.
        if (ids.size < count) {
            throw SyncTransportException.Server(200, "Drive returned ${ids.size} of $count requested file ids")
        }
        return ids.take(count)
    }

    override suspend fun about(): GoogleAccountInfo {
        val url = apiBase.newBuilder()
            .addPathSegment("about")
            .addQueryParameter("fields", "user(permissionId,emailAddress,displayName)")
            .build()
        val user = send(Request.Builder().url(url).get()) { decode<DriveAbout>(it).user }
        val permissionId = user?.permissionId?.takeIf { it.isNotBlank() }
            ?: throw SyncTransportException.Server(200, "Drive returned no permissionId")
        return GoogleAccountInfo(
            permissionId = permissionId,
            email = user.emailAddress?.takeIf { it.isNotBlank() },
            displayName = user.displayName?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Runs [request] with a Bearer token. 2xx responses and statuses in [accept]
     * go to [read] (called on OkHttp's thread with the body still open); a 401 is
     * retried once with a fresh token; everything else becomes a [SyncTransportException].
     */
    private suspend fun <T> send(
        request: Request.Builder,
        accept: Set<Int> = emptySet(),
        read: (Response) -> T,
    ): T {
        val firstToken = freshToken()
        attempt(request, firstToken, accept, read)?.let { return it.value }
        tokens.invalidate(firstToken)
        val secondToken = freshToken()
        attempt(request, secondToken, accept, read)?.let { return it.value }
        throw SyncTransportException.Unauthorized("Drive rejected a refreshed access token")
    }

    private suspend fun freshToken(): String = try {
        tokens.token()
    } catch (error: IOException) {
        throw SyncTransportException.Network(error.message ?: "Couldn't get a Google access token", error)
    }

    /** null = 401 (caller decides whether to retry). */
    private suspend fun <T> attempt(
        request: Request.Builder,
        token: String,
        accept: Set<Int>,
        read: (Response) -> T,
    ): Outcome<T>? {
        val authorized = request.header("Authorization", "Bearer $token").build()
        return try {
            http.executeReading(authorized) { response ->
                when {
                    response.code == 401 -> null
                    response.isSuccessful || response.code in accept -> Outcome(read(response))
                    else -> throw errorFor(response)
                }
            }
        } catch (error: IOException) {
            throw SyncTransportException.Network(error.message ?: "Couldn't reach Google Drive", error)
        }
    }

    private class Outcome<T>(val value: T)

    private inline fun <reified T> decode(response: Response): T {
        val text = response.body.string()
        return try {
            json.decodeFromString<T>(text)
        } catch (error: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException.
            throw SyncTransportException.Server(response.code, "Malformed Drive response: ${error.message}")
        }
    }

    companion object {
        const val DEFAULT_API_BASE = "https://www.googleapis.com/drive/v3/"
        const val DEFAULT_UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3/"

        private const val APP_DATA_FOLDER = "appDataFolder"
        private const val FILE_FIELDS = "id,name,version,modifiedTime,size,appProperties"
        private const val MAX_GENERATED_IDS = 1000
        private const val MAX_ERROR_BODY_BYTES = 64L * 1024

        private val MULTIPART_RELATED = "multipart/related".toMediaType()
        private val JSON_UTF8 = "application/json; charset=UTF-8".toMediaType()
        private val GZIP = "application/gzip".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Dedicated client for Drive: no disk cache, no logging (bearer tokens and note text
         * must never reach logcat), timeouts long enough for a multi-MB upload to commit.
         * "gzip" in the User-Agent asks Google to gzip JSON responses.
         */
        fun defaultHttpClient(versionName: String): OkHttpClient {
            val userAgent = "Yoin/$versionName (gzip)"
            return OkHttpClient.Builder()
                .cache(null)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
                }
                .build()
        }

        private fun String.asBaseUrl(): HttpUrl = (if (endsWith("/")) this else "$this/").toHttpUrl()

        /** Content-Type for a media-only update, so the file keeps the type it was created with. */
        private fun sniffMediaType(bytes: ByteArray) =
            if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) GZIP else OCTET_STREAM

        private fun errorFor(response: Response): SyncTransportException {
            val code = response.code
            val error = runCatching {
                json.decodeFromString<DriveErrorResponse>(response.peekBody(MAX_ERROR_BODY_BYTES).string()).error
            }.getOrNull()
            val reasons = buildSet {
                error?.errors?.forEach { item -> item.reason?.let(::add) }
                error?.details?.forEach { detail -> detail.reason?.let(::add) }
            }
            val message = "Drive HTTP $code" +
                (reasons.firstOrNull()?.let { " ($it)" } ?: "") +
                (error?.message?.take(200)?.let { ": $it" } ?: "")
            return when {
                "storageQuotaExceeded" in reasons -> SyncTransportException.StorageFull(message)
                reasons.any { it in MISCONFIGURED_REASONS } -> SyncTransportException.Misconfigured(message)
                reasons.any { it in UNAUTHORIZED_REASONS } -> SyncTransportException.Unauthorized(message)
                code == 429 || reasons.any { it in RATE_LIMIT_REASONS } ->
                    SyncTransportException.RateLimited(message, retryAfterMs(response))
                else -> SyncTransportException.Server(code, message)
            }
        }

        private val MISCONFIGURED_REASONS = setOf("accessNotConfigured", "SERVICE_DISABLED")
        private val UNAUTHORIZED_REASONS = setOf("insufficientPermissions", "ACCESS_TOKEN_SCOPE_INSUFFICIENT")
        private val RATE_LIMIT_REASONS = setOf("userRateLimitExceeded", "rateLimitExceeded", "RATE_LIMIT_EXCEEDED")

        /** Retry-After as delta-seconds or an HTTP date. */
        private fun retryAfterMs(response: Response): Long? {
            val header = response.header("Retry-After")?.trim() ?: return null
            header.toLongOrNull()?.let { seconds -> return (seconds * 1000).coerceAtLeast(0) }
            val date = response.headers.getDate("Retry-After") ?: return null
            return (date.time - System.currentTimeMillis()).coerceAtLeast(0)
        }
    }
}

/**
 * Executes [request] and runs [read] on the response before it is closed, all
 * under coroutine cancellation: cancelling the caller cancels the call, which
 * also aborts a body that is still streaming.
 */
private suspend fun <T> OkHttpClient.executeReading(request: Request, read: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resumeWith(runCatching { response.use(read) })
                }
            },
        )
    }
