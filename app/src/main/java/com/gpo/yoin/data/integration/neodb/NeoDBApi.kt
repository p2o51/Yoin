package com.gpo.yoin.data.integration.neodb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    suspend fun searchAlbum(
        instance: String,
        token: String,
        query: String,
    ): List<ShelfItem.Item> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildUrl(instance, "/api/catalog/search?category=album&query=${encode(query)}"))
            .header("Authorization", "Bearer $token")
            .header("Accept", JSON_MEDIA_TYPE_VALUE)
            .get()
            .build()

        executeJson<AlbumSearchResponse>(request).data
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
            if (!res.isSuccessful) throw res.toNeoDBException(body)
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
            if (!res.isSuccessful) throw res.toNeoDBException(body)
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
            if (!res.isSuccessful) throw res.toNeoDBException(body)
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

    private fun executeVoid(request: Request) {
        client.newCall(request).execute().use { res ->
            if (!res.isSuccessful) throw res.toNeoDBException(res.body?.string().orEmpty())
        }
    }

    private inline fun <reified T> executeJson(request: Request): T {
        client.newCall(request).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw res.toNeoDBException(body)
            return json.decodeFromString<T>(body)
        }
    }

    private fun buildUrl(instance: String, path: String): String {
        val normalizedBase = instance.trimEnd('/')
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        return normalizedBase + normalizedPath
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8)

    private fun Response.toNeoDBException(body: String): NeoDBException {
        val parsed = runCatching { json.decodeFromString<NeoDBErrorBody>(body) }.getOrNull()
        val message = parsed?.detail ?: parsed?.error ?: body.take(200)
        return NeoDBException(code = code, message = message)
    }

    companion object {
        private const val JSON_MEDIA_TYPE_VALUE = "application/json"
        private val JSON_MEDIA_TYPE = JSON_MEDIA_TYPE_VALUE.toMediaType()
    }
}

internal class NeoDBException(val code: Int, message: String) : Exception(message)
