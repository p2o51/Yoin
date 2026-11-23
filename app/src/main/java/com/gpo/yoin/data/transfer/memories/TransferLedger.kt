package com.gpo.yoin.data.transfer.memories

import androidx.room.withTransaction
import com.gpo.yoin.data.sync.CanonicalJson
import com.gpo.yoin.data.sync.SyncDatabase
import com.gpo.yoin.data.sync.SyncFormat
import com.gpo.yoin.data.sync.SyncMetaEntity

/**
 * What earlier runs of a transfer did, per (source scope, target profile): source item key -> result.
 * Values: `copied|<target key>`, `unmatched`, `matched|<target media id>`, `chosen|<target media id>`
 * (songs/albums the user picked). Reruns skip copied items, reuse earlier matches and picks, retry unmatched.
 */
interface TransferLedger {
    suspend fun read(srcScope: String, dstProfileId: String): Map<String, String>

    suspend fun write(srcScope: String, dstProfileId: String, entries: Map<String, String>)

    companion object {
        const val COPIED = "copied|"
        const val UNMATCHED = "unmatched"
        const val MATCHED = "matched|"
        const val CHOSEN = "chosen|"
    }
}

/**
 * The ledger in the sync side database's key/value table (sync_meta), one row per item, so neither database
 * changes schema. Keys: `transfer:<16 hex of sha256(srcScope ␟ dstProfileId)>:<item key>`. Lives in
 * no_backup like the rest of the sync database; "delete cloud data" and replica resets leave it alone.
 */
class SyncMetaTransferLedger(private val db: SyncDatabase) : TransferLedger {
    private val dao get() = db.syncDao()

    override suspend fun read(srcScope: String, dstProfileId: String): Map<String, String> {
        val prefix = prefix(srcScope, dstProfileId)
        return dao.metaWithPrefix(prefix)
            .filter { it.key.startsWith(prefix) }
            .associate { it.key.removePrefix(prefix) to it.value }
    }

    override suspend fun write(srcScope: String, dstProfileId: String, entries: Map<String, String>) {
        if (entries.isEmpty()) return
        val prefix = prefix(srcScope, dstProfileId)
        db.withTransaction {
            for ((key, value) in entries) dao.putMeta(SyncMetaEntity(prefix + key, value))
        }
    }

    private fun prefix(srcScope: String, dstProfileId: String): String =
        PREFIX + CanonicalJson.sha256Hex(SyncFormat.compositeKey(srcScope, dstProfileId)).take(16) + ":"

    companion object {
        const val PREFIX = "transfer:"
    }
}

/** For tests and previews. */
class InMemoryTransferLedger : TransferLedger {
    private val entries = HashMap<Pair<String, String>, MutableMap<String, String>>()

    override suspend fun read(srcScope: String, dstProfileId: String): Map<String, String> =
        entries[srcScope to dstProfileId].orEmpty().toMap()

    override suspend fun write(srcScope: String, dstProfileId: String, entries: Map<String, String>) {
        this.entries.getOrPut(srcScope to dstProfileId) { HashMap() }.putAll(entries)
    }
}
