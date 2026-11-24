package com.gpo.yoin.shared.portability.memories

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

/** A song or album on the source side: provider + raw id. */
data class SourceRef(val provider: String, val id: String) {
    override fun toString(): String = "$provider:$id"
}

/** A song or album on the target side: what a source item is copied onto. "provider:id" like a MediaId. */
data class TargetRef(val provider: String, val id: String) {
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

/**
 * Where one source song goes, as matching decided it ([MatchStatus.Matched] / [MatchStatus.Picked] with a
 * [chosen] target) or didn't. [chosen]'s title / artist name the copied notes; its album places the song.
 */
data class TrackPick(
    val source: SourceRef,
    val status: MatchStatus,
    val chosen: TrackTarget?,
)

/** A target song: its id, the names it carries, and the album it is on there (when known). */
data class TrackTarget(
    val provider: String,
    val id: String,
    val title: String?,
    val artist: String?,
    val albumProvider: String?,
    val albumId: String?,
) {
    val ref: TargetRef get() = TargetRef(provider, id)

    val album: TargetRef? get() = if (albumProvider != null && albumId != null) TargetRef(albumProvider, albumId) else null
}

/** Where one source album goes. */
data class AlbumPick(
    val source: SourceRef,
    val status: MatchStatus,
    val chosen: AlbumTarget?,
)

data class AlbumTarget(
    val provider: String,
    val id: String,
    val name: String,
    val artist: String?,
) {
    val ref: TargetRef get() = TargetRef(provider, id)
}

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

/** What applying a transfer did. */
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
