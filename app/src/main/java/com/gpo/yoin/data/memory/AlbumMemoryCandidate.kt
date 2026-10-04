package com.gpo.yoin.data.memory

data class AlbumMemoryCandidate(
    val profileId: String,
    val provider: String,
    val albumId: String,
    val albumName: String,
    val artistName: String?,
    val totalTracks: Int,
    val ratedTrackCount: Int,
    val ratingCoverage: Float,
    val averageSongRating: Float?,
    val albumRating: Float?,
    val hasAlbumReview: Boolean,
    val noteCount: Int,
    val askAiCount: Int,
    val firstPlayedAt: Long?,
    val lastPlayedAt: Long?,
    /**
     * Newest time the user WROTE something on this album: a non-empty album
     * rating/review row, a track rating, or a non-blank song note. Plays and
     * visits never count. Null when the album qualifies by plays alone.
     */
    val lastWrittenAt: Long? = null,
    val playCount: Int,
    val neoDbSynced: Boolean,
    val isMemoryEligible: Boolean,
    val year: Int?,
    val durationSeconds: Int?,
    val coverArtUrl: String?,
    /** play_history MIN(playedAt) only; VISITED events never count. */
    val firstPlayedFromHistoryAt: Long? = null,
    /** play_history MAX(playedAt) only; VISITED events never count. */
    val lastPlayedFromHistoryAt: Long? = null,
    /** play_history row count. [playCount] keeps its Memory meaning. */
    val playCountFromHistory: Int = 0,
) {
    val sessionId: Long = stableAlbumMemorySessionId(profileId, provider, albumId)
}

/** The Memory pool: eligible candidates, in input order, capped at [limit]. */
fun List<AlbumMemoryCandidate>.memoryEligible(limit: Int): List<AlbumMemoryCandidate> =
    filter(AlbumMemoryCandidate::isMemoryEligible).take(limit)

fun stableAlbumMemorySessionId(
    profileId: String,
    provider: String,
    albumId: String,
): Long {
    var hash = 1125899906842597L
    "$profileId|$provider|$albumId".forEach { char ->
        hash = 31L * hash + char.code
    }
    return hash
}
