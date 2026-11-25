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
    var onCachedBeforeReturned: (suspend () -> Unit)? = null

    /** Completes when an upsert reaches the DAO (before any [upsertGate]). */
    val upsertEntered = CompletableDeferred<Unit>()

    /** Completes when an upsert has stored its row. */
    val upsertStored = CompletableDeferred<Unit>()

    /** How many times eviction scanned the table ([sizesOldestFirst]). */
    var evictionScans = 0
        private set

    /** How many batched SQL delete statements ran ([deleteBatch]). */
    var deleteBatchCalls = 0
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

    override suspend fun deleteBatch(profileId: String, kind: String, entityIds: List<String>) {
        if (entityIds.isEmpty()) return
        val ids = entityIds.toHashSet()
        synchronized(rows) {
            deleteBatchCalls++
            rows.keys.removeAll { (p, k, id) -> p == profileId && k == kind && id in ids }
        }
    }

    override suspend fun deleteExpiredBatch(
        profileId: String,
        kind: String,
        entityIds: List<String>,
        cutoff: Long,
    ) {
        if (entityIds.isEmpty()) return
        val ids = entityIds.toHashSet()
        synchronized(rows) {
            deleteBatchCalls++
            rows.entries.removeAll { (k, entry) ->
                k.first == profileId && k.second == kind && k.third in ids && entry.cachedAt < cutoff
            }
        }
    }

    override suspend fun totalBytes(): Long = synchronized(rows) { rows.values.sumOf { it.json.codePointLength() } }

    override suspend fun sizesOldestFirst(): List<DetailCacheSize> = synchronized(rows) {
        evictionScans++
        rows.values.sortedBy { it.accessedAt }
            .map { DetailCacheSize(it.profileId, it.kind, it.entityId, it.json.codePointLength()) }
    }

    override suspend fun cachedBefore(cutoff: Long): List<DetailCacheSize> {
        val snapshot = synchronized(rows) {
            rows.values.filter { it.cachedAt < cutoff }
                .map { DetailCacheSize(it.profileId, it.kind, it.entityId, it.json.codePointLength()) }
        }
        onCachedBeforeReturned?.invoke()
        return snapshot
    }

    override suspend fun deleteOlderThan(cutoff: Long) {
        synchronized(rows) { rows.values.removeAll { it.cachedAt < cutoff } }
    }

    override suspend fun deleteProfile(profileId: String) {
        synchronized(rows) { rows.values.removeAll { it.profileId == profileId } }
    }

    private fun key(profileId: String, kind: String, entityId: String) = Triple(profileId, kind, entityId)

    private fun String.codePointLength(): Long = codePointCount(0, length).toLong()
}
