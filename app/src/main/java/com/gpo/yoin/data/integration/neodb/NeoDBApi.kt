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
 *
 * 所有调用走用户在 [NeoDBConfig] 里存的 personal access token，以 Bearer
 * 头带；instance 可换（默认 neodb.social，自建实例可换成自己域名）。
 *
 * **不在这里做 merge**：调用方（NeoDBSyncService）负责 GET → 合并本地覆写
 * → POST，避免把 NeoDB 上别的客户端写的 tags / comment_text 清掉。
 */
class NeoDBApi(
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

class NeoDBException(val code: Int, message: String) : Exception(message)
