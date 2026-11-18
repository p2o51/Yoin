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

/*
 * Ports of Android's SongNoteSyncAdapter, AlbumTitleSyncAdapter,
 * MemoryCopySyncAdapter / MemoryTitleSyncAdapter and SongAboutSyncAdapter.
 */

/**
 * `song_notes` <-> [SyncKinds.SONG_NOTE]. Key = note id (globally unique): a note id that already lives
 * under another local profile is never re-parented ([SkipReason.OWNED_ELSEWHERE]). `updatedAt` is a synced
 * field, so apply writes the payload's value, keeping the read-back projection identical to the payload's.
 */
class SongNoteSyncAdapter(private val domain: SyncDomain) : SyncAdapter {
    override val kind = SyncKinds.SONG_NOTE
    override val kindVersion = 1
    override val fileClass = FileClass.STATE
    override val perAccount = true
    override val propagatesDeletes = true
    override val conflictPolicy = ConflictPolicy.LWW_LIVE_WINS

    override suspend fun readAll(localProfileId: String?): List<DomainRow> {
        val profileId = requireNotNull(localProfileId) { "song_note is per-account" }
        return domain.notes(profileId).map { note ->
            DomainRow(key = note.id, projection = projection(note), rowTs = note.updatedAt)
        }
    }

    override fun project(payload: JsonObject): JsonObject? = parse(payload, id = "", profileId = "")?.let(::projection)

    override suspend fun apply(
        localProfileId: String?,
        key: String,
        payload: JsonObject,
        versionTs: Long,
        expectedLocalHash: String?,
    ): ApplyOutcome {
        val profileId = requireNotNull(localProfileId) { "song_note is per-account" }
        val existing = domain.note(key)
        if (existing != null && existing.profileId != profileId) {
            return ApplyOutcome.Skipped(SkipReason.OWNED_ELSEWHERE, null)
        }
        val currentHash = existing?.let(::projection).hashOrNull()
        val row = parse(payload, id = key, profileId = profileId)
            ?: return ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, currentHash)
        if (currentHash != expectedLocalHash) return ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
        domain.putNote(row).orFailWrite("putNote")
        return ApplyOutcome.Applied(domain.note(key)?.let(::projection).hashOrNull())
    }

    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome {
        val profileId = requireNotNull(localProfileId) { "song_note is per-account" }
        val current = domain.note(key)?.takeIf { it.profileId == profileId } ?: return ApplyOutcome.Applied(null)
        val currentHash = projection(current).hashOrNull()
        if (currentHash != expectedLocalHash) return ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
        domain.deleteNote(key, profileId).orFailWrite("deleteNote")
        return ApplyOutcome.Applied(null)
    }

    private fun projection(note: SongNoteRow) = JsonObject(
        mapOf(
            "trackId" to jsonString(note.trackId),
            "provider" to jsonString(note.provider),
            "content" to jsonString(note.content),
            "createdAt" to jsonLong(note.createdAt),
            "updatedAt" to jsonLong(note.updatedAt),
            "title" to jsonString(note.title),
            "artist" to jsonString(note.artist),
            "positionMs" to jsonLong(note.positionMs),
        ),
    )

    private fun parse(payload: JsonObject, id: String, profileId: String): SongNoteRow? {
        val positionMs = payload.optionalLong("positionMs").orElse { return null }
        return SongNoteRow(
            id = id,
            profileId = profileId,
            trackId = payload.requiredString("trackId")?.takeIf(String::isNotEmpty) ?: return null,
            provider = payload.requiredString("provider")?.takeIf(String::isNotEmpty) ?: return null,
            content = payload.requiredString("content") ?: return null,
            createdAt = payload.requiredLong("createdAt") ?: return null,
            updatedAt = payload.requiredLong("updatedAt") ?: return null,
            title = payload.requiredString("title") ?: return null,
            artist = payload.requiredString("artist") ?: return null,
            positionMs = positionMs,
        )
    }
}

/**
 * `album_memory_titles` <-> [SyncKinds.ALBUM_TITLE]: the title the user gave an album's Memory. A missing row
 * means "use Yoin's title", so deletes propagate as tombstones.
 */
class AlbumTitleSyncAdapter(private val domain: SyncDomain) : SyncAdapter {
    override val kind = SyncKinds.ALBUM_TITLE
    override val kindVersion = 1
    override val fileClass = FileClass.STATE
    override val perAccount = true
    override val propagatesDeletes = true
    override val conflictPolicy = ConflictPolicy.LWW

    override suspend fun readAll(localProfileId: String?): List<DomainRow> {
        val profileId = requireNotNull(localProfileId) { "album_title is per-account" }
        return domain.albumTitles(profileId).map { row ->
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
        val currentHash = domain.albumTitle(profileId, provider, albumId)?.let { projection(it.title) }.hashOrNull()
        val title = project(payload)?.requiredString(TITLE)
            ?: return ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, currentHash)
        if (currentHash != expectedLocalHash) return ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
        domain.putAlbumTitle(profileId, AlbumTitleRow(provider, albumId, title, updatedAt = versionTs)).orFailWrite("putAlbumTitle")
        return ApplyOutcome.Applied(projection(title).hashOrNull())
    }

    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome {
        val profileId = requireNotNull(localProfileId) { "album_title is per-account" }
        val (provider, albumId) = albumKeyParts(key) ?: return ApplyOutcome.Applied(null)
        val current = domain.albumTitle(profileId, provider, albumId) ?: return ApplyOutcome.Applied(null)
        val currentHash = projection(current.title).hashOrNull()
        if (currentHash != expectedLocalHash) return ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
        domain.deleteAlbumTitle(profileId, provider, albumId).orFailWrite("deleteAlbumTitle")
        return ApplyOutcome.Applied(null)
    }

    private fun projection(title: String) = JsonObject(mapOf(TITLE to jsonString(title)))

    private companion object {
        const val TITLE = "title"
    }
}

private fun memoryKey(row: MemoryCopyRow) = SyncFormat.compositeKey(row.provider, row.entityType, row.entityId)

private data class MemoryKey(val provider: String, val entityType: String, val entityId: String)

private fun memoryKeyParts(key: String): MemoryKey? =
    splitKey(key, 3)?.let { (provider, entityType, entityId) -> MemoryKey(provider, entityType, entityId) }

/** The Gemini mood line of a Memory (`memory_copy_cache.copy` + promptHash + generatedAt). Never deleted. */
class MemoryCopySyncAdapter(private val domain: SyncDomain) : SyncAdapter {
    override val kind = SyncKinds.MEMORY_COPY
    override val kindVersion = 1
    override val fileClass = FileClass.STATE
    override val perAccount = true
    override val propagatesDeletes = false
    override val conflictPolicy = ConflictPolicy.LWW

    override suspend fun readAll(localProfileId: String?): List<DomainRow> {
        val profileId = requireNotNull(localProfileId) { "memory_copy is per-account" }
        return domain.memoryCopies(profileId)
            .filter { it.copyText.isNotBlank() }
            .map {
                DomainRow(key = memoryKey(it), projection = projection(it.copyText, it.promptHash, it.generatedAt), rowTs = it.generatedAt)
            }
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
        val existing = domain.memoryCopy(profileId, parts.provider, parts.entityType, parts.entityId)
        val currentHash = existing?.takeIf { it.copyText.isNotBlank() }
            ?.let { projection(it.copyText, it.promptHash, it.generatedAt) }.hashOrNull()
        val incoming = project(payload) ?: return ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, currentHash)
        if (currentHash != expectedLocalHash) return ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
        val copy = incoming.requiredString(COPY)!!
        val promptHash = incoming.requiredString(PROMPT_HASH)!!
        val generatedAt = incoming.requiredLong(GENERATED_AT)!!
        // Field-level: the title half of the row belongs to memory_title.
        val base = existing ?: MemoryCopyRow(parts.provider, parts.entityType, parts.entityId, "", "", 0L, null, null)
        domain.putMemoryCopy(profileId, base.copy(copyText = copy, promptHash = promptHash, generatedAt = generatedAt)).orFailWrite("putMemoryCopy")
        return ApplyOutcome.Applied(projection(copy, promptHash, generatedAt).hashOrNull())
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

/** The AI ("AI 拟题") album title of a Memory (`memory_copy_cache.title` + titlePromptHash). Never deleted. */
class MemoryTitleSyncAdapter(private val domain: SyncDomain) : SyncAdapter {
    override val kind = SyncKinds.MEMORY_TITLE
    override val kindVersion = 1
    override val fileClass = FileClass.STATE
    override val perAccount = true
    override val propagatesDeletes = false
    override val conflictPolicy = ConflictPolicy.LWW

    override suspend fun readAll(localProfileId: String?): List<DomainRow> {
        val profileId = requireNotNull(localProfileId) { "memory_title is per-account" }
        return domain.memoryCopies(profileId).mapNotNull { row ->
            val title = row.title?.takeIf(String::isNotBlank) ?: return@mapNotNull null
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
        val existing = domain.memoryCopy(profileId, parts.provider, parts.entityType, parts.entityId)
        val currentHash = existing?.title?.takeIf(String::isNotBlank)
            ?.let { projection(it, existing.titlePromptHash) }.hashOrNull()
        val incoming = project(payload) ?: return ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, currentHash)
        if (currentHash != expectedLocalHash) return ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
        val title = incoming.requiredString(TITLE)!!
        val titlePromptHash = (incoming.optionalString(TITLE_PROMPT_HASH) as? Field.Ok)?.value
        val base = existing ?: MemoryCopyRow(parts.provider, parts.entityType, parts.entityId, "", "", 0L, null, null)
        domain.putMemoryCopy(profileId, base.copy(title = title, titlePromptHash = titlePromptHash)).orFailWrite("putMemoryCopy")
        return ApplyOutcome.Applied(projection(title, titlePromptHash).hashOrNull())
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

/**
 * `song_about_entries` <-> [SyncKinds.SONG_ABOUT] (global). Every record carries the language it was written
 * in, which is part of the key; a device only applies records in its own language.
 */
class SongAboutSyncAdapter(private val domain: SyncDomain) : SyncAdapter {
    override val kind = SyncKinds.SONG_ABOUT
    override val kindVersion = 1
    override val fileClass = FileClass.ARTIFACTS
    override val perAccount = false
    override val propagatesDeletes = false
    override val conflictPolicy = ConflictPolicy.LWW

    override suspend fun readAll(localProfileId: String?): List<DomainRow> {
        val language = domain.aboutLanguage()
        return domain.songAbouts().map { row ->
            val stamp = stampOf(row, language)
            DomainRow(key = keyOf(stamp, row), projection = projection(stamp, row), rowTs = row.updatedAt)
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
        val local = stampOf(entry, domain.aboutLanguage())
        // Another language's answers stay in Drive; "not now" so they apply after a switch to that language.
        if (language != local) return ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, null)
        val existing = domain.songAbout(entry.titleKey, entry.artistKey, entry.albumKey, entry.kind, entry.entryKey)
        val currentHash = existing?.let { projection(local, it) }.hashOrNull()
        if (currentHash != expectedLocalHash) return ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
        domain.putSongAbout(entry).orFailWrite("putSongAbout")
        return ApplyOutcome.Applied(projection(local, entry).hashOrNull())
    }

    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome =
        ApplyOutcome.Skipped(SkipReason.POLICY, expectedLocalHash)

    /** The language a row is filed under in Drive (Android stampOf): Gemini's rows the device's, the listener's own none. */
    private fun stampOf(row: SongAboutRow, language: String) = if (row.kind == KIND_OWN) ANY_LANGUAGE else language

    private fun keyOf(language: String, row: SongAboutRow) =
        SyncFormat.compositeKey(language, row.titleKey, row.artistKey, row.albumKey, row.kind, row.entryKey)

    private fun projection(language: String, row: SongAboutRow) = JsonObject(
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

    private fun entryOf(payload: JsonObject): SongAboutRow? = SongAboutRow(
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
        /** Android SongAboutEntry.KIND_OWN / ANY_LANGUAGE: the listener's own entries belong to no language. */
        const val KIND_OWN = "own"
        const val ANY_LANGUAGE = "*"
    }
}
