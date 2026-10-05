package com.gpo.yoin.data.sync.adapters

import androidx.room.withTransaction
import com.gpo.yoin.data.local.MemoryCopyCache
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.sync.ApplyOutcome
import com.gpo.yoin.data.sync.ConflictPolicy
import com.gpo.yoin.data.sync.DomainRow
import com.gpo.yoin.data.sync.FileClass
import com.gpo.yoin.data.sync.SkipReason
import com.gpo.yoin.data.sync.SyncAdapter
import com.gpo.yoin.data.sync.SyncFormat
import com.gpo.yoin.data.sync.SyncKinds
import kotlinx.serialization.json.JsonObject

/*
 * memory_copy and memory_title are two synced kinds over ONE memory_copy_cache
 * row (the Gemini mood line and the "AI 拟题" album title). They are separate
 * kinds on purpose: the copy is regenerated whenever ratings move, and as one
 * record a peer's newer copy without a title would win last-writer-wins and
 * wipe a title written elsewhere. Each adapter reads and writes only its own
 * columns; the hashes travel along so the receiving device reuses the text
 * instead of paying Gemini again (its own ratings/notes are synced too, so the
 * hashes normally match). Nothing is ever deleted: the cache has no delete path.
 */

private fun memoryKey(row: MemoryCopyCache) = SyncFormat.compositeKey(row.provider, row.entityType, row.entityId)

private data class MemoryKey(val provider: String, val entityType: String, val entityId: String)

private fun memoryKeyParts(key: String): MemoryKey? =
    splitKey(key, 3)?.let { (provider, entityType, entityId) -> MemoryKey(provider, entityType, entityId) }

class MemoryCopySyncAdapter(private val db: YoinDatabase) : SyncAdapter {
    override val kind = SyncKinds.MEMORY_COPY
    override val kindVersion = 1
    override val fileClass = FileClass.STATE
    override val perAccount = true
    override val propagatesDeletes = false
    override val conflictPolicy = ConflictPolicy.LWW

    private val dao get() = db.syncDomainDao()

    override suspend fun readAll(localProfileId: String?): List<DomainRow> {
        val profileId = requireNotNull(localProfileId) { "memory_copy is per-account" }
        return dao.memoryCopiesForProfile(profileId)
            .filter { it.copy.isNotBlank() }
            .map { DomainRow(key = memoryKey(it), projection = projection(it.copy, it.promptHash, it.generatedAt), rowTs = it.generatedAt) }
    }

    override fun project(payload: JsonObject): JsonObject? {
        val copy = payload.requiredString(COPY)?.takeIf(String::isNotBlank) ?: return null
        val promptHash = payload.requiredString(PROMPT_HASH) ?: return null
        val generatedAt = payload.requiredLong(GENERATED_AT) ?: return null
        return projection(copy, promptHash, generatedAt)
    }

    override suspend fun apply(
        localProfileId: String?,
        key: String,
        payload: JsonObject,
        versionTs: Long,
        expectedLocalHash: String?,
    ): ApplyOutcome {
        val profileId = requireNotNull(localProfileId) { "memory_copy is per-account" }
        val parts = memoryKeyParts(key) ?: return ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null)
        return db.withTransaction {
            val existing = dao.memoryCopy(profileId, parts.provider, parts.entityType, parts.entityId)
            val currentHash = existing?.takeIf { it.copy.isNotBlank() }
                ?.let { projection(it.copy, it.promptHash, it.generatedAt) }.hashOrNull()
            val incoming = project(payload)
                ?: return@withTransaction ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, currentHash)
            if (currentHash != expectedLocalHash) {
                return@withTransaction ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
            }
            val copy = incoming.requiredString(COPY)!!
            val promptHash = incoming.requiredString(PROMPT_HASH)!!
            val generatedAt = incoming.requiredLong(GENERATED_AT)!!
            // Field-level: the title half of the row belongs to memory_title.
            dao.upsertMemoryCopy(
                (existing ?: MemoryCopyCache(profileId, parts.provider, parts.entityType, parts.entityId, "", "")).copy(
                    copy = copy,
                    promptHash = promptHash,
                    generatedAt = generatedAt,
                ),
            )
            ApplyOutcome.Applied(projection(copy, promptHash, generatedAt).hashOrNull())
        }
    }

    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome =
        ApplyOutcome.Skipped(SkipReason.POLICY, expectedLocalHash)

    private fun projection(copy: String, promptHash: String, generatedAt: Long) = JsonObject(
        mapOf(COPY to jsonString(copy), PROMPT_HASH to jsonString(promptHash), GENERATED_AT to jsonLong(generatedAt)),
    )

    private companion object {
        const val COPY = "copy"
        const val PROMPT_HASH = "promptHash"
        const val GENERATED_AT = "generatedAt"
    }
}

class MemoryTitleSyncAdapter(private val db: YoinDatabase) : SyncAdapter {
    override val kind = SyncKinds.MEMORY_TITLE
    override val kindVersion = 1
    override val fileClass = FileClass.STATE
    override val perAccount = true
    override val propagatesDeletes = false
    override val conflictPolicy = ConflictPolicy.LWW

    private val dao get() = db.syncDomainDao()

    override suspend fun readAll(localProfileId: String?): List<DomainRow> {
        val profileId = requireNotNull(localProfileId) { "memory_title is per-account" }
        return dao.memoryCopiesForProfile(profileId).mapNotNull { row ->
            val title = row.title?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            // The title has no timestamp of its own; the row's is the closest for first-join ordering.
            DomainRow(key = memoryKey(row), projection = projection(title, row.titlePromptHash), rowTs = row.generatedAt)
        }
    }

    override fun project(payload: JsonObject): JsonObject? {
        val title = payload.requiredString(TITLE)?.takeIf(String::isNotBlank) ?: return null
        val titlePromptHash = payload.optionalString(TITLE_PROMPT_HASH).orElse { return null }
        return projection(title, titlePromptHash)
    }

    override suspend fun apply(
        localProfileId: String?,
        key: String,
        payload: JsonObject,
        versionTs: Long,
        expectedLocalHash: String?,
    ): ApplyOutcome {
        val profileId = requireNotNull(localProfileId) { "memory_title is per-account" }
        val parts = memoryKeyParts(key) ?: return ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null)
        return db.withTransaction {
            val existing = dao.memoryCopy(profileId, parts.provider, parts.entityType, parts.entityId)
            val currentHash = existing?.title?.takeIf(String::isNotBlank)
                ?.let { projection(it, existing.titlePromptHash) }.hashOrNull()
            val incoming = project(payload)
                ?: return@withTransaction ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, currentHash)
            if (currentHash != expectedLocalHash) {
                return@withTransaction ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
            }
            val title = incoming.requiredString(TITLE)!!
            val titlePromptHash = (incoming.optionalString(TITLE_PROMPT_HASH) as? Field.Ok)?.value
            // Home reads a title from any row; with no copy yet the row starts with an empty copy
            // and a promptHash that never matches, so Memories regenerates or falls back for it.
            dao.upsertMemoryCopy(
                (existing ?: MemoryCopyCache(profileId, parts.provider, parts.entityType, parts.entityId, "", "", 0L)).copy(
                    title = title,
                    titlePromptHash = titlePromptHash,
                ),
            )
            ApplyOutcome.Applied(projection(title, titlePromptHash).hashOrNull())
        }
    }

    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome =
        ApplyOutcome.Skipped(SkipReason.POLICY, expectedLocalHash)

    private fun projection(title: String, titlePromptHash: String?) =
        JsonObject(mapOf(TITLE to jsonString(title), TITLE_PROMPT_HASH to jsonString(titlePromptHash)))

    private companion object {
        const val TITLE = "title"
        const val TITLE_PROMPT_HASH = "titlePromptHash"
    }
}
