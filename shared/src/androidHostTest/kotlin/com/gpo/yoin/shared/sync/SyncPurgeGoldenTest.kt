package com.gpo.yoin.shared.sync

import com.gpo.yoin.shared.sync.engine.SimDevice
import com.gpo.yoin.shared.sync.engine.SnapshotCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The golden vectors other ports of the scope purge ("remove this account from the cloud", docs/cloud-sync.md)
 * are tested against (HarmonyOS's Dart engine: harmony/test/data/sync/sync_purge_golden_test.dart). Every
 * expected value is what this Kotlin computes; the test fails when the checked-in file drifts from it.
 *
 * File: shared/src/commonTest/resources/golden/sync-purge.json. Sections:
 * - `markerCodec`: [SnapshotCodec.encodePurge] text and [SyncFileProps.purgeFileName] for a marker.
 * - `markerDecode`: [SnapshotCodec.decodePurge] on raw text (null = rejected).
 * - `merge`: which records of a peer file a replica with purges applied takes (the envelope goes through
 *   [SnapshotCodec.decode]), how many it refuses, and the replica afterwards.
 * - `apply`: [com.gpo.yoin.shared.sync.engine.SyncEngine.applyScopePurge] call by call on a replica.
 * - `stamp`: a capture after a purge (a resumed binding): the versions' times and the published `purges`.
 *
 * Regenerate after an intentional change:
 * `YOIN_WRITE_SYNC_GOLDEN=1 ./gradlew :shared:testAndroidHostTest --tests '*SyncPurgeGoldenTest*'`.
 */
class SyncPurgeGoldenTest {

    @Test
    fun should_matchCheckedInGolden_when_kotlinComputesEveryVector() = runTest {
        val json = goldenJson()
        val file = goldenFile()
        if (System.getenv("YOIN_WRITE_SYNC_GOLDEN") == "1" || !file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(json)
        }
        assertEquals(file.readText(), json)
    }

    private fun goldenFile(): File {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "shared").isDirectory && File(it, "settings.gradle.kts").isFile }
        return File(root, "shared/src/commonTest/resources/golden/sync-purge.json")
    }

    private suspend fun goldenJson(): String {
        val sections = linkedMapOf(
            "markerCodec" to markerCorpus().map(::markerVector),
            "markerDecode" to decodeCorpus().map(::decodeVector),
            "merge" to mergeCorpus().map { mergeVector(it) },
            "apply" to listOf(applyVector()),
            "stamp" to stampCorpus().map { stampVector(it) },
        )
        val about = "Golden vectors for scope purge markers (remove an account from the cloud), generated from " +
            ":shared Kotlin by SyncPurgeGoldenTest (androidHostTest). Do not edit by hand."
        return buildString {
            append("{\n")
            append("  \"about\": ").append(encode(str(about)))
            sections.forEach { (name, vectors) ->
                append(",\n  \"").append(name).append("\": [\n")
                vectors.forEachIndexed { i, vector ->
                    append("    ").append(encode(vector))
                    append(if (i == vectors.lastIndex) "\n" else ",\n")
                }
                append("  ]")
            }
            append("\n}\n")
        }
    }

    // ── markers ──────────────────────────────────────────────────────

    private fun markerCorpus(): List<ScopePurgeMarker> = listOf(
        ScopePurgeMarker("s-0123456789abcdef", 1_800_000_000_000, 1_800_000_000_123, "a1b2c3d4e5f60718", "Pixel 9"),
        ScopePurgeMarker("s-a", 2_000, 2_001, "dev", ""),
        ScopePurgeMarker("am-pixel", 1_800_000_000_000, 1_799_999_999_000, "f00dbabe00000001", "Mate 70 的 \"平板\" \\ ✓"),
        ScopePurgeMarker("s-b", 9_007_199_254_740_991, 0, "d", "Ünïcødé 🎵"),
    )

    private fun markerVector(marker: ScopePurgeMarker): JsonObject = obj(
        "marker" to markerJson(marker),
        "json" to str(SnapshotCodec.encodePurge(marker).decodeToString()),
        "fileName" to str(SyncFileProps.purgeFileName(marker.epoch, marker.deviceId)),
    )

    private fun decodeCorpus(): List<String> = listOf(
        """{"scope":"s-a","epoch":5,"at":6,"deviceId":"d","deviceName":"n"}""",
        """{"scope":"s-a","epoch":5,"at":6,"deviceId":"d","deviceName":"n","future":true}""",
        """{"deviceName":"n","deviceId":"d","at":6,"epoch":5,"scope":"s-a"}""",
        """{"scope":"","epoch":5,"at":6,"deviceId":"d","deviceName":"n"}""",
        """{"epoch":5,"at":6,"deviceId":"d","deviceName":"n"}""",
        """{"scope":"s-a","at":6,"deviceId":"d","deviceName":"n"}""",
        """{"scope":"s-a","epoch":"5","at":6,"deviceId":"d","deviceName":"n"}""",
        """[]""",
        """not json""",
    )

    private fun decodeVector(text: String): JsonObject {
        val marker = runCatching { SnapshotCodec.decodePurge(text.encodeToByteArray()) }.getOrNull()
        return obj("text" to str(text), "marker" to (marker?.let(::markerJson) ?: JsonNull))
    }

    private fun markerJson(m: ScopePurgeMarker) = obj(
        "scope" to str(m.scope),
        "epoch" to num(m.epoch),
        "at" to num(m.at),
        "deviceId" to str(m.deviceId),
        "deviceName" to str(m.deviceName),
    )

    // ── merge ────────────────────────────────────────────────────────

    private class MergeCase(
        val name: String,
        val applied: Map<String, Long>,
        val purges: Map<String, Long>?,
        val records: List<JsonObject>,
    )

    private fun rec(kind: String, scope: String, key: String, ts: Long, deleted: Boolean = false, payload: JsonObject? = null) =
        obj(
            "k" to str(kind),
            "kv" to num(1),
            "s" to str(scope),
            "id" to str(key),
            "ts" to num(ts),
            "d" to str("peer"),
            "del" to JsonPrimitive(deleted),
            "v" to (if (deleted) JsonNull else payload ?: obj("content" to str("$kind $key"))),
        ).let { JsonObject(it.filterValues { v -> v != JsonNull }) }

    /** A purged account's records, another account's, its descriptor and a global setting, before and after. */
    private fun mixedRecords(): List<JsonObject> = listOf(
        rec(SyncKinds.SONG_NOTE, "s-a", "n-before", 2_500),
        rec(SyncKinds.SONG_NOTE, "s-a", "n-at", 3_000),
        rec(SyncKinds.SONG_NOTE, "s-a", "n-after", 3_001),
        rec(SyncKinds.TRACK_RATING, "s-a", "subsonic\u001Ftr-1", 50_000),
        rec(SyncKinds.SONG_NOTE, "s-a", "n-gone", 4_000, deleted = true),
        rec(SyncKinds.SONG_NOTE, "s-b", "b-1", 1_000),
        rec(SyncKinds.ACCOUNT, SyncFormat.GLOBAL_SCOPE, "s-a", 1_000, payload = obj("provider" to str("subsonic"))),
        rec(SyncKinds.ACCOUNT, SyncFormat.GLOBAL_SCOPE, "s-a2", 3_500, payload = obj("provider" to str("subsonic"))),
        rec(SyncKinds.ACCOUNT, SyncFormat.GLOBAL_SCOPE, "s-b", 1_000, payload = obj("provider" to str("subsonic"))),
        rec(SyncKinds.SETTING, SyncFormat.GLOBAL_SCOPE, "seam_top_style", 1_000, payload = obj("value" to str("fade"))),
    )

    private fun mergeCorpus(): List<MergeCase> = listOf(
        MergeCase("nothing purged here: every record is taken", emptyMap(), null, mixedRecords()),
        MergeCase("the file knows a purge this device doesn't: every record is taken", emptyMap(), mapOf("s-a" to 3_000), mixedRecords()),
        MergeCase("a writer that doesn't know the purge: the scope is refused, whatever its clock", mapOf("s-a" to 3_000), null, mixedRecords()),
        MergeCase("a writer that knows another scope's purge only", mapOf("s-a" to 3_000), mapOf("s-b" to 9_000), mixedRecords()),
        MergeCase("a writer that knows an older purge of the scope", mapOf("s-a" to 3_000), mapOf("s-a" to 2_999), mixedRecords()),
        MergeCase("a writer that knows the purge: only versions after the epoch", mapOf("s-a" to 3_000), mapOf("s-a" to 3_000), mixedRecords()),
        MergeCase("a writer that knows a newer purge: still cut at this device's epoch", mapOf("s-a" to 3_000), mapOf("s-a" to 4_000), mixedRecords()),
        MergeCase(
            "two purges, the writer knows both",
            mapOf("s-a" to 3_000, "s-b" to 500),
            mapOf("s-a" to 3_000, "s-b" to 500),
            mixedRecords(),
        ),
        MergeCase(
            "two purges, the writer knows one",
            mapOf("s-a" to 3_000, "s-b" to 500),
            mapOf("s-b" to 500),
            mixedRecords(),
        ),
        MergeCase(
            "a descriptor follows its account (key = scope)",
            mapOf("s-a" to 3_000),
            mapOf("s-a" to 3_000),
            listOf(
                rec(SyncKinds.ACCOUNT, SyncFormat.GLOBAL_SCOPE, "s-a", 3_000, payload = obj("provider" to str("subsonic"))),
                rec(SyncKinds.ACCOUNT, SyncFormat.GLOBAL_SCOPE, "s-a", 3_001, payload = obj("provider" to str("subsonic"))),
            ),
        ),
    )

    private suspend fun mergeVector(case: MergeCase): JsonObject {
        val envelope = obj(
            "format" to str(SyncFormat.FORMAT),
            "schema" to num(SyncFormat.SCHEMA.toLong()),
            "epoch" to num(0),
            "deviceId" to str("peer"),
            "deviceName" to str("Peer"),
            "fileClass" to str("state"),
            "shard" to num(0),
            "writtenAt" to num(60_000),
            "appVersionCode" to num(1),
            "records" to JsonArray(case.records),
        ).let { if (case.purges == null) it else JsonObject(it + ("purges" to obj(*case.purges.map { (k, v) -> k to num(v) }.toTypedArray()))) }
        val phone = SimDevice("phone", "Phone", 100_000)
        case.applied.forEach { (scope, epoch) -> phone.engine.applyScopePurge(scope, epoch) }
        val decoded = SnapshotCodec.decode(encode(envelope).encodeToByteArray())
        val file = RemoteFile("peer-state", "yoin-state-peer.json.gz", 1, null, null, emptyMap())
        val result = phone.engine.mergeRemote(file, decoded)
        return obj(
            "name" to str(case.name),
            "applied" to obj(*case.applied.map { (k, v) -> k to num(v) }.toTypedArray()),
            "envelope" to envelope,
            "received" to num(result.received.toLong()),
            "purged" to num(result.purged.toLong()),
            "replica" to replicaJson(phone),
        )
    }

    private suspend fun replicaJson(device: SimDevice): JsonArray = JsonArray(
        device.dao.allRecords()
            .sortedWith(compareBy<SyncRecordEntity>({ it.kind }, { it.scope }, { it.key }))
            .map { r -> str(listOf(r.kind, r.scope, r.key, r.ts, r.device, r.deleted).joinToString("|")) },
    )

    // ── apply ────────────────────────────────────────────────────────

    private suspend fun applyVector(): JsonObject {
        val phone = SimDevice("phone", "Phone", 100_000)
        val seed = obj(
            "format" to str(SyncFormat.FORMAT),
            "schema" to num(SyncFormat.SCHEMA.toLong()),
            "deviceId" to str("peer"),
            "deviceName" to str("Peer"),
            "fileClass" to str("state"),
            "writtenAt" to num(60_000),
            "appVersionCode" to num(1),
            "records" to JsonArray(mixedRecords()),
        )
        phone.engine.mergeRemote(
            RemoteFile("peer-state", "yoin-state-peer.json.gz", 1, null, null, emptyMap()),
            SnapshotCodec.decode(encode(seed).encodeToByteArray()),
        )
        val before = replicaJson(phone)
        val calls = listOf("s-a" to 3_000L, "s-a" to 3_000L, "s-a" to 2_000L, "s-b" to 500L, "s-a" to 5_000L, "s-zzz" to 1L)
        val steps = calls.map { (scope, epoch) ->
            val removed = phone.engine.applyScopePurge(scope, epoch)
            obj(
                "scope" to str(scope),
                "epoch" to num(epoch),
                "removed" to num(removed.toLong()),
                "purgedScopes" to obj(*phone.engine.purgedScopes().toSortedMap().map { (k, v) -> k to num(v) }.toTypedArray()),
                "replica" to replicaJson(phone),
            )
        }
        return obj("seed" to seed, "replicaBefore" to before, "calls" to JsonArray(steps))
    }

    // ── stamp ────────────────────────────────────────────────────────

    private class StampCase(val name: String, val applied: Map<String, Long>, val now: Long, val rows: List<Pair<String, Long>>)

    private fun stampCorpus(): List<StampCase> = listOf(
        StampCase(
            "a resumed account's old rows move after the epoch",
            mapOf("s-a" to 9_000),
            10_000,
            listOf("n-old" to 1_000L, "n-at" to 9_000L, "n-after" to 9_500L),
        ),
        StampCase("no purge: rows keep their own time", emptyMap(), 10_000, listOf("n-old" to 1_000L, "n-after" to 9_500L)),
        StampCase(
            "another scope's purge changes nothing here, but is still acknowledged",
            mapOf("s-other" to 9_000),
            10_000,
            listOf("n-old" to 1_000L),
        ),
        StampCase(
            "an epoch ahead of the clock still wins",
            mapOf("s-a" to 50_000, "s-z" to 7),
            10_000,
            listOf("n-old" to 1_000L, "n-now" to 10_000L),
        ),
    )

    private suspend fun stampVector(case: StampCase): JsonObject {
        val phone = SimDevice("phone", "Phone", case.now)
        val notes = phone.adapter(SyncKinds.SONG_NOTE)
        case.rows.forEach { (key, ts) -> notes.put("p", key, ts, "trackId" to "t-$key", "content" to "note $key") }
        case.applied.forEach { (scope, epoch) -> phone.engine.applyScopePurge(scope, epoch) }
        phone.bind("p", "s-a")
        phone.capture()
        val state = phone.engine.outgoingFiles(epoch = 0, writtenAt = case.now, appVersionCode = 1)
            .first { it.fileKind == SyncFileProps.KIND_STATE }
        val published = SnapshotCodec.decode(state.bytes)
        return obj(
            "name" to str(case.name),
            "applied" to obj(*case.applied.map { (k, v) -> k to num(v) }.toTypedArray()),
            "now" to num(case.now),
            "scope" to str("s-a"),
            "rows" to JsonArray(case.rows.map { (key, ts) -> obj("key" to str(key), "rowTs" to num(ts)) }),
            "versions" to obj(
                *case.rows.map { (key, _) -> key to num(phone.record(SyncKinds.SONG_NOTE, "s-a", key)!!.ts) }.toTypedArray(),
            ),
            "publishedPurges" to (published.purges?.let { p -> obj(*p.toSortedMap().map { (k, v) -> k to num(v) }.toTypedArray()) } ?: JsonNull),
        )
    }

    // ── json helpers ─────────────────────────────────────────────────

    private fun encode(element: JsonElement): String = Json.encodeToString(JsonElement.serializer(), element)

    private fun str(value: String) = JsonPrimitive(value)

    private fun num(value: Long) = JsonPrimitive(value)

    private fun num(value: Int) = JsonPrimitive(value.toLong())

    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*pairs))
}
