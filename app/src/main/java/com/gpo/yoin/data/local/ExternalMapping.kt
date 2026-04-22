package com.gpo.yoin.data.local

import androidx.room.Entity
import androidx.room.Index

/**
 * 多对一关联表：多个 Yoin 实体 (provider, entityType, entityId) 可以指向
 * 同一个外部实体 (externalService, externalId)。
 *
 * 设计要点：
 *  - PK 以 `(externalService, externalId, provider, entityType, entityId)`
 *    的顺序复合，**锚点在 external 侧**。同一张 NeoDB 专辑 (externalId =
 *    uuid-X) 可以有多行关联 —— 比如 Subsonic 版 + Spotify 版共享同一个
 *    NeoDB 记录。
 *  - 反查（Yoin → uuid）靠 unique index
 *    `(provider, entityType, entityId, externalService)`；这条 index 同时
 *    强制「每个 Yoin 实体 per service 最多一个 uuid」—— 不允许一个专辑在同
 *    一个 service 里被推到两个不同 uuid。
 *  - 正查（uuid → 所有关联的 Yoin 实体）走 PK 前缀
 *    `(externalService, externalId)`，不用额外 index。
 *
 * [AlbumRating] 仍然绑 `(albumId, provider)`（评分是 Yoin 本地语义），
 * push 时通过这张表把同一张专辑的不同 provider 行映射到同一个 uuid，
 * 在 NeoDB 侧只写一次 Mark / Review。
 *
 * `entityType` 使用小写字符串（"album" / "song"），给未来 Last.fm /
 * MusicBrainz 留口子，不需要再做一次 migration。
 */
@Entity(
    tableName = "external_mappings",
    primaryKeys = [
        "externalService",
        "externalId",
        "provider",
        "entityType",
        "entityId",
    ],
    indices = [
        Index(
            value = ["provider", "entityType", "entityId", "externalService"],
            unique = true,
            name = "index_external_mappings_yoin_entity",
        ),
    ],
)
data class ExternalMapping(
    val externalService: String,
    val externalId: String,
    val provider: String,
    val entityType: String,
    val entityId: String,
    val syncedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val SERVICE_NEODB = "neodb"
        const val ENTITY_ALBUM = "album"
        const val ENTITY_SONG = "song"
    }
}
