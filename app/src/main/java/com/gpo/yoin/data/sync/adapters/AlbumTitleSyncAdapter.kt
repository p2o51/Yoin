package com.gpo.yoin.data.sync.adapters

import androidx.room.withTransaction
import com.gpo.yoin.data.local.AlbumMemoryTitle
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.sync.ApplyOutcome
import com.gpo.yoin.data.sync.ConflictPolicy
import com.gpo.yoin.data.sync.DomainRow
import com.gpo.yoin.data.sync.FileClass
import com.gpo.yoin.data.sync.SkipReason
import com.gpo.yoin.data.sync.SyncAdapter
import com.gpo.yoin.data.sync.SyncKinds
import kotlinx.serialization.json.JsonObject

/**
 * `album_memory_titles` <-> [SyncKinds.ALBUM_TITLE]: the title the user gave an
 * album's Memory. One record per album (key provider␟albumId, the same as the
 * album rating), last writer wins on the user's edit time. A missing row means
 * "use Yoin's title" (AlbumMemoryTitleStore deletes it on Restore), so deletes
 * propagate as tombstones. The AI title syncs separately ([SyncKinds.MEMORY_TITLE])
 * and never touches this table, so a user's title always wins on every device.
 */
class AlbumTitleSyncAdapter(private val db: YoinDatabase) : SyncAdapter {
    override val kind = SyncKinds.ALBUM_TITLE
    override val kindVersion = 1
    override val fileClass = FileClass.STATE
    override val perAccount = true
    override val propagatesDeletes = true
    override val conflictPolicy = ConflictPolicy.LWW

    private val dao get() = db.albumMemoryTitleDao()

    override suspend fun readAll(localProfileId: String?): List<DomainRow> {
        val profileId = requireNotNull(localProfileId) { "album_title is per-account" }
        return dao.getAllForProfile(profileId).map { row ->
            DomainRow(key = albumKey(row.provider, row.albumId), projection = projection(row.title), rowTs = row.updatedAt)
        }
    }

    override fun project(payload: JsonObject): JsonObject? =
        payload.requiredString(TITLE)?.trim()?.takeIf(String::isNotEmpty)?.let(::projection)

    override suspend fun apply(
        localProfileId: String?,
        key: String,
        payload: JsonObject,
        versionTs: Long,
        expectedLocalHash: String?,
    ): ApplyOutcome {
        val profileId = requireNotNull(localProfileId) { "album_title is per-account" }
        val (provider, albumId) = albumKeyParts(key) ?: return ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null)
        return db.withTransaction {
            val currentHash = dao.get(profileId, provider, albumId)?.let { projection(it.title) }.hashOrNull()
            val title = project(payload)?.requiredString(TITLE)
                ?: return@withTransaction ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, currentHash)
            if (currentHash != expectedLocalHash) {
                return@withTransaction ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
            }
            dao.upsert(AlbumMemoryTitle(profileId, provider, albumId, title, updatedAt = versionTs))
            ApplyOutcome.Applied(projection(title).hashOrNull())
        }
    }

    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome {
        val profileId = requireNotNull(localProfileId) { "album_title is per-account" }
        val (provider, albumId) = albumKeyParts(key) ?: return ApplyOutcome.Applied(null)
        return db.withTransaction {
            val current = dao.get(profileId, provider, albumId) ?: return@withTransaction ApplyOutcome.Applied(null)
            val currentHash = projection(current.title).hashOrNull()
            if (currentHash != expectedLocalHash) {
                return@withTransaction ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
            }
            dao.delete(profileId, provider, albumId)
            ApplyOutcome.Applied(null)
        }
    }

    private fun projection(title: String) = JsonObject(mapOf(TITLE to jsonString(title)))

    private companion object {
        const val TITLE = "title"
    }
}
