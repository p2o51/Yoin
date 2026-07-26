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
        // OpenAPI（MarkInSchema）确认 body 就是扁平对象，必填 shelf_type +
        // visibility。验证错误里的 "body.mark.xxx" 前缀是 django-ninja 对
        // 名为 mark 的 body 参数的定位路径 —— 之前据此推断「服务端要
        // {mark:{...}} 信封」并做二次重试是误读，信封形态从来不是合法
        // 请求体，那条重试只会把一个缺字段错误放大成两个。
        val request = Request.Builder()
            .url(buildUrl(instance, "/api/me/shelf/item/$itemUuid"))
            .header("Authorization", "Bearer $token")
            .header("Accept", JSON_MEDIA_TYPE_VALUE)
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        executeVoid(request)
    }

    /**
     * 反查当前用户在某个 item 下的 Review。NeoDB 保证每用户每 item 最多
     * 1 条 Review，所以这里只看第一页的第一条匹配即可 —— 用户手上理论上
     * 每 item 只会有一条。
     *
     * 用于 pullAlbum 的冷启动：本地没 review uuid 但远端可能已经有
     * review 的场景。通过 `item_uuid` 过滤，不需要分页扫全库。
     */
    /**
     * 取当前用户在某个 item 下的 Review。现行 API 是 item 中心：
     * `GET /api/me/review/item/{item_uuid}`，每用户每 item 至多 1 条，
     * 404 = 没写过。旧的 `GET /api/me/review/?item_uuid=` 列表反查与按
     * review uuid 直读两条路径都已从 OpenAPI 消失。
     */
    suspend fun getMyReviewForItem(
        instance: String,
        token: String,
        itemUuid: String,
    ): ReviewResponse? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildUrl(instance, "/api/me/review/item/$itemUuid"))
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
     * 写长评 —— `POST /api/me/review/item/{item_uuid}` 是 upsert 语义
     * （新建与覆写同一条路），没有单独的 create/update 端点；旧的
     * `POST /api/me/review/` 现在只答 405。
     */
    suspend fun postReview(
        instance: String,
        token: String,
        itemUuid: String,
        body: ReviewRequest,
    ): ReviewResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildUrl(instance, "/api/me/review/item/$itemUuid"))
            .header("Authorization", "Bearer $token")
            .header("Accept", JSON_MEDIA_TYPE_VALUE)
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        executeJson(request)
    }

    /** 删长评 —— 同样按 item uuid 走：`DELETE /api/me/review/item/{item_uuid}`。 */
    suspend fun deleteReview(
        instance: String,
        token: String,
        itemUuid: String,
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildUrl(instance, "/api/me/review/item/$itemUuid"))
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
        // The Charset overload of URLEncoder.encode requires API 33; the charset-name
        // overload is available since API 1 and produces identical UTF-8 output.
        java.net.URLEncoder.encode(value, "UTF-8")

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
