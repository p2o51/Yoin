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
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class AlbumMemoryCandidateBuilder(
    private val profileId: String,
    private val provider: String,
    /** Album detail loader — must be the repository's cached path, not a raw source call. */
    private val getAlbum: suspend (MediaId) -> Album?,
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

        // Bound the fan-out: candidate builds mostly hit the repository's album
        // cache, but a cold start would otherwise fire scanLimit concurrent
        // network fetches at once.
        val buildGate = Semaphore(MAX_CONCURRENT_CANDIDATE_BUILDS)
        seeds.values
            .take(scanLimit)
            .map { seed -> async { buildGate.withPermit { buildCandidate(seed) } } }
            .awaitAll()
            .filterNotNull()
            .filter(AlbumMemoryCandidate::isMemoryEligible)
            .sortedWith(albumMemoryCandidateComparator)
            .take(limit)
    }

    private suspend fun buildCandidate(seed: AlbumMemorySeed): AlbumMemoryCandidate? {
        if (seed.albumId.isBlank()) return null
        val albumId = MediaId(provider, seed.albumId)
        val album = runCatching { getAlbum(albumId) }.getOrNull()
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

    /**
     * One grouped COUNT query per distinct album key instead of one query per
     * track. Key normalization and blank-skip rules are identical to the old
     * per-track [SongAboutEntryDao.countAskRows] lookups: tracks with a blank
     * title / artist / album contribute 0, and two tracks that normalize to the
     * same key each count the matching rows.
     */
    private suspend fun countAskAiRows(tracks: List<Track>): Int {
        val trackKeys = tracks.mapNotNull { track ->
            val title = track.title.orEmpty()
            val artist = track.artist.orEmpty()
            val album = track.album.orEmpty()
            if (title.isBlank() || artist.isBlank() || album.isBlank()) {
                null
            } else {
                Triple(
                    SongAboutEntry.normalize(title),
                    SongAboutEntry.normalize(artist),
                    SongAboutEntry.normalize(album),
                )
            }
        }
        if (trackKeys.isEmpty()) return 0
        return trackKeys
            .groupBy { (_, _, albumKey) -> albumKey }
            .entries
            .sumOf { (albumKey, keys) ->
                val counts = songAboutEntryDao
                    .countAskRowsByAlbum(albumKey)
                    .associate { row -> (row.titleKey to row.artistKey) to row.askCount }
                keys.sumOf { (titleKey, artistKey, _) -> counts[titleKey to artistKey] ?: 0 }
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
        private const val MAX_CONCURRENT_CANDIDATE_BUILDS = 4

        private val albumMemoryCandidateComparator =
            compareByDescending<AlbumMemoryCandidate> { candidate -> candidate.hasAlbumReview }
                .thenByDescending { candidate -> candidate.ratingCoverage }
                .thenByDescending { candidate -> candidate.noteCount }
                .thenByDescending { candidate -> candidate.askAiCount }
                .thenByDescending { candidate -> candidate.lastPlayedAt ?: candidate.firstPlayedAt ?: 0L }
    }
}

// 不再要求 neoDbReviewUuid：现行 NeoDB API 是 item 中心，POST review 的
// 响应只是一个 Result 消息壳（ReviewSchema 里连 uuid 属性都没有），本地
// 永远拿不到 uuid —— 拿它当 SYNCED 门槛会让状态永远停在 READY。
// 「推过且不脏」就是同步完成的全部证据。
private fun AlbumRating?.isSyncedToNeoDb(): Boolean =
    this != null &&
        rating > 0f &&
        !review.isNullOrBlank() &&
        !ratingNeedsSync &&
        !reviewNeedsSync
