package com.gpo.yoin.shared.sync.adapters

import com.gpo.yoin.shared.sync.ApplyOutcome
import com.gpo.yoin.shared.sync.ConflictPolicy
import com.gpo.yoin.shared.sync.DomainRow
import com.gpo.yoin.shared.sync.FileClass
import com.gpo.yoin.shared.sync.SkipReason
import com.gpo.yoin.shared.sync.SyncAdapter
import com.gpo.yoin.shared.sync.SyncKinds
import kotlinx.serialization.json.JsonObject

/**
 * Port of Android's HomeLayoutSyncAdapter. `home_layout` <-> [SyncKinds.HOME_LAYOUT]: one record per
 * account, key [KEY]. The sections JSON travels as an opaque string (the layout's versioned document,
 * stored exactly as written or received, so peers with other encoders hash alike and unknown section ids
 * survive an older device).
 *
 * A missing row means "default layout": Reset deletes it, so the delete propagates as a tombstone and every
 * device goes back to the default instead of pulling the old layout back.
 */
class HomeLayoutSyncAdapter(private val domain: SyncDomain) : SyncAdapter {
    override val kind = SyncKinds.HOME_LAYOUT
    override val kindVersion = 1
    override val fileClass = FileClass.STATE
    override val perAccount = true
    override val propagatesDeletes = true
    override val conflictPolicy = ConflictPolicy.LWW

    override suspend fun readAll(localProfileId: String?): List<DomainRow> {
        val profileId = requireNotNull(localProfileId) { "home_layout is per-account" }
        val row = domain.homeLayout(profileId) ?: return emptyList()
        return listOf(DomainRow(key = KEY, projection = projection(row.sectionsJson), rowTs = row.updatedAt))
    }

    override fun project(payload: JsonObject): JsonObject? = payload.requiredString(SECTIONS)?.let(::projection)

    override suspend fun apply(
        localProfileId: String?,
        key: String,
        payload: JsonObject,
        versionTs: Long,
        expectedLocalHash: String?,
    ): ApplyOutcome {
        val profileId = requireNotNull(localProfileId) { "home_layout is per-account" }
        val currentHash = domain.homeLayout(profileId)?.let { projection(it.sectionsJson) }.hashOrNull()
        val sectionsJson = payload.requiredString(SECTIONS)
        if (key != KEY || sectionsJson == null) return ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, currentHash)
        if (currentHash != expectedLocalHash) return ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
        domain.putHomeLayout(profileId, HomeLayoutRow(sectionsJson, updatedAt = versionTs)).orFailWrite("putHomeLayout")
        return ApplyOutcome.Applied(domain.homeLayout(profileId)?.let { projection(it.sectionsJson) }.hashOrNull())
    }

    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome {
        val profileId = requireNotNull(localProfileId) { "home_layout is per-account" }
        if (key != KEY) return ApplyOutcome.Applied(null)
        val current = domain.homeLayout(profileId) ?: return ApplyOutcome.Applied(null)
        val currentHash = projection(current.sectionsJson).hashOrNull()
        if (currentHash != expectedLocalHash) return ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
        domain.deleteHomeLayout(profileId).orFailWrite("deleteHomeLayout")
        return ApplyOutcome.Applied(null)
    }

    private fun projection(sectionsJson: String) = JsonObject(mapOf(SECTIONS to jsonString(sectionsJson)))

    companion object {
        const val KEY = "layout"
        private const val SECTIONS = "sectionsJson"
    }
}
