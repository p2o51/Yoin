package com.gpo.yoin.shared.memory

/*
 * The memory ledger's entries (owner 2026-11-08): every memory the listener has kept, on one timeline, across
 * all their accounts. The shape search reads (com.gpo.yoin.shared.search); each platform builds the entries
 * from its own stores (Android: data/memory/MemoryLedger.kt buildMemoryLedger; iOS: Data/Memories/MemoryLedger.swift).
 */

/** What kind of mark a ledger entry is. The first four are writing; the last two are scores. */
enum class MemoryLedgerKind(val wrote: Boolean) {
    AlbumNote(true),
    SongNote(true),
    Review(true),
    Title(true),
    AlbumRating(false),
    TrackRating(false),
}

/** The album an entry belongs to, in its own account: what the grid colours a cell with. */
data class MemoryLedgerAlbum(
    val profileId: String,
    val provider: String,
    /** Raw id in [provider]'s namespace. */
    val albumId: String,
    val name: String,
    val artist: String,
    /** The cover's storage key (Android: CoverRef.toStorageKey); null when unknown. */
    val coverKey: String?,
) {
    /** One album in one account: two accounts on the same album are two albums here, as everywhere. */
    val key: String get() = ledgerAlbumKey(profileId, provider, albumId)
}

/** `profileId|provider|albumId`: one album in one account. */
fun ledgerAlbumKey(profileId: String, provider: String, albumId: String): String =
    "$profileId|$provider|$albumId"

data class MemoryLedgerEntry(
    /** Stable across reloads: the kind plus the row's own key. */
    val id: String,
    val kind: MemoryLedgerKind,
    /** When it was written or scored (epoch ms). */
    val at: Long,
    val profileId: String,
    /** Null for a song note or track score whose album Yoin never learned. */
    val album: MemoryLedgerAlbum?,
    /** A note's or review's text, or the title itself. */
    val text: String? = null,
    /** The song a song note or track score is about, when known. */
    val trackTitle: String? = null,
    /** That song's raw id (song notes and track scores): what tells two songs of one name apart. */
    val songId: String? = null,
    /** That song's provider (song notes and track scores), known even when its album isn't. */
    val songProvider: String? = null,
    /** Where in the song a note was written (ms). */
    val positionMs: Long? = null,
    /** An album's or track's score, 0–10. */
    val score: Float? = null,
)
