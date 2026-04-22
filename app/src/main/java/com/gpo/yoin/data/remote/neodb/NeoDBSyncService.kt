package com.gpo.yoin.data.remote.neodb

import com.gpo.yoin.data.local.AlbumRating
import com.gpo.yoin.data.local.AlbumRatingDao
import com.gpo.yoin.data.local.ExternalMapping
import com.gpo.yoin.data.local.ExternalMappingDao
import com.gpo.yoin.data.local.NeoDBConfigDao
import com.gpo.yoin.data.model.Album
import kotlin.math.roundToInt

/**
 * 在 Yoin `album_ratings` 表和 NeoDB `Mark` + `Review` 之间做双向同步。
 *
 * 设计要点：
 *  - rating: Yoin 浮点 0–10 对齐 NeoDB `rating_grade` 整数 0–10，推送前
 *    `roundToInt()`；回拉不覆写本地小数精度（本地有小数就保留）。
 *  - review: 用户自写 `album_ratings.review` ↔ NeoDB `Review.body`，
 *    双向覆写。song_notes 仅用于 AlbumDetail 展示，不推 NeoDB。
 *  - Mark 覆写雷：POST `/api/me/shelf/item/{uuid}` 会整体覆写 —— 先 GET
 *    合并 tags + comment_text + visibility 再 POST，避免把其它客户端
 *    写的字段清掉。
 *  - 映射缓存：Yoin `albumId` ↔ NeoDB item uuid 存 `external_mappings`。
 *    没命中时走 [NeoDBApi.searchAlbum] 按 "name artist" 查。
 *
 * 所有方法都会在没有 token 时直接返回 [Result.success] / no-op，让调用方
 * 不用分叉逻辑；UI 层需要时再读 [isConfigured] 判断能不能触发同步。
 */
class NeoDBSyncService(
    private val api: NeoDBApi,
    private val configDao: NeoDBConfigDao,
    private val mappingDao: ExternalMappingDao,
    private val albumRatingDao: AlbumRatingDao,
) {

    suspend fun isConfigured(): Boolean {
        val cfg = configDao.get() ?: return false
        return cfg.accessToken.isNotBlank() && cfg.instance.isNotBlank()
    }

    /**
     * 把本地 [AlbumRating] 的 rating + review 推到 NeoDB。
     * 需要先已经解析出 album 的 NeoDB uuid（通过 [resolveAlbumUuid]）。
     *
     * 流程（终局 v2）：
     *  1. GET `/api/me/shelf/item/{uuid}` 拿当前 tags / comment_text /
     *     shelf_type / visibility。
     *  2. 本地 rating 覆写 `rating_grade`；其它字段保留。POST 回去。
     *  3. review 有变化时：
     *     - 本地 reviewUuid 为空 → POST 创建新 Review，回写 uuid。
     *     - 已有 uuid → PUT 覆写 body。
     *     - 本地 review 清空 → DELETE。
     */
    suspend fun pushAlbum(album: Album): Result<Unit> = runCatching {
        val cfg = configDao.get() ?: error("NeoDB 未配置")
        require(cfg.accessToken.isNotBlank()) { "NeoDB token 为空" }

        val local = albumRatingDao.get(album.id.rawId, album.id.provider)
            ?: return@runCatching
        if (!local.ratingNeedsSync && !local.reviewNeedsSync) return@runCatching

        val itemUuid = resolveAlbumUuid(album)
            ?: error("无法在 NeoDB 上找到对应专辑")

        if (local.ratingNeedsSync) {
            val existing = api.getShelfItem(cfg.instance, cfg.accessToken, itemUuid)
            val body = ShelfMarkRequest(
                shelfType = existing?.shelfType ?: "complete",
                visibility = existing?.visibility ?: 0,
                ratingGrade = local.rating.roundToInt().coerceIn(0, 10),
                commentText = existing?.commentText,
                tags = existing?.tags ?: emptyList(),
            )
            api.postShelfMark(cfg.instance, cfg.accessToken, itemUuid, body)
        }

        if (local.reviewNeedsSync) {
            val reviewBody = local.review.orEmpty()
            val existingUuid = local.neoDbReviewUuid
            val newUuid = when {
                reviewBody.isBlank() && existingUuid != null -> {
                    runCatching {
                        api.deleteReview(cfg.instance, cfg.accessToken, existingUuid)
                    }
                    null
                }
                reviewBody.isBlank() -> null
                existingUuid != null -> {
                    api.updateReview(
                        cfg.instance,
                        cfg.accessToken,
                        existingUuid,
                        ReviewRequest(
                            itemUuid = itemUuid,
                            title = album.name,
                            body = reviewBody,
                        ),
                    ).uuid ?: existingUuid
                }
                else -> {
                    api.createReview(
                        cfg.instance,
                        cfg.accessToken,
                        ReviewRequest(
                            itemUuid = itemUuid,
                            title = album.name,
                            body = reviewBody,
                        ),
                    ).uuid
                }
            }

            albumRatingDao.upsert(
                local.copy(
                    neoDbReviewUuid = newUuid,
                    reviewNeedsSync = false,
                    ratingNeedsSync = false,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        } else if (local.ratingNeedsSync) {
            albumRatingDao.upsert(
                local.copy(
                    ratingNeedsSync = false,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /**
     * 把 NeoDB 侧的 Mark + Review 拉到本地 —— 仅 NeoDB 有值时覆写本地；
     * 本地有未推的 needsSync 时跳过该字段（避免把用户在飞行模式下打的
     * 草稿干掉）。
     */
    suspend fun pullAlbum(album: Album): Result<AlbumRating?> = runCatching {
        val cfg = configDao.get() ?: error("NeoDB 未配置")
        require(cfg.accessToken.isNotBlank()) { "NeoDB token 为空" }

        val itemUuid = resolveAlbumUuid(album) ?: return@runCatching null
        val remote = api.getShelfItem(cfg.instance, cfg.accessToken, itemUuid)
            ?: return@runCatching null

        val existing = albumRatingDao.get(album.id.rawId, album.id.provider)
        val mergedRating = when {
            existing?.ratingNeedsSync == true -> existing.rating
            remote.ratingGrade != null -> remote.ratingGrade.toFloat()
            else -> existing?.rating ?: 0f
        }
        val mergedReview = when {
            existing?.reviewNeedsSync == true -> existing.review
            else -> fetchReviewBody(cfg.instance, cfg.accessToken, existing?.neoDbReviewUuid)
                ?: existing?.review
        }

        val resolved = AlbumRating(
            albumId = album.id.rawId,
            provider = album.id.provider,
            rating = mergedRating,
            review = mergedReview,
            neoDbReviewUuid = existing?.neoDbReviewUuid,
            ratingNeedsSync = existing?.ratingNeedsSync ?: false,
            reviewNeedsSync = existing?.reviewNeedsSync ?: false,
            updatedAt = System.currentTimeMillis(),
        )
        albumRatingDao.upsert(resolved)
        resolved
    }

    /**
     * 查 / 建 Yoin album ↔ NeoDB uuid 映射。先读 `external_mappings`；
     * 没命中时按 "name artist" 搜第一条 album result 作为匹配，并把
     * 结果落库 —— 匹配准确度不够的专辑（小众 / 同名）由用户在 0.5 的
     * 映射校验 UI 里手动修。
     */
    private suspend fun resolveAlbumUuid(album: Album): String? {
        val cached = mappingDao.get(
            provider = album.id.provider,
            entityType = ExternalMapping.ENTITY_ALBUM,
            entityId = album.id.rawId,
            service = ExternalMapping.SERVICE_NEODB,
        )
        if (cached != null) return cached.externalId

        val cfg = configDao.get() ?: return null
        if (cfg.accessToken.isBlank()) return null

        val query = buildString {
            append(album.name)
            if (!album.artist.isNullOrBlank()) {
                append(' ')
                append(album.artist)
            }
        }.trim()
        if (query.isEmpty()) return null

        val hit = api.searchAlbum(cfg.instance, cfg.accessToken, query).firstOrNull()
        val uuid = hit?.uuid ?: return null

        mappingDao.upsert(
            ExternalMapping(
                provider = album.id.provider,
                entityType = ExternalMapping.ENTITY_ALBUM,
                entityId = album.id.rawId,
                externalService = ExternalMapping.SERVICE_NEODB,
                externalId = uuid,
            ),
        )
        return uuid
    }

    private suspend fun fetchReviewBody(
        instance: String,
        token: String,
        reviewUuid: String?,
    ): String? {
        val uuid = reviewUuid ?: return null
        return runCatching {
            api.getReview(instance, token, uuid)?.body
        }.getOrNull()
    }
}
