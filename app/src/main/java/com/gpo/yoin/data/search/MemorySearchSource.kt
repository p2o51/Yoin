package com.gpo.yoin.data.search

import com.gpo.yoin.data.local.MemoryLedgerDao
import com.gpo.yoin.data.memory.LedgerAlbumLookup
import com.gpo.yoin.data.memory.MemoryLedgerRows
import com.gpo.yoin.data.memory.buildMemoryLedger
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.shared.memory.MemoryLedgerEntry
import com.gpo.yoin.shared.memory.ledgerAlbumKey
import com.gpo.yoin.shared.search.MemoryAttachment
import com.gpo.yoin.shared.search.MemorySearchMatches
import com.gpo.yoin.shared.search.MemorySearchPort
import com.gpo.yoin.shared.search.MemorySearchService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The ledger as search reads it: every account's memories (#178), built once and kept until a write
 * changes them ([MemoryLedgerDao.changes]), so typing doesn't rebuild it on every key. Local only: it
 * runs beside the service's search and can answer before it does.
 *
 * [profileIds] are the accounts that count (a removed account's rows don't), null for all; [lookup]
 * names an album Yoin never played, as the ledger page does. The search itself is :shared's
 * ([MemorySearchService]); this is its [MemorySearchPort] on Room.
 */
class MemorySearchSource(
    private val dao: MemoryLedgerDao,
    private val profileIds: suspend () -> Set<String>?,
    private val lookup: LedgerAlbumLookup = LedgerAlbumLookup { _, _, _ -> null },
) {
    private val service = MemorySearchService(
        object : MemorySearchPort {
            override suspend fun ledger(): List<MemoryLedgerEntry> = this@MemorySearchSource.ledger()

            override suspend fun aiTitles(): Map<String, String> = this@MemorySearchSource.aiTitles()
        },
    )
    private val mutex = Mutex()
    private var stamp: Long? = null
    private var accounts: Set<String>? = null
    private var entries: List<MemoryLedgerEntry> = emptyList()

    /** The memories and notes [query] meets, best first. Blank finds nothing (and reads nothing). */
    suspend fun search(query: String, activeProfileId: String?): MemorySearchMatches =
        service.search(query, activeProfileId)

    /** The memories kept on [albums] in the account in use, by album id ([memoryAttachments]). */
    suspend fun attachments(albums: List<Album>, activeProfileId: String?): Map<MediaId, MemoryAttachment> =
        service.attachments(albums.map { it.id.toRef() }, activeProfileId).mapKeys { (ref, _) -> ref.toMediaId() }

    private suspend fun ledger(): List<MemoryLedgerEntry> = mutex.withLock {
        val now = dao.changes().first()
        val counted = profileIds()
        if (stamp == now && accounts == counted) return entries
        val rows = MemoryLedgerRows(
            albumNotes = dao.albumNotes(),
            songNotes = dao.songNotes(),
            albumRatings = dao.albumRatings(),
            trackRatings = dao.trackRatings(),
            titles = dao.titles(),
            albumFacts = dao.albumFacts(),
        )
        entries = buildMemoryLedger(rows, counted, lookup = lookup)
        stamp = now
        accounts = counted
        entries
    }

    // Read each time: an AI title lands without the ledger changing, and the read is one small table.
    private suspend fun aiTitles(): Map<String, String> = dao.aiTitles().associate { row ->
        ledgerAlbumKey(row.profileId, row.provider, MediaId.storedRawId(row.provider, row.albumId)) to row.title
    }
}
