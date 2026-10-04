package com.gpo.yoin.data.sync.engine

import com.gpo.yoin.data.sync.RecordVersion
import com.gpo.yoin.data.sync.SkipReason
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Identity of a replicated record. */
data class RecordKey(val kind: String, val scope: String, val key: String)

/** scope -> kind -> count. */
typealias ScopeKindCounts = Map<String, Map<String, Int>>

/** One serialized per-device file, ready to upload if [contentHash] differs from the last uploaded one. */
class OutgoingFile(
    /** [com.gpo.yoin.data.sync.SyncFileProps.KIND_STATE] or [com.gpo.yoin.data.sync.SyncFileProps.KIND_ARTIFACTS]. */
    val fileKind: String,
    val shard: Int,
    /** gzip JSON envelope. */
    val bytes: ByteArray,
    /** [com.gpo.yoin.data.sync.CanonicalJson.hash] over the sorted records only (never writtenAt / deviceName). */
    val contentHash: String,
    /** Version of every record serialized into [bytes]; [SyncEngine.markUploaded] clears pending against these. */
    val includedVersions: Map<RecordKey, RecordVersion>,
) {
    val recordCount: Int get() = includedVersions.size

    override fun toString(): String =
        "OutgoingFile($fileKind#$shard, records=$recordCount, bytes=${bytes.size}, hash=$contentHash)"
}

/** A (kind, scope) whose domain read or write failed this cycle; everything else kept going. */
data class KindScopeFailure(val kind: String, val scope: String, val error: Throwable)

/** A held mass delete (spec §4.5 valve), persisted in sync_meta until the user resolves it. */
@Serializable
data class HeldReview(
    @SerialName("kind") val kind: String,
    @SerialName("scope") val scope: String,
    @SerialName("keys") val keys: List<String>,
) {
    val count: Int get() = keys.size
}

data class MergeResult(
    val fileId: String,
    val deviceId: String,
    val deviceName: String,
    /** Records in the envelope. */
    val received: Int,
    /** Records new to the replica. */
    val inserted: Int,
    /** Existing records replaced by a newer (or tie-winning) remote version. */
    val replaced: Int,
    /** Remote versions concurrent with an unpublished local change. */
    val conflicts: Int,
    /** Concurrent text edits merged into one version that keeps both texts. */
    val keptBoth: Int,
    /** Unpublished local changes that lost to a remote version. */
    val localChangesDropped: Int,
    /** Records of kinds (or kind versions) this app does not understand: stored opaquely and republished. */
    val opaque: Int,
    /** Records skipped because they are structurally invalid (e.g. live without payload). */
    val malformed: Int,
    /** Replica records that changed value, scope -> kind -> count. */
    val changedByScope: ScopeKindCounts,
) {
    val changed: Boolean get() = inserted + replaced + keptBoth > 0
}

data class CaptureResult(
    /** Live versions this device wrote (new rows, edits, winning join candidates, merged texts). */
    val newVersions: ScopeKindCounts,
    /** Tombstones this device wrote. */
    val tombstonesCreated: ScopeKindCounts,
    /** Join reconciliations where local and cloud texts were both kept. */
    val keptBoth: Int,
    /** Rows found already equal to the cloud on join (no version). */
    val adopted: Int,
    /** Rows that already held the replica value after an interrupted apply (no version). */
    val repaired: Int,
    /** Rows where the cloud wins on join; the next apply overwrites or deletes them. */
    val cloudWins: Int,
    /** Local states dropped for missing rows of kinds that never delete (re-applied next). */
    val droppedLocalStates: Int,
    /** Scopes whose domain data looked recreated: local state dropped, nothing tombstoned. */
    val domainResets: List<String>,
    /** Candidate tombstones held back by the valve this cycle. */
    val heldTombstones: Int,
    /** The held review stored in sync_meta after this capture, if any. */
    val heldReview: HeldReview?,
    val failures: List<KindScopeFailure>,
) {
    val totalNewVersions: Int get() = newVersions.total()
    val totalTombstones: Int get() = tombstonesCreated.total()
    val changed: Boolean get() = totalNewVersions + totalTombstones > 0
}

data class ApplyResult(
    /** Live records written to the domain DB, scope -> kind -> count (e.g. "Restored 128 notes for alice"). */
    val appliedLive: ScopeKindCounts,
    /** Tombstones that removed a row this device knew about, scope -> kind -> count. */
    val removed: ScopeKindCounts,
    /** Rows edited locally since capture; the next capture picks them up. */
    val skippedChangedLocally: Int,
    /** Other declined applies (policy, owned elsewhere, unsupported payload). */
    val skipped: Map<SkipReason, Int>,
    /** Records of a newer kind version than this app supports (never applied). */
    val unsupported: Int,
    val failures: List<KindScopeFailure>,
) {
    val totalApplied: Int get() = appliedLive.total()
    val totalRemoved: Int get() = removed.total()
}

fun ScopeKindCounts.total(): Int = values.sumOf { it.values.sum() }

/** Count for [kind] in [scope], 0 when absent. */
fun ScopeKindCounts.count(scope: String, kind: String): Int = this[scope]?.get(kind) ?: 0

internal class ScopeKindCounter {
    private val counts = LinkedHashMap<String, LinkedHashMap<String, Int>>()

    fun add(scope: String, kind: String, delta: Int = 1) {
        val byKind = counts.getOrPut(scope) { LinkedHashMap() }
        byKind[kind] = (byKind[kind] ?: 0) + delta
    }

    fun addAll(other: ScopeKindCounter) {
        for ((scope, byKind) in other.counts) for ((kind, count) in byKind) add(scope, kind, count)
    }

    fun snapshot(): ScopeKindCounts = counts.mapValues { (_, byKind) -> byKind.toMap() }
}
