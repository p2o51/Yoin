package com.gpo.yoin.data.sync.adapters

import androidx.room.withTransaction
import com.gpo.yoin.data.local.HomeLayoutPreference
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
 * `home_layout` <-> [SyncKinds.HOME_LAYOUT]: one record per account, key
 * [KEY]. The sections JSON travels as an opaque string (HomeLayoutStore's
 * versioned document; it keeps unknown section ids, so a newer device's
 * sections survive an older one).
 *
 * A missing row means "default layout": HomeLayoutStore.clearLayout deletes
 * it on Restore default, so the delete propagates as a tombstone and every
 * device goes back to the default instead of pulling the old layout back.
 */
class HomeLayoutSyncAdapter(private val db: YoinDatabase) : SyncAdapter {
    override val kind = SyncKinds.HOME_LAYOUT
    override val kindVersion = 1
    override val fileClass = FileClass.STATE
    override val perAccount = true
    override val propagatesDeletes = true
    override val conflictPolicy = ConflictPolicy.LWW

    private val dao get() = db.syncDomainDao()

    override suspend fun readAll(localProfileId: String?): List<DomainRow> {
        val profileId = requireNotNull(localProfileId) { "home_layout is per-account" }
        val row = dao.homeLayout(profileId) ?: return emptyList()
        return listOf(DomainRow(key = KEY, projection = projection(row.sectionsJson), rowTs = row.updatedAt))
    }

    override fun project(payload: JsonObject): JsonObject? = payload.requiredString("sectionsJson")?.let(::projection)

    override suspend fun apply(
        localProfileId: String?,
        key: String,
        payload: JsonObject,
        versionTs: Long,
        expectedLocalHash: String?,
    ): ApplyOutcome {
        val profileId = requireNotNull(localProfileId) { "home_layout is per-account" }
        return db.withTransaction {
            val currentHash = dao.homeLayout(profileId)?.let { projection(it.sectionsJson) }.hashOrNull()
            val sectionsJson = payload.requiredString("sectionsJson")
            if (key != KEY || sectionsJson == null) {
                return@withTransaction ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, currentHash)
            }
            if (currentHash != expectedLocalHash) {
                return@withTransaction ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
            }
            dao.upsertHomeLayout(
                HomeLayoutPreference(profileId = profileId, sectionsJson = sectionsJson, updatedAt = versionTs),
            )
            ApplyOutcome.Applied(dao.homeLayout(profileId)?.let { projection(it.sectionsJson) }.hashOrNull())
        }
    }

    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome {
        val profileId = requireNotNull(localProfileId) { "home_layout is per-account" }
        return db.withTransaction {
            val current = dao.homeLayout(profileId)?.takeIf { key == KEY }
                ?: return@withTransaction ApplyOutcome.Applied(null)
            val currentHash = projection(current.sectionsJson).hashOrNull()
            if (currentHash != expectedLocalHash) {
                return@withTransaction ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
            }
            dao.deleteHomeLayout(profileId)
            ApplyOutcome.Applied(null)
        }
    }

    private fun projection(sectionsJson: String) = JsonObject(mapOf("sectionsJson" to jsonString(sectionsJson)))

    companion object {
        const val KEY = "layout"
    }
}
