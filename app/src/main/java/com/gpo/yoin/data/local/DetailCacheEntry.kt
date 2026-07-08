package com.gpo.yoin.data.local

import androidx.room.Entity
import androidx.room.Index

/**
 * One cached album / artist / playlist detail response, stored as JSON.
 *
 * Keyed by (profileId, kind, entityId) so different accounts and entity types
 * never collide. [accessedAt] drives LRU eviction (hence the index; eviction
 * scans order by it); [cachedAt] drives freshness and age-out.
 * See `DetailCacheStore`.
 */
@Entity(
    tableName = "detail_cache",
    primaryKeys = ["profileId", "kind", "entityId"],
    indices = [Index(value = ["accessedAt"])],
)
data class DetailCacheEntry(
    val profileId: String,
    val kind: String,
    val entityId: String,
    val json: String,
    val cachedAt: Long,
    val accessedAt: Long,
)

/** Projection for byte-budget eviction (avoids loading the JSON blobs). */
data class DetailCacheSize(
    val profileId: String,
    val kind: String,
    val entityId: String,
    val bytes: Long,
)
