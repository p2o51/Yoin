package com.gpo.yoin.data.sync.adapters

import androidx.room.withTransaction
import com.gpo.yoin.data.local.AlbumAboutEntry
import com.gpo.yoin.data.local.GeminiConfig
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

/**
 * `album_about_entries` <-> [SyncKinds.ALBUM_ABOUT]: an album's AI credits and paragraph, and the questions
 * asked about it. The same rules as [SongAboutSyncAdapter], for the same reasons: global (reference
 * material, keyed by the album's provider + id rather than by account), every record stamped with the AI
 * language it was written in and applied only by devices reading that language, deletes never propagate
 * (a language switch clears the table locally; Drive keeps the records for their language), last write wins.
 */
class AlbumAboutSyncAdapter(private val db: YoinDatabase) : SyncAdapter {
    override val kind = SyncKinds.ALBUM_ABOUT
    override val kindVersion = 1
    override val fileClass = FileClass.ARTIFACTS
    override val perAccount = false
    override val propagatesDeletes = false
    override val conflictPolicy = ConflictPolicy.LWW

    private val dao get() = db.syncDomainDao()

    override suspend fun readAll(localProfileId: String?): List<DomainRow> {
        val language = currentLanguage()
        return dao.allAlbumAbout().map { row ->
            DomainRow(key = keyOf(language, row), projection = projection(language, row), rowTs = row.updatedAt)
        }
    }

    override fun project(payload: JsonObject): JsonObject? {
        val language = payload.requiredString(LANGUAGE) ?: return null
        return entryOf(payload)?.let { projection(language, it) }
    }

    override suspend fun apply(
        localProfileId: String?,
        key: String,
        payload: JsonObject,
        versionTs: Long,
        expectedLocalHash: String?,
    ): ApplyOutcome = db.withTransaction {
        val language = payload.requiredString(LANGUAGE)
        val entry = entryOf(payload)
        if (language == null || entry == null || key != keyOf(language, entry)) {
            return@withTransaction ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null)
        }
        val local = currentLanguage()
        // Another language's About stays in Drive for the devices reading it; retried after a switch.
        if (language != local) return@withTransaction ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, null)
        val existing = dao.albumAbout(entry.provider, entry.albumId, entry.kind, entry.entryKey)
        val currentHash = existing?.let { projection(local, it) }.hashOrNull()
        if (currentHash != expectedLocalHash) {
            return@withTransaction ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
        }
        dao.upsertAlbumAbout(entry)
        ApplyOutcome.Applied(projection(local, entry).hashOrNull())
    }

    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome =
        ApplyOutcome.Skipped(SkipReason.POLICY, expectedLocalHash)

    private suspend fun currentLanguage(): String =
        GeminiConfig.normalizeTargetLanguage(dao.geminiConfig()?.targetLanguage)

    private fun keyOf(language: String, row: AlbumAboutEntry) =
        SyncFormat.compositeKey(language, row.provider, row.albumId, row.kind, row.entryKey)

    private fun projection(language: String, row: AlbumAboutEntry) = JsonObject(
        mapOf(
            LANGUAGE to jsonString(language),
            "provider" to jsonString(row.provider),
            "albumId" to jsonString(row.albumId),
            "albumDisplay" to jsonString(row.albumDisplay),
            "artistDisplay" to jsonString(row.artistDisplay),
            "kind" to jsonString(row.kind),
            "entryKey" to jsonString(row.entryKey),
            "promptText" to jsonString(row.promptText),
            "titleText" to jsonString(row.titleText),
            "answerText" to jsonString(row.answerText),
            "createdAt" to jsonLong(row.createdAt),
            "updatedAt" to jsonLong(row.updatedAt),
        ),
    )

    private fun entryOf(payload: JsonObject): AlbumAboutEntry? = AlbumAboutEntry(
        provider = payload.requiredString("provider") ?: return null,
        albumId = payload.requiredString("albumId") ?: return null,
        albumDisplay = payload.requiredString("albumDisplay") ?: return null,
        artistDisplay = payload.requiredString("artistDisplay") ?: return null,
        kind = payload.requiredString("kind") ?: return null,
        entryKey = payload.requiredString("entryKey") ?: return null,
        promptText = payload.optionalString("promptText").orElse { return null },
        titleText = payload.optionalString("titleText").orElse { return null },
        answerText = payload.requiredString("answerText") ?: return null,
        createdAt = payload.requiredLong("createdAt") ?: return null,
        updatedAt = payload.requiredLong("updatedAt") ?: return null,
    )

    private companion object {
        const val LANGUAGE = "targetLanguage"
    }
}
