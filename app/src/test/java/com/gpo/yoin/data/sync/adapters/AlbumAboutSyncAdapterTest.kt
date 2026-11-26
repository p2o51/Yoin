package com.gpo.yoin.data.sync.adapters

import com.gpo.yoin.data.local.AlbumAboutEntry
import com.gpo.yoin.data.local.GeminiConfig
import com.gpo.yoin.data.sync.ApplyOutcome
import com.gpo.yoin.data.sync.ConflictPolicy
import com.gpo.yoin.data.sync.FileClass
import com.gpo.yoin.data.sync.SkipReason
import com.gpo.yoin.data.sync.SyncKinds
import com.gpo.yoin.data.sync.engine.SyncEngine
import com.gpo.yoin.data.sync.testing.SyncDomainFixtures
import com.gpo.yoin.data.sync.testing.hashOf
import com.gpo.yoin.data.sync.testing.payload
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AlbumAboutSyncAdapterTest {
    private val fixtures = SyncDomainFixtures()
    private val adapter = AlbumAboutSyncAdapter(fixtures.db)
    private val dao = fixtures.db.syncDomainDao()

    @After
    fun tearDown() = fixtures.close()

    private fun remote(language: String = "English") = payload(
        """{"targetLanguage":"$language","provider":"spotify","albumId":"al-1","albumDisplay":"Blonde",
           "artistDisplay":"Frank Ocean","kind":"canonical","entryKey":"blurb","promptText":null,"titleText":null,
           "answerText":"The paragraph.","createdAt":100,"updatedAt":150}""",
    )

    private val key = listOf("English", "spotify", "al-1", "canonical", "blurb").joinToString("\u001F")

    @Test
    fun should_followTheSongAboutRules() {
        assertEquals("album_about", SyncKinds.ALBUM_ABOUT)
        assertEquals(SyncKinds.ALBUM_ABOUT, adapter.kind)
        assertEquals(FileClass.ARTIFACTS, adapter.fileClass)
        assertFalse(adapter.perAccount)
        assertFalse(adapter.propagatesDeletes)
        assertEquals(ConflictPolicy.LWW, adapter.conflictPolicy)
        assertTrue(SyncKinds.ALBUM_ABOUT in SyncEngine.KNOWN_ARTIFACT_KINDS)
    }

    @Test
    fun should_readBackTheSameProjection_when_applied() = runTest {
        val outcome = adapter.apply(null, key, remote(), versionTs = 150L, expectedLocalHash = null)

        val row = adapter.readAll(null).single()
        assertEquals(ApplyOutcome.Applied(hashOf(adapter.project(remote()))), outcome)
        assertEquals(key, row.key)
        assertEquals("The paragraph.", dao.albumAbout("spotify", "al-1", "canonical", "blurb")?.answerText)
    }

    @Test
    fun should_leaveAnotherLanguagesAboutInDrive() = runTest {
        val zhKey = listOf("Simplified Chinese", "spotify", "al-1", "canonical", "blurb").joinToString("\u001F")

        val outcome = adapter.apply(null, zhKey, remote("Simplified Chinese"), versionTs = 150L, expectedLocalHash = null)

        assertEquals(ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, null), outcome)
        assertNull(dao.albumAbout("spotify", "al-1", "canonical", "blurb"))
    }

    @Test
    fun should_neverDeleteFromSync() = runTest {
        dao.insertGeminiConfigIfAbsent(GeminiConfig(apiKey = ""))
        dao.upsertAlbumAbout(
            AlbumAboutEntry("spotify", "al-1", "Blonde", "Frank Ocean", "canonical", "blurb", null, null, "x", 1, 1),
        )

        val outcome = adapter.delete(null, key, expectedLocalHash = "h")

        assertEquals(ApplyOutcome.Skipped(SkipReason.POLICY, "h"), outcome)
        assertEquals(1, dao.albumAboutEntryCount())
    }
}
