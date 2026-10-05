package com.gpo.yoin.data.sync.adapters

import androidx.room.withTransaction
import com.gpo.yoin.data.local.GeminiConfig
import com.gpo.yoin.data.local.SongAboutEntry
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
 * `song_about_entries` <-> [SyncKinds.SONG_ABOUT]: Gemini's About rows and the
 * user's own Ask Gemini questions with their answers (global, keyed by song
 * metadata like the table itself).
 *
 * The rows carry no language column, and changing the Gemini language wipes
 * the table. So every record is stamped with the language it was written in
 * (the device's current one: older rows were wiped by the last switch) and
 * that language is part of the key; a device only applies records in its own
 * language. Deletes never propagate: the language-switch wipe is local
 * housekeeping, and the records stay in Drive for the language they belong to.
 */
class SongAboutSyncAdapter(private val db: YoinDatabase) : SyncAdapter {
    override val kind = SyncKinds.SONG_ABOUT
    override val kindVersion = 1
    override val fileClass = FileClass.ARTIFACTS
    override val perAccount = false
    override val propagatesDeletes = false
    override val conflictPolicy = ConflictPolicy.LWW

    private val dao get() = db.syncDomainDao()

    override suspend fun readAll(localProfileId: String?): List<DomainRow> {
        val language = currentLanguage()
        return dao.allSongAbout().map { row ->
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
        // Another language's answers stay in Drive for devices reading that language. Reported as
        // "not now" (CHANGED_LOCALLY) rather than POLICY so the engine retries them: after the
        // user switches to that language they apply on the next cycle.
        if (language != local) return@withTransaction ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, null)
        val existing = dao.songAbout(entry.titleKey, entry.artistKey, entry.albumKey, entry.kind, entry.entryKey)
        val currentHash = existing?.let { projection(local, it) }.hashOrNull()
        if (currentHash != expectedLocalHash) {
            return@withTransaction ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
        }
        dao.upsertSongAbout(entry)
        ApplyOutcome.Applied(projection(local, entry).hashOrNull())
    }

    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome =
        ApplyOutcome.Skipped(SkipReason.POLICY, expectedLocalHash)

    private suspend fun currentLanguage(): String =
        GeminiConfig.normalizeTargetLanguage(dao.geminiConfig()?.targetLanguage)

    private fun keyOf(language: String, row: SongAboutEntry) =
        SyncFormat.compositeKey(language, row.titleKey, row.artistKey, row.albumKey, row.kind, row.entryKey)

    private fun projection(language: String, row: SongAboutEntry) = JsonObject(
        mapOf(
            LANGUAGE to jsonString(language),
            "titleKey" to jsonString(row.titleKey),
            "artistKey" to jsonString(row.artistKey),
            "albumKey" to jsonString(row.albumKey),
            "titleDisplay" to jsonString(row.titleDisplay),
            "artistDisplay" to jsonString(row.artistDisplay),
            "albumDisplay" to jsonString(row.albumDisplay),
            "kind" to jsonString(row.kind),
            "entryKey" to jsonString(row.entryKey),
            "promptText" to jsonString(row.promptText),
            "titleText" to jsonString(row.titleText),
            "answerText" to jsonString(row.answerText),
            "createdAt" to jsonLong(row.createdAt),
            "updatedAt" to jsonLong(row.updatedAt),
        ),
    )

    private fun entryOf(payload: JsonObject): SongAboutEntry? = SongAboutEntry(
        titleKey = payload.requiredString("titleKey") ?: return null,
        artistKey = payload.requiredString("artistKey") ?: return null,
        albumKey = payload.requiredString("albumKey") ?: return null,
        titleDisplay = payload.requiredString("titleDisplay") ?: return null,
        artistDisplay = payload.requiredString("artistDisplay") ?: return null,
        albumDisplay = payload.requiredString("albumDisplay") ?: return null,
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
