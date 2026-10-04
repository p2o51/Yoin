package com.gpo.yoin.data.sync.adapters

import com.gpo.yoin.data.local.LyricsCache
import com.gpo.yoin.data.local.LyricsTranslationCache
import com.gpo.yoin.data.sync.ApplyOutcome
import com.gpo.yoin.data.sync.SkipReason
import com.gpo.yoin.data.sync.testing.SyncDomainFixtures
import com.gpo.yoin.data.sync.testing.hashOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsTranslationSyncAdapterTest {
    private val fixtures = SyncDomainFixtures()
    private var now = 100L * DAY
    private val adapter = LyricsTranslationSyncAdapter(fixtures.db) { now }
    private val dao = fixtures.db.syncDomainDao()

    private val lrc = "[ti:Song]\n[00:01.00]first line\n[00:02.00]second line\n[00:03.00]   \n[00:04.00]third line"
    private val translations = """["eins","zwei","drei"]"""
    private val paid = row(model = "gemini-3.1-flash-lite", cachedAt = 50L * DAY)
    private val key = LyricsTranslationSyncAdapter.keyOf("spotify", "trk", "hash1", "German", "gemini-3.1-flash-lite")

    @After
    fun tearDown() = fixtures.close()

    @Test
    fun should_readOnlyPaidRows_when_providerTranslationsExist() = runTest {
        dao.insertTranslationIfAbsent(paid)
        dao.insertTranslationIfAbsent(row(model = "provider:qq", cachedAt = 1L))

        val rows = adapter.readAll(null)

        assertEquals(listOf(key), rows.map { it.key })
        assertEquals(50L * DAY, rows.single().rowTs)
    }

    @Test
    fun should_attachSnapshotOutsideProjection_when_lyricsMatchTranslation() = runTest {
        dao.insertTranslationIfAbsent(paid)
        dao.writeLyricsCache(lyrics(cachedAt = 40L * DAY))

        val row = adapter.readAll(null).single()
        val decorated = adapter.decoratePayload(row.projection, deviceName = "Pixel")

        val snapshot = decorated[LyricsTranslationSyncAdapter.LYRICS_FIELD]!!.jsonObject
        assertEquals(lrc, snapshot["lrc"]!!.jsonPrimitive.content)
        assertEquals("qq", snapshot["lyricsProvider"]!!.jsonPrimitive.content)
        assertFalse(row.projection.containsKey(LyricsTranslationSyncAdapter.LYRICS_FIELD))
        assertEquals(hashOf(row.projection), hashOf(adapter.project(decorated)))
    }

    @Test
    fun should_notAttachSnapshot_when_lyricsCachedAfterTranslation() = runTest {
        dao.insertTranslationIfAbsent(paid)
        dao.writeLyricsCache(lyrics(cachedAt = 60L * DAY))

        val row = adapter.readAll(null).single()

        assertFalse(hasSnapshot(adapter.decoratePayload(row.projection, "Pixel")))
    }

    @Test
    fun should_notAttachSnapshot_when_lineCountDiffers() = runTest {
        dao.insertTranslationIfAbsent(paid.copy(translationsJson = """["eins","zwei"]"""))
        dao.writeLyricsCache(lyrics(cachedAt = 40L * DAY))

        val row = adapter.readAll(null).single()

        assertFalse(hasSnapshot(adapter.decoratePayload(row.projection, "Pixel")))
    }

    @Test
    fun should_notAttachSnapshot_when_lyricsWereHandEdited() = runTest {
        dao.insertTranslationIfAbsent(paid)
        dao.writeLyricsCache(lyrics(cachedAt = 40L * DAY, provider = "manual"))

        val row = adapter.readAll(null).single()

        assertFalse(hasSnapshot(adapter.decoratePayload(row.projection, "Pixel")))
    }

    @Test
    fun should_insertTranslationAndSeedLyrics_when_trackHasNoLyricsRow() = runTest {
        val outcome = adapter.apply(null, key, remotePayload(), versionTs = 7L, expectedLocalHash = null)

        val stored = dao.translation("spotify", "trk", "hash1", "German", "gemini-3.1-flash-lite")!!
        assertEquals(translations, stored.translationsJson)
        val seeded = dao.lyricsCache("spotify", "trk")!!
        assertEquals(lrc, seeded.lrc)
        assertEquals(now, seeded.cachedAt)
        assertEquals("song-9", seeded.lyricsProviderSongId)
        assertEquals(ApplyOutcome.Applied(hashOf(adapter.project(remotePayload()))), outcome)
        assertEquals(hashOf(adapter.project(remotePayload())), hashOf(adapter.readAll(null).single().projection))
    }

    @Test
    fun should_neverOverwrite_when_translationAlreadyExists() = runTest {
        dao.insertTranslationIfAbsent(paid.copy(translationsJson = """["un","deux","trois"]"""))
        val current = hashOf(adapter.readAll(null).single().projection)

        val outcome = adapter.apply(null, key, remotePayload(), versionTs = 7L, expectedLocalHash = current)

        assertEquals(ApplyOutcome.Skipped(SkipReason.POLICY, current), outcome)
        val stored = dao.translation("spotify", "trk", "hash1", "German", "gemini-3.1-flash-lite")!!
        assertEquals("""["un","deux","trois"]""", stored.translationsJson)
    }

    @Test
    fun should_keepFreshLyrics_when_seedingOverAutomaticRow() = runTest {
        dao.writeLyricsCache(lyrics(cachedAt = now - DAY, lrcText = "[00:01.00]other"))

        adapter.apply(null, key, remotePayload(), versionTs = 7L, expectedLocalHash = null)

        assertEquals("[00:01.00]other", dao.lyricsCache("spotify", "trk")!!.lrc)
    }

    @Test
    fun should_replaceExpiredAutomaticLyrics_when_seeding() = runTest {
        dao.writeLyricsCache(lyrics(cachedAt = now - 31L * DAY, lrcText = "[00:01.00]other"))

        adapter.apply(null, key, remotePayload(), versionTs = 7L, expectedLocalHash = null)

        val stored = dao.lyricsCache("spotify", "trk")!!
        assertEquals(lrc, stored.lrc)
        assertEquals(now, stored.cachedAt)
    }

    @Test
    fun should_neverSeedOverManualLyrics_when_manualRowIsOld() = runTest {
        dao.writeLyricsCache(lyrics(cachedAt = 1L, provider = "manual", lrcText = "[00:01.00]mine"))

        adapter.apply(null, key, remotePayload(), versionTs = 7L, expectedLocalHash = null)

        val stored = dao.lyricsCache("spotify", "trk")!!
        assertEquals("manual", stored.lyricsProvider)
        assertEquals("[00:01.00]mine", stored.lrc)
    }

    @Test
    fun should_notSeed_when_snapshotLineCountDiffers() = runTest {
        val bad = remotePayload(snapshotLrc = "[00:01.00]only one line")

        adapter.apply(null, key, bad, versionTs = 7L, expectedLocalHash = null)

        assertNull(dao.lyricsCache("spotify", "trk"))
        assertTrue(dao.translation("spotify", "trk", "hash1", "German", "gemini-3.1-flash-lite") != null)
    }

    @Test
    fun should_skipUnsupported_when_payloadIsProviderTranslation() = runTest {
        val providerKey = LyricsTranslationSyncAdapter.keyOf("spotify", "trk", "hash1", "German", "provider:qq")
        val providerPayload = JsonObject(remotePayload() + ("model" to JsonPrimitive("provider:qq")))

        val outcome = adapter.apply(null, providerKey, providerPayload, versionTs = 7L, expectedLocalHash = null)

        assertEquals(ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null), outcome)
    }

    private fun hasSnapshot(payload: JsonObject) = payload.containsKey(LyricsTranslationSyncAdapter.LYRICS_FIELD)

    private fun remotePayload(snapshotLrc: String = lrc) = JsonObject(
        mapOf(
            "trackProvider" to JsonPrimitive("spotify"),
            "trackRawId" to JsonPrimitive("trk"),
            "sourceHash" to JsonPrimitive("hash1"),
            "targetLanguage" to JsonPrimitive("German"),
            "model" to JsonPrimitive("gemini-3.1-flash-lite"),
            "translationsJson" to JsonPrimitive(translations),
            "lyrics" to JsonObject(
                mapOf(
                    "lyricsProvider" to JsonPrimitive("qq"),
                    "lyricsProviderSongId" to JsonPrimitive("song-9"),
                    "lrc" to JsonPrimitive(snapshotLrc),
                ),
            ),
        ),
    )

    private fun row(model: String, cachedAt: Long) = LyricsTranslationCache(
        trackProvider = "spotify",
        trackRawId = "trk",
        sourceHash = "hash1",
        targetLanguage = "German",
        model = model,
        translationsJson = translations,
        cachedAt = cachedAt,
    )

    private fun lyrics(cachedAt: Long, provider: String = "qq", lrcText: String = lrc) = LyricsCache(
        trackProvider = "spotify",
        trackRawId = "trk",
        lyricsProvider = provider,
        lyricsProviderSongId = "song-9",
        lrc = lrcText,
        cachedAt = cachedAt,
    )

    private companion object {
        const val DAY = 24L * 60L * 60L * 1000L
    }
}
