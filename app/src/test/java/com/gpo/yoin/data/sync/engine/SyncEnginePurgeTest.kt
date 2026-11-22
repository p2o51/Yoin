package com.gpo.yoin.data.sync.engine

import com.gpo.yoin.data.sync.SyncBindingEntity
import com.gpo.yoin.data.sync.SyncEnvelope
import com.gpo.yoin.data.sync.SyncFormat
import com.gpo.yoin.data.sync.SyncKinds
import com.gpo.yoin.data.sync.SyncMetaKeys
import com.gpo.yoin.data.sync.SyncRecordEntity
import com.gpo.yoin.data.sync.WireRecord
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Scope purge markers at the engine level: what a replica forgets, refuses and stamps. */
@RunWith(RobolectricTestRunner::class)
class SyncEnginePurgeTest {
    private val drive = FakeDrive()
    private val devices = mutableListOf<SimDevice>()

    @After
    fun tearDown() = devices.forEach { it.close() }

    private fun device(id: String, start: Long): SimDevice = SimDevice(id, id, start).also { devices += it }

    private fun SimDevice.note(profile: String, key: String, rowTs: Long, content: String = "note $key") =
        adapter(SyncKinds.SONG_NOTE).put(profile, key, rowTs, "trackId" to "t-$key", "content" to content)

    private suspend fun SimDevice.putDescriptor(scope: String, ts: Long) {
        dao.upsertRecord(
            SyncRecordEntity(
                kind = SyncKinds.ACCOUNT,
                scope = SyncFormat.GLOBAL_SCOPE,
                key = scope,
                kindVersion = 1,
                ts = ts,
                device = "elsewhere",
                prevTs = null,
                prevDevice = null,
                deleted = false,
                payload = """{"provider":"subsonic","displayName":"$scope"}""",
                payloadHash = "h-$scope",
            ),
        )
    }

    @Test
    fun should_forgetOnlyThatScopesRecordsAtOrBeforeTheEpoch_when_purged() = runTest {
        val phone = device("phone", 1_000)
        phone.bind("p-a", "s-a")
        phone.bind("p-b", "s-b")
        phone.note("p-a", "a1", 1_000)
        phone.note("p-b", "b1", 1_000)
        phone.capture()
        phone.putDescriptor("s-a", 1_500)
        phone.putDescriptor("s-b", 1_500)

        val removed = phone.engine.applyScopePurge("s-a", epoch = 2_000)

        assertEquals("note + descriptor", 2, removed)
        assertNull(phone.record(SyncKinds.SONG_NOTE, "s-a", "a1"))
        assertNull(phone.record(SyncKinds.ACCOUNT, SyncFormat.GLOBAL_SCOPE, "s-a"))
        assertNotNull(phone.record(SyncKinds.SONG_NOTE, "s-b", "b1"))
        assertNotNull(phone.record(SyncKinds.ACCOUNT, SyncFormat.GLOBAL_SCOPE, "s-b"))
        assertTrue(phone.dao.localStatesInScope("s-a").isEmpty())
        assertEquals(mapOf("s-a" to 2_000L), phone.engine.purgedScopes())
        assertEquals("idempotent", 0, phone.engine.applyScopePurge("s-a", epoch = 2_000))
        assertEquals("an older marker changes nothing", 0, phone.engine.applyScopePurge("s-a", epoch = 1_000))
        assertEquals("domain rows stay", setOf("a1"), phone.adapter(SyncKinds.SONG_NOTE).keys("p-a"))
    }

    @Test
    fun should_refuseTheScope_when_aPeerThatDoesntKnowThePurgeStillRelaysIt() = runTest {
        val old = device("old", 1_000)
        old.bind("p", "s-a")
        old.note("p", "n1", 1_000)
        old.cycle(drive)
        // A clock far ahead: its later edit is "after" the epoch, but the file still doesn't know the purge.
        old.now = 50_000
        old.note("p", "n2", 50_000)
        old.cycle(drive)

        val phone = device("phone", 5_000)
        phone.engine.applyScopePurge("s-a", epoch = 3_000)
        val purged = phone.pull(drive).sumOf { it.purged }

        assertEquals(2, purged)
        assertTrue(phone.dao.recordsInScope("s-a").isEmpty())
    }

    @Test
    fun should_acceptNewerVersions_when_theWriterAcknowledgesThePurge() = runTest {
        val resumed = device("resumed", 10_000)
        resumed.engine.applyScopePurge("s-a", epoch = 3_000)
        resumed.bind("p", "s-a")
        resumed.note("p", "n1", rowTs = 1_000)
        resumed.cycle(drive)

        val phone = device("phone", 20_000)
        phone.engine.applyScopePurge("s-a", epoch = 3_000)
        phone.pull(drive)

        val record = phone.record(SyncKinds.SONG_NOTE, "s-a", "n1")
        assertEquals("stamped after the epoch by the resumed device", 3_001L, record?.ts)
        val envelope = SnapshotCodec.decode(drive.file("resumed")!!.bytes)
        assertEquals(mapOf("s-a" to 3_000L), envelope.purges)
    }

    @Test
    fun should_acceptTheResumedAccountsDescriptor_when_aPeerKnowsThePurge() = runTest {
        val withDescriptors: (FakeDomain) -> List<FakeAdapter> = { domain ->
            standardAdapters(domain) + FakeAdapter(domain, SyncKinds.ACCOUNT, fields = listOf("provider"), perAccount = false)
        }
        val resumed = SimDevice("resumed", "resumed", 10_000, withDescriptors).also { devices += it }
        resumed.engine.applyScopePurge("s-a", epoch = 3_000)
        // The descriptor's own time (the profile's creation) is long before the purge.
        resumed.adapter(SyncKinds.ACCOUNT).put(null, "s-a", 1_000, "provider" to "subsonic")
        resumed.bind("p", "s-a")
        resumed.note("p", "n1", rowTs = 1_000)
        resumed.cycle(drive)

        val peer = SimDevice("peer", "peer", 20_000, withDescriptors).also { devices += it }
        peer.engine.applyScopePurge("s-a", epoch = 3_000)
        val refused = peer.pull(drive).sumOf { it.purged }

        assertEquals(0, refused)
        assertEquals(3_001L, peer.record(SyncKinds.ACCOUNT, SyncFormat.GLOBAL_SCOPE, "s-a")?.ts)
        assertEquals(3_001L, peer.record(SyncKinds.SONG_NOTE, "s-a", "n1")?.ts)
    }

    @Test
    fun should_refuseThePurgedAccountsDescriptor_when_merged() = runTest {
        val phone = device("phone", 5_000)
        phone.engine.applyScopePurge("s-a", epoch = 3_000)
        val envelope = SyncEnvelope(
            purges = mapOf("s-b" to 1L),
            deviceId = "peer",
            deviceName = "Peer",
            fileClass = "state",
            writtenAt = 2_000,
            appVersionCode = 1,
            records = listOf(
                WireRecord(kind = SyncKinds.ACCOUNT, key = "s-a", ts = 2_000, device = "peer", payload = json("provider" to "subsonic")),
                WireRecord(kind = SyncKinds.ACCOUNT, key = "s-b", ts = 2_000, device = "peer", payload = json("provider" to "subsonic")),
            ),
        )

        val result = phone.engine.mergeImported(envelope)

        assertEquals(1, result.purged)
        assertNull(phone.record(SyncKinds.ACCOUNT, SyncFormat.GLOBAL_SCOPE, "s-a"))
        assertNotNull(phone.record(SyncKinds.ACCOUNT, SyncFormat.GLOBAL_SCOPE, "s-b"))
        assertTrue("no remote-file bookkeeping for an import", phone.dao.remoteFiles().isEmpty())
    }

    @Test
    fun should_stampNewVersionsAfterTheEpoch_when_aResumedBindingCapturesOldRows() = runTest {
        val phone = device("phone", 10_000)
        phone.note("p", "n1", rowTs = 1_000)
        phone.engine.applyScopePurge("s-a", epoch = 9_000)
        phone.bind("p", "s-a")

        phone.capture()

        val record = phone.record(SyncKinds.SONG_NOTE, "s-a", "n1")!!
        assertEquals(9_001L, record.ts)
        val outgoing = phone.engine.outgoingFiles(epoch = 0, writtenAt = 10_000, appVersionCode = 1)
        val published = SnapshotCodec.decode(outgoing.first().bytes).records
        assertTrue(published.any { it.key == "n1" && it.ts == 9_001L })
    }

    @Test
    fun should_neverPublishPurgedRecords_when_theReplicaIsSerialized() = runTest {
        val phone = device("phone", 1_000)
        phone.bind("p", "s-a")
        phone.note("p", "n1", 1_000)
        phone.capture()
        phone.engine.applyScopePurge("s-a", epoch = 2_000)

        val outgoing = phone.engine.outgoingFiles(epoch = 0, writtenAt = 3_000, appVersionCode = 1)

        assertFalse(outgoing.any { file -> SnapshotCodec.decode(file.bytes).records.any { it.scope == "s-a" } })
    }

    @Test
    fun should_forgetPurgesAndResumePausedBindings_when_theReplicaIsCleared() = runTest {
        val phone = device("phone", 1_000)
        phone.engine.applyScopePurge("s-a", epoch = 2_000)
        phone.dao.upsertBinding(
            SyncBindingEntity("p", "s-a", "subsonic", "fp", SyncBindingEntity.STATE_PURGED, null, 1_000),
        )

        phone.engine.clearReplica()

        assertTrue(phone.engine.purgedScopes().isEmpty())
        assertTrue(phone.dao.metaWithPrefix(SyncMetaKeys.PURGED_SCOPE_PREFIX).isEmpty())
        assertEquals(SyncBindingEntity.STATE_ACTIVE, phone.dao.bindingFor("p")?.state)
    }

    @Test
    fun should_roundTripThePurgeMarker_when_encoded() {
        val marker = com.gpo.yoin.data.sync.ScopePurgeMarker("s-a", 2_000, 2_001, "dev", "Pixel")

        assertEquals(marker, SnapshotCodec.decodePurge(SnapshotCodec.encodePurge(marker)))
        val text = SnapshotCodec.encodePurge(marker).decodeToString()
        assertTrue(text.contains("\"scope\":\"s-a\"") && text.contains("\"epoch\":2000"))
    }

    @Test
    fun should_skipUnknownFieldsButRejectNoScope_when_decodingAPurgeMarker() {
        val withExtra = """{"scope":"s-a","epoch":5,"at":6,"deviceId":"d","deviceName":"n","future":true}"""
        assertEquals("s-a", SnapshotCodec.decodePurge(withExtra.encodeToByteArray()).scope)
        val noScope = """{"scope":"","epoch":5,"at":6,"deviceId":"d","deviceName":"n"}"""
        assertTrue(runCatching { SnapshotCodec.decodePurge(noScope.encodeToByteArray()) }.isFailure)
    }
}
