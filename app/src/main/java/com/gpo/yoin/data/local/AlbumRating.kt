package com.gpo.yoin.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import com.gpo.yoin.data.model.MediaId

/**
 * 专辑级评分 + 长评（Review）。
 *
 * [rating] 为 0.0–10.0 浮点，和 NeoDB `Mark.rating_grade` 对齐（整数 0–10）；
 * 推送前做 `roundToInt()` 即可。Yoin 内部允许 0.1 精度以保留 Memory 里
 * 曲目均分的平滑感。
 *
 * [review] 是用户自写的长评（markdown）。不自动从 song_notes 聚合 ——
 * 聚合结果只用于 AlbumDetail 的默认展示和 Review 草稿灵感。
 *
 * [neoDbReviewUuid] 记录 NeoDB 侧 Review 资源的 uuid；首次推送后回填，
 * 用于后续 PUT/DELETE。
 *
 * [needsSync] / [ratingNeedsSync] / [reviewNeedsSync] 分离，因为 NeoDB
 * 的 Mark（含 rating）和 Review 是两个资源，离线写入时可能只有一边脏。
 */
@Entity(tableName = "album_ratings", primaryKeys = ["albumId", "provider"])
data class AlbumRating(
    val albumId: String,
    @ColumnInfo(defaultValue = MediaId.PROVIDER_SUBSONIC)
    val provider: String = MediaId.PROVIDER_SUBSONIC,
    val rating: Float,
    val review: String?,
    val neoDbReviewUuid: String?,
    val ratingNeedsSync: Boolean = false,
    val reviewNeedsSync: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)
