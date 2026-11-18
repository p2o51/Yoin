package com.gpo.yoin.shared.sync.adapters

import com.gpo.yoin.shared.backup.BackupTrack
import com.gpo.yoin.shared.backup.PlaylistBackupCodec
import com.gpo.yoin.shared.backup.PlaylistBackupContent
import com.gpo.yoin.shared.backup.PlaylistBackupRecord
import com.gpo.yoin.shared.backup.PlaylistRestoreRecord
import com.gpo.yoin.shared.sync.ApplyOutcome
import com.gpo.yoin.shared.sync.CanonicalJson
import com.gpo.yoin.shared.sync.FileClass
import com.gpo.yoin.shared.sync.SkipReason
import com.gpo.yoin.shared.sync.SyncKinds
import com.gpo.yoin.shared.sync.testing.FakeSyncDomain
import com.gpo.yoin.shared.sync.testing.assertEquals
import com.gpo.yoin.shared.sync.testing.assertFalse
import com.gpo.yoin.shared.sync.testing.assertNull
import com.gpo.yoin.shared.sync.testing.assertTrue
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Android's PlaylistBackupSyncAdapterTest, plus the wire shape Android's adapter writes. */
class PlaylistBackupSyncAdapterTest {
    private val domain = FakeSyncDomain()
    private val adapter = PlaylistBackupSyncAdapter(domain)

    private fun hashOf(o: JsonObject?) = o?.let(CanonicalJson::hash)

    private val backup = PlaylistBackupRecord(
        id = "b1",
        name = "Road trip",
        sourceProvider = "spotify",
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

    private fun save(record: PlaylistBackupRecord) = domain.putPlaylistBackup(PlaylistBackupCodec.toRow(record))

    @Test
    fun should_beAGlobalArtifactKindWithLiveWinningDeletes_when_registered() {
        assertEquals(SyncKinds.PLAYLIST_BACKUP, adapter.kind)
        assertEquals("playlist_backup", adapter.kind)
        assertEquals(1, adapter.kindVersion)
        assertEquals(FileClass.ARTIFACTS, adapter.fileClass)
        assertFalse(adapter.perAccount)
        assertTrue(adapter.propagatesDeletes)
    }

    @Test
    fun should_projectReadAndApplyAlike_when_backupMovesBetweenDevices() = runTest {
        save(backup)
        val local = adapter.readAll(null).single()
        domain.deletePlaylistBackup("b1")

        // The local row's projection is the payload another device receives.
        val outcome = adapter.apply(null, "b1", local.projection, versionTs = 100L, expectedLocalHash = null)

        assertEquals("b1", local.key)
        assertEquals(100L, local.rowTs)
        assertEquals(hashOf(adapter.project(local.projection)), hashOf(local.projection))
        assertEquals(ApplyOutcome.Applied(hashOf(local.projection)), outcome)
        assertEquals(backup, PlaylistBackupCodec.fromRow(domain.playlistBackup("b1")!!))
    }

    @Test
    fun should_writeAndroidsFieldsAndTypes_when_projecting() = runTest {
        save(backup)
        val projection = adapter.readAll(null).single().projection

        // Android PlaylistBackupSyncAdapter.projection: these names, strings as strings, times as numbers,
        // the three JSON documents as strings of exactly the text the row holds.
        val expected = Json.parseToJsonElement(
            """{"name":"Road trip","sourceProvider":"spotify","sourceProfileLabel":"Chen",
               "sourcePlaylistId":"spotify:p1","playlistCreatedAt":null,"firstBackedUpAt":100,
               "lastBackedUpAt":100,"lastChangedAt":100,"sourceGoneAt":null,"updatedAt":100,
               "content":"{\"description\":\"drive\",\"tracks\":[{\"title\":\"Creep\",\"artists\":[\"Radiohead\"],\"album\":null,\"durationSec\":238,\"isrc\":\"GBAYE9200070\",\"sourceId\":\"spotify:t1\"}],\"unexported\":[]}",
               "versions":"[]","restores":"[]"}""",
        ).jsonObject
        assertEquals(expected, projection)
        assertEquals(CanonicalJson.hash(expected), hashOf(projection))
    }

    @Test
    fun should_keepUnknownFields_when_newerDeviceWroteContent() = runTest {
        save(backup)
        val projection = adapter.readAll(null).single().projection
        val newerContent =
            """{"description":null,"tracks":[{"title":"A","sourceId":"spotify:x","mood":"sunny"}],"unexported":[]}"""
        val payload = JsonObject(
            projection + ("content" to JsonPrimitive(newerContent)) + ("future" to JsonPrimitive(1)),
        )

        val outcome = adapter.apply(null, "b1", payload, versionTs = 200L, expectedLocalHash = hashOf(projection))

        assertTrue(outcome is ApplyOutcome.Applied)
        assertEquals(newerContent, domain.playlistBackup("b1")?.contentJson)
        assertEquals(hashOf(adapter.project(payload)), hashOf(adapter.readAll(null).single().projection))
    }

    @Test
    fun should_keepThisDevicesProfile_when_anotherDevicesVersionArrives() = runTest {
        domain.putPlaylistBackup(PlaylistBackupCodec.withProfile(PlaylistBackupCodec.toRow(backup), "here"))
        val projection = adapter.readAll(null).single().projection
        val renamed = JsonObject(projection + ("name" to JsonPrimitive("Road trip 2")))

        adapter.apply(null, "b1", renamed, versionTs = 300L, expectedLocalHash = hashOf(projection))

        assertEquals("Road trip 2", domain.playlistBackup("b1")?.name)
        assertEquals("here", domain.playlistBackup("b1")?.sourceProfileId)
    }

    @Test
    fun should_readNoVersions_when_anOlderPayloadHasNone() = runTest {
        save(backup)
        val projection = adapter.readAll(null).single().projection
        val older = JsonObject(projection - "versions")

        assertEquals(hashOf(projection), hashOf(adapter.project(older)))
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
        save(backup)
        val seen = hashOf(adapter.readAll(null).single().projection)
        val row = domain.playlistBackup("b1")!!
        domain.putPlaylistBackup(
            PlaylistBackupCodec.withRestore(row, PlaylistRestoreRecord(9_000L, "applemusic", "Apple", 1, 0, 0), 9_000L),
        )
        val current = hashOf(adapter.readAll(null).single().projection)

        val stale = adapter.apply(null, "b1", adapter.readAll(null).single().projection, 300L, expectedLocalHash = seen)
        val delete = adapter.delete(null, "b1", expectedLocalHash = seen)

        assertEquals(ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, current), stale)
        assertEquals(ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, current), delete)
        assertEquals(1, PlaylistBackupCodec.fromRow(domain.playlistBackup("b1")!!)?.restores?.size)
        assertEquals(9_000L, domain.playlistBackup("b1")?.updatedAt)
    }

    @Test
    fun should_deleteBackup_when_tombstoneArrives() = runTest {
        save(backup)
        val hash = hashOf(adapter.readAll(null).single().projection)

        assertEquals(ApplyOutcome.Applied(null), adapter.delete(null, "b1", expectedLocalHash = hash))
        assertNull(domain.playlistBackup("b1"))
        assertEquals(ApplyOutcome.Applied(null), adapter.delete(null, "b1", expectedLocalHash = null))
        assertEquals(PlaylistBackupCodec.encodeRestores(emptyList()), "[]")
    }

    @Test
    fun should_failTheRecord_when_theStoreCantWrite() = runTest {
        save(backup)
        val projection = adapter.readAll(null).single().projection
        domain.failWrites = true

        val failed = runCatching { adapter.apply(null, "b1", projection, 100L, expectedLocalHash = hashOf(projection)) }

        assertTrue(failed.exceptionOrNull() is SyncDomainWriteException)
    }
}
