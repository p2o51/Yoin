package com.gpo.yoin.data.integration.neodb

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * NeoDB REST 客户端 —— OpenAPI 0.14.0.5 的最小子集。
 *
 * 覆盖：
 *  - 搜索 album 拿 uuid（首次同步时把 Yoin albumId 映射到 NeoDB uuid）
 *  - GET / POST 自己 shelf 里的 Mark（rating + tags + comment + shelf_type）
 *  - POST / PUT / DELETE Review（长评）
 *  - 反查自己的 review 列表（按 item uuid 找，用于「远端有、本地没推过」
 *    的双向同步冷启动场景）
 *
 * 所有调用走用户配的 NeoDB personal access token，以 Bearer 头带；
 * instance 可换（默认 neodb.social，自建实例可换成自己域名）。
 *
 * **不在这里做 merge**：调用方（NeoDBSyncService）负责 GET → 合并本地覆写
 * → POST，避免把 NeoDB 上别的客户端写的 tags / comment_text 清掉。
 *
 * **Visibility**: 整个类 `internal`，因为它暴露的 DTO（ShelfItem /
 * ReviewResponse 等）也都是 `internal`。外层通过 [NeoDBSyncService] 这个
 * public 包装访问 NeoDB，内部 DTO 不泄露到 UI / Repository 层。
 */
internal class NeoDBApi(
    private val client: OkHttpClient,
    private val json: Json,
) {

    suspend fun registerOAuthApp(
        instance: String,
        redirectUri: String,
        clientName: String,
        website: String,
    ): OAuthClientRegistration = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_name", clientName)
            .add("redirect_uris", redirectUri)
            .add("website", website)
            .build()
        val request = Request.Builder()
            .url(buildUrl(instance, "/api/v1/apps"))
            .header("Accept", JSON_MEDIA_TYPE_VALUE)
            .post(body)
            .build()
        executeJson(request)
    }

    suspend fun exchangeOAuthCode(
        instance: String,
        clientId: String,
        clientSecret: String,
        code: String,
        redirectUri: String,
    ): OAuthTokenResponse = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("grant_type", "authorization_code")
            .build()
        val request = Request.Builder()
            .url(buildUrl(instance, "/oauth/token"))
            .header("Accept", JSON_MEDIA_TYPE_VALUE)
            .post(body)
            .build()
        executeJson(request)
    }

    suspend fun searchAlbum(
        instance: String,
        token: String,
        query: String,
    ): List<ShelfItem.Item> = withContext(Dispatchers.IO) {
        runCatching {
            searchCatalog(
                instance = instance,
                token = token,
                query = query,
                category = "music",
            )
        }.recoverCatching { error ->
            val neoDbError = error as? NeoDBException
            val shouldRetryLegacyAlbumCategory = neoDbError?.code == 422 &&
                neoDbError.message.orEmpty().contains("category", ignoreCase = true)
            if (!shouldRetryLegacyAlbumCategory) throw error

            Log.w(
                TAG,
                "searchAlbum: category=music rejected by ${instance.trimEnd('/')} " +
                    "— retrying legacy category=album",
            )
            searchCatalog(
                instance = instance,
                token = token,
                query = query,
                category = "album",
            )
        }.getOrThrow()
    }

    suspend fun getShelfItem(
        instance: String,
        token: String,
        itemUuid: String,
    ): ShelfItem? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildUrl(instance, "/api/me/shelf/item/$itemUuid"))
            .header("Authorization", "Bearer $token")
            .header("Accept", JSON_MEDIA_TYPE_VALUE)
            .get()
            .build()

        val response = client.newCall(request).execute()
        response.use { res ->
            if (res.code == 404) return@withContext null
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val error = res.toNeoDBException(body)
                logRequestFailure(request, error, body)
                throw error
            }
            if (body.isBlank()) return@withContext null
            json.decodeFromString<ShelfItem>(body)
        }
    }

    suspend fun postShelfMark(
        instance: String,
        token: String,
        itemUuid: String,
        body: ShelfMarkRequest,
    ) = withContext(Dispatchers.IO) {
        val endpoint = buildUrl(instance, "/api/me/shelf/item/$itemUuid")
        val directRequest = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $token")
            .header("Accept", JSON_MEDIA_TYPE_VALUE)
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            executeVoid(directRequest, logOnFailure = false)
        } catch (error: NeoDBException) {
            val shouldRetryWrappedMark = error.code == 422 &&
                error.message.orEmpty().contains("body.mark.", ignoreCase = true)
            if (!shouldRetryWrappedMark) {
                logRequestFailure(directRequest, error, error.rawBody)
                throw error
            }

            Log.w(
                TAG,
                "postShelfMark: direct body rejected by ${instance.trimEnd('/')} " +
                    "— retrying wrapped mark payload",
            )
            val wrappedRequest = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $token")
                .header("Accept", JSON_MEDIA_TYPE_VALUE)
                .post(
                    json.encodeToString(ShelfMarkEnvelope(mark = body))
                        .toRequestBody(JSON_MEDIA_TYPE),
                )
                .build()
            executeVoid(wrappedRequest)
        }
    }

    /**
     * 反查当前用户在某个 item 下的 Review。NeoDB 保证每用户每 item 最多
     * 1 条 Review，所以这里只看第一页的第一条匹配即可 —— 用户手上理论上
     * 每 item 只会有一条。
     *
     * 用于 pullAlbum 的冷启动：本地没 review uuid 但远端可能已经有
     * review 的场景。通过 `item_uuid` 过滤，不需要分页扫全库。
     */
    suspend fun listMyReviewsForItem(
        instance: String,
        token: String,
        itemUuid: String,
    ): List<ReviewResponse> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildUrl(instance, "/api/me/review/?item_uuid=${encode(itemUuid)}"))
            .header("Authorization", "Bearer $token")
            .header("Accept", JSON_MEDIA_TYPE_VALUE)
            .get()
            .build()

        val response = client.newCall(request).execute()
        response.use { res ->
            if (res.code == 404) return@withContext emptyList()
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val error = res.toNeoDBException(body)
                logRequestFailure(request, error, body)
                throw error
            }
            if (body.isBlank()) return@withContext emptyList()
            // 服务端可能返回 paged {data:[...]} 或 bare [...]，都兼容。
            runCatching {
                json.decodeFromString<ReviewListResponse>(body).data
            }.getOrElse {
                runCatching {
                    json.decodeFromString<List<ReviewResponse>>(body)
                }.getOrElse { emptyList() }
            }.filter { review ->
                // 客户端再过一层 item_uuid 匹配，防止实例无视 query 参数。
                review.item?.uuid == itemUuid || review.item == null
            }
        }
    }

    suspend fun getReview(
        instance: String,
        token: String,
        reviewUuid: String,
    ): ReviewResponse? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildUrl(instance, "/api/me/review/$reviewUuid"))
            .header("Authorization", "Bearer $token")
            .header("Accept", JSON_MEDIA_TYPE_VALUE)
            .get()
            .build()
        val response = client.newCall(request).execute()
        response.use { res ->
            if (res.code == 404) return@withContext null
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val error = res.toNeoDBException(body)
                logRequestFailure(request, error, body)
                throw error
            }
            if (body.isBlank()) return@withContext null
            json.decodeFromString<ReviewResponse>(body)
        }
    }

    /**
     * 新建长评。NeoDB 每用户每 item 只允许 1 条 Review —— 已存在时应走
     * [updateReview] 覆写，否则会 400。调用方保证入参时机。
     */
    suspend fun createReview(
        instance: String,
        token: String,
        body: ReviewRequest,
    ): ReviewResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildUrl(instance, "/api/me/review/"))
            .header("Authorization", "Bearer $token")
            .header("Accept", JSON_MEDIA_TYPE_VALUE)
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        executeJson(request)
    }

    suspend fun updateReview(
        instance: String,
        token: String,
        reviewUuid: String,
        body: ReviewRequest,
    ): ReviewResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildUrl(instance, "/api/me/review/$reviewUuid"))
            .header("Authorization", "Bearer $token")
            .header("Accept", JSON_MEDIA_TYPE_VALUE)
            .put(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        executeJson(request)
    }

    suspend fun deleteReview(
        instance: String,
        token: String,
        reviewUuid: String,
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildUrl(instance, "/api/me/review/$reviewUuid"))
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        executeVoid(request)
    }

    private fun executeVoid(
        request: Request,
        logOnFailure: Boolean = true,
    ) {
        client.newCall(request).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val error = res.toNeoDBException(body)
                if (logOnFailure) {
                    logRequestFailure(request, error, body)
                }
                throw error
            }
        }
    }

    private inline fun <reified T> executeJson(request: Request): T {
        client.newCall(request).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val error = res.toNeoDBException(body)
                logRequestFailure(request, error, body)
                throw error
            }
            return json.decodeFromString<T>(body)
        }
    }

    private fun searchCatalog(
        instance: String,
        token: String,
        query: String,
        category: String,
    ): List<ShelfItem.Item> {
        val request = Request.Builder()
            .url(buildUrl(instance, "/api/catalog/search?category=$category&query=${encode(query)}"))
            .header("Authorization", "Bearer $token")
            .header("Accept", JSON_MEDIA_TYPE_VALUE)
            .get()
            .build()

        return executeJson<AlbumSearchResponse>(request).data
    }

    private fun buildUrl(instance: String, path: String): String {
        val normalizedBase = instance.trimEnd('/')
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        return normalizedBase + normalizedPath
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8)

    private fun Response.toNeoDBException(body: String): NeoDBException {
        val message = parseErrorMessage(body) ?: body.take(200)
        return NeoDBException(code = code, message = message, rawBody = body)
    }

    private fun parseErrorMessage(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return null

        root["detail"]?.let { detail ->
            when (detail) {
                is JsonPrimitive -> detail.contentOrNull?.let { return it }
                is JsonArray -> {
                    val messages = detail.mapNotNull(::formatDetailEntry)
                    if (messages.isNotEmpty()) return messages.joinToString(" | ")
                }
                is JsonObject -> formatDetailEntry(detail)?.let { return it }
                else -> Unit
            }
        }

        return root["error"]?.jsonPrimitive?.contentOrNull
    }

    private fun formatDetailEntry(entry: JsonElement): String? {
        val objectEntry = entry as? JsonObject
            ?: return (entry as? JsonPrimitive)?.contentOrNull
        val msg = objectEntry["msg"]?.jsonPrimitive?.contentOrNull
        val loc = formatLocation(objectEntry["loc"])
        return when {
            !msg.isNullOrBlank() && !loc.isNullOrBlank() -> "$loc: $msg"
            !msg.isNullOrBlank() -> msg
            else -> null
        }
    }

    private fun formatLocation(element: JsonElement?): String? {
        val location = element as? JsonArray ?: return null
        return location.mapNotNull { token ->
            token.jsonPrimitive.contentOrNull
        }.takeIf { it.isNotEmpty() }?.joinToString(".")
    }

    private fun logRequestFailure(
        request: Request,
        error: NeoDBException,
        body: String,
    ) {
        Log.w(
            TAG,
            "request failed: ${request.debugTarget()} code=${error.code} " +
                "message=${error.message.orEmpty()} body=${body.singleLineSnippet()}",
        )
    }

    private fun Request.debugTarget(): String {
        val pieces = buildList {
            url.queryParameter("category")?.let { add("category=$it") }
            url.queryParameter("item_uuid")?.let { add("item_uuid=$it") }
        }
        return buildString {
            append(method)
            append(' ')
            append(url.encodedPath)
            if (pieces.isNotEmpty()) {
                append('?')
                append(pieces.joinToString("&"))
            }
        }
    }

    private fun String.singleLineSnippet(limit: Int = 240): String =
        replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .take(limit)

    companion object {
        private const val TAG = "NeoDBApi"
        private const val JSON_MEDIA_TYPE_VALUE = "application/json"
        private val JSON_MEDIA_TYPE = JSON_MEDIA_TYPE_VALUE.toMediaType()
    }
}

internal class NeoDBException(
    val code: Int,
    message: String,
    val rawBody: String = "",
) : Exception(message)

@Serializable
internal data class OAuthClientRegistration(
    @kotlinx.serialization.SerialName("client_id")
    val clientId: String,
    @kotlinx.serialization.SerialName("client_secret")
    val clientSecret: String,
)

@Serializable
internal data class OAuthTokenResponse(
    @kotlinx.serialization.SerialName("access_token")
    val accessToken: String,
    @kotlinx.serialization.SerialName("token_type")
    val tokenType: String = "Bearer",
    @kotlinx.serialization.SerialName("scope")
    val scope: String? = null,
    @kotlinx.serialization.SerialName("created_at")
    val createdAt: Long? = null,
)
