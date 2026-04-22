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
    )
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
    val visibility: Int = 0,
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

@Serializable
internal data class ReviewRequest(
    @SerialName("item_uuid") val itemUuid: String,
    val visibility: Int = 0,
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
