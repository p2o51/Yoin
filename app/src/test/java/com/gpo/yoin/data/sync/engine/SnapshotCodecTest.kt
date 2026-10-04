package com.gpo.yoin.data.sync.engine

import com.gpo.yoin.data.sync.CanonicalJson
import com.gpo.yoin.data.sync.ResetMarker
import com.gpo.yoin.data.sync.SyncEnvelope
import com.gpo.yoin.data.sync.WireRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SnapshotCodecTest {

    private fun envelope(records: List<WireRecord>) = SyncEnvelope(
        epoch = 7L,
        deviceId = "dev-a",
        deviceName = "Pixel Tablet",
        fileClass = "state",
        shard = 0,
        writtenAt = 1_000L,
        appVersionCode = 5,
        records = records,
    )

    private val note = WireRecord(
        kind = "song_note",
        scope = "s-1",
        key = "n1",
        ts = 100L,
        device = "dev-a",
        payload = json("trackId" to "t1", "content" to "hello", "positionMs" to 1200),
    )

    @Test
    fun should_roundTripEnvelope_when_encodedWithGzip() {
        val tombstone = note.copy(key = "n2", deleted = true, payload = null, prevTs = 50L, prevDevice = "dev-b")
        val original = envelope(listOf(note, tombstone))

        val bytes = SnapshotCodec.encode(original)

        assertTrue(SnapshotCodec.isGzip(bytes))
        assertEquals(original, SnapshotCodec.decode(bytes))
    }

    @Test
    fun should_decodePlainJson_when_bytesAreNotGzip() {
        val text = SyncJson.encodeToString(SyncEnvelope.serializer(), envelope(listOf(note)))

        val decoded = SnapshotCodec.decode(text.toByteArray())

        assertEquals(listOf(note), decoded.records)
        assertEquals("Pixel Tablet", decoded.deviceName)
    }

    @Test
    fun should_throwSchemaTooNew_when_envelopeWasWrittenByNewerSchema() {
        // A future schema may change shapes entirely; the gate must fire before typed decoding.
        val future = """{"format":"yoin-sync","schema":2,"deviceId":"dev-z","deviceName":"Future phone",""" +
            """"records":{"chunks":[]}}"""

        try {
            SnapshotCodec.decode(future.toByteArray())
            fail("expected SyncSchemaTooNewException")
        } catch (e: SyncSchemaTooNewException) {
            assertEquals(2, e.schema)
            assertEquals("Future phone", e.deviceName)
            assertEquals("dev-z", e.deviceId)
        }
    }

    @Test
    fun should_throwMalformed_when_bytesAreNotAYoinFile() {
        val inputs = listOf(
            """{"format":"something-else","schema":1}""",
            """[1,2,3]""",
            "not json at all",
            """{"format":"yoin-sync","schema":1,"deviceId":"d"}""",
        )
        for (input in inputs) {
            try {
                SnapshotCodec.decode(input.toByteArray())
                fail("expected MalformedSyncFileException for $input")
            } catch (e: MalformedSyncFileException) {
                // expected
            }
        }
    }

    @Test
    fun should_dropOnlyTheBrokenRecord_when_oneRecordDoesNotDecode() {
        val good = SyncJson.encodeToString(WireRecord.serializer(), note)
        val text = """{"format":"yoin-sync","schema":1,"deviceId":"dev-a","deviceName":"A","fileClass":"state",""" +
            """"writtenAt":1,"appVersionCode":1,"records":[$good,{"k":"song_note","id":"x"}]}"""

        val decoded = SnapshotCodec.decode(text.toByteArray())

        assertEquals(listOf(note), decoded.records)
    }

    @Test
    fun should_ignoreUnknownEnvelopeKeys_when_decoding() {
        val good = SyncJson.encodeToString(WireRecord.serializer(), note)
        val text = """{"format":"yoin-sync","schema":1,"deviceId":"dev-a","deviceName":"A","fileClass":"state",""" +
            """"writtenAt":1,"appVersionCode":1,"futureField":{"a":1},"records":[$good]}"""

        assertEquals(1, SnapshotCodec.decode(text.toByteArray()).records.size)
    }

    @Test
    fun should_keepPayloadKeyOrderAndCanonicalHash_when_convertingWireToEntity() {
        val payload = json("z" to 1, "a" to "x", "unknownFuture" to true)
        val entity = note.copy(payload = payload).toEntity()

        assertEquals("""{"z":1,"a":"x","unknownFuture":true}""", entity.payload)
        assertEquals(CanonicalJson.hash(json("a" to "x", "unknownFuture" to true, "z" to 1)), entity.payloadHash)
        assertEquals(note.copy(payload = payload), entity.toWire())
    }

    @Test
    fun should_neverTurnLiveRecordIntoTombstone_when_payloadIsMissing() {
        val entity = note.copy(payload = null).toEntity()

        assertFalse(entity.deleted)
        assertNull(entity.payload)
    }

    @Test
    fun should_roundTripResetMarker_when_encodedAsPlainJson() {
        val marker = ResetMarker(epoch = 42L, at = 43L, deviceId = "dev-a", deviceName = "Phone")

        val bytes = SnapshotCodec.encodeReset(marker)

        assertFalse(SnapshotCodec.isGzip(bytes))
        assertEquals(marker, SnapshotCodec.decodeReset(bytes))
        assertArrayEquals(bytes, SnapshotCodec.encodeReset(SnapshotCodec.decodeReset(bytes)))
    }
}
