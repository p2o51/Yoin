package com.gpo.yoin.shared.sync.adapters

import com.gpo.yoin.shared.data.lyrics.LyricsTranslationIdentity
import com.gpo.yoin.shared.sync.ApplyOutcome
import com.gpo.yoin.shared.sync.CanonicalJson
import com.gpo.yoin.shared.sync.InMemorySyncStore
import com.gpo.yoin.shared.sync.SkipReason
import com.gpo.yoin.shared.sync.SyncFormat
import com.gpo.yoin.shared.sync.SyncKinds
import com.gpo.yoin.shared.sync.SyncSettingKeys
import com.gpo.yoin.shared.sync.engine.SyncEngine
import com.gpo.yoin.shared.sync.gunzipBytes
import com.gpo.yoin.shared.sync.testing.FakeSyncDomain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Ports of Android's LyricsTranslationSyncAdapter / SettingsSyncAdapter, checked against Android's shapes. */
class LyricsAndSettingAdaptersTest {
    private val now = 1_793_000_000_000L
    private val model = "gemini-3.1-flash-lite" // Android GeminiService.MODEL
    private val sep = SyncFormat.KEY_SEPARATOR

    // ---- Android's identity

    @Test
    fun should_matchAndroidSourceHash_when_hashingTheSameLyrics() {
        // Values from Android YoinRepository.buildLyricsTranslationSourceHash run on the JVM (MessageDigest).
        assertEquals(
            "7683870ed73d9ab40dd19a98fe5a28a629f7ef22452ef10fb253b285b599a05e",
            LyricsTranslationIdentity.sourceHash(
                "夜に駆ける",
                "YOASOBI",
                listOf("沈むように溶けてゆくように", "Hello 🌙 world", "a:b"),
            ),
        )
        assertEquals(
            "ef8c017a6554372e4b8a86ba47e9e52e476e6a2fed9a9d68632b89c040c602fa",
            LyricsTranslationIdentity.sourceHash("", "", listOf("x")),
        )
        // translateLyrics trims title / artist / lines and drops blank lines before hashing.
        assertEquals(
            LyricsTranslationIdentity.sourceHash("夜に駆ける", "YOASOBI", listOf("沈むように溶けてゆくように", "Hello 🌙 world", "a:b")),
            LyricsTranslationIdentity.geminiSourceHash(
                " 夜に駆ける ",
                "YOASOBI\t",
                listOf("  沈むように溶けてゆくように", " ", "Hello 🌙 world ", "", "a:b"),
            ),
        )
    }

    @Test
    fun should_encodeTranslationsLikeAndroid_when_writingTranslationsJson() {
        val json = LyricsTranslationIdentity.encodeTranslations(listOf("像沉没一样", "", "say \"hi\"\n/ok"))
        assertEquals("""["像沉没一样","","say \"hi\"\n/ok"]""", json)
        assertEquals(listOf("像沉没一样", "", "say \"hi\"\n/ok"), LyricsTranslationIdentity.decodeTranslations(json))
        assertNull(LyricsTranslationIdentity.decodeTranslations("{}"))
    }

    // ---- lyrics_translation

    private fun translation(
        trackRawId: String = "song-1",
        language: String = "Simplified Chinese",
        model: String = this.model,
        translations: List<String> = listOf("沉下去", "融化"),
        cachedAt: Long = now - 1_000,
    ) = LyricsTranslationRow(
        trackProvider = "subsonic",
        trackRawId = trackRawId,
        sourceHash = "h-$trackRawId",
        targetLanguage = language,
        model = model,
        translationsJson = LyricsTranslationIdentity.encodeTranslations(translations),
        cachedAt = cachedAt,
    )

    private val lrc = "[00:01.00]Sink\n[00:02.00]Melt"

    private fun records(engineFiles: List<ByteArray>): List<JsonObject> = engineFiles.flatMap { bytes ->
        Json.parseToJsonElement(gunzipBytes(bytes).decodeToString()).jsonObject["records"]!!.jsonArray
            .map { it.jsonObject }
    }

    @Test
    fun should_publishAndroidShapedRecords_when_capturingTranslationsAndLanguage() = runTest {
        val domain = FakeSyncDomain("Simplified Chinese")
        domain.insertLyricsTranslationIfAbsent(translation())
        domain.insertLyricsTranslationIfAbsent(translation(model = "provider:qq")) // free: never synced
        domain.writeLyricsCache(LyricsCacheRow("subsonic", "song-1", "lrclib", "77", lrc, now - 5_000))
        val store = InMemorySyncStore()
        val engine = SyncEngine(store, SharedSyncAdapters.create(domain, store), "ios0000000000001", { "iPhone" }, {
            now
        })

        engine.capture(emptyList())
        val ours = records(engine.outgoingFiles(0L, now, 1).map { it.bytes })
            .filter { it["k"]!!.jsonPrimitive.content != SyncKinds.ACCOUNT }

        // What Android's SyncEngine + adapters write for the same rows (field order of their JsonObject(mapOf(...))).
        val key = "subsonic${sep}song-1${sep}h-song-1${sep}Simplified Chinese$sep$model"
        val keyJson = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(),
            kotlinx.serialization.json.JsonPrimitive(key))
        val expected = listOf(
            """{"k":"lyrics_translation","kv":1,"s":"","id":$keyJson,"ts":${now - 1_000},"d":"ios0000000000001",""" +
                """"del":false,"v":{"trackProvider":"subsonic","trackRawId":"song-1","sourceHash":"h-song-1",""" +
                """"targetLanguage":"Simplified Chinese","model":"$model",""" +
                """"translationsJson":"[\"沉下去\",\"融化\"]",""" +
                """"lyrics":{"lyricsProvider":"lrclib","lyricsProviderSongId":"77","lrc":"[00:01.00]Sink\n[00:02.00]Melt"}}}""",
            """{"k":"setting","kv":1,"s":"","id":"translation_language","ts":$now,"d":"ios0000000000001",""" +
                """"del":false,"v":{"value":"Simplified Chinese"}}""",
        )
        assertEquals(
            expected.map { Json.parseToJsonElement(it).toString() }.sorted(),
            ours.map { it.toString() }.sorted(),
        )
    }

    @Test
    fun should_leaveTheLyricsOut_when_theyCannotHaveBeenTheSource() = runTest {
        val adapter = LyricsTranslationSyncAdapter(FakeSyncDomain().apply {
            insertLyricsTranslationIfAbsent(translation("newer"))
            writeLyricsCache(LyricsCacheRow("subsonic", "newer", "lrclib", "1", lrc, now)) // fetched after
            insertLyricsTranslationIfAbsent(translation("typed"))
            writeLyricsCache(LyricsCacheRow("subsonic", "typed", "manual", null, lrc, 0))
            insertLyricsTranslationIfAbsent(translation("count", translations = listOf("one")))
            writeLyricsCache(LyricsCacheRow("subsonic", "count", "qq", "1", lrc, 0))
            insertLyricsTranslationIfAbsent(translation("chosen"))
            writeLyricsCache(LyricsCacheRow("subsonic", "chosen", "qq", "user|9", lrc, now)) // any age
        })
        val rows = adapter.readAll(null)
        val decorated = rows.associate { row ->
            row.key.split(sep)[1] to adapter.decoratePayload(row.projection, "iPhone").containsKey("lyrics")
        }
        assertEquals(mapOf("newer" to false, "typed" to false, "count" to false, "chosen" to true), decorated)
    }

    private fun payload(row: LyricsTranslationRow, lyrics: JsonObject? = null): JsonObject {
        val base = mapOf(
            "trackProvider" to jsonString(row.trackProvider),
            "trackRawId" to jsonString(row.trackRawId),
            "sourceHash" to jsonString(row.sourceHash),
            "targetLanguage" to jsonString(row.targetLanguage),
            "model" to jsonString(row.model),
            "translationsJson" to jsonString(row.translationsJson),
        )
        return JsonObject(if (lyrics == null) base else base + ("lyrics" to lyrics))
    }

    private fun snapshot(provider: String, songId: String?) = JsonObject(
        mapOf("lyricsProvider" to jsonString(provider), "lyricsProviderSongId" to jsonString(songId), "lrc" to jsonString(lrc)),
    )

    private fun keyOf(row: LyricsTranslationRow) = LyricsTranslationSyncAdapter.keyOf(
        row.trackProvider, row.trackRawId, row.sourceHash, row.targetLanguage, row.model,
    )

    @Test
    fun should_insertAndSeedLyrics_when_applyingAnAndroidTranslation() = runTest {
        val domain = FakeSyncDomain()
        val adapter = LyricsTranslationSyncAdapter(domain) { now }
        val row = translation(cachedAt = 1L)

        val outcome = adapter.apply(null, keyOf(row), payload(row, snapshot("qq", "user|42")), 1L, null)

        assertIs<ApplyOutcome.Applied>(outcome)
        assertEquals(row.copy(cachedAt = now), domain.lyricsTranslation("subsonic", "song-1", "h-song-1",
            "Simplified Chinese", model))
        // The user's choice follows them (the marker is kept); the seeded row counts as fresh.
        assertEquals(LyricsCacheRow("subsonic", "song-1", "qq", "user|42", lrc, now), domain.lyricsCache("subsonic", "song-1"))
    }

    @Test
    fun should_keepLocalLyricsAndRow_when_theyAreTheUsersOrDiffer() = runTest {
        val domain = FakeSyncDomain()
        val adapter = LyricsTranslationSyncAdapter(domain) { now }
        val typed = LyricsCacheRow("subsonic", "song-1", "manual", null, "[00:01.00]Mine\n[00:02.00]Too", 0L)
        domain.writeLyricsCache(typed)
        val row = translation()
        adapter.apply(null, keyOf(row), payload(row, snapshot("qq", "1")), 1L, null)
        assertEquals(typed, domain.lyricsCache("subsonic", "song-1"))

        // Write-once: the same key with other text is declined, the local row stays.
        val other = row.copy(translationsJson = LyricsTranslationIdentity.encodeTranslations(listOf("x", "y")))
        val localHash = adapter.readAll(null).single().projection.let(CanonicalJson::hash)
        val outcome = adapter.apply(null, keyOf(other), payload(other), 2L, localHash)
        assertEquals(ApplyOutcome.Skipped(SkipReason.POLICY, localHash), outcome)
        assertEquals(row.translationsJson, domain.paidLyricsTranslations().single().translationsJson)
    }

    @Test
    fun should_replaceStaleAutomaticLyrics_when_seeding() = runTest {
        val domain = FakeSyncDomain()
        val adapter = LyricsTranslationSyncAdapter(domain) { now }
        domain.writeLyricsCache(LyricsCacheRow("subsonic", "song-1", "lrclib", "1", "[00:01.00]old", now - 31L * 86_400_000L))
        val row = translation()
        adapter.apply(null, keyOf(row), payload(row, snapshot("netease", "5")), 1L, null)
        assertEquals("netease", domain.lyricsCache("subsonic", "song-1")?.lyricsProvider)
    }

    @Test
    fun should_declineProviderRowsAndMismatchedKeys_when_applying() = runTest {
        val domain = FakeSyncDomain()
        val adapter = LyricsTranslationSyncAdapter(domain) { now }
        val free = translation(model = "provider:qq")
        assertEquals(ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null), adapter.apply(null, keyOf(free), payload(free), 1L, null))
        val row = translation()
        assertEquals(
            ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null),
            adapter.apply(null, keyOf(row.copy(trackRawId = "other")), payload(row), 1L, null),
        )
        assertTrue(domain.translations.isEmpty())
    }

    // ---- setting

    private fun setting(value: String) = JsonObject(mapOf("value" to jsonString(value)))

    @Test
    fun should_captureLanguageOnlyWhenExplicit_when_reading() = runTest {
        val domain = FakeSyncDomain("English")
        assertTrue(SettingsSyncAdapter(domain).readAll(null).isEmpty(), "the default is not an opinion")
        assertEquals(
            listOf(setting("English")),
            SettingsSyncAdapter(domain) { it == SyncSettingKeys.TRANSLATION_LANGUAGE }.readAll(null).map { it.projection },
        )
        domain.language = "Korean"
        assertEquals(listOf(setting("Korean")), SettingsSyncAdapter(domain).readAll(null).map { it.projection })
    }

    @Test
    fun should_applyLanguageOnlyWhileNoAboutRows_when_applying() = runTest {
        val domain = FakeSyncDomain("English")
        val adapter = SettingsSyncAdapter(domain)
        assertIs<ApplyOutcome.Applied>(adapter.apply(null, SyncSettingKeys.TRANSLATION_LANGUAGE, setting("Japanese"), 1L, null))
        assertEquals("Japanese", domain.language)

        val japanese = CanonicalJson.hash(setting("Japanese"))
        assertEquals(
            ApplyOutcome.Skipped(SkipReason.POLICY, japanese),
            adapter.apply(null, SyncSettingKeys.TRANSLATION_LANGUAGE, setting("Klingon"), 2L, japanese),
        )
        domain.putSongAbout(
            SongAboutRow("t", "a", "b", "T", "A", "B", "about", "k", null, null, "text", 1L, 1L),
        )
        assertEquals(
            ApplyOutcome.Skipped(SkipReason.POLICY, japanese),
            adapter.apply(null, SyncSettingKeys.TRANSLATION_LANGUAGE, setting("French"), 3L, japanese),
        )
        assertEquals("Japanese", domain.language)
    }

    @Test
    fun should_declineSettingsIosDoesNotHave_when_applying() = runTest {
        val domain = FakeSyncDomain("English")
        val adapter = SettingsSyncAdapter(domain)
        assertEquals(ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null),
            adapter.apply(null, SyncSettingKeys.SEAM_TOP_STYLE, setting("x"), 1L, null))
        assertEquals("English", domain.language)
    }

    @Test
    fun should_bootstrapTheSpotifyClientId_when_thisDeviceHasNoneAndNoSpotifyAccount() = runTest {
        val domain = FakeSyncDomain("English")
        val adapter = SettingsSyncAdapter(domain)
        val outcome = adapter.apply(null, SyncSettingKeys.SPOTIFY_CLIENT_ID, setting("abc123"), 1L, null)
        assertTrue(outcome is ApplyOutcome.Applied)
        assertEquals("abc123", domain.spotifyClientId)
        assertEquals(listOf(SyncSettingKeys.SPOTIFY_CLIENT_ID), adapter.readAll(null).map { it.key })
    }

    @Test
    fun should_keepTheLocalSpotifyClientId_when_oneIsSetOrASpotifyAccountExists() = runTest {
        val own = FakeSyncDomain("English").apply { spotifyClientId = "mine" }
        assertTrue(SettingsSyncAdapter(own).apply(null, SyncSettingKeys.SPOTIFY_CLIENT_ID, setting("theirs"), 1L, null)
            is ApplyOutcome.Skipped)
        assertEquals("mine", own.spotifyClientId)
        val signedIn = FakeSyncDomain("English").apply { spotifyProfiles = 1 }
        assertTrue(SettingsSyncAdapter(signedIn).apply(null, SyncSettingKeys.SPOTIFY_CLIENT_ID, setting("theirs"), 1L, null)
            is ApplyOutcome.Skipped)
        assertEquals(null, signedIn.spotifyClientId)
    }
}
