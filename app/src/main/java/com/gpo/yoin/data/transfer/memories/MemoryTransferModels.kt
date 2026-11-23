package com.gpo.yoin.data.transfer.memories

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.sync.backup.ParsedBackup

/** Where Memories are copied from. Copy, never move: the source is only read. */
sealed interface MemorySource {
    /** A profile on this device. */
    data class LocalProfile(val profileId: String) : MemorySource

    /** An account only in the cloud, read from this device's sync replica (no sign-in needed). */
    data class CloudAccount(val syncProfileId: String) : MemorySource

    /** One account of a backup file that was read ([com.gpo.yoin.data.sync.backup.BackupReader]). */
    data class BackupAccount(val backup: ParsedBackup, val scope: String) : MemorySource
}

/** One side of a transfer. */
data class TransferAccount(
    /** The account's scope (sync id, or "local-<profile id>"): what copied note ids are derived from. */
    val scope: String,
    val provider: String,
    val displayName: String,
    /** Subsonic "host[:port]": two Subsonic accounts share ids only on one server. */
    val serverKey: String?,
    /** The local profile, when the account is on this device. */
    val localProfileId: String?,
)

/**
 * What the UI layer provides: the TARGET account's catalog (to find counterparts) and, where it can, what a
 * SOURCE song or album is (title, artist, duration, ISRC). Search failures should return empty lists.
 */
interface MemoryCatalog {
    /** The source song as its service describes it; null when unknown (no sign-in, cloud-only account). */
    suspend fun describeSourceTrack(provider: String, trackId: String): Track? = null

    /** The source album; null when unknown. */
    suspend fun describeSourceAlbum(provider: String, albumId: String): Album? = null

    /** Target tracks by ISRC, where the target can look ISRCs up. */
    suspend fun tracksByIsrc(isrcs: List<String>): Map<String, List<Track>> = emptyMap()

    suspend fun searchTracks(query: String): List<Track>

    suspend fun searchAlbums(query: String): List<Album>
}

/** A song or album on the source side: provider + raw id. */
data class SourceRef(val provider: String, val id: String) {
    override fun toString(): String = "$provider:$id"
}

enum class MatchStatus {
    /** Same id space: the key is copied as it is. */
    Direct,

    /** Found with confidence (ISRC, title + artist + duration, or the album of its matched songs). */
    Matched,

    /** Close candidates only: not copied unless the user picks one. */
    Uncertain,

    /** Nothing found (or nothing known about the source to search with). */
    Unmatched,

    /** The user picked the counterpart (this run or a previous one). */
    Picked,
}

enum class AlbumMatchMethod {
    /** Most of the album's matched songs are on this target album. */
    Tracks,

    /** Album search by title + artist. */
    Search,
}

data class TrackMatch(
    val source: SourceRef,
    /** What is known about the source song (title, artist...), when anything is. */
    val sourceTrack: Track?,
    val status: MatchStatus,
    /** Best first; for [MatchStatus.Uncertain] up to three. */
    val candidates: List<Track>,
    /** What will be written; null = this song's Memories are not copied. */
    val chosen: Track?,
)

data class AlbumMatch(
    val source: SourceRef,
    val sourceAlbum: Album?,
    val status: MatchStatus,
    val candidates: List<Album>,
    val chosen: Album?,
    val method: AlbumMatchMethod? = null,
)

/** Counts per Memories kind, for previews and results. */
data class TransferCounts(
    val songNotes: Int = 0,
    val trackRatings: Int = 0,
    val albumRatings: Int = 0,
    val albumReviews: Int = 0,
    val albumNotes: Int = 0,
    val albumTitles: Int = 0,
    val memoryTitles: Int = 0,
    val memoryCopies: Int = 0,
    val trackAlbumLinks: Int = 0,
) {
    val total: Int
        get() = songNotes + trackRatings + albumRatings + albumReviews + albumNotes + albumTitles + memoryTitles +
            memoryCopies + trackAlbumLinks
}

/**
 * A transfer ready to run: what will be copied from [source] into [target], and for songs/albums whose ids
 * can't be copied, how each was matched. Adjust with [pickTrack]/[pickAlbum], then
 * [MemoryTransfer.execute]. [tracks]/[albums] list only what needed matching (empty when every id is direct).
 */
data class TransferPlan(
    val source: TransferAccount,
    val target: TransferAccount,
    val items: MemoryItems,
    val tracks: List<TrackMatch>,
    val albums: List<AlbumMatch>,
    /** Items a previous run already copied: skipped this time. */
    val previouslyCopied: Int,
    /** Items this run would copy (before conflicts with what the target already has). */
    val toCopy: TransferCounts,
    internal val ledger: Map<String, String>,
) {
    val matched: List<TrackMatch> get() = tracks.filter { it.status == MatchStatus.Matched || it.status == MatchStatus.Picked }
    val uncertain: List<TrackMatch> get() = tracks.filter { it.status == MatchStatus.Uncertain }
    val unmatched: List<TrackMatch> get() = tracks.filter { it.status == MatchStatus.Unmatched }
    val albumsUncertain: List<AlbumMatch> get() = albums.filter { it.status == MatchStatus.Uncertain }
    val albumsUnmatched: List<AlbumMatch> get() = albums.filter { it.status == MatchStatus.Unmatched }

    /** [track] (a candidate, or one the user found by searching) as [source]'s counterpart; null = don't copy. */
    fun pickTrack(source: SourceRef, track: Track?): TransferPlan = copy(
        tracks = tracks.map { match ->
            if (match.source != source) {
                match
            } else if (track == null) {
                match.copy(status = if (match.candidates.isEmpty()) MatchStatus.Unmatched else MatchStatus.Uncertain, chosen = null)
            } else {
                match.copy(status = MatchStatus.Picked, chosen = track, candidates = (listOf(track) + match.candidates).distinctBy { it.id })
            }
        },
    )

    fun pickAlbum(source: SourceRef, album: Album?): TransferPlan = copy(
        albums = albums.map { match ->
            if (match.source != source) {
                match
            } else if (album == null) {
                match.copy(status = if (match.candidates.isEmpty()) MatchStatus.Unmatched else MatchStatus.Uncertain, chosen = null)
            } else {
                match.copy(status = MatchStatus.Picked, chosen = album, candidates = (listOf(album) + match.candidates).distinctBy { it.id })
            }
        },
    )
}

/** What [MemoryTransfer.execute] did. */
data class TransferResult(
    /** New rows in the target. */
    val copied: TransferCounts,
    /** Target rows updated (a newer source value, a review joined, a missing field filled). */
    val updated: TransferCounts,
    /** The target already had it (same value, or a newer one). */
    val kept: TransferCounts,
    /** Reviews where both texts were kept. */
    val reviewsJoined: Int,
    /** Items whose song or album has no counterpart (yet): retried on the next run. */
    val unmatched: TransferCounts,
    /** Items a previous run already copied. */
    val skippedPreviously: Int,
)
