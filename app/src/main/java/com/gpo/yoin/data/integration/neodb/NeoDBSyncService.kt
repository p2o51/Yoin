package com.gpo.yoin.data.integration.neodb

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
 *  - **Token 存储**：NeoDB access token 放 [NeoDbTokenStore]（加密落在
 *    `noBackupFilesDir`），不进 Room —— Room 走云备份，token 不应跟着走。
 *
 * 所有方法在未登录时直接返回 [Result.success] / no-op，让调用方不用分叉
 * 逻辑；UI 层需要时再读 [isConfigured] 判能不能触发同步。
 */
class NeoDBSyncService internal constructor(
    private val api: NeoDBApi,
    private val configDao: NeoDBConfigDao,
    private val tokenStore: NeoDbTokenStore,
    private val mappingDao: ExternalMappingDao,
    private val albumRatingDao: AlbumRatingDao,
) {

    private data class Session(val instance: String, val token: String)

    /**
     * 拿当前可用的 (instance, token)。两者任一缺失直接返回 null；调用方
     * 靠这个 null 短路到「未登录」分支。
     */
    private suspend fun currentSession(): Session? {
        val instance = configDao.get()?.instance?.takeUnless(String::isBlank)
            ?: return null
        val token = tokenStore.readToken()?.takeUnless(String::isBlank) ?: return null
        return Session(instance = instance, token = token)
    }

    suspend fun isConfigured(): Boolean = currentSession() != null

    /**
     * 把本地 [AlbumRating] 的 rating + review 推到 NeoDB。
     *
     * 流程（终局 v2）：
     *  1. GET `/api/me/shelf/item/{uuid}` 拿当前 tags / comment_text /
     *     shelf_type / visibility。
     *  2. 本地 rating 覆写 `rating_grade`；其它字段保留。POST 回去。
     *  3. review 有变化时：
     *     - 本地 reviewUuid 为空 → POST 创建新 Review，回写 uuid。
     *     - 已有 uuid → PUT 覆写 body。
     *     - 本地 review 清空 → DELETE。**失败不会清本地脏位 / uuid**，
     *       让用户下次能重试；只有确认远端删除成功（或 NeoDB 回 404 ==
     *       已经不在了）才 treat 成功。
     *
     * **多对一复用**：`external_mappings` 表允许多条 Yoin 实体指向同一
     * `externalId`，所以同一张专辑的 Subsonic 版和 Spotify 版 push 时
     * uuid 是同一个，NeoDB 侧 Mark/Review 只会被覆写一次（POST 幂等）。
     */
    suspend fun pushAlbum(profileId: String, album: Album): Result<Unit> = runCatching {
        val session = currentSession() ?: error("NeoDB 未配置")

        val local = albumRatingDao.get(album.id.rawId, album.id.provider, profileId)
            ?: return@runCatching
        if (!local.ratingNeedsSync && !local.reviewNeedsSync) return@runCatching

        val itemUuid = resolveAlbumUuid(album, session)
            ?: error("无法在 NeoDB 上找到对应专辑")

        if (local.ratingNeedsSync) {
            val existing = api.getShelfItem(session.instance, session.token, itemUuid)
            val body = ShelfMarkRequest(
                shelfType = existing?.shelfType ?: "complete",
                visibility = existing?.visibility ?: 0,
                ratingGrade = local.rating.roundToInt().coerceIn(0, 10),
                commentText = existing?.commentText,
                tags = existing?.tags ?: emptyList(),
            )
            api.postShelfMark(session.instance, session.token, itemUuid, body)
        }

        if (local.reviewNeedsSync) {
            val reviewBody = local.review.orEmpty()
            val existingUuid = local.neoDbReviewUuid
            val newUuid = when {
                reviewBody.isBlank() -> {
                    // Delete path：**不再把失败吞掉**。删除按 item uuid 走，
                    // 且不再拿本地 review uuid 当触发条件 —— 现行 API 的
                    // review 没有 uuid 概念（POST 响应是 Result 消息壳），
                    // 本地 uuid 永远是空的，靠它判断会漏删远端孤儿。
                    //  - 204 / 200 / 404 都视为已不在（NeoDB 上已经没这条
                    //    Review 了，目标状态一致）→ 清脏位。
                    //  - 其它失败（401 / 5xx / 网络）抛给外层 runCatching，
                    //    Room 的 upsert 不会执行 → 本地保持脏位，下次同步
                    //    时继续重试。
                    try {
                        api.deleteReview(session.instance, session.token, itemUuid)
                    } catch (error: NeoDBException) {
                        if (error.code != 404) throw error
                    }
                    null
                }
                else -> {
                    // POST /api/me/review/item/{uuid} 是 upsert —— 新建与
                    // 覆写同一条路，不再需要 create/update 分叉。
                    api.postReview(
                        session.instance,
                        session.token,
                        itemUuid,
                        ReviewRequest(
                            visibility = 0,
                            title = album.name,
                            body = reviewBody,
                        ),
                    ).uuid ?: existingUuid
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
     * 把 NeoDB 侧的 Mark + Review 拉到本地。
     *
     * Review merge：
     *  - 本地有 `reviewNeedsSync = true` → 跳过远端值，保护飞行模式草稿。
     *  - 其余一律 [NeoDBApi.getMyReviewForItem] 按 item uuid 拉（现行 API
     *    是 item 中心，本地有没有 review uuid 都是同一条查询）——「远端
     *    先写、本地从没推过」的孤儿 review 冷启动场景天然覆盖，找到就
     *    回写本地 uuid + body，让后续双向同步能接上。
     */
    suspend fun pullAlbum(profileId: String, album: Album): Result<AlbumRating?> = runCatching {
        val session = currentSession() ?: error("NeoDB 未配置")

        val itemUuid = resolveAlbumUuid(album, session) ?: return@runCatching null
        val remote = api.getShelfItem(session.instance, session.token, itemUuid)
        // remote 为 null（NeoDB 上这张专辑还没 shelf 记录）时，仍可能有
        // 孤儿 Review —— 继续尝试拉 review。
        val existing = albumRatingDao.get(album.id.rawId, album.id.provider, profileId)

        val mergedRating = when {
            existing?.ratingNeedsSync == true -> existing.rating
            remote?.ratingGrade != null -> remote.ratingGrade.toFloat()
            else -> existing?.rating ?: 0f
        }

        val reviewLookup = resolveReviewForPull(
            session = session,
            itemUuid = itemUuid,
            existingUuid = existing?.neoDbReviewUuid,
        )
        val mergedReview = when {
            existing?.reviewNeedsSync == true -> existing.review
            reviewLookup != null -> reviewLookup.body
            else -> existing?.review
        }
        val mergedUuid = when {
            existing?.reviewNeedsSync == true -> existing.neoDbReviewUuid
            reviewLookup != null -> reviewLookup.uuid
            else -> existing?.neoDbReviewUuid
        }

        // 远端全空 + 本地全空 → 不写新行；避免在 album_ratings 里造出
        // 一堆零分占位。
        val hasAnyContent = mergedRating > 0f ||
            !mergedReview.isNullOrBlank() ||
            existing != null
        if (!hasAnyContent) return@runCatching null

        val resolved = AlbumRating(
            profileId = profileId,
            albumId = album.id.rawId,
            provider = album.id.provider,
            rating = mergedRating,
            review = mergedReview,
            neoDbReviewUuid = mergedUuid,
            ratingNeedsSync = existing?.ratingNeedsSync ?: false,
            reviewNeedsSync = existing?.reviewNeedsSync ?: false,
            updatedAt = System.currentTimeMillis(),
        )
        albumRatingDao.upsert(resolved)
        resolved
    }

    /**
     * 查 / 建 Yoin album ↔ NeoDB uuid 映射。
     *
     * 走两级缓存：
     *  1. **反查当前 Yoin 实体** —— `findForYoinEntity` 命中就直接复用。
     *  2. **搜 NeoDB** —— name + artist 模糊搜索第一条结果；命中后落
     *     `external_mappings`。
     *
     * 跨源模糊匹配（例如 Subsonic 版已映射后把 Spotify 版也自动绑到同一
     * uuid）留给 0.5 的映射校验 UI —— 这里保持搜索兜底。
     */
    private suspend fun resolveAlbumUuid(album: Album, session: Session): String? {
        val cached = mappingDao.findForYoinEntity(
            provider = album.id.provider,
            entityType = ExternalMapping.ENTITY_ALBUM,
            entityId = album.id.rawId,
            service = ExternalMapping.SERVICE_NEODB,
        )
        if (cached != null) return cached.externalId

        val query = buildString {
            append(album.name)
            if (!album.artist.isNullOrBlank()) {
                append(' ')
                append(album.artist)
            }
        }.trim()
        if (query.isEmpty()) return null

        val hit = api.searchAlbum(session.instance, session.token, query).firstOrNull()
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

    /**
     * 拉 review 时决定要 merge 哪份远端内容。
     *  - 本地有 uuid 就直接 GET /api/me/review/{uuid}（精确路径，一次到位）
     *  - 本地没 uuid → 调 [NeoDBApi.listMyReviews] 按 itemUuid 反查；
     *    NeoDB 保证每用户每 item 最多 1 条 Review，所以命中就是唯一答案。
     *
     *  任一失败都返回 null，调用方降级到「没拉到 review，保留本地」分支。
     */
    private suspend fun resolveReviewForPull(
        session: Session,
        itemUuid: String,
        existingUuid: String?,
    ): ReviewLookup? {
        // item 中心 API 下本地有没有 uuid 都是同一条查询；远端 uuid 缺失时
        // 退回本地已知的，保持旧行为。
        val remote = runCatching {
            api.getMyReviewForItem(session.instance, session.token, itemUuid)
        }.getOrNull() ?: return null
        return ReviewLookup(uuid = remote.uuid ?: existingUuid, body = remote.body)
    }

    private data class ReviewLookup(val uuid: String?, val body: String?)
}
