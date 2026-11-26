package com.gpo.yoin.shared.sync.adapters

import com.gpo.yoin.shared.sync.ApplyOutcome
import com.gpo.yoin.shared.sync.ConflictPolicy
import com.gpo.yoin.shared.sync.DomainRow
import com.gpo.yoin.shared.sync.FileClass
import com.gpo.yoin.shared.sync.SkipReason
import com.gpo.yoin.shared.sync.SyncAdapter
import com.gpo.yoin.shared.sync.SyncFormat
import com.gpo.yoin.shared.sync.SyncKinds
import kotlinx.serialization.json.JsonObject

/**
 * `album_about_entries` <-> [SyncKinds.ALBUM_ABOUT] (global): a port of Android's AlbumAboutSyncAdapter (PR #326),
 * field for field, so the records Android and iOS write are the same. The same rules as [SongAboutSyncAdapter]:
 * keyed by the album's provider + id rather than by account; every record stamped with the AI language it was
 * written in (part of the key) and applied only by devices reading that language; deletes never propagate (a
 * language switch clears or hides rows locally, Drive keeps them for their language); last write wins. There are
 * no listener-written rows here, so no record is filed under any language.
 */
class AlbumAboutSyncAdapter(private val domain: SyncDomain) : SyncAdapter {
    override val kind = SyncKinds.ALBUM_ABOUT
    override val kindVersion = 1
    override val fileClass = FileClass.ARTIFACTS
    override val perAccount = false
    override val propagatesDeletes = false
    override val conflictPolicy = ConflictPolicy.LWW

    override suspend fun readAll(localProfileId: String?): List<DomainRow> {
        val language = domain.aboutLanguage()
        return domain.albumAbouts().map { row ->
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
    ): ApplyOutcome {
        val language = payload.requiredString(LANGUAGE)
        val entry = entryOf(payload)
        if (language == null || entry == null || key != keyOf(language, entry)) {
            return ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null)
        }
        val local = domain.aboutLanguage()
        // Another language's About stays in Drive for the devices reading it; retried after a switch.
        if (language != local) return ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, null)
        val existing = domain.albumAbout(entry.provider, entry.albumId, entry.kind, entry.entryKey)
        val currentHash = existing?.let { projection(local, it) }.hashOrNull()
        if (currentHash != expectedLocalHash) return ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
        domain.putAlbumAbout(entry).orFailWrite("putAlbumAbout")
        return ApplyOutcome.Applied(projection(local, entry).hashOrNull())
    }

    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome =
        ApplyOutcome.Skipped(SkipReason.POLICY, expectedLocalHash)

    private fun keyOf(language: String, row: AlbumAboutEntryRow) =
        SyncFormat.compositeKey(language, row.provider, row.albumId, row.kind, row.entryKey)

    private fun projection(language: String, row: AlbumAboutEntryRow) = JsonObject(
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

    private fun entryOf(payload: JsonObject): AlbumAboutEntryRow? = AlbumAboutEntryRow(
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
