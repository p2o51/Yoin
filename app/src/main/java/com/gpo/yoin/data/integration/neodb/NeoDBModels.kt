package com.gpo.yoin.data.integration.neodb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * NeoDB OpenAPI 0.14.0.5 子集 —— Yoin 目前只管 album 级别的 Mark / Review。
 *
 * 字段可空设计：NeoDB 允许部分字段缺省，这里全部给默认值 / 可空，避免
 * 用户实例返回较旧版本数据时 kotlinx.serialization 爆错。
 */
@Serializable
internal data class ShelfItem(
    val uuid: String? = null,
    @SerialName("shelf_type") val shelfType: String? = null,
    @SerialName("visibility") val visibility: Int = 0,
    @SerialName("item") val item: Item? = null,
    @SerialName("rating_grade") val ratingGrade: Int? = null,
    @SerialName("comment_text") val commentText: String? = null,
    @SerialName("tags") val tags: List<String> = emptyList(),
) {
    @Serializable
    data class Item(
        val uuid: String? = null,
        @SerialName("display_title") val displayTitle: String? = null,
        @SerialName("category") val category: String? = null,
        // Catalog fields (search / item detail). Absent on shelf payloads.
        @SerialName("title") val title: String? = null,
        @SerialName("url") val url: String? = null,
        @SerialName("artist") val artist: List<String> = emptyList(),
        @SerialName("rating") val rating: Double? = null,
        @SerialName("rating_count") val ratingCount: Int? = null,
        /** Five buckets, low to high, in percent. */
        @SerialName("rating_distribution") val ratingDistribution: List<Int>? = null,
        @SerialName("external_resources") val externalResources: List<ExternalResource>? = null,
    )

    @Serializable
    data class ExternalResource(val url: String = "")
}

/**
 * POST body for `/api/me/shelf/item/{uuid}`. `shelf_type` 取 "wishlist" /
 * "progress" / "complete" / "dropped"；Yoin 评过分的专辑默认 "complete"。
 *
 * 为了不覆写 NeoDB 上别的客户端写的字段，调用前必须先 GET 同一 uuid 拿到
 * 当前 [tags] / [commentText]，再 merge 回来。
 */
@Serializable
internal data class ShelfMarkRequest(
    @SerialName("shelf_type") val shelfType: String,
    // 服务端 MarkInSchema 把 shelf_type 和 visibility 列为必填。visibility
    // 不能带默认值：neoDbJson 是 encodeDefaults=false，字段值等于默认值时
    // 会整个从 JSON 里消失 —— 曾把 visibility=0 丢掉，422 报
    // "body.mark.visibility: Field required"（body.mark. 是 django-ninja
    // 的参数名定位，不是要包一层 mark 信封）。
    val visibility: Int,
    @SerialName("rating_grade") val ratingGrade: Int? = null,
    @SerialName("comment_text") val commentText: String? = null,
    @SerialName("tags") val tags: List<String> = emptyList(),
    @SerialName("post_to_fediverse") val postToFediverse: Boolean = false,
    @SerialName("created_time") val createdTime: String? = null,
)

@Serializable
internal data class ReviewResponse(
    val uuid: String? = null,
    @SerialName("visibility") val visibility: Int = 0,
    val title: String? = null,
    val body: String? = null,
    @SerialName("item") val item: ShelfItem.Item? = null,
)

/**
 * `POST /api/me/review/item/{item_uuid}` 的 body（ReviewInSchema）。item uuid
 * 在路径里，不再进 body。
 */
@Serializable
internal data class ReviewRequest(
    // ReviewInSchema 同样必填 visibility —— 同 ShelfMarkRequest，不带默认值
    // 以免 encodeDefaults=false 把 0 丢出 JSON。
    val visibility: Int,
    val title: String,
    val body: String,
    @SerialName("post_to_fediverse") val postToFediverse: Boolean = false,
)

@Serializable
internal data class AlbumSearchResponse(
    val data: List<ShelfItem.Item> = emptyList(),
)

@Serializable
internal data class NeoDBErrorBody(
    val detail: String? = null,
    val error: String? = null,
)

/**
 * `GET /api/item/{uuid}/posts/` —— 某条目下所有人的帖子（短评、长评、标记）。
 * 帖子是 Mastodon 形状；NeoDB 自己的含义在 `ext_neodb.relatedWith` 里
 * （Comment 的纯文字、Rating 的分数、Review 的标题和正文），所以那一层按
 * JsonObject 读，交给 [NeoDbVoices] 解释。
 */
@Serializable
internal data class NeoDbPostPage(
    val data: List<NeoDbPost> = emptyList(),
    val pages: Int = 0,
    val count: Int = 0,
)

@Serializable
internal data class NeoDbPost(
    val id: String = "",
    val url: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val sensitive: Boolean = false,
    @SerialName("spoiler_text") val spoilerText: String? = null,
    val account: NeoDbAccount? = null,
    @SerialName("ext_neodb") val extNeodb: NeoDbPostExt? = null,
)

@Serializable
internal data class NeoDbAccount(
    val acct: String? = null,
    val username: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val avatar: String? = null,
    val url: String? = null,
)

@Serializable
internal data class NeoDbPostExt(
    val relatedWith: List<kotlinx.serialization.json.JsonObject> = emptyList(),
)
