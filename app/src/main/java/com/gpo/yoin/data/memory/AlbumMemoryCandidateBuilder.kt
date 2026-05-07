package com.gpo.yoin.data.memory

import com.gpo.yoin.data.local.ActivityEventDao
import com.gpo.yoin.data.local.AlbumNoteDao
import com.gpo.yoin.data.local.AlbumRating
import com.gpo.yoin.data.local.AlbumRatingDao
import com.gpo.yoin.data.local.LocalRating
import com.gpo.yoin.data.local.LocalRatingDao
import com.gpo.yoin.data.local.PlayHistoryDao
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.local.SongAboutEntryDao
import com.gpo.yoin.data.local.SongNoteDao
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.source.MusicSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class AlbumMemoryCandidateBuilder(
    private val profileId: String,
    private val provider: String,
    private val source: MusicSource,
    private val playHistoryDao: PlayHistoryDao,
    private val activityEventDao: ActivityEventDao,
    private val localRatingDao: LocalRatingDao,
    private val albumRatingDao: AlbumRatingDao,
    private val albumNoteDao: AlbumNoteDao,
    private val songNoteDao: SongNoteDao,
    private val songAboutEntryDao: SongAboutEntryDao,
    private val resolveCoverUrl: (CoverRef, Int) -> String?,
) {
    suspend fun build(limit: Int): List<AlbumMemoryCandidate> = coroutineScope {
        val scanLimit = (limit * 4).coerceAtLeast(limit).coerceAtMost(MAX_CANDIDATE_SCAN_SIZE)
        val playAggregates = playHistoryDao.getAlbumAggregates(profileId, provider, scanLimit)
        val albumEvents = activityEventDao.getRecentAlbumEvents(profileId, provider, scanLimit)
        val albumRatings = albumRatingDao.getAllForProfile(provider, profileId)
        val albumNoteCounts = albumNoteDao.getNoteCountsForProfile(provider, profileId)

        val seeds = linkedMapOf<String, AlbumMemorySeed>()
        albumRatings
            .sortedWith(
                compareByDescending<AlbumRating> { rating -> !rating.review.isNullOrBlank() }
                    .thenByDescending { rating -> rating.rating }
                    .thenByDescending { rating -> rating.updatedAt },
            )
            .forEach { rating ->
                seeds.getOrPut(rating.albumId) { AlbumMemorySeed(albumId = rating.albumId) }
                    .albumRating = rating
            }
        albumNoteCounts.sortedByDescending { count -> count.noteCount }.forEach { count ->
            seeds.getOrPut(count.albumId) { AlbumMemorySeed(albumId = count.albumId) }
                .albumNoteCount = count.noteCount
        }
        playAggregates.forEach { aggregate ->
            seeds.getOrPut(aggregate.albumId) { AlbumMemorySeed(albumId = aggregate.albumId) }
                .apply {
                    albumName = albumName ?: aggregate.albumName.takeIf(String::isNotBlank)
                    artistName = artistName ?: aggregate.artistName.takeIf(String::isNotBlank)
                    coverArtId = coverArtId ?: aggregate.coverArtId
                    playCount = aggregate.playCount
                    firstPlayedAt = aggregate.firstPlayedAt
                    lastPlayedAt = aggregate.lastPlayedAt
                }
        }
        albumEvents.forEach { event ->
            seeds.getOrPut(event.entityId) { AlbumMemorySeed(albumId = event.entityId) }
                .apply {
                    albumName = albumName ?: event.title.takeIf(String::isNotBlank)
                    artistName = artistName ?: event.subtitle.takeIf(String::isNotBlank)
                    coverArtId = coverArtId ?: event.coverArtId
                    lastPlayedAt = maxOf(lastPlayedAt ?: Long.MIN_VALUE, event.timestamp)
                        .takeUnless { it == Long.MIN_VALUE }
                    firstPlayedAt = listOfNotNull(firstPlayedAt, event.timestamp).minOrNull()
                }
        }

        seeds.values
            .take(scanLimit)
            .map { seed -> async { buildCandidate(seed) } }
            .awaitAll()
            .filterNotNull()
            .filter(AlbumMemoryCandidate::isMemoryEligible)
            .sortedWith(albumMemoryCandidateComparator)
            .take(limit)
    }

    private suspend fun buildCandidate(seed: AlbumMemorySeed): AlbumMemoryCandidate? {
        if (seed.albumId.isBlank()) return null
        val albumId = MediaId(provider, seed.albumId)
        val album = runCatching { source.library().getAlbum(albumId) }.getOrNull()
        val tracks = album?.tracks.orEmpty()
        val ratings = loadTrackRatings(tracks)
        val rated = ratings.values.filter { rating -> rating.rating > 0f }
        val totalTracks = tracks.size.takeIf { it > 0 } ?: album?.songCount ?: 0
        val ratedTrackCount = rated.size
        val ratingCoverage = if (totalTracks > 0) {
            ratedTrackCount.toFloat() / totalTracks.toFloat()
        } else {
            0f
        }
        val averageSongRating = rated
            .map(LocalRating::rating)
            .takeIf(List<Float>::isNotEmpty)
            ?.average()
            ?.toFloat()

        val albumRating = seed.albumRating
            ?: albumRatingDao.get(seed.albumId, provider, profileId)
        val hasAlbumReview = !albumRating?.review.isNullOrBlank()
        val noteCount = seed.albumNoteCount + loadSongNoteCount(tracks)
        val askAiCount = countAskAiRows(tracks)
        val isEligible = ratingCoverage >= MEMORY_RATING_COVERAGE_GATE ||
            hasAlbumReview ||
            noteCount >= MEMORY_NOTE_COUNT_GATE

        return AlbumMemoryCandidate(
            profileId = profileId,
            provider = provider,
            albumId = seed.albumId,
            albumName = album?.name ?: seed.albumName ?: seed.albumId,
            artistName = album?.artist ?: seed.artistName,
            totalTracks = totalTracks,
            ratedTrackCount = ratedTrackCount,
            ratingCoverage = ratingCoverage,
            averageSongRating = averageSongRating,
            albumRating = albumRating?.rating?.takeIf { rating -> rating > 0f },
            hasAlbumReview = hasAlbumReview,
            noteCount = noteCount,
            askAiCount = askAiCount,
            firstPlayedAt = seed.firstPlayedAt,
            lastPlayedAt = seed.lastPlayedAt,
            playCount = seed.playCount,
            neoDbSynced = albumRating.isSyncedToNeoDb(),
            isMemoryEligible = isEligible,
            year = album?.year,
            durationSeconds = album?.durationSec,
            coverArtUrl = album?.coverArt?.let { cover -> resolveCoverUrl(cover, 480) }
                ?: seed.coverArtId
                    ?.let(CoverRef::fromStorageKey)
                    ?.let { cover -> resolveCoverUrl(cover, 480) },
        )
    }

    private suspend fun loadTrackRatings(tracks: List<Track>): Map<MediaId, LocalRating> {
        val trackIds = tracks.map(Track::id).filter { id -> id.provider == provider }
        if (trackIds.isEmpty()) return emptyMap()
        return localRatingDao
            .getRatings(
                songIds = trackIds.map(MediaId::rawId),
                provider = provider,
                profileId = profileId,
            )
            .associateBy { rating -> MediaId(rating.provider, rating.songId) }
    }

    private suspend fun loadSongNoteCount(tracks: List<Track>): Int {
        val trackIds = tracks.map(Track::id).filter { id -> id.provider == provider }
        if (trackIds.isEmpty()) return 0
        return songNoteDao
            .getForTracks(
                trackIds = trackIds.map(MediaId::rawId),
                provider = provider,
                profileId = profileId,
            )
            .count { note -> note.content.isNotBlank() }
    }

    private suspend fun countAskAiRows(tracks: List<Track>): Int =
        tracks.sumOf { track ->
            val title = track.title.orEmpty()
            val artist = track.artist.orEmpty()
            val album = track.album.orEmpty()
            if (title.isBlank() || artist.isBlank() || album.isBlank()) {
                0
            } else {
                songAboutEntryDao.countAskRows(
                    titleKey = SongAboutEntry.normalize(title),
                    artistKey = SongAboutEntry.normalize(artist),
                    albumKey = SongAboutEntry.normalize(album),
                )
            }
        }

    private data class AlbumMemorySeed(
        val albumId: String,
        var albumName: String? = null,
        var artistName: String? = null,
        var coverArtId: String? = null,
        var playCount: Int = 0,
        var firstPlayedAt: Long? = null,
        var lastPlayedAt: Long? = null,
        var albumRating: AlbumRating? = null,
        var albumNoteCount: Int = 0,
    )

    private companion object {
        private const val MEMORY_RATING_COVERAGE_GATE = 0.6f
        private const val MEMORY_NOTE_COUNT_GATE = 2
        private const val MAX_CANDIDATE_SCAN_SIZE = 48

        private val albumMemoryCandidateComparator =
            compareByDescending<AlbumMemoryCandidate> { candidate -> candidate.hasAlbumReview }
                .thenByDescending { candidate -> candidate.ratingCoverage }
                .thenByDescending { candidate -> candidate.noteCount }
                .thenByDescending { candidate -> candidate.askAiCount }
                .thenByDescending { candidate -> candidate.lastPlayedAt ?: candidate.firstPlayedAt ?: 0L }
    }
}

private fun AlbumRating?.isSyncedToNeoDb(): Boolean =
    this != null &&
        rating > 0f &&
        !review.isNullOrBlank() &&
        !ratingNeedsSync &&
        !reviewNeedsSync &&
        !neoDbReviewUuid.isNullOrBlank()
