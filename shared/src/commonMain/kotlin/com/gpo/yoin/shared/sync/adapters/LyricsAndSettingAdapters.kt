package com.gpo.yoin.shared.sync.adapters

import com.gpo.yoin.shared.data.lyrics.LrcParser
import com.gpo.yoin.shared.data.lyrics.LyricsTranslationIdentity
import com.gpo.yoin.shared.sync.ApplyOutcome
import com.gpo.yoin.shared.sync.ConflictPolicy
import com.gpo.yoin.shared.sync.DomainRow
import com.gpo.yoin.shared.sync.FileClass
import com.gpo.yoin.shared.sync.SkipReason
import com.gpo.yoin.shared.sync.SyncAdapter
import com.gpo.yoin.shared.sync.SyncFormat
import com.gpo.yoin.shared.sync.SyncKinds
import com.gpo.yoin.shared.sync.SyncSettingKeys
import com.gpo.yoin.shared.sync.syncNowMillis
import kotlinx.serialization.json.JsonObject

/*
 * Ports of Android's LyricsTranslationSyncAdapter and SettingsSyncAdapter
 * (app/src/main/java/com/gpo/yoin/data/sync/adapters/). Same keys, projections,
 * write-once / explicit-only rules and expected-hash guards.
 */

/**
 * Paid (Gemini) rows of `lyrics_translation_cache` <-> [SyncKinds.LYRICS_TRANSLATION],
 * key = trackProvider␟trackRawId␟sourceHash␟targetLanguage␟model. Free `provider:*` rows never sync.
 *
 * Write-once union: an existing row is never overwritten. The payload may carry a snapshot of the lyrics
 * the translation was made from ([LYRICS_FIELD]: lyricsProvider, lyricsProviderSongId, lrc), outside the
 * projection; [readAll] collects it, [decoratePayload] stamps it on, and a received one seeds the lyrics
 * cache only where nothing better is there (Android's rules, see [snapshotOf] / [seedLyrics]).
 */
class LyricsTranslationSyncAdapter(
    private val domain: SyncDomain,
    private val clock: () -> Long = ::syncNowMillis,
) : SyncAdapter {
    override val kind = SyncKinds.LYRICS_TRANSLATION
    override val kindVersion = 1
    override val fileClass = FileClass.ARTIFACTS
    override val perAccount = false
    override val propagatesDeletes = false
    override val conflictPolicy = ConflictPolicy.LWW

    /** Lyrics snapshots found by the last [readAll], by record key, for [decoratePayload]. */
    private val snapshots = HashMap<String, JsonObject>()

    override suspend fun readAll(localProfileId: String?): List<DomainRow> {
        val rows = domain.paidLyricsTranslations().filterNot { LyricsTranslationIdentity.isProviderModel(it.model) }
        val lyricsByTrack = HashMap<Pair<String, String>, LyricsCacheRow?>()
        val found = HashMap<String, JsonObject>()
        val result = rows.map { row ->
            val fields = TranslationFields.of(row)
            val lyrics = lyricsByTrack.getOrPut(row.trackProvider to row.trackRawId) {
                domain.lyricsCache(row.trackProvider, row.trackRawId)
            }
            snapshotOf(row, lyrics)?.let { found[fields.key] = it }
            DomainRow(key = fields.key, projection = fields.projection(), rowTs = row.cachedAt)
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
    ): ApplyOutcome {
        val parts = splitKey(key, 5) ?: return ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null)
        val existing = domain.lyricsTranslation(parts[0], parts[1], parts[2], parts[3], parts[4])
        val currentHash = existing?.let { TranslationFields.of(it).projection() }.hashOrNull()
        val fields = TranslationFields.parse(payload)
        if (fields == null || fields.key != key || LyricsTranslationIdentity.isProviderModel(fields.model)) {
            return ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, currentHash)
        }
        val now = clock()
        if (existing != null) {
            // Write-once: an identical row only gets its lyrics seeded; a different one is kept as is.
            if (currentHash != fields.projection().hashOrNull()) {
                val reason = if (currentHash != expectedLocalHash) SkipReason.CHANGED_LOCALLY else SkipReason.POLICY
                return ApplyOutcome.Skipped(reason, currentHash)
            }
            seedLyrics(fields, payload, now)
            return ApplyOutcome.Applied(currentHash)
        }
        if (expectedLocalHash != null) return ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, null)
        seedLyrics(fields, payload, now)
        domain.insertLyricsTranslationIfAbsent(fields.toRow(cachedAt = now)).orFailWrite("insertLyricsTranslation")
        return ApplyOutcome.Applied(
            domain.lyricsTranslation(parts[0], parts[1], parts[2], parts[3], parts[4])
                ?.let { TranslationFields.of(it).projection() }
                .hashOrNull(),
        )
    }

    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome {
        val parts = splitKey(key, 5) ?: return ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null)
        val current = domain.lyricsTranslation(parts[0], parts[1], parts[2], parts[3], parts[4])
            ?: return ApplyOutcome.Applied(null)
        val currentHash = TranslationFields.of(current).projection().hashOrNull()
        if (currentHash != expectedLocalHash) return ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
        domain.deleteLyricsTranslation(parts[0], parts[1], parts[2], parts[3], parts[4])
            .orFailWrite("deleteLyricsTranslation")
        return ApplyOutcome.Applied(null)
    }

    /**
     * Android seedLyrics: seeds the lyrics cache from a received snapshot when the track has none, or only an
     * automatic row past its 30 days. Never replaces lyrics the user chose or typed; a `user|` marker is kept.
     * A seed that doesn't persist fails the record (Android: the transaction rolls back), so it is retried.
     */
    private fun seedLyrics(fields: TranslationFields, payload: JsonObject, now: Long) {
        val snapshot = payload[LYRICS_FIELD] as? JsonObject ?: return
        val lyricsProvider = snapshot.requiredString("lyricsProvider")
            ?.takeIf { it.isNotEmpty() && it != MANUAL_LYRICS_PROVIDER }
            ?: return
        val lyricsProviderSongId = snapshot.optionalString("lyricsProviderSongId").orElse { return }
        val lrc = snapshot.requiredString("lrc")?.takeIf(String::isNotBlank) ?: return
        val translationCount = translationCount(fields.translationsJson) ?: return
        if (sourceLineCount(lrc) != translationCount) return
        val existing = domain.lyricsCache(fields.trackProvider, fields.trackRawId)
        if (existing != null && isUsable(existing, now)) return
        domain.writeLyricsCache(
            LyricsCacheRow(fields.trackProvider, fields.trackRawId, lyricsProvider, lyricsProviderSongId, lrc, now),
        ).orFailWrite("writeLyricsCache")
    }

    private fun snapshotOf(row: LyricsTranslationRow, lyrics: LyricsCacheRow?): JsonObject? {
        if (lyrics == null || lyrics.lyricsProvider == MANUAL_LYRICS_PROVIDER) return null
        // An automatic row's cachedAt is when it was fetched: newer than the translation means the lyrics changed
        // since. A user-chosen row's cachedAt is "last used", so only the line-count check vouches for it.
        if (!isUserChosen(lyrics) && lyrics.cachedAt > row.cachedAt) return null
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

        fun toRow(cachedAt: Long) = LyricsTranslationRow(
            trackProvider, trackRawId, sourceHash, targetLanguage, model, translationsJson, cachedAt,
        )

        companion object {
            fun of(row: LyricsTranslationRow) = TranslationFields(
                row.trackProvider, row.trackRawId, row.sourceHash, row.targetLanguage, row.model, row.translationsJson,
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

    companion object {
        /** Payload field holding the optional lyrics snapshot. */
        const val LYRICS_FIELD = "lyrics"

        /** Android LyricsCachePolicy.MANUAL_PROVIDER / USER_CHOSEN_SONG_ID_PREFIX / AUTOMATIC_TTL_MS. */
        const val MANUAL_LYRICS_PROVIDER = "manual"
        const val USER_CHOSEN_SONG_ID_PREFIX = "user|"
        const val AUTOMATIC_TTL_MS: Long = 30L * 24L * 60L * 60L * 1000L

        fun keyOf(
            trackProvider: String,
            trackRawId: String,
            sourceHash: String,
            targetLanguage: String,
            model: String,
        ): String = SyncFormat.compositeKey(trackProvider, trackRawId, sourceHash, targetLanguage, model)

        /** Android LyricsCachePolicy.isUserChosen. */
        fun isUserChosen(row: LyricsCacheRow): Boolean =
            row.lyricsProvider == MANUAL_LYRICS_PROVIDER ||
                row.lyricsProviderSongId?.startsWith(USER_CHOSEN_SONG_ID_PREFIX) == true

        /** Android LyricsCachePolicy.isUsable: a user-chosen row at any age, an automatic one within 30 days. */
        fun isUsable(row: LyricsCacheRow, now: Long): Boolean =
            isUserChosen(row) || row.cachedAt >= now - AUTOMATIC_TTL_MS

        /** Lines Android translates from [lrc]: the parser's lines, trimmed, blanks dropped. */
        fun sourceLineCount(lrc: String): Int = LrcParser.parse(lrc).lines.count { it.text.trim().isNotEmpty() }

        fun translationCount(translationsJson: String): Int? =
            LyricsTranslationIdentity.decodeTranslations(translationsJson)?.size
    }
}

/**
 * Global app settings as [SyncKinds.SETTING] records, payload `{value}`. iOS has
 * [SyncSettingKeys.TRANSLATION_LANGUAGE] and [SyncSettingKeys.SPOTIFY_CLIENT_ID] (bootstrap-only, see
 * [applyClientId]) but no Scroll edge style, so `seam_top_style` records are declined as UNSUPPORTED and
 * carried unchanged.
 *
 * Android's rules: a setting is captured only once the user set it (a default is not an opinion); once sync
 * tracks it ([tracked]) the default is an explicit choice too. The language applies only while there are no
 * About / Ask rows (they are language-specific), field-level (the API key is never touched); an unknown value
 * is declined, never coerced. Declines return POLICY with the current hash. Deletes never propagate.
 */
class SettingsSyncAdapter(
    private val domain: SyncDomain,
    /** Whether sync already tracks a local value for a setting key (its local state has a hash). */
    private val tracked: suspend (key: String) -> Boolean = { false },
) : SyncAdapter {
    override val kind = SyncKinds.SETTING
    override val kindVersion = 1
    override val fileClass = FileClass.STATE
    override val perAccount = false
    override val propagatesDeletes = false
    override val conflictPolicy = ConflictPolicy.LWW

    override suspend fun readAll(localProfileId: String?): List<DomainRow> = buildList {
        languageValue(tracked(SyncSettingKeys.TRANSLATION_LANGUAGE))
            ?.let { add(DomainRow(SyncSettingKeys.TRANSLATION_LANGUAGE, projection(it), null)) }
        clientIdValue(tracked(SyncSettingKeys.SPOTIFY_CLIENT_ID))
            ?.let { add(DomainRow(SyncSettingKeys.SPOTIFY_CLIENT_ID, projection(it), null)) }
    }

    override fun project(payload: JsonObject): JsonObject? = payload.requiredString("value")?.let(::projection)

    override suspend fun apply(
        localProfileId: String?,
        key: String,
        payload: JsonObject,
        versionTs: Long,
        expectedLocalHash: String?,
    ): ApplyOutcome = when (key) {
        SyncSettingKeys.TRANSLATION_LANGUAGE ->
            applyLanguage(payload.requiredString("value"), expectedLocalHash, tracked = expectedLocalHash != null)
        SyncSettingKeys.SPOTIFY_CLIENT_ID ->
            applyClientId(payload.requiredString("value"), expectedLocalHash, tracked = expectedLocalHash != null)
        else -> ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null)
    }

    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome =
        ApplyOutcome.Skipped(
            SkipReason.POLICY,
            when (key) {
                SyncSettingKeys.TRANSLATION_LANGUAGE -> languageValue(tracked = expectedLocalHash != null)
                SyncSettingKeys.SPOTIFY_CLIENT_ID -> clientIdValue(tracked = expectedLocalHash != null)
                else -> null
            }?.let(::projection).hashOrNull(),
        )

    private fun applyLanguage(payloadValue: String?, expectedLocalHash: String?, tracked: Boolean): ApplyOutcome {
        val currentHash = languageValue(tracked)?.let(::projection).hashOrNull()
        val value = payloadValue ?: return ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, currentHash)
        if (currentHash != expectedLocalHash) return ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
        if (value !in SUPPORTED_TARGET_LANGUAGES) return ApplyOutcome.Skipped(SkipReason.POLICY, currentHash)
        if (domain.songAboutEntryCount() > 0) return ApplyOutcome.Skipped(SkipReason.POLICY, currentHash)
        domain.putTranslationLanguage(value).orFailWrite("putTranslationLanguage")
        return ApplyOutcome.Applied(languageValue(tracked)?.let(::projection).hashOrNull())
    }

    /**
     * Android applyClientId: bootstrap-only. A Client ID arrives only on a device with no override of
     * its own (not even one cleared since sync tracked it) and no Spotify account yet.
     */
    private fun applyClientId(payloadValue: String?, expectedLocalHash: String?, tracked: Boolean): ApplyOutcome {
        val currentHash = clientIdValue(tracked)?.let(::projection).hashOrNull()
        val value = payloadValue ?: return ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, currentHash)
        if (currentHash != expectedLocalHash) return ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
        if (value.isBlank()) return ApplyOutcome.Skipped(SkipReason.POLICY, currentHash)
        if (currentHash != null) return ApplyOutcome.Skipped(SkipReason.POLICY, currentHash)
        if (domain.spotifyProfileCount() > 0) return ApplyOutcome.Skipped(SkipReason.POLICY, currentHash)
        domain.putSpotifyClientId(value).orFailWrite("putSpotifyClientId")
        return ApplyOutcome.Applied(clientIdValue(tracked)?.let(::projection).hashOrNull())
    }

    /** The override set here; "" once sync tracks the setting and it was cleared (Android clientIdValue). */
    private fun clientIdValue(tracked: Boolean): String? =
        domain.spotifyClientId()?.takeIf(String::isNotBlank) ?: if (tracked) "" else null

    /** Explicit only: a non-default language, or the default once sync tracks this setting. */
    private fun languageValue(tracked: Boolean): String? {
        val stored = domain.aboutLanguage()
        return when {
            stored != DEFAULT_TARGET_LANGUAGE -> stored
            tracked -> DEFAULT_TARGET_LANGUAGE
            else -> null
        }
    }

    private fun projection(value: String) = JsonObject(mapOf("value" to jsonString(value)))

    companion object {
        /** Android GeminiConfig.DEFAULT_TARGET_LANGUAGE / SUPPORTED_TARGET_LANGUAGES. */
        const val DEFAULT_TARGET_LANGUAGE = "English"
        val SUPPORTED_TARGET_LANGUAGES: List<String> = listOf(
            "English",
            "Simplified Chinese",
            "Traditional Chinese",
            "Japanese",
            "Korean",
            "Spanish",
            "French",
            "German",
        )
    }
}
