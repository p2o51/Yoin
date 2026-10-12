package com.gpo.yoin.data.cache

import com.gpo.yoin.data.local.DetailCacheDao
import com.gpo.yoin.data.local.DetailCacheEntry
import com.gpo.yoin.data.local.DetailCacheSize
import kotlinx.coroutines.CompletableDeferred

/**
 * In-memory [DetailCacheDao] with SQLite's units (LENGTH() counts code points). A set gate makes
 * that call suspend until it completes, so a test can hold a write or a touch mid-flight.
 */
internal class FakeDetailCacheDao : DetailCacheDao {
    private val rows = linkedMapOf<Triple<String, String, String>, DetailCacheEntry>()

    var upsertGate: CompletableDeferred<Unit>? = null
    var touchGate: CompletableDeferred<Unit>? = null

    /** Completes when an upsert reaches the DAO (before any [upsertGate]). */
    val upsertEntered = CompletableDeferred<Unit>()

    /** Completes when an upsert has stored its row. */
    val upsertStored = CompletableDeferred<Unit>()

    /** How many times eviction scanned the table ([sizesOldestFirst]). */
    var evictionScans = 0
        private set

    val entityIds: Set<String> get() = synchronized(rows) { rows.values.mapTo(linkedSetOf()) { it.entityId } }

    override suspend fun upsert(entry: DetailCacheEntry) {
        upsertEntered.complete(Unit)
        upsertGate?.await()
        synchronized(rows) { rows[key(entry.profileId, entry.kind, entry.entityId)] = entry }
        upsertStored.complete(Unit)
    }

    override suspend fun get(profileId: String, kind: String, entityId: String): DetailCacheEntry? =
        synchronized(rows) { rows[key(profileId, kind, entityId)] }

    override suspend fun jsonLength(profileId: String, kind: String, entityId: String): Long? =
        get(profileId, kind, entityId)?.json?.codePointLength()

    override suspend fun touch(profileId: String, kind: String, entityId: String, now: Long) {
        touchGate?.await()
        synchronized(rows) {
            val key = key(profileId, kind, entityId)
            rows[key]?.let { rows[key] = it.copy(accessedAt = now) }
        }
    }

    override suspend fun delete(profileId: String, kind: String, entityId: String) {
        synchronized(rows) { rows.remove(key(profileId, kind, entityId)) }
    }

    override suspend fun totalBytes(): Long = synchronized(rows) { rows.values.sumOf { it.json.codePointLength() } }

    override suspend fun sizesOldestFirst(): List<DetailCacheSize> = synchronized(rows) {
        evictionScans++
        rows.values.sortedBy { it.accessedAt }
            .map { DetailCacheSize(it.profileId, it.kind, it.entityId, it.json.codePointLength()) }
    }

    override suspend fun deleteOlderThan(cutoff: Long) {
        synchronized(rows) { rows.values.removeAll { it.cachedAt < cutoff } }
    }

    private fun key(profileId: String, kind: String, entityId: String) = Triple(profileId, kind, entityId)

    private fun String.codePointLength(): Long = codePointCount(0, length).toLong()
}
