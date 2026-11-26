package com.gpo.yoin.shared.sync.adapters

import com.gpo.yoin.shared.sync.ApplyOutcome
import com.gpo.yoin.shared.sync.FileClass
import com.gpo.yoin.shared.sync.SkipReason
import com.gpo.yoin.shared.sync.SyncFormat
import com.gpo.yoin.shared.sync.SyncKinds
import com.gpo.yoin.shared.sync.SyncSettingKeys
import com.gpo.yoin.shared.sync.engine.SyncEngine
import com.gpo.yoin.shared.sync.testing.FakeSyncDomain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shared port of Android's AlbumAboutSyncAdapter (app/src/test/.../AlbumAboutSyncAdapterTest): the same kind,
 * key and projection, language-stamped, deletes never sync, global.
 */
class AlbumAboutSyncAdapterTest {
    private fun row(kind: String = "canonical", entryKey: String = "blurb", answer: String = "Cut by the sea.") =
        AlbumAboutEntryRow(
            provider = "spotify", albumId = "al1", albumDisplay = "Tide Tables", artistDisplay = "Morikawa",
            kind = kind, entryKey = entryKey, promptText = null, titleText = null, answerText = answer,
            createdAt = 1L, updatedAt = 2L,
        )

    @Test
    fun should_beAGlobalArtifactKindThatNeverDeletes() {
        val adapter = AlbumAboutSyncAdapter(FakeSyncDomain())
        assertEquals("album_about", SyncKinds.ALBUM_ABOUT)
        assertEquals(SyncKinds.ALBUM_ABOUT, adapter.kind)
        assertEquals(FileClass.ARTIFACTS, adapter.fileClass)
        assertEquals(false, adapter.perAccount)
        assertEquals(false, adapter.propagatesDeletes)
        assertTrue(SyncKinds.ALBUM_ABOUT in SyncEngine.KNOWN_ARTIFACT_KINDS)
        assertTrue(SharedSyncAdapters.create(FakeSyncDomain(), com.gpo.yoin.shared.sync.InMemorySyncStore()).any {
            it.kind == SyncKinds.ALBUM_ABOUT
        })
    }

    @Test
    fun should_keyByLanguageProviderAlbumKindAndEntry_when_reading() = runTest {
        val domain = FakeSyncDomain("Japanese").apply { putAlbumAbout(row()) }
        val read = AlbumAboutSyncAdapter(domain).readAll(null).single()
        assertEquals(SyncFormat.compositeKey("Japanese", "spotify", "al1", "canonical", "blurb"), read.key)
        assertEquals(
            setOf(
                "targetLanguage", "provider", "albumId", "albumDisplay", "artistDisplay", "kind", "entryKey",
                "promptText", "titleText", "answerText", "createdAt", "updatedAt",
            ),
            read.projection.keys,
        )
        assertEquals(2L, read.rowTs)
    }

    @Test
    fun should_applyOnlyInTheDevicesLanguage_and_roundTrip() = runTest {
        val writer = FakeSyncDomain("English").apply { putAlbumAbout(row(kind = "ask", entryKey = "who?", answer = "Ren")) }
        val sent = AlbumAboutSyncAdapter(writer).readAll(null).single()

        val other = FakeSyncDomain("Japanese")
        val skipped = AlbumAboutSyncAdapter(other).apply(null, sent.key, sent.projection, 2L, null)
        assertTrue(skipped is ApplyOutcome.Skipped && skipped.reason == SkipReason.CHANGED_LOCALLY, skipped.toString())
        assertEquals(0, other.albumAboutEntryCount())

        val reader = FakeSyncDomain("English")
        val adapter = AlbumAboutSyncAdapter(reader)
        val applied = adapter.apply(null, sent.key, sent.projection, 2L, null)
        assertTrue(applied is ApplyOutcome.Applied, applied.toString())
        assertEquals(row(kind = "ask", entryKey = "who?", answer = "Ren"), reader.albumAbouts().single())
        assertEquals(sent.projection, adapter.readAll(null).single().projection)
    }

    @Test
    fun should_neverDelete() = runTest {
        val outcome = AlbumAboutSyncAdapter(FakeSyncDomain()).delete(null, "k", "h")
        assertTrue(outcome is ApplyOutcome.Skipped && outcome.reason == SkipReason.POLICY, outcome.toString())
    }

    @Test
    fun should_keepTheLanguage_when_onlyAlbumAboutRowsExist() = runTest {
        // A synced answer language never replaces this device's while it holds About rows (Android SettingsSyncAdapter).
        val domain = FakeSyncDomain("English").apply { putAlbumAbout(row()) }
        val outcome = SettingsSyncAdapter(domain).apply(
            null, SyncSettingKeys.TRANSLATION_LANGUAGE, JsonObject(mapOf("value" to jsonString("Japanese"))), 1L, null,
        )
        assertTrue(outcome is ApplyOutcome.Skipped && outcome.reason == SkipReason.POLICY, outcome.toString())
        assertEquals("English", domain.language)
    }
}
