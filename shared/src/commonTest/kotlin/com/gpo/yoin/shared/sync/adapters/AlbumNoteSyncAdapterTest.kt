package com.gpo.yoin.shared.sync.adapters

import com.gpo.yoin.shared.sync.ActiveBinding
import com.gpo.yoin.shared.sync.ApplyOutcome
import com.gpo.yoin.shared.sync.CanonicalJson
import com.gpo.yoin.shared.sync.ConflictPolicy
import com.gpo.yoin.shared.sync.InMemorySyncStore
import com.gpo.yoin.shared.sync.RemoteFile
import com.gpo.yoin.shared.sync.SkipReason
import com.gpo.yoin.shared.sync.SyncAdapter
import com.gpo.yoin.shared.sync.SyncKinds
import com.gpo.yoin.shared.sync.engine.SnapshotCodec
import com.gpo.yoin.shared.sync.engine.SyncEngine
import com.gpo.yoin.shared.sync.gunzipBytes
import com.gpo.yoin.shared.sync.testing.FakeSyncDomain
import com.gpo.yoin.shared.sync.testing.assertEquals
import com.gpo.yoin.shared.sync.testing.assertNotNull
import com.gpo.yoin.shared.sync.testing.assertNull
import com.gpo.yoin.shared.sync.testing.assertTrue
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Android's AlbumNoteSyncAdapterTest over the shared port (what iOS runs), plus the move from carrying
 * `album_note` records opaquely to applying them: Android's own state file, merged by an engine without the
 * adapter, then by one with it over the same replica.
 */
class AlbumNoteSyncAdapterTest {
    private val domain = FakeSyncDomain()
    private val adapter = AlbumNoteSyncAdapter(domain)

    private fun payload(json: String) = Json.parseToJsonElement(json).jsonObject
    private fun hashOf(o: JsonObject?) = o?.let(CanonicalJson::hash)

    private val remote = payload(
        """{"albumId":"al-1","provider":"spotify","content":"像 [@Andromeda](yoin:track/spotify:t2) 的白天",
           "createdAt":100,"updatedAt":150,"albumName":"Charm","artist":"Clairo","futureField":true}""",
    )

    @Test
    fun should_declareTheSongNoteContract_forTheAlbumNoteKind() {
        assertEquals("album_note", SyncKinds.ALBUM_NOTE)
        assertEquals(SyncKinds.ALBUM_NOTE, adapter.kind)
        assertEquals(1, adapter.kindVersion)
        assertTrue(adapter.perAccount)
        assertTrue(adapter.propagatesDeletes)
        assertEquals(ConflictPolicy.LWW_LIVE_WINS, adapter.conflictPolicy)
        val store = InMemorySyncStore()
        assertTrue(SharedSyncAdapters.create(domain, store).any { it.kind == SyncKinds.ALBUM_NOTE })
    }

    @Test
    fun should_readBackSameProjection_when_payloadApplied() = runTest {
        val outcome = adapter.apply("p1", "an1", remote, versionTs = 150L, expectedLocalHash = null)

        val row = adapter.readAll("p1").single()
        val expected = hashOf(adapter.project(remote))
        assertEquals(ApplyOutcome.Applied(expected), outcome)
        assertEquals(expected, hashOf(row.projection))
        assertEquals("an1", row.key)
        assertEquals(150L, row.rowTs)
        val stored = domain.albumNote("an1")
        assertEquals("p1", stored?.profileId)
        assertEquals("像 [@Andromeda](yoin:track/spotify:t2) 的白天", stored?.content)
        assertEquals(100L, stored?.createdAt)
        assertEquals("Charm", stored?.albumName)
        assertEquals("Clairo", stored?.artist)
    }

    @Test
    fun should_projectAndroidsFieldsInAndroidsOrder() {
        val projected = adapter.project(remote)!!
        assertEquals(
            listOf("albumId", "provider", "content", "createdAt", "updatedAt", "albumName", "artist"),
            projected.keys.toList(),
        )
    }

    @Test
    fun should_keepSeveralNotesPerAlbum() = runTest {
        adapter.apply("p1", "an1", remote, versionTs = 150L, expectedLocalHash = null)
        adapter.apply("p1", "an2", remote, versionTs = 160L, expectedLocalHash = null)

        assertEquals(setOf("an1", "an2"), adapter.readAll("p1").map { it.key }.toSet())
    }

    @Test
    fun should_skipChangedLocally_when_rowDiffersFromExpectedHash() = runTest {
        domain.putAlbumNote(note("an1", "p1", content = "edited here"))
        val localHash = hashOf(adapter.readAll("p1").single().projection)

        val outcome = adapter.apply("p1", "an1", remote, versionTs = 200L, expectedLocalHash = "stale")

        assertEquals(ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, localHash), outcome)
        assertEquals("edited here", domain.albumNote("an1")?.content)
    }

    @Test
    fun should_skipOwnedElsewhere_when_noteIdLivesUnderAnotherProfile() = runTest {
        domain.putAlbumNote(note("an1", "p2", content = "theirs"))

        val outcome = adapter.apply("p1", "an1", remote, versionTs = 200L, expectedLocalHash = null)

        assertEquals(ApplyOutcome.Skipped(SkipReason.OWNED_ELSEWHERE, null), outcome)
        assertEquals("p2", domain.albumNote("an1")?.profileId)
        // and a delete from p1's scope never touches it
        assertEquals(ApplyOutcome.Applied(null), adapter.delete("p1", "an1", expectedLocalHash = null))
        assertNotNull(domain.albumNote("an1"))
    }

    @Test
    fun should_deleteNote_when_hashMatches_andKeepIt_whenEditedLocally() = runTest {
        domain.putAlbumNote(note("an1", "p1"))
        val hash = hashOf(adapter.readAll("p1").single().projection)

        assertTrue((adapter.delete("p1", "an1", "older") as ApplyOutcome.Skipped).reason == SkipReason.CHANGED_LOCALLY)
        assertNotNull(domain.albumNote("an1"))
        assertEquals(ApplyOutcome.Applied(null), adapter.delete("p1", "an1", expectedLocalHash = hash))
        assertNull(domain.albumNote("an1"))
    }

    @Test
    fun should_skipUnsupported_when_payloadMissesRequiredField() = runTest {
        val broken = payload("""{"albumId":"al-1","provider":"spotify","createdAt":1,"updatedAt":1}""")

        val outcome = adapter.apply("p1", "an1", broken, versionTs = 1L, expectedLocalHash = null)

        assertEquals(ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null), outcome)
        assertNull(adapter.project(broken))
        assertNull(domain.albumNote("an1"))
    }

    @Test
    fun should_failTheRecord_when_theStoreCannotPersist() = runTest {
        domain.failWrites = true
        var thrown: Throwable? = null
        try {
            adapter.apply("p1", "an1", remote, versionTs = 150L, expectedLocalHash = null)
        } catch (e: SyncDomainWriteException) {
            thrown = e
        }
        assertNotNull(thrown)
        assertNull(domain.albumNote("an1"))
    }

    // ---- Opaque → first-class (iOS before / after notes phase 1b)

    private val scope = "s-1f2e3d4c5b6a79880a1b2c3d"
    private val binding = ActiveBinding(localProfileId = "ios-subsonic", syncProfileId = scope, provider = "subsonic")
    private val now = 1_793_000_000_000L

    /** Android's state file: two live notes on one album (one with a field this build doesn't know) and a tombstone. */
    private val androidState = """
        {"format":"yoin-sync","schema":1,"epoch":0,"deviceId":"a1b2c3d4e5f60718","deviceName":"Pixel 9",
         "fileClass":"state","shard":0,"writtenAt":1792999999000,"appVersionCode":31,"records":[
          {"k":"album_note","kv":1,"s":"$scope","id":"9b0c-an-1","ts":1792990000000,"d":"a1b2c3d4e5f60718","del":false,
           "v":{"albumId":"al-7","provider":"subsonic","content":"# Side B\n- [ ] [@Kites in July](yoin:track/subsonic:tr-4?a=Mira%20Sol)\n**late buses**",
                "createdAt":1792980000000,"updatedAt":1792990000000,"albumName":"Paper Moons","artist":"Mira Sol"}},
          {"k":"album_note","kv":1,"s":"$scope","id":"9b0c-an-2","ts":1792991000000,"d":"a1b2c3d4e5f60718","del":false,
           "v":{"albumId":"al-7","provider":"subsonic","content":"  spaced  ","createdAt":1792985000000,
                "updatedAt":1792991000000,"albumName":"Paper Moons","artist":"Mira Sol","futureField":{"x":1}}},
          {"k":"album_note","kv":1,"s":"$scope","id":"9b0c-an-3","ts":1792992000000,"d":"a1b2c3d4e5f60718",
           "pts":1792982000000,"pd":"a1b2c3d4e5f60718","del":true}
        ]}
    """.trimIndent()

    private fun albumNoteRecords(engineFiles: List<ByteArray>): List<JsonObject> = engineFiles.flatMap { bytes ->
        Json.parseToJsonElement(gunzipBytes(bytes).decodeToString()).jsonObject["records"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["k"]!!.jsonPrimitive.content == SyncKinds.ALBUM_NOTE }
    }

    private suspend fun merge(engine: SyncEngine) {
        val envelope = SnapshotCodec.decode(androidState.encodeToByteArray())
        engine.mergeRemote(
            RemoteFile("android-state", "yoin-state-a1b2c3d4e5f60718.json.gz", 1, null, null, emptyMap()),
            envelope,
        )
    }

    @Test
    fun should_applyRecordsCarriedOpaquely_once_theAdapterArrives() = runTest {
        val store = InMemorySyncStore()
        val without: List<SyncAdapter> = SharedSyncAdapters.create(domain, store).filter { it.kind != SyncKinds.ALBUM_NOTE }
        val before = SyncEngine(store, without, "ios0000000000001", { "iPhone" }, { now })
        merge(before)
        before.capture(listOf(binding))
        before.apply(listOf(binding))
        assertTrue(domain.albumNotes.isEmpty())
        val carried = albumNoteRecords(before.outgoingFiles(0L, now, 1).map { it.bytes })
        assertEquals(3, carried.size)

        // The update: the same replica, now with the adapter.
        val after = SyncEngine(store, SharedSyncAdapters.create(domain, store), "ios0000000000001", { "iPhone" }, {
            now + 60_000
        })
        val applied = after.apply(listOf(binding))
        assertEquals(2, applied.appliedLive[scope]?.get(SyncKinds.ALBUM_NOTE))
        assertEquals(listOf("9b0c-an-1", "9b0c-an-2"), domain.albumNotes(binding.localProfileId).map { it.id }.sorted())
        assertEquals(
            "# Side B\n- [ ] [@Kites in July](yoin:track/subsonic:tr-4?a=Mira%20Sol)\n**late buses**",
            domain.albumNote("9b0c-an-1")!!.content,
        )
        // Stored exactly as received (no trim), with the record's own times.
        assertEquals("  spaced  ", domain.albumNote("9b0c-an-2")!!.content)
        assertEquals(1792985000000L, domain.albumNote("9b0c-an-2")!!.createdAt)
        assertEquals(1792991000000L, domain.albumNote("9b0c-an-2")!!.updatedAt)
        assertNull(domain.albumNote("9b0c-an-3"))

        // Nothing new to say: no version, no tombstone, and the records go out as they came.
        val capture = after.capture(listOf(binding))
        assertNull(capture.newVersions[scope]?.get(SyncKinds.ALBUM_NOTE))
        assertNull(capture.tombstonesCreated[scope]?.get(SyncKinds.ALBUM_NOTE))
        assertEquals(carried, albumNoteRecords(after.outgoingFiles(0L, now + 60_000, 1).map { it.bytes }))

        // Applying again writes nothing twice.
        assertEquals(0, after.apply(listOf(binding)).totalApplied)
        assertEquals(2, domain.albumNotes.size)
    }

    @Test
    fun should_publishAndroidShapedRecords_when_aNoteIsWrittenEditedAndDeletedHere() = runTest {
        val store = InMemorySyncStore()
        var clock = now
        val engine = SyncEngine(store, SharedSyncAdapters.create(domain, store), "ios0000000000001", { "iPhone" }, {
            clock
        })
        domain.putAlbumNote(
            AlbumNoteRow("ios-an-1", binding.localProfileId, "al-7", "subsonic", "**words**", now - 10, now - 10,
                "Paper Moons", "Mira Sol"),
        )
        engine.capture(listOf(binding))
        val firstFiles = engine.outgoingFiles(0L, now, 1)
        firstFiles.forEach { engine.markUploaded(it) } // uploaded: the edit below builds on this version
        val first = albumNoteRecords(firstFiles.map { it.bytes }).single()
        assertEquals(
            Json.parseToJsonElement(
                """{"k":"album_note","kv":1,"s":"$scope","id":"ios-an-1","ts":${now - 10},"d":"ios0000000000001",""" +
                    """"del":false,"v":{"albumId":"al-7","provider":"subsonic","content":"**words**",""" +
                    """"createdAt":${now - 10},"updatedAt":${now - 10},"albumName":"Paper Moons","artist":"Mira Sol"}}""",
            ),
            first,
        )

        clock = now + 1_000
        domain.putAlbumNote(domain.albumNote("ios-an-1")!!.copy(content = "**more words**", updatedAt = now + 900))
        engine.capture(listOf(binding))
        val edited = albumNoteRecords(engine.outgoingFiles(0L, clock, 1).map { it.bytes }).single()
        assertEquals("**more words**", edited["v"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals((now - 10).toString(), edited["pts"]!!.jsonPrimitive.content)

        clock = now + 2_000
        domain.deleteAlbumNote("ios-an-1", binding.localProfileId)
        // The only note: the mass-delete valve needs two live notes, so this delete goes out.
        engine.capture(listOf(binding))
        val gone = albumNoteRecords(engine.outgoingFiles(0L, clock, 1).map { it.bytes }).single()
        assertEquals("true", gone["del"]!!.jsonPrimitive.content)
        assertNull(gone["v"])
    }

    private fun note(id: String, profileId: String, content: String = "hello") = AlbumNoteRow(
        id = id,
        profileId = profileId,
        albumId = "al-1",
        provider = "spotify",
        content = content,
        createdAt = 10L,
        updatedAt = 20L,
        albumName = "Charm",
        artist = "Clairo",
    )
}
