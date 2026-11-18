package com.gpo.yoin.shared.sync.drive

import com.gpo.yoin.shared.data.subsonic.platformHttpEngine
import com.gpo.yoin.shared.sync.GoogleAccountInfo
import com.gpo.yoin.shared.sync.RemoteFile
import com.gpo.yoin.shared.sync.SyncTokenProvider
import com.gpo.yoin.shared.sync.SyncTransport
import com.gpo.yoin.shared.sync.SyncTransportException
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.serialization.json.Json

/**
 * Drive v3 REST client scoped to the hidden appDataFolder (scope drive.appdata).
 * Port of Android's OkHttp `data/sync/drive/DriveAppDataClient.kt` onto Ktor:
 * the same requests (paths, query parameters, `fields`), the same multipart
 * create and the same error mapping.
 *
 * Every request carries a Bearer token from [tokens]; a 401 invalidates that
 * token and retries exactly once with a fresh one. HTTP failures are mapped to
 * [SyncTransportException] subtypes so the sync manager can pick a user-visible
 * state without parsing Drive errors itself.
 */
class DriveAppDataClient(
    private val tokens: SyncTokenProvider,
    apiBase: String = DEFAULT_API_BASE,
    uploadBase: String = DEFAULT_UPLOAD_BASE,
    userAgent: String = "Yoin (gzip)",
    engine: HttpClientEngine? = null,
) : SyncTransport {
    private val apiBase: Url = Url(apiBase.asBase())
    private val uploadBase: Url = Url(uploadBase.asBase())

    /** "gzip" in the User-Agent asks Google to gzip JSON responses. */
    private val userAgentHeader: String = userAgent

    private val http: HttpClient = HttpClient(engine ?: platformHttpEngine()) {
        expectSuccess = false
        followRedirects = true
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 120_000
            socketTimeoutMillis = 60_000
        }
    }

    override suspend fun list(): List<RemoteFile> {
        val files = mutableListOf<RemoteFile>()
        val seenTokens = mutableSetOf<String>()
        var pageToken: String? = null
        do {
            val url = URLBuilder(apiBase).apply {
                appendPathSegments("files")
                parameters.append("spaces", APP_DATA_FOLDER)
                parameters.append("pageSize", "1000")
                parameters.append("fields", "nextPageToken,files($FILE_FIELDS)")
                pageToken?.let { parameters.append("pageToken", it) }
            }.build()
            val page = send(HttpMethod.Get, url) { decode(it, DriveFileList.serializer()) }
            page.files.mapTo(files) { it.toRemoteFile() }
            pageToken = page.nextPageToken?.takeIf { it.isNotEmpty() }
            if (pageToken != null && !seenTokens.add(pageToken)) {
                throw SyncTransportException.Server(200, "Drive repeated a page token")
            }
        } while (pageToken != null)
        return files
    }

    override suspend fun download(fileId: String): ByteArray? {
        val url = URLBuilder(apiBase).apply {
            appendPathSegments("files", fileId)
            parameters.append("alt", "media")
        }.build()
        return send(HttpMethod.Get, url, accept = setOf(404)) { response ->
            if (response.status.value == 404) null else response.bodyAsBytes()
        }
    }

    override suspend fun create(
        fileId: String,
        name: String,
        appProperties: Map<String, String>,
        mimeType: String,
        bytes: ByteArray,
    ): RemoteFile {
        val url = URLBuilder(uploadBase).apply {
            appendPathSegments("files")
            parameters.append("uploadType", "multipart")
            parameters.append("fields", FILE_FIELDS)
        }.build()
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
        val boundary = "yoin" + Random.nextLong().toULong().toString(16) + Random.nextLong().toULong().toString(16)
        val body = multipartRelated(boundary, metadata, mimeType, bytes)
        val contentType = ContentType.parse("multipart/related; boundary=$boundary")
        return send(HttpMethod.Post, url, accept = setOf(409), body = ByteArrayContent(body, contentType)) { response ->
            if (response.status.value == 409) {
                throw SyncTransportException.AlreadyExists("Drive file $fileId already exists")
            }
            decode(response, DriveFile.serializer()).toRemoteFile()
        }
    }

    override suspend fun update(fileId: String, bytes: ByteArray): RemoteFile? {
        val url = URLBuilder(uploadBase).apply {
            appendPathSegments("files", fileId)
            parameters.append("uploadType", "media")
            parameters.append("fields", FILE_FIELDS)
        }.build()
        val body = ByteArrayContent(bytes, sniffMediaType(bytes))
        return send(HttpMethod.Patch, url, accept = setOf(404), body = body) { response ->
            if (response.status.value == 404) null else decode(response, DriveFile.serializer()).toRemoteFile()
        }
    }

    override suspend fun delete(fileId: String): Boolean {
        val url = URLBuilder(apiBase).apply { appendPathSegments("files", fileId) }.build()
        return send(HttpMethod.Delete, url, accept = setOf(404)) { true }
    }

    override suspend fun generateIds(count: Int): List<String> {
        require(count in 1..MAX_GENERATED_IDS) { "count must be in 1..$MAX_GENERATED_IDS" }
        val url = URLBuilder(apiBase).apply {
            appendPathSegments("files", "generateIds")
            parameters.append("space", APP_DATA_FOLDER)
            parameters.append("count", count.toString())
        }.build()
        val ids = send(HttpMethod.Get, url) { decode(it, DriveGeneratedIds.serializer()).ids }
            .filter { it.isNotBlank() }
        // Callers index into the result (generateIds(1).first()); a short list is a malformed response.
        if (ids.size < count) {
            throw SyncTransportException.Server(200, "Drive returned ${ids.size} of $count requested file ids")
        }
        return ids.take(count)
    }

    override suspend fun about(): GoogleAccountInfo {
        val url = URLBuilder(apiBase).apply {
            appendPathSegments("about")
            parameters.append("fields", "user(permissionId,emailAddress,displayName)")
        }.build()
        val user = send(HttpMethod.Get, url) { decode(it, DriveAbout.serializer()).user }
        val permissionId = user?.permissionId?.takeIf { it.isNotBlank() }
            ?: throw SyncTransportException.Server(200, "Drive returned no permissionId")
        return GoogleAccountInfo(
            permissionId = permissionId,
            email = user.emailAddress?.takeIf { it.isNotBlank() },
            displayName = user.displayName?.takeIf { it.isNotBlank() },
        )
    }

    fun close() = http.close()

    /**
     * Runs a request with a Bearer token. 2xx responses and statuses in [accept]
     * go to [read]; a 401 is retried once with a fresh token; everything else
     * becomes a [SyncTransportException].
     */
    private suspend fun <T> send(
        method: HttpMethod,
        url: Url,
        accept: Set<Int> = emptySet(),
        body: ByteArrayContent? = null,
        read: suspend (HttpResponse) -> T,
    ): T {
        val firstToken = freshToken()
        attempt(method, url, firstToken, accept, body, read)?.let { return it.value }
        tokens.invalidate(firstToken)
        val secondToken = freshToken()
        attempt(method, url, secondToken, accept, body, read)?.let { return it.value }
        throw SyncTransportException.Unauthorized("Drive rejected a refreshed access token")
    }

    private suspend fun freshToken(): String = try {
        tokens.token()
    } catch (error: CancellationException) {
        throw error
    } catch (error: SyncTransportException) {
        throw error
    } catch (error: Exception) {
        throw SyncTransportException.Network(error.message ?: "Couldn't get a Google access token", error)
    }

    /** null = 401 (caller decides whether to retry). */
    private suspend fun <T> attempt(
        method: HttpMethod,
        url: Url,
        token: String,
        accept: Set<Int>,
        body: ByteArrayContent?,
        read: suspend (HttpResponse) -> T,
    ): Outcome<T>? {
        val response = try {
            http.request(url) {
                this.method = method
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.UserAgent, userAgentHeader)
                if (body != null) setBody(body)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw SyncTransportException.Network(error.message ?: "Couldn't reach Google Drive", error)
        }
        val code = response.status.value
        return when {
            code == 401 -> null
            response.status.isSuccess() || code in accept -> try {
                Outcome(read(response))
            } catch (error: CancellationException) {
                throw error
            } catch (error: SyncTransportException) {
                throw error
            } catch (error: Exception) {
                throw SyncTransportException.Network(error.message ?: "Drive response was cut off", error)
            }
            else -> throw errorFor(response)
        }
    }

    private class Outcome<T>(val value: T)

    private suspend fun <T> decode(response: HttpResponse, serializer: kotlinx.serialization.KSerializer<T>): T {
        val text = response.bodyAsText()
        return try {
            json.decodeFromString(serializer, text)
        } catch (error: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException.
            throw SyncTransportException.Server(response.status.value, "Malformed Drive response: ${error.message}")
        }
    }

    private suspend fun errorFor(response: HttpResponse): SyncTransportException {
        val code = response.status.value
        val error = runCatching {
            json.decodeFromString(DriveErrorResponse.serializer(), response.bodyAsText().take(MAX_ERROR_BODY_CHARS)).error
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

    companion object {
        const val DEFAULT_API_BASE = "https://www.googleapis.com/drive/v3/"
        const val DEFAULT_UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3/"

        private const val APP_DATA_FOLDER = "appDataFolder"
        private const val FILE_FIELDS = "id,name,version,modifiedTime,size,appProperties"
        private const val MAX_GENERATED_IDS = 1000
        private const val MAX_ERROR_BODY_CHARS = 64 * 1024

        private val json = Json { ignoreUnknownKeys = true }

        private val MISCONFIGURED_REASONS = setOf("accessNotConfigured", "SERVICE_DISABLED")
        private val UNAUTHORIZED_REASONS = setOf("insufficientPermissions", "ACCESS_TOKEN_SCOPE_INSUFFICIENT")
        private val RATE_LIMIT_REASONS = setOf("userRateLimitExceeded", "rateLimitExceeded", "RATE_LIMIT_EXCEEDED")

        private fun String.asBase(): String = if (endsWith("/")) this else "$this/"

        /** Content-Type for a media-only update, so the file keeps the type it was created with. */
        private fun sniffMediaType(bytes: ByteArray): ContentType =
            if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                ContentType("application", "gzip")
            } else {
                ContentType.Application.OctetStream
            }

        /** Drive's multipart upload: a JSON metadata part, then the media part (RFC 2387 multipart/related). */
        internal fun multipartRelated(boundary: String, metadata: String, mimeType: String, bytes: ByteArray): ByteArray {
            val head = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
                "--$boundary\r\nContent-Type: $mimeType\r\n\r\n"
            val tail = "\r\n--$boundary--\r\n"
            return head.encodeToByteArray() + bytes + tail.encodeToByteArray()
        }

        /** Retry-After as delta-seconds (an HTTP-date is ignored: no common date parser). */
        private fun retryAfterMs(response: HttpResponse): Long? {
            val header = response.headers[HttpHeaders.RetryAfter]?.trim() ?: return null
            return header.toLongOrNull()?.let { seconds -> (seconds * 1000).coerceAtLeast(0) }
        }
    }
}
