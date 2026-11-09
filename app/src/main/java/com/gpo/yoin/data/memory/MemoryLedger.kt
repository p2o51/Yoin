package com.gpo.yoin.data.memory

import com.gpo.yoin.data.local.AlbumMemoryTitle
import com.gpo.yoin.data.local.AlbumNote
import com.gpo.yoin.data.local.AlbumRating
import com.gpo.yoin.data.local.LedgerAlbumFactRow
import com.gpo.yoin.data.local.LedgerSongNoteRow
import com.gpo.yoin.data.local.LedgerTrackRatingRow
import com.gpo.yoin.data.model.MediaId
import kotlinx.coroutines.CancellationException

/*
 * The memory ledger (owner 2026-11-08): every memory the listener has kept, on one timeline, across all
 * their accounts. The pull-down Memories deck deals them one at a time; the ledger is the whole account
 * book: Home's Memories grid and the All Memories page read it. Local only.
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
    /** The cover's storage key ([com.gpo.yoin.data.model.CoverRef.toStorageKey]); null when unknown. */
    val coverKey: String?,
) {
    /** One album in one account: two accounts on the same album are two albums here, as everywhere. */
    val key: String get() = ledgerAlbumKey(profileId, provider, albumId)

    val mediaId: MediaId get() = MediaId(provider, albumId)
}

internal fun ledgerAlbumKey(profileId: String, provider: String, albumId: String): String =
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
    /** Where in the song a note was written (ms). */
    val positionMs: Long? = null,
    /** An album's or track's score, 0–10. */
    val score: Float? = null,
)

data class MemoryLedgerStats(
    /** Albums with any memory. */
    val albums: Int,
    /** Notes and reviews (titles and scores aren't writing you'd count). */
    val writings: Int,
    /** Characters written in them; a CJK character counts as one, like everything else. */
    val characters: Int,
    /** Tracks with a score. */
    val ratedTracks: Int,
    /** The first memory's time; null without any. */
    val firstAt: Long?,
)

fun memoryLedgerStats(entries: List<MemoryLedgerEntry>): MemoryLedgerStats {
    val writings = entries.filter {
        it.kind == MemoryLedgerKind.AlbumNote ||
            it.kind == MemoryLedgerKind.SongNote ||
            it.kind == MemoryLedgerKind.Review
    }
    return MemoryLedgerStats(
        albums = entries.mapNotNullTo(HashSet()) { it.album?.key }.size,
        writings = writings.size,
        characters = writings.sumOf { entry -> entry.text?.codePointCount(0, entry.text.length) ?: 0 },
        ratedTracks = entries.count { it.kind == MemoryLedgerKind.TrackRating },
        firstAt = entries.minOfOrNull { it.at },
    )
}

/** The ledger's raw rows, as [com.gpo.yoin.data.local.MemoryLedgerDao] reads them. */
data class MemoryLedgerRows(
    val albumNotes: List<AlbumNote> = emptyList(),
    val songNotes: List<LedgerSongNoteRow> = emptyList(),
    val albumRatings: List<AlbumRating> = emptyList(),
    val trackRatings: List<LedgerTrackRatingRow> = emptyList(),
    val titles: List<AlbumMemoryTitle> = emptyList(),
    val albumFacts: List<LedgerAlbumFactRow> = emptyList(),
)

/** Name, artist and cover for an album Yoin never played (its cached detail), or null. */
fun interface LedgerAlbumLookup {
    suspend fun find(profileId: String, provider: String, albumId: String): MemoryLedgerAlbum?
}

/**
 * Turns [rows] into entries, newest first. Only accounts in [profileIds] count (a removed account's rows
 * don't); null counts every row. An album's name, artist and cover come from, in order: Yoin's own plays,
 * an album note (which carries the name), then [lookup] (which also fills a missing cover). A song note or track score whose album is
 * unknown keeps a null album; an album entry whose album can't be named at all is left out.
 */
suspend fun buildMemoryLedger(
    rows: MemoryLedgerRows,
    profileIds: Set<String>?,
    // A scored song's name Yoin never saw played or noted: from its album's cached tracks.
    trackTitle: suspend (album: MemoryLedgerAlbum, songId: String) -> String? = { _, _ -> null },
    lookup: LedgerAlbumLookup = LedgerAlbumLookup { _, _, _ -> null },
): List<MemoryLedgerEntry> {
    fun counts(profileId: String) = profileIds == null || profileId in profileIds
    val albums = HashMap<String, MemoryLedgerAlbum?>()
    rows.albumFacts.forEach { fact ->
        val id = MediaId.storedRawId(fact.provider, fact.albumId)
        albums[ledgerAlbumKey(fact.profileId, fact.provider, id)] = MemoryLedgerAlbum(
            profileId = fact.profileId,
            provider = fact.provider,
            albumId = id,
            name = fact.albumName,
            artist = fact.artist,
            coverKey = fact.coverArtId?.takeIf { it.isNotBlank() },
        )
    }
    rows.albumNotes.forEach { note ->
        val id = MediaId.storedRawId(note.provider, note.albumId)
        val key = ledgerAlbumKey(note.profileId, note.provider, id)
        if (albums[key] == null && note.albumName.isNotBlank()) {
            albums[key] = MemoryLedgerAlbum(
                note.profileId,
                note.provider,
                id,
                note.albumName,
                note.artist,
                coverKey = null,
            )
        }
    }
    val asked = HashSet<String>()
    suspend fun album(profileId: String, provider: String, storedId: String?): MemoryLedgerAlbum? {
        if (storedId.isNullOrBlank()) return null
        val id = MediaId.storedRawId(provider, storedId)
        val key = ledgerAlbumKey(profileId, provider, id)
        val known = albums[key]
        if (known?.coverKey != null || !asked.add(key)) return known
        // Unknown, or known without a cover: the cached detail may have one (asked once per album).
        val found = try {
            lookup.find(profileId, provider, id)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
        val merged = when {
            known == null -> found
            found?.coverKey != null -> known.copy(coverKey = found.coverKey)
            else -> known
        }
        albums[key] = merged
        return merged
    }

    val out = ArrayList<MemoryLedgerEntry>()
    rows.albumNotes.filter { counts(it.profileId) }.forEach { note ->
        val album = album(note.profileId, note.provider, note.albumId) ?: return@forEach
        out += MemoryLedgerEntry(
            id = "album-note:${note.id}",
            kind = MemoryLedgerKind.AlbumNote,
            at = note.createdAt,
            profileId = note.profileId,
            album = album,
            text = note.content,
        )
    }
    rows.songNotes.filter { counts(it.note.profileId) }.forEach { row ->
        val note = row.note
        out += MemoryLedgerEntry(
            id = "song-note:${note.id}",
            kind = MemoryLedgerKind.SongNote,
            at = note.createdAt,
            profileId = note.profileId,
            album = album(note.profileId, note.provider, row.linkedAlbumId),
            text = note.content,
            trackTitle = note.title.takeIf { it.isNotBlank() },
            songId = MediaId.storedRawId(note.provider, note.trackId),
            positionMs = note.positionMs,
        )
    }
    rows.albumRatings.filter { counts(it.profileId) }.forEach { rating ->
        val album = album(rating.profileId, rating.provider, rating.albumId) ?: return@forEach
        val base = "${rating.profileId}|${rating.provider}|${album.albumId}"
        if (rating.rating > 0f) {
            out += MemoryLedgerEntry(
                id = "album-rating:$base",
                kind = MemoryLedgerKind.AlbumRating,
                at = rating.updatedAt,
                profileId = rating.profileId,
                album = album,
                score = rating.rating,
            )
        }
        val review = rating.review?.trim()
        if (!review.isNullOrEmpty()) {
            out += MemoryLedgerEntry(
                id = "review:$base",
                kind = MemoryLedgerKind.Review,
                at = rating.reviewUpdatedAt ?: rating.updatedAt,
                profileId = rating.profileId,
                album = album,
                text = review,
            )
        }
    }
    rows.trackRatings.filter { counts(it.profileId) }.forEach { row ->
        val score = row.rating ?: return@forEach
        val album = album(row.profileId, row.provider, row.linkedAlbumId)
        // Which song was scored: two scores on one album must read apart.
        val title = row.title?.takeIf { it.isNotBlank() }
            ?: album?.let { known ->
                try {
                    trackTitle(known, MediaId.storedRawId(row.provider, row.songId))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
            }?.takeIf { it.isNotBlank() }
        out += MemoryLedgerEntry(
            id = "track-rating:${row.profileId}|${row.provider}|${row.songId}",
            kind = MemoryLedgerKind.TrackRating,
            at = row.updatedAt,
            profileId = row.profileId,
            album = album,
            trackTitle = title,
            songId = MediaId.storedRawId(row.provider, row.songId),
            score = score,
        )
    }
    rows.titles.filter { counts(it.profileId) }.forEach { title ->
        val album = album(title.profileId, title.provider, title.albumId) ?: return@forEach
        out += MemoryLedgerEntry(
            id = "title:${title.profileId}|${title.provider}|${album.albumId}",
            kind = MemoryLedgerKind.Title,
            at = title.updatedAt,
            profileId = title.profileId,
            album = album,
            text = title.title,
        )
    }
    return out.sortedWith(compareByDescending<MemoryLedgerEntry> { it.at }.thenBy { it.id })
}
