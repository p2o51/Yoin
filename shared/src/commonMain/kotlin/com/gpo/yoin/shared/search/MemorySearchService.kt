package com.gpo.yoin.shared.search

import com.gpo.yoin.shared.data.model.MediaRef
import com.gpo.yoin.shared.memory.MemoryLedgerEntry

/*
 * The ledger search reads, behind a port: each platform builds its ledger from its own stores (Android: Room,
 * data/search/MemorySearchSource.kt, which also caches it between writes; iOS: its MemoryLedgerSource).
 */

/** Where search reads the ledger from. */
interface MemorySearchPort {
    /** Every counted account's ledger entries. */
    suspend fun ledger(): List<MemoryLedgerEntry>

    /** AI-written memory titles by album key ([com.gpo.yoin.shared.memory.ledgerAlbumKey]). */
    suspend fun aiTitles(): Map<String, String>
}

class MemorySearchService(private val port: MemorySearchPort) {
    /** The memories and notes [query] meets, best first. Blank finds nothing (and reads nothing). */
    suspend fun search(query: String, activeProfileId: String?): MemorySearchMatches {
        val parsed = SearchQuery(query)
        if (parsed.isBlank) return MemorySearchMatches.Empty
        return searchMemoryLedger(port.ledger(), parsed, activeProfileId, port.aiTitles())
    }

    /** The memories kept on [albums] in the account in use ([memoryAttachments]); reads nothing without either. */
    suspend fun attachments(albums: List<MediaRef>, activeProfileId: String?): Map<MediaRef, MemoryAttachment> {
        if (albums.isEmpty() || activeProfileId == null) return emptyMap()
        return memoryAttachments(albums, port.ledger(), activeProfileId, port.aiTitles())
    }
}
