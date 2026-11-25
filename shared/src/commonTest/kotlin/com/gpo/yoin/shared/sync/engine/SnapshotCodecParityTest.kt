package com.gpo.yoin.shared.sync.engine

import com.gpo.yoin.shared.sync.SyncEnvelope
import com.gpo.yoin.shared.sync.SyncFormat
import com.gpo.yoin.shared.sync.WireRecord
import com.gpo.yoin.shared.sync.gunzipBytes
import com.gpo.yoin.shared.sync.testing.assertEquals
import com.gpo.yoin.shared.sync.testing.fail
import kotlin.random.Random
import kotlin.test.Test
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Shared KMP strict differential parity suite comparing the legacy DOM-tree decoder with
 * [SnapshotCodec.decode] across missing headers, missing commas, 17 edge cases, and 20,000
 * random mutations with zero skipped categories.
 */
class SnapshotCodecParityTest {

    private fun legacyDecode(bytes: ByteArray): SyncEnvelope {
        val text = (if (SnapshotCodec.isGzip(bytes)) gunzipBytes(bytes) else bytes).decodeToString()
        val element = try {
            SyncJson.json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw MalformedSyncFileException("Sync file is not JSON", e)
        }
        val root = element as? JsonObject ?: throw MalformedSyncFileException("Sync file is not a JSON object")
        val format = (root["format"] as? JsonPrimitive)?.contentOrNull
        if (format != SyncFormat.FORMAT) throw MalformedSyncFileException("Not a Yoin sync file (format=$format)")
        val schema = (root["schema"] as? JsonPrimitive)?.intOrNull
            ?: throw MalformedSyncFileException("Sync file without schema")
        if (schema > SyncFormat.SCHEMA) {
            throw SyncSchemaTooNewException(schema, null, null)
        }
        if (schema < 1) throw MalformedSyncFileException("Invalid sync schema $schema")
        val records = (root["records"] as? JsonArray)
            ?: throw MalformedSyncFileException("Sync file without records")
        val header = JsonObject(root + ("records" to JsonArray(emptyList())))
        val envelope = try {
            SyncJson.json.decodeFromJsonElement(SyncEnvelope.serializer(), header)
        } catch (e: SerializationException) {
            throw MalformedSyncFileException("Unreadable sync file header", e)
        } catch (e: IllegalArgumentException) {
            throw MalformedSyncFileException("Unreadable sync file header", e)
        }
        return envelope.copy(
            records = records.mapNotNull { el: JsonElement ->
                try {
                    SyncJson.json.decodeFromJsonElement(WireRecord.serializer(), el)
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            },
        )
    }

    private fun outcome(block: () -> SyncEnvelope): String = try {
        "OK " + block().toString()
    } catch (e: SyncFormatException) {
        "THROW " + (e::class.simpleName ?: "SyncFormatException")
    } catch (e: Throwable) {
        "CRASH " + (e::class.simpleName ?: "Throwable") + ": " + e.message
    }

    private fun compare(text: String): Pair<String, String> {
        val bytes = text.encodeToByteArray()
        return outcome { legacyDecode(bytes) } to outcome { SnapshotCodec.decode(bytes) }
    }

    private val header =
        "\"epoch\":3,\"deviceId\":\"dev-x\",\"deviceName\":\"Pixel\"," +
            "\"fileClass\":\"state\",\"shard\":0,\"writtenAt\":10,\"appVersionCode\":31"

    @Test
    fun should_rejectMissingCommasAndHeaders_matchingLegacyDecoder() {
        val tail = "\"deviceId\":\"dev-x\",\"deviceName\":\"Pixel\"," +
            "\"fileClass\":\"state\",\"shard\":0,\"writtenAt\":10,\"appVersionCode\":31"
        val r1 = """{"k":"song_note","id":"n1","ts":5,"d":"dev-x","v":{}}"""
        val r2 = """{"k":"song_note","id":"n2","ts":6,"d":"dev-x","v":{}}"""
        val cases = listOf(
            """{"format":"yoin-sync",$header,"records":[]}""",
            """{"schema":1,$header,"records":[]}""",
            """{"format":"yoin-sync""schema":1,$header,"records":[]}""",
            """{"format":"yoin-sync","schema":1 "epoch":3,$tail,"records":[]}""",
            """{"format":"yoin-sync","schema":1,$header,"records":[{"k":"song_note","id":"n1","ts":5"d":"dev-x","v":{}}]}""",
            """{"format":"yoin-sync","schema":1,$header,"records":[$r1$r2]}""",
        )
        for (text in cases) {
            val (old, new) = compare(text)
            assertEquals("THROW MalformedSyncFileException", old)
            assertEquals(old, new)
        }
    }

    @Test
    fun should_agreeWithLegacyDecoder_onHandWrittenEdgeCases() {
        val rec =
            """{"k":"song_note","s":"s1","id":"n1","ts":5,"d":"dev-x",""" +
                """"v":{"content":"hé🎵 \"q\" \\ \n","n":1.0,"big":12345678901234567890,"neg":-0,"exp":1e3}}"""
        val cases = listOf(
            """{"format":"yoin-sync","schema":1,$header,"records":[$rec]}""",
            """{"format":"yoin-sync","schema":"1",$header,"records":[$rec]}""",
            """{"format":"yoin-sync","schema":1,$header,"records":[$rec,{"k":"x"}]}""",
            """{"format":"yoin-sync","schema":1,$header,"records":[$rec],"unknownTop":{"a":[1,2]}}""",
            """{"format":"yoin-sync","schema":1,$header,"records":[{"k":"song_note","id":"n","ts":"7","d":"d","v":{},"zz":1}]}""",
            """{"format":"yoin-sync","schema":1,$header,"records":[{"k":"song_note","id":"n","ts":7,"d":"d","v":[1]}]}""",
            """{"format":"yoin-sync","schema":1,$header,"records":[{"k":"song_note","id":"n","ts":7,"d":"d","del":true,"v":null}]}""",
            """{"format":"yoin-sync","schema":1,$header,"records":[{"k":"song_note","id":"n","ts":7,"d":"d","pts":null,"pd":null,"v":{"a":null}}]}""",
            """{"format":"yoin-sync","schema":1,$header,"records":[{"k":"song_note","id":"n","ts":7,"ts":8,"d":"d","v":{"a":1,"a":2}}]}""",
            """{"format":"yoin-sync","schema":2,$header,"records":[{"k":5}]}""",
            """{"format":"yoin-sync","schema":0,$header,"records":[]}""",
            """{"format":"yoin-sync","schema":1,$header,"records":[],"purges":{"s1":"9"}}""",
            """{"format":"yoin-sync","schema":1,$header,"records":[],"purges":null}""",
            """{"format":"yoin-sync","schema":1,"epoch":3,"deviceId":"dev-x","fileClass":"state","writtenAt":10,"appVersionCode":31,"records":[]}""",
            """{"format":"yoin-sync","schema":1,$header,"records":{}}""",
            """{"format":"yoin-sync","schema":1.5,$header,"records":[]}""",
            """{"format":"yoin-sync","schema":1,$header,"records":[$rec]} trailing""",
        )
        val diffs = cases.mapNotNull { text ->
            val (old, new) = compare(text)
            if (old != new) "INPUT $text\n  legacy: $old\n  new:    $new" else null
        }
        if (diffs.isNotEmpty()) fail("Divergences:\n" + diffs.joinToString("\n"))
    }

    @Test
    fun should_matchLegacyDecoderExactly_across20000RandomMutations() {
        val rnd = Random(42)
        val base = buildString {
            append("""{"format":"yoin-sync","schema":1,$header,"records":[""")
            repeat(6) { i ->
                if (i > 0) append(',')
                append(
                    """{"k":"song_note","kv":1,"s":"s$i","id":"n$i","ts":${100 + i},""" +
                        """"d":"dev-x","pts":$i,"pd":"dev-y","del":false,"v":{"content":"téxt $i","pos":$i}}""",
                )
            }
            append("""],"purges":{"s1":4}}""")
        }
        val alphabet = "{}[]:,\"0123456789-.eEtrufalsn \\ux"
        val diffs = mutableListOf<String>()
        repeat(20_000) {
            val chars = base.toCharArray().toMutableList()
            repeat(1 + rnd.nextInt(3)) {
                when (rnd.nextInt(3)) {
                    0 -> if (chars.isNotEmpty()) chars.removeAt(rnd.nextInt(chars.size))
                    1 -> chars.add(rnd.nextInt(chars.size + 1), alphabet[rnd.nextInt(alphabet.length)])
                    else -> if (chars.isNotEmpty()) chars[rnd.nextInt(chars.size)] = alphabet[rnd.nextInt(alphabet.length)]
                }
            }
            val text = chars.joinToString("")
            val (old, new) = compare(text)
            if (old != new && diffs.size < 15) {
                diffs += "INPUT $text\n  legacy: $old\n  new:    $new"
            }
        }
        if (diffs.isNotEmpty()) fail("Divergences (first ${diffs.size}):\n" + diffs.joinToString("\n"))
    }
}
