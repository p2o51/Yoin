package com.gpo.yoin.data.sync.adapters

import androidx.room.withTransaction
import com.gpo.yoin.data.local.LyricsCache
import com.gpo.yoin.data.local.LyricsTranslationCache
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.lyrics.LrcParser
import com.gpo.yoin.data.lyrics.LyricsCachePolicy
import com.gpo.yoin.data.model.Lyrics
import com.gpo.yoin.data.sync.ApplyOutcome
import com.gpo.yoin.data.sync.ConflictPolicy
import com.gpo.yoin.data.sync.DomainRow
import com.gpo.yoin.data.sync.FileClass
import com.gpo.yoin.data.sync.SkipReason
import com.gpo.yoin.data.sync.SyncAdapter
import com.gpo.yoin.data.sync.SyncFormat
import com.gpo.yoin.data.sync.SyncKinds
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Paid (Gemini) rows of `lyrics_translation_cache` <-> [SyncKinds.LYRICS_TRANSLATION],
 * key = trackProvider␟trackRawId␟sourceHash␟targetLanguage␟model. Free
 * `provider:*` rows are re-fetchable and never sync.
 *
 * Write-once union: the key pins the content, an existing row is never
 * overwritten. A translation is only usable where the exact source lines load
 * again, so the payload may carry a snapshot of the lyrics it was made from
 * ([LYRICS_FIELD]: lyricsProvider, lyricsProviderSongId, lrc). The snapshot is
 * NOT part of the projection (its hash never decides "changed"); readAll
 * collects it and [decoratePayload] stamps it onto the payload this device
 * writes. It is attached only when it provably matches (cached before the
 * translation, same line count), never from a hand-edited ("manual") row, and
 * a received snapshot seeds `lyrics_cache` only where nothing better is there.
 */
class LyricsTranslationSyncAdapter(
    private val db: YoinDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : SyncAdapter {
    override val kind = SyncKinds.LYRICS_TRANSLATION
    override val kindVersion = 1
    override val fileClass = FileClass.ARTIFACTS
    override val perAccount = false
    override val propagatesDeletes = false
    override val conflictPolicy = ConflictPolicy.LWW

    private val dao get() = db.syncDomainDao()

    /** Lyrics snapshots found by the last [readAll], by record key, for [decoratePayload]. */
    private val snapshots = ConcurrentHashMap<String, JsonObject>()

    override suspend fun readAll(localProfileId: String?): List<DomainRow> {
        val rows = dao.paidTranslations().filterNot { it.model.startsWith(PROVIDER_MODEL_PREFIX) }
        val lyricsByTrack = HashMap<Pair<String, String>, LyricsCache?>()
        val found = HashMap<String, JsonObject>()
        val result = rows.map { row ->
            val key = keyOf(row.trackProvider, row.trackRawId, row.sourceHash, row.targetLanguage, row.model)
            val lyrics = lyricsByTrack.getOrPut(row.trackProvider to row.trackRawId) {
                dao.lyricsCache(row.trackProvider, row.trackRawId)
            }
            snapshotOf(row, lyrics)?.let { found[key] = it }
            DomainRow(key = key, projection = TranslationFields.of(row).projection(), rowTs = row.cachedAt)
        }
        snapshots.keys.retainAll(found.keys)
        snapshots.putAll(found)
        return result
    }

    override fun project(payload: JsonObject): JsonObject? = TranslationFields.parse(payload)?.projection()

    override fun decoratePayload(payload: JsonObject, deviceName: String): JsonObject {
        if (payload.containsKey(LYRICS_FIELD)) return payload
        val key = TranslationFields.parse(payload)?.key ?: return payload
        val snapshot = snapshots[key] ?: return payload
        return payload.with(LYRICS_FIELD, snapshot)
    }

    override suspend fun apply(
        localProfileId: String?,
        key: String,
        payload: JsonObject,
        versionTs: Long,
        expectedLocalHash: String?,
    ): ApplyOutcome = db.withTransaction {
        val parts = splitKey(key, 5)
            ?: return@withTransaction ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null)
        val existing = dao.translation(parts[0], parts[1], parts[2], parts[3], parts[4])
        val currentHash = existing?.let { TranslationFields.of(it).projection() }.hashOrNull()
        val fields = TranslationFields.parse(payload)
        if (fields == null || fields.key != key || fields.model.startsWith(PROVIDER_MODEL_PREFIX)) {
            return@withTransaction ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, currentHash)
        }
        val now = clock()
        if (existing != null) {
            // Write-once: an identical row only gets its lyrics seeded; a different one is kept as is.
            if (currentHash != fields.projection().hashOrNull()) {
                val reason = if (currentHash != expectedLocalHash) SkipReason.CHANGED_LOCALLY else SkipReason.POLICY
                return@withTransaction ApplyOutcome.Skipped(reason, currentHash)
            }
            seedLyrics(fields, payload, now)
            return@withTransaction ApplyOutcome.Applied(currentHash)
        }
        if (expectedLocalHash != null) {
            return@withTransaction ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, null)
        }
        seedLyrics(fields, payload, now)
        dao.insertTranslationIfAbsent(fields.toEntity(cachedAt = now))
        // fields.key == key, so the key parts name the row just inserted.
        ApplyOutcome.Applied(
            dao.translation(parts[0], parts[1], parts[2], parts[3], parts[4])
                ?.let { TranslationFields.of(it).projection() }
                .hashOrNull(),
        )
    }

    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome =
        db.withTransaction {
            val parts = splitKey(key, 5)
                ?: return@withTransaction ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null)
            val current = dao.translation(parts[0], parts[1], parts[2], parts[3], parts[4])
                ?: return@withTransaction ApplyOutcome.Applied(null)
            val currentHash = TranslationFields.of(current).projection().hashOrNull()
            if (currentHash != expectedLocalHash) {
                return@withTransaction ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
            }
            dao.deleteTranslation(parts[0], parts[1], parts[2], parts[3], parts[4])
            ApplyOutcome.Applied(null)
        }

    /**
     * Seeds `lyrics_cache` from a received snapshot when the track has no row,
     * or only an automatic one that the repository already treats as expired
     * ([LyricsCachePolicy.isUsable]). Never replaces a row the user chose (manual
     * or `user|`-marked, any age); the seeded row counts as fresh. A `user|`
     * marker in the snapshot is kept, so the user's choice follows them.
     */
    private suspend fun seedLyrics(fields: TranslationFields, payload: JsonObject, now: Long) {
        val snapshot = payload[LYRICS_FIELD] as? JsonObject ?: return
        val lyricsProvider = snapshot.requiredString("lyricsProvider")
            ?.takeIf { it.isNotEmpty() && it != MANUAL_LYRICS_PROVIDER }
            ?: return
        val lyricsProviderSongId = snapshot.optionalString("lyricsProviderSongId").orElse { return }
        val lrc = snapshot.requiredString("lrc")?.takeIf(String::isNotBlank) ?: return
        val translationCount = translationCount(fields.translationsJson) ?: return
        if (sourceLineCount(lrc) != translationCount) return
        val existing = dao.lyricsCache(fields.trackProvider, fields.trackRawId)
        val replaceable = existing == null ||
            !LyricsCachePolicy.isUsable(existing, now)
        if (!replaceable) return
        dao.writeLyricsCache(
            LyricsCache(
                trackProvider = fields.trackProvider,
                trackRawId = fields.trackRawId,
                lyricsProvider = lyricsProvider,
                lyricsProviderSongId = lyricsProviderSongId,
                lrc = lrc,
                cachedAt = now,
            ),
        )
    }

    private fun snapshotOf(row: LyricsTranslationCache, lyrics: LyricsCache?): JsonObject? {
        if (lyrics == null || lyrics.lyricsProvider == MANUAL_LYRICS_PROVIDER) return null
        // An automatic row's cachedAt is when it was fetched: newer than the translation means the
        // lyrics changed since. A user-chosen row's cachedAt is "last used" and moves on reads, so
        // only the line-count check below vouches for it.
        if (!LyricsCachePolicy.isUserChosen(lyrics) && lyrics.cachedAt > row.cachedAt) return null
        val translationCount = translationCount(row.translationsJson) ?: return null
        if (sourceLineCount(lyrics.lrc) != translationCount) return null
        return JsonObject(
            mapOf(
                "lyricsProvider" to jsonString(lyrics.lyricsProvider),
                "lyricsProviderSongId" to jsonString(lyrics.lyricsProviderSongId),
                "lrc" to jsonString(lyrics.lrc),
            ),
        )
    }

    private data class TranslationFields(
        val trackProvider: String,
        val trackRawId: String,
        val sourceHash: String,
        val targetLanguage: String,
        val model: String,
        val translationsJson: String,
    ) {
        val key: String get() = keyOf(trackProvider, trackRawId, sourceHash, targetLanguage, model)

        fun projection() = JsonObject(
            mapOf(
                "trackProvider" to jsonString(trackProvider),
                "trackRawId" to jsonString(trackRawId),
                "sourceHash" to jsonString(sourceHash),
                "targetLanguage" to jsonString(targetLanguage),
                "model" to jsonString(model),
                "translationsJson" to jsonString(translationsJson),
            ),
        )

        fun toEntity(cachedAt: Long) = LyricsTranslationCache(
            trackProvider = trackProvider,
            trackRawId = trackRawId,
            sourceHash = sourceHash,
            targetLanguage = targetLanguage,
            model = model,
            translationsJson = translationsJson,
            cachedAt = cachedAt,
        )

        companion object {
            fun of(row: LyricsTranslationCache) = TranslationFields(
                trackProvider = row.trackProvider,
                trackRawId = row.trackRawId,
                sourceHash = row.sourceHash,
                targetLanguage = row.targetLanguage,
                model = row.model,
                translationsJson = row.translationsJson,
            )

            fun parse(payload: JsonObject): TranslationFields? = TranslationFields(
                trackProvider = payload.requiredString("trackProvider")?.takeIf(String::isNotEmpty) ?: return null,
                trackRawId = payload.requiredString("trackRawId")?.takeIf(String::isNotEmpty) ?: return null,
                sourceHash = payload.requiredString("sourceHash")?.takeIf(String::isNotEmpty) ?: return null,
                targetLanguage = payload.requiredString("targetLanguage")?.takeIf(String::isNotEmpty) ?: return null,
                model = payload.requiredString("model")?.takeIf(String::isNotEmpty) ?: return null,
                translationsJson = payload.requiredString("translationsJson") ?: return null,
            )
        }
    }

    internal companion object {
        /** Payload field holding the optional lyrics snapshot. */
        const val LYRICS_FIELD = "lyrics"

        const val PROVIDER_MODEL_PREFIX = "provider:"
        const val MANUAL_LYRICS_PROVIDER = "manual"

        /** Same freshness window as the repository's lyrics cache reads. */
        const val LYRICS_CACHE_TTL_MS: Long = LyricsCachePolicy.AUTOMATIC_TTL_MS

        fun keyOf(
            trackProvider: String,
            trackRawId: String,
            sourceHash: String,
            targetLanguage: String,
            model: String,
        ): String = SyncFormat.compositeKey(trackProvider, trackRawId, sourceHash, targetLanguage, model)

        /** Lines the repository translates from [lrc]: LrcParser's lines, trimmed, blanks dropped. */
        fun sourceLineCount(lrc: String): Int = when (val parsed = LrcParser.parse(lrc)) {
            is Lyrics.Synced -> parsed.lines.count { it.text.trim().isNotEmpty() }
            is Lyrics.Unsynced -> parsed.text.lineSequence().count { it.trim().isNotEmpty() }
        }

        fun translationCount(translationsJson: String): Int? =
            runCatching { Json.parseToJsonElement(translationsJson) as? JsonArray }.getOrNull()?.size
    }
}
