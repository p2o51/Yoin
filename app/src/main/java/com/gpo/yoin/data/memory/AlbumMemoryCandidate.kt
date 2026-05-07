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
    val playCount: Int,
    val neoDbSynced: Boolean,
    val isMemoryEligible: Boolean,
    val year: Int?,
    val durationSeconds: Int?,
    val coverArtUrl: String?,
) {
    val sessionId: Long = stableAlbumMemorySessionId(profileId, provider, albumId)
}

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
