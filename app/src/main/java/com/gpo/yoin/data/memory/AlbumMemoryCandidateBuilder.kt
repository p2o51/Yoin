package com.gpo.yoin.data.memory

import com.gpo.yoin.data.local.ActivityEventDao
import com.gpo.yoin.data.local.AlbumNoteDao
import com.gpo.yoin.data.local.AlbumPlayHistoryAggregate
import com.gpo.yoin.data.local.AlbumRating
import com.gpo.yoin.data.local.AlbumRatingDao
import com.gpo.yoin.data.local.AlbumTrackSignalAggregate
import com.gpo.yoin.data.local.LocalRating
import com.gpo.yoin.data.local.LocalRatingDao
import com.gpo.yoin.data.local.PlayHistoryDao
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.local.SongAboutEntryDao
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.local.SongNoteDao
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import kotlinx.coroutines.CancellationException
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
    /**
     * Builds up to [limit] Memory-eligible candidates. With [includeIneligible]
     * every scanned candidate comes back, in the same order, so
     * `build(n, true).memoryEligible(n) == build(n)` — followed by the
     * Rediscover-only albums of [buildTrackSignalCandidates], which are never
     * Memory-eligible and so never change that subset.
     */
    suspend fun build(limit: Int, includeIneligible: Boolean = false): List<AlbumMemoryCandidate> = coroutineScope {
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
                    historyPlayCount = aggregate.playCount
                    historyFirstPlayedAt = aggregate.firstPlayedAt
                    historyLastPlayedAt = aggregate.lastPlayedAt
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
        val scanned = seeds.values.take(scanLimit)
        // Runs for both flags, so the eligible subset is identical either way.
        fillHistoryGaps(scanned)
        // sortedWith is stable: sort-then-filter keeps the old filter-then-sort
        // order, ties included.
        val sorted = scanned
            .map { seed -> async { buildGate.withPermit { buildCandidate(seed) } } }
            .awaitAll()
            .filterNotNull()
            .sortedWith(albumMemoryCandidateComparator)
        if (includeIneligible) sorted + buildTrackSignalCandidates(scanned, buildGate) else sorted.memoryEligible(limit)
    }

    /**
     * Rediscover's two extra sources (owner 2026-10-05: any memory counts,
     * track notes and track ratings included): albums whose tracks carry a
     * non-blank song note or a track rating, found through the tracks' play
     * history, that the Memory scan above did not reach. Each is built like
     * any candidate but outside the Memory scan, so it is never
     * Memory-eligible ([AlbumMemorySeed.inMemoryScan]) — the Memory pool and
     * the default build stay exactly what they were. Strongest signal first,
     * at most [MAX_TRACK_SIGNAL_SEEDS]; a failed read adds nothing.
     */
    private suspend fun buildTrackSignalCandidates(
        scanned: List<AlbumMemorySeed>,
        buildGate: Semaphore,
    ): List<AlbumMemoryCandidate> = coroutineScope {
        val signals = try {
            songNoteDao.getNotedAlbumAggregates(provider, profileId, TRACK_SIGNAL_QUERY_LIMIT) +
                localRatingDao.getRatedAlbumAggregates(provider, profileId, TRACK_SIGNAL_QUERY_LIMIT)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return@coroutineScope emptyList()
        }
        // Scanned seeds may carry the legacy provider:raw form; history ids are raw.
        val known = scanned.mapTo(HashSet()) { seed -> MediaId.storedRawId(provider, seed.albumId) }
        val seeds = signals
            .filter { signal -> signal.albumId.isNotBlank() && signal.albumId !in known }
            .groupBy(AlbumTrackSignalAggregate::albumId)
            .map { (albumId, rows) ->
                Triple(albumId, rows.sumOf(AlbumTrackSignalAggregate::signalCount), rows.maxOf { it.lastWrittenAt })
            }
            .sortedWith(compareByDescending<Triple<String, Int, Long>> { it.second }.thenByDescending { it.third })
            .take(MAX_TRACK_SIGNAL_SEEDS)
            .map { (albumId, _, _) -> AlbumMemorySeed(albumId = albumId, inMemoryScan = false) }
        if (seeds.isEmpty()) return@coroutineScope emptyList()
        fillTrackSignalHistory(seeds)
        seeds
            .map { seed -> async { buildGate.withPermit { buildCandidate(seed) } } }
            .awaitAll()
            .filterNotNull()
            .sortedWith(albumMemoryCandidateComparator)
    }

    /**
     * The extra seeds' names, cover and plays, from their play history (which
     * is how they were found). They stand outside the Memory scan, so the
     * Memory-side play fields take the history too, as play-aggregate seeds'
     * do. A failed lookup leaves them empty.
     */
    private suspend fun fillTrackSignalHistory(seeds: List<AlbumMemorySeed>) {
        val rows = try {
            playHistoryDao.getAlbumAggregatesFor(profileId, provider, seeds.map(AlbumMemorySeed::albumId))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return
        }
        val byId = rows.associateBy(AlbumPlayHistoryAggregate::albumId)
        seeds.forEach { seed ->
            val aggregate = byId[seed.albumId] ?: return@forEach
            seed.albumName = aggregate.albumName.takeIf(String::isNotBlank)
            seed.artistName = aggregate.artistName.takeIf(String::isNotBlank)
            seed.coverArtId = aggregate.coverArtId
            seed.playCount = aggregate.playCount
            seed.firstPlayedAt = aggregate.firstPlayedAt
            seed.lastPlayedAt = aggregate.lastPlayedAt
            seed.historyPlayCount = aggregate.playCount
            seed.historyFirstPlayedAt = aggregate.firstPlayedAt
            seed.historyLastPlayedAt = aggregate.lastPlayedAt
        }
    }

    /**
     * Seeds outside [PlayHistoryDao.getAlbumAggregates]' recency window get
     * their play history by id. Only the history-only fields are filled — the
     * Memory inputs (playCount / firstPlayedAt / lastPlayedAt) are never
     * touched. A failed lookup leaves them null.
     */
    private suspend fun fillHistoryGaps(scanned: List<AlbumMemorySeed>) {
        val missing = scanned.filter { seed ->
            seed.historyLastPlayedAt == null && seed.albumId.isNotBlank()
        }
        if (missing.isEmpty()) return
        val rows = try {
            playHistoryDao.getAlbumAggregatesFor(
                profileId = profileId,
                provider = provider,
                // History stores raw ids; seeds may carry the legacy provider:raw form.
                albumIds = missing.map { seed -> MediaId.storedRawId(provider, seed.albumId) }.distinct(),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return
        }
        val byRawId = rows.associateBy(AlbumPlayHistoryAggregate::albumId)
        missing.forEach { seed ->
            byRawId[MediaId.storedRawId(provider, seed.albumId)]?.let { aggregate ->
                seed.historyPlayCount = aggregate.playCount
                seed.historyFirstPlayedAt = aggregate.firstPlayedAt
                seed.historyLastPlayedAt = aggregate.lastPlayedAt
            }
        }
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
        val noteStats = loadSongNoteStats(tracks)
        val noteCount = seed.albumNoteCount + noteStats.count
        // Writes only — every input is a row already loaded above (zero extra
        // queries). Plays / visits never count: VISITED events inflate
        // lastPlayedAt. An empty album_ratings row (no score, no review) is not
        // a write. Album notes are left out — nothing writes them yet.
        val lastWrittenAt = listOfNotNull(
            albumRating?.takeIf { it.rating > 0f || !it.review.isNullOrBlank() }?.updatedAt,
            rated.maxOfOrNull(LocalRating::updatedAt),
            noteStats.lastUpdatedAt,
        ).maxOrNull()
        val askAiCount = countAskAiRows(tracks)
        // Singles and short EPs never become Memories (MEMORY_MIN_TRACK_COUNT); the count is the album's own,
        // so a partly loaded track list can't hide a long album. Unknown (no detail) defers to the gates.
        val albumTrackCount = album?.let { loaded -> maxOf(loaded.tracks.size, loaded.songCount ?: 0) }
        val passesWritingGate = ratingCoverage >= MEMORY_RATING_COVERAGE_GATE ||
            hasAlbumReview ||
            noteCount >= MEMORY_NOTE_COUNT_GATE
        // Rediscover-only seeds never join the Memory pool, whatever they pass.
        val isEligible = seed.inMemoryScan && passesWritingGate && meetsMemoryTrackCount(albumTrackCount)

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
            lastWrittenAt = lastWrittenAt,
            playCount = seed.playCount,
            neoDbSynced = albumRating.isSyncedToNeoDb(),
            isMemoryEligible = isEligible,
            year = album?.year,
            durationSeconds = album?.durationSec,
            coverArtUrl = album?.coverArt?.let { cover -> resolveCoverUrl(cover, 480) }
                ?: seed.coverArtId
                    ?.let(CoverRef::fromStorageKey)
                    ?.let { cover -> resolveCoverUrl(cover, 480) },
            firstPlayedFromHistoryAt = seed.historyFirstPlayedAt,
            lastPlayedFromHistoryAt = seed.historyLastPlayedAt,
            playCountFromHistory = seed.historyPlayCount,
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

    /**
     * Count and newest edit time of the album's NON-BLANK song notes, from the
     * same single [SongNoteDao.getForTracks] read. Blank notes contribute to
     * neither value, so [SongNoteStats.count] keeps the old noteCount meaning.
     */
    private suspend fun loadSongNoteStats(tracks: List<Track>): SongNoteStats {
        val trackIds = tracks.map(Track::id).filter { id -> id.provider == provider }
        if (trackIds.isEmpty()) return SongNoteStats(count = 0, lastUpdatedAt = null)
        val notes = songNoteDao
            .getForTracks(
                trackIds = trackIds.map(MediaId::rawId),
                provider = provider,
                profileId = profileId,
            )
            .filter { note -> note.content.isNotBlank() }
        return SongNoteStats(
            count = notes.size,
            lastUpdatedAt = notes.maxOfOrNull(SongNote::updatedAt),
        )
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
        // play_history only; the fields above also absorb VISITED events.
        var historyPlayCount: Int = 0,
        var historyFirstPlayedAt: Long? = null,
        var historyLastPlayedAt: Long? = null,
        // False for a Rediscover-only seed (buildTrackSignalCandidates): never Memory-eligible.
        val inMemoryScan: Boolean = true,
    )

    private data class SongNoteStats(
        val count: Int,
        val lastUpdatedAt: Long?,
    )

    private companion object {
        private const val MEMORY_RATING_COVERAGE_GATE = 0.6f
        private const val MEMORY_NOTE_COUNT_GATE = 2
        private const val MAX_CANDIDATE_SCAN_SIZE = 48
        private const val MAX_CONCURRENT_CANDIDATE_BUILDS = 4

        // Rediscover-only albums found through track notes / ratings: rows read
        // per source, and albums built (each a cached album-detail read).
        private const val TRACK_SIGNAL_QUERY_LIMIT = 96
        private const val MAX_TRACK_SIGNAL_SEEDS = 16

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
