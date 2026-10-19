package com.gpo.yoin.data.sync.adapters

import com.gpo.yoin.data.backup.BackupTrack
import com.gpo.yoin.data.backup.PlaylistBackupCodec
import com.gpo.yoin.data.backup.PlaylistBackupContent
import com.gpo.yoin.data.backup.PlaylistBackupRecord
import com.gpo.yoin.data.backup.PlaylistBackupStore
import com.gpo.yoin.data.backup.PlaylistRestoreRecord
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.sync.ApplyOutcome
import com.gpo.yoin.data.sync.SkipReason
import com.gpo.yoin.data.sync.testing.SyncDomainFixtures
import com.gpo.yoin.data.sync.testing.hashOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaylistBackupSyncAdapterTest {
    private val fixtures = SyncDomainFixtures()
    private val adapter = PlaylistBackupSyncAdapter(fixtures.db)
    private val store = PlaylistBackupStore(fixtures.db) { 9_000L }
    private val dao = fixtures.db.playlistBackupDao()

    @After
    fun tearDown() = fixtures.close()

    private val backup = PlaylistBackupRecord(
        id = "b1",
        name = "Road trip",
        sourceProvider = MediaId.PROVIDER_SPOTIFY,
        sourceProfileLabel = "Chen",
        sourcePlaylistId = "spotify:p1",
        sourceProfileId = null,
        playlistCreatedAt = null,
        firstBackedUpAt = 100L,
        lastBackedUpAt = 100L,
        lastChangedAt = 100L,
        sourceGoneAt = null,
        content = PlaylistBackupContent(
            description = "drive",
            tracks = listOf(BackupTrack("Creep", listOf("Radiohead"), null, 238, "GBAYE9200070", "spotify:t1")),
        ),
        restores = emptyList(),
        updatedAt = 100L,
    )

    @Test
    fun should_projectReadAndApplyAlike_when_backupMovesBetweenDevices() = runTest {
        store.save(backup)
        val local = adapter.readAll(null).single()
        dao.delete("b1")

        // The local row's projection is the payload another device receives.
        val outcome = adapter.apply(null, "b1", local.projection, versionTs = 100L, expectedLocalHash = null)

        assertEquals("b1", local.key)
        assertEquals(100L, local.rowTs)
        assertEquals(hashOf(adapter.project(local.projection)), hashOf(local.projection))
        assertEquals(ApplyOutcome.Applied(hashOf(local.projection)), outcome)
        assertEquals(backup, store.get("b1"))
        assertEquals(1, dao.get("b1")?.trackCount)
    }

    @Test
    fun should_keepUnknownFields_when_newerDeviceWroteContent() = runTest {
        store.save(backup)
        val projection = adapter.readAll(null).single().projection
        val newerContent =
            """{"description":null,"tracks":[{"title":"A","sourceId":"spotify:x","mood":"sunny"}],"unexported":[]}"""
        val payload = JsonObject(
            projection + ("content" to JsonPrimitive(newerContent)) + ("future" to JsonPrimitive(1)),
        )

        val outcome = adapter.apply(null, "b1", payload, versionTs = 200L, expectedLocalHash = hashOf(projection))

        assertTrue(outcome is ApplyOutcome.Applied)
        assertEquals(newerContent, dao.get("b1")?.contentJson)
        assertEquals(hashOf(adapter.project(payload)), hashOf(adapter.readAll(null).single().projection))
    }

    @Test
    fun should_skipUnsupported_when_contentDoesNotDecode() = runTest {
        val payload = JsonObject(
            mapOf(
                "name" to JsonPrimitive("x"),
                "sourceProvider" to JsonPrimitive("spotify"),
                "sourceProfileLabel" to JsonPrimitive("Chen"),
                "sourcePlaylistId" to JsonPrimitive("spotify:p"),
                "backedUpAt" to JsonPrimitive(1L),
                "updatedAt" to JsonPrimitive(1L),
                "content" to JsonPrimitive("not json"),
                "restores" to JsonPrimitive("[]"),
            ),
        )

        assertNull(adapter.project(payload))
        assertEquals(
            ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null),
            adapter.apply(null, "b2", payload, versionTs = 1L, expectedLocalHash = null),
        )
    }

    @Test
    fun should_skipChangedLocally_when_restoreWasRecordedMidCycle() = runTest {
        store.save(backup)
        val seen = hashOf(adapter.readAll(null).single().projection)
        store.recordRestore("b1", PlaylistRestoreRecord(9_000L, MediaId.PROVIDER_APPLE_MUSIC, "Apple", 1, 0, 0))
        val current = hashOf(adapter.readAll(null).single().projection)

        val stale = adapter.apply(null, "b1", adapter.readAll(null).single().projection, 300L, expectedLocalHash = seen)
        val delete = adapter.delete(null, "b1", expectedLocalHash = seen)

        assertEquals(ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, current), stale)
        assertEquals(ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, current), delete)
        assertEquals(1, store.get("b1")?.restores?.size)
    }

    @Test
    fun should_deleteBackup_when_tombstoneArrives() = runTest {
        store.save(backup)
        val hash = hashOf(adapter.readAll(null).single().projection)

        assertEquals(ApplyOutcome.Applied(null), adapter.delete(null, "b1", expectedLocalHash = hash))
        assertNull(dao.get("b1"))
        assertEquals(ApplyOutcome.Applied(null), adapter.delete(null, "b1", expectedLocalHash = null))
        assertEquals(PlaylistBackupCodec.encodeRestores(emptyList()), "[]")
    }
}
