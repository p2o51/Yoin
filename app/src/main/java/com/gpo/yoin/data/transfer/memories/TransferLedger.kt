package com.gpo.yoin.data.transfer.memories

import androidx.room.withTransaction
import com.gpo.yoin.data.sync.SyncDatabase
import com.gpo.yoin.data.sync.SyncMetaEntity
import com.gpo.yoin.shared.portability.memories.TransferLedger

/**
 * The [TransferLedger] in the sync side database's key/value table (sync_meta), one row per item, so neither
 * database changes schema. Keys: [TransferLedger.metaPrefix] + item key
 * (`transfer:<16 hex of sha256(srcScope ␟ dstProfileId)>:<item key>`). Lives in no_backup like the rest of
 * the sync database; "delete cloud data" and replica resets leave it alone.
 */
class SyncMetaTransferLedger(private val db: SyncDatabase) : TransferLedger {
    private val dao get() = db.syncDao()

    override suspend fun read(srcScope: String, dstProfileId: String): Map<String, String> {
        val prefix = TransferLedger.metaPrefix(srcScope, dstProfileId)
        return dao.metaWithPrefix(prefix)
            .filter { it.key.startsWith(prefix) }
            .associate { it.key.removePrefix(prefix) to it.value }
    }

    override suspend fun write(srcScope: String, dstProfileId: String, entries: Map<String, String>) {
        if (entries.isEmpty()) return
        val prefix = TransferLedger.metaPrefix(srcScope, dstProfileId)
        db.withTransaction {
            for ((key, value) in entries) dao.putMeta(SyncMetaEntity(prefix + key, value))
        }
    }

    companion object {
        const val PREFIX = TransferLedger.META_PREFIX
    }
}
