package com.gpo.yoin.data.sync.engine

import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.sync.ActiveBinding
import com.gpo.yoin.data.sync.ApplyOutcome
import com.gpo.yoin.data.sync.CanonicalJson
import com.gpo.yoin.data.sync.ConflictPolicy
import com.gpo.yoin.data.sync.DomainRow
import com.gpo.yoin.data.sync.FileClass
import com.gpo.yoin.data.sync.RemoteFile
import com.gpo.yoin.data.sync.SkipReason
import com.gpo.yoin.data.sync.SyncAdapter
import com.gpo.yoin.data.sync.SyncDatabase
import com.gpo.yoin.data.sync.SyncFileProps
import com.gpo.yoin.data.sync.SyncKinds
import com.gpo.yoin.data.sync.SyncRecordEntity
import java.io.IOException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Builds a JSON object from simple Kotlin values. */
fun json(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
    pairs.associate { (key, value) ->
        key to when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            else -> error("Unsupported JSON value $value")
        }
    },
)

/** One device's in-memory domain DB: rows per (kind, localProfileId) -- "" for global kinds. */
class FakeDomain {
    data class Row(val projection: JsonObject, val rowTs: Long?)

    private val tables = HashMap<String, HashMap<String, LinkedHashMap<String, Row>>>()

    fun table(kind: String, localProfileId: String?): LinkedHashMap<String, Row> =
        tables.getOrPut(kind) { HashMap() }.getOrPut(localProfileId ?: "") { LinkedHashMap() }

    /** Local profile ids holding [key] for [kind]. */
    fun owners(kind: String, key: String): List<String> =
        tables[kind].orEmpty().filterValues { key in it }.keys.toList()

    fun clearProfile(localProfileId: String?) {
        tables.values.forEach { it.remove(localProfileId ?: "") }
    }
}

/**
 * Configurable [SyncAdapter] over [FakeDomain]. Projection = the configured [fields] (missing -> null);
 * apply/delete honour the expected-hash guard exactly like the real adapters must.
 */
class FakeAdapter(
    private val domain: FakeDomain,
    override val kind: String,
    private val fields: List<String>,
    override val perAccount: Boolean = true,
    override val propagatesDeletes: Boolean = false,
    override val conflictPolicy: ConflictPolicy = ConflictPolicy.LWW,
    override val fileClass: FileClass = FileClass.STATE,
    override val kindVersion: Int = 1,
    /** KEEP_BOTH_TEXT field. */
    private val textField: String? = null,
    /** false for timestamp-less kinds (settings). */
    private val tracksRowTs: Boolean = true,
    /** song_note-like: a key under another local profile is never re-parented (OWNED_ELSEWHERE). */
    private val keysUniqueAcrossProfiles: Boolean = false,
    /** Stamps the writing device's name into this payload key (decoratePayload). */
    private val decorateKey: String? = null,
    /** A sloppy adapter: OWNED_ELSEWHERE reports the owning profile's row hash instead of null. */
    private val ownedElsewhereReportsOwnerHash: Boolean = false,
) : SyncAdapter {
    var failReads = false
    var failMergeConcurrent = false

    /** decoratePayload throws (a buggy adapter hook inside capture's transaction). */
    var failDecorate = false

    /** apply throws for these keys (an adapter choking on one payload). */
    var failApplyFor: Set<String> = emptySet()

    /** delete throws once this many deletes have succeeded (an interrupted purge). */
    var failDeletesAfter: Int? = null
    private var deletesDone = 0
    var policy: (key: String, payload: JsonObject) -> SkipReason? = { _, _ -> null }
    val applyCalls = mutableListOf<String>()
    val deleteCalls = mutableListOf<String>()

    fun put(localProfileId: String?, key: String, rowTs: Long?, vararg values: Pair<String, Any?>) {
        domain.table(kind, localProfileId)[key] = FakeDomain.Row(project(json(*values)), rowTs)
    }

    fun remove(localProfileId: String?, key: String) {
        domain.table(kind, localProfileId).remove(key)
    }

    fun row(localProfileId: String?, key: String): JsonObject? = domain.table(kind, localProfileId)[key]?.projection

    fun value(localProfileId: String?, key: String, field: String): String? =
        row(localProfileId, key)?.get(field)?.let { (it as? JsonPrimitive)?.contentOrNull }

    fun keys(localProfileId: String?): Set<String> = domain.table(kind, localProfileId).keys.toSet()

    override suspend fun readAll(localProfileId: String?): List<DomainRow> {
        if (failReads) throw IOException("simulated read failure for $kind")
        return domain.table(kind, localProfileId).map { (key, row) ->
            DomainRow(key, row.projection, if (tracksRowTs) row.rowTs else null)
        }
    }

    override fun project(payload: JsonObject): JsonObject = JsonObject(fields.associateWith { payload[it] ?: JsonNull })

    override suspend fun apply(
        localProfileId: String?,
        key: String,
        payload: JsonObject,
        versionTs: Long,
        expectedLocalHash: String?,
    ): ApplyOutcome {
        applyCalls += key
        if (key in failApplyFor) throw IllegalStateException("simulated apply failure for $key")
        val table = domain.table(kind, localProfileId)
        val currentHash = table[key]?.let { CanonicalJson.hash(it.projection) }
        val owner = domain.owners(kind, key).firstOrNull { it != (localProfileId ?: "") }
        if (keysUniqueAcrossProfiles && owner != null) {
            val reported = if (ownedElsewhereReportsOwnerHash) {
                domain.table(kind, owner)[key]?.let { CanonicalJson.hash(it.projection) }
            } else {
                currentHash
            }
            return ApplyOutcome.Skipped(SkipReason.OWNED_ELSEWHERE, reported)
        }
        policy(key, payload)?.let { return ApplyOutcome.Skipped(it, currentHash) }
        if (currentHash != expectedLocalHash) return ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
        val projection = project(payload)
        table[key] = FakeDomain.Row(projection, if (tracksRowTs) versionTs else null)
        return ApplyOutcome.Applied(CanonicalJson.hash(projection))
    }

    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome {
        deleteCalls += key
        failDeletesAfter?.let { limit -> if (deletesDone >= limit) throw IOException("simulated delete failure") }
        val table = domain.table(kind, localProfileId)
        val currentHash = table[key]?.let { CanonicalJson.hash(it.projection) }
        if (currentHash != expectedLocalHash) return ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
        table.remove(key)
        deletesDone++
        return ApplyOutcome.Applied(null)
    }

    override fun mergeConcurrent(winner: JsonObject, loser: JsonObject, loserLabel: String): JsonObject? {
        if (failMergeConcurrent) throw IllegalStateException("simulated merge failure")
        val field = textField ?: return null
        val winnerText = winner[field]?.jsonPrimitive?.contentOrNull.orEmpty()
        val loserText = loser[field]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (winnerText.isBlank() || loserText.isBlank() || winnerText.contains(loserText)) return null
        return JsonObject(winner + (field to JsonPrimitive("$winnerText\n\n— $loserLabel —\n$loserText")))
    }

    override fun decoratePayload(payload: JsonObject, deviceName: String): JsonObject {
        if (failDecorate) throw IllegalStateException("simulated decorate failure for $kind")
        return decorateKey?.let { JsonObject(payload + (it to JsonPrimitive(deviceName))) } ?: payload
    }
}

/** The adapter set most tests use, mirroring the real kinds' shapes. */
fun standardAdapters(domain: FakeDomain): List<FakeAdapter> = listOf(
    FakeAdapter(
        domain,
        SyncKinds.SONG_NOTE,
        fields = listOf("trackId", "content"),
        propagatesDeletes = true,
        conflictPolicy = ConflictPolicy.LWW_LIVE_WINS,
        keysUniqueAcrossProfiles = true,
    ),
    FakeAdapter(domain, SyncKinds.TRACK_RATING, fields = listOf("songId", "rating")),
    FakeAdapter(domain, SyncKinds.ALBUM_RATING, fields = listOf("albumId", "rating")),
    FakeAdapter(
        domain,
        SyncKinds.ALBUM_REVIEW,
        fields = listOf("albumId", "review"),
        conflictPolicy = ConflictPolicy.KEEP_BOTH_TEXT,
        textField = "review",
    ),
    FakeAdapter(domain, SyncKinds.SETTING, fields = listOf("value"), perAccount = false, tracksRowTs = false),
    FakeAdapter(
        domain,
        SyncKinds.LYRICS_TRANSLATION,
        fields = listOf("lines"),
        perAccount = false,
        fileClass = FileClass.ARTIFACTS,
    ),
)

/** Drive appDataFolder stand-in: one writer per file, a global monotonically increasing version. */
class FakeDrive {
    class StoredFile(
        val id: String,
        val deviceId: String,
        val fileKind: String,
        val shard: Int,
        val version: Long,
        val bytes: ByteArray,
    ) {
        fun remote(): RemoteFile = RemoteFile(
            id = id,
            name = id,
            version = version,
            modifiedTime = null,
            size = bytes.size.toLong(),
            appProperties = mapOf(
                SyncFileProps.KIND to fileKind,
                SyncFileProps.DEVICE_ID to deviceId,
                SyncFileProps.SHARD to shard.toString(),
            ),
        )
    }

    private val files = LinkedHashMap<String, StoredFile>()
    private var nextVersion = 1L

    fun upload(deviceId: String, fileKind: String, shard: Int, bytes: ByteArray): StoredFile {
        val id = "$fileKind-$deviceId-$shard"
        return StoredFile(id, deviceId, fileKind, shard, nextVersion++, bytes).also { files[id] = it }
    }

    fun files(): List<StoredFile> = files.values.toList()

    fun file(deviceId: String, fileKind: String = SyncFileProps.KIND_STATE, shard: Int = 0): StoredFile? =
        files["$fileKind-$deviceId-$shard"]

    fun remove(deviceId: String) {
        files.values.removeAll { it.deviceId == deviceId }
    }
}

/** A simulated install: its own sync DB, engine, clock and fake domain, exchanging files through [FakeDrive]. */
class SimDevice(
    val deviceId: String,
    var name: String,
    startTime: Long,
    adapterFactory: (FakeDomain) -> List<FakeAdapter> = ::standardAdapters,
) {
    val domain = FakeDomain()
    val adapters: List<FakeAdapter> = adapterFactory(domain)
    val db: SyncDatabase = SyncDatabase.inMemory(ApplicationProvider.getApplicationContext())
    var now: Long = startTime
    val engine = SyncEngine(db, adapters, deviceId, { name }, { now })
    val bindings = mutableListOf<ActiveBinding>()
    val dao get() = db.syncDao()

    fun adapter(kind: String): FakeAdapter = adapters.first { it.kind == kind }

    fun bind(localProfileId: String, syncProfileId: String, provider: String = "subsonic") {
        bindings.removeAll { it.localProfileId == localProfileId || it.syncProfileId == syncProfileId }
        bindings += ActiveBinding(localProfileId, syncProfileId, provider)
    }

    /** Merges every remote file whose version differs from the seen one. */
    suspend fun pull(drive: FakeDrive): List<MergeResult> {
        val results = ArrayList<MergeResult>()
        for (file in drive.files()) {
            if (file.deviceId == deviceId) continue
            if (dao.remoteFile(file.id)?.version == file.version) continue
            results += engine.mergeRemote(file.remote(), SnapshotCodec.decode(file.bytes))
        }
        return results
    }

    suspend fun capture(): CaptureResult = engine.capture(bindings)

    suspend fun applyReplica(): ApplyResult = engine.apply(bindings)

    /** Uploads changed files; returns how many were uploaded. */
    suspend fun publish(drive: FakeDrive): Int {
        var uploaded = 0
        for (file in engine.outgoingFiles(epoch = 0L, writtenAt = now, appVersionCode = 1)) {
            if (file.contentHash == engine.lastUploadedHash(file.fileKind, file.shard)) continue
            drive.upload(deviceId, file.fileKind, file.shard, file.bytes)
            engine.markUploaded(file)
            uploaded++
        }
        return uploaded
    }

    /** Spec §4 order: merge, capture, apply, publish. */
    suspend fun cycle(drive: FakeDrive): CycleReport {
        val merges = pull(drive)
        val capture = capture()
        val apply = applyReplica()
        val uploads = publish(drive)
        now += 1_000
        return CycleReport(merges, capture, apply, uploads)
    }

    /** Comparable view of the replica. */
    suspend fun replica(): List<String> = dao.allRecords()
        .sortedWith(compareBy<SyncRecordEntity>({ it.kind }, { it.scope }, { it.key }))
        .map { record ->
            with(record) { listOf(kind, scope, key, ts, device, kindVersion, deleted, payloadHash).joinToString("|") }
        }

    suspend fun record(kind: String, scope: String, key: String): SyncRecordEntity? = dao.getRecord(kind, scope, key)

    fun close() = db.close()
}

data class CycleReport(
    val merges: List<MergeResult>,
    val capture: CaptureResult,
    val apply: ApplyResult,
    val uploads: Int,
)
