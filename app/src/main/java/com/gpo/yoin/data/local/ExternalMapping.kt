package com.gpo.yoin.data.local

import androidx.room.Entity
import androidx.room.Index

/**
 * 把 Yoin 侧的实体（provider+entityType+entityId）与外部服务（NeoDB、
 * Last.fm 等）的 uuid 对齐。目前只用于 NeoDB 双向同步 ——
 * Album ↔ NeoDB Album uuid。
 *
 * `entityType` 使用小写字符串（"album" / "song"），而不是强枚举，
 * 这样以后接 Last.fm / MusicBrainz 时不需要再做一次 migration。
 *
 * [externalService] e.g. `"neodb"`；[externalId] 是对端 uuid。
 */
@Entity(
    tableName = "external_mappings",
    primaryKeys = ["provider", "entityType", "entityId", "externalService"],
    indices = [
        Index(value = ["externalService", "externalId"]),
    ],
)
data class ExternalMapping(
    val provider: String,
    val entityType: String,
    val entityId: String,
    val externalService: String,
    val externalId: String,
    val syncedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val SERVICE_NEODB = "neodb"
        const val ENTITY_ALBUM = "album"
        const val ENTITY_SONG = "song"
    }
}
