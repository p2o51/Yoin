package com.gpo.yoin.data.memory

import com.gpo.yoin.data.local.PlayHistoryDao
import com.gpo.yoin.data.local.SongMemoryAggregate
import com.gpo.yoin.data.model.MediaId

/** How long an album must go unplayed in Yoin before it comes back: 90 days. */
const val REDISCOVER_AWAY_MS = 90L * 24 * 60 * 60 * 1000

/** Track-average coverage gate; same value as the builder's Memory gate. */
const val REDISCOVER_COVERAGE_GATE = 0.6f

/** Compact shelf maximum; Medium / Wide show fewer. */
const val REDISCOVER_LIMIT = 6

/**
 * The album's Rediscover score: its album rating when set, otherwise the
 * track average once at least [REDISCOVER_COVERAGE_GATE] of tracks are rated.
 * Null = no score to show; the album can still come back on another memory.
 */
fun rediscoverScore(candidate: AlbumMemoryCandidate): Float? =
    candidate.albumRating?.takeIf { rating -> rating > 0f }
        ?: candidate.averageSongRating?.takeIf {
            candidate.ratingCoverage >= REDISCOVER_COVERAGE_GATE
        }

/**
 * Whether the user ever kept something on this album: an album rating, an
 * album review, a note (album or non-blank song note) or a track rating.
 * Plays, visits and Ask AI rows never count.
 */
fun hasRediscoverMemory(candidate: AlbumMemoryCandidate): Boolean =
    (candidate.albumRating ?: 0f) > 0f ||
        candidate.hasAlbumReview ||
        candidate.noteCount > 0 ||
        candidate.ratedTrackCount > 0

/**
 * The Memory rule's track count, for a Rediscover album: singles and short
 * EPs come back as songs, never as albums. A Memory-eligible candidate has
 * passed [meetsMemoryTrackCount] already; any other is checked on its
 * [AlbumMemoryCandidate.totalTracks], where 0 means the album detail never
 * loaded — the rule then stays open, as it does for Memories.
 */
fun meetsRediscoverTrackCount(candidate: AlbumMemoryCandidate): Boolean =
    candidate.isMemoryEligible || meetsMemoryTrackCount(candidate.totalTracks.takeIf { count -> count > 0 })

/** One card on the Rediscover shelf: an album ([RediscoverPick]) or a song ([RediscoverSongPick]). */
sealed interface RediscoverEntry {
    /** Null when the card comes back without a score. */
    val score: Float?

    /** play_history MAX(playedAt); visits never count. */
    val lastPlayedAt: Long
}

data class RediscoverPick(
    val candidate: AlbumMemoryCandidate,
    /** [rediscoverScore]; null when the album comes back without one. */
    override val score: Float?,
    override val lastPlayedAt: Long,
) : RediscoverEntry

data class RediscoverSongPick(
    val song: SongMemoryAggregate,
    /** The track rating; null when the song comes back on a note alone. */
    override val score: Float?,
    override val lastPlayedAt: Long,
) : RediscoverEntry

/** Whether the user kept something on this song: a track rating, or a non-blank note. */
fun hasRediscoverMemory(song: SongMemoryAggregate): Boolean =
    (song.rating ?: 0f) > 0f || (song.noteCount > 0 && !song.latestNote.isNullOrBlank())

/**
 * Rediscover's one order, albums and songs alike: scored cards first, best
 * score first, then the unscored ones; longest away first within a score.
 */
private val rediscoverOrder: Comparator<RediscoverEntry> =
    compareBy<RediscoverEntry> { entry -> entry.score == null }
        .thenByDescending { entry -> entry.score ?: 0f }
        .thenBy { entry -> entry.lastPlayedAt }

/**
 * Every album with a memory ([hasRediscoverMemory]) and enough tracks to be
 * an album ([meetsRediscoverTrackCount]) that hasn't played in Yoin for
 * [REDISCOVER_AWAY_MS], in [rediscoverOrder]. Visited-only albums never
 * qualify. Deduped by raw album id (legacy `provider:raw` seeds would
 * otherwise collide as LazyRow keys), then [excludeRawAlbumIds] — the albums
 * already shown elsewhere on Home — are dropped.
 */
fun selectRediscover(
    candidates: List<AlbumMemoryCandidate>,
    nowMillis: Long,
    excludeRawAlbumIds: Set<String>,
    limit: Int = REDISCOVER_LIMIT,
): List<RediscoverPick> =
    candidates
        .mapNotNull { candidate ->
            val lastPlayedAt = candidate.lastPlayedFromHistoryAt ?: return@mapNotNull null
            if (
                !hasRediscoverMemory(candidate) ||
                !meetsRediscoverTrackCount(candidate) ||
                nowMillis - lastPlayedAt < REDISCOVER_AWAY_MS
            ) {
                null
            } else {
                RediscoverPick(candidate = candidate, score = rediscoverScore(candidate), lastPlayedAt = lastPlayedAt)
            }
        }
        .sortedWith(rediscoverOrder)
        .distinctBy { pick -> pick.rawAlbumId() }
        .filterNot { pick -> pick.rawAlbumId() in excludeRawAlbumIds }
        .take(limit)

/**
 * Every song with a memory ([hasRediscoverMemory]) that hasn't played in
 * Yoin for [REDISCOVER_AWAY_MS], in [rediscoverOrder]; its score is its track
 * rating. Deduped by raw song id; [excludeRawSongIds] (shown elsewhere on
 * Home, or played this session) and songs of [excludeRawAlbumIds] are dropped.
 */
fun selectRediscoverSongs(
    songs: List<SongMemoryAggregate>,
    nowMillis: Long,
    excludeRawSongIds: Set<String> = emptySet(),
    excludeRawAlbumIds: Set<String> = emptySet(),
): List<RediscoverSongPick> =
    songs
        .mapNotNull { song ->
            if (!hasRediscoverMemory(song) || nowMillis - song.lastPlayedAt < REDISCOVER_AWAY_MS) {
                null
            } else {
                RediscoverSongPick(
                    song = song,
                    score = song.rating?.takeIf { rating -> rating > 0f },
                    lastPlayedAt = song.lastPlayedAt,
                )
            }
        }
        .sortedWith(rediscoverOrder)
        .distinctBy { pick -> pick.rawSongId() }
        .filterNot { pick -> pick.rawSongId() in excludeRawSongIds || pick.rawAlbumId() in excludeRawAlbumIds }

/**
 * The shelf: [albums] and [songs] interleaved in [rediscoverOrder] (an album
 * first on a tie), at most [limit]. A song whose album is on the shelf as an
 * album card stays off it; one whose album only qualifies past the limit
 * stays on.
 */
fun selectRediscoverShelf(
    albums: List<RediscoverPick>,
    songs: List<RediscoverSongPick>,
    limit: Int = REDISCOVER_LIMIT,
): List<RediscoverEntry> {
    val merged: List<RediscoverEntry> = (albums + songs).sortedWith(rediscoverOrder)
    // Dropping a song only lets later cards in, so the shelf's albums only
    // grow: this settles within [limit] rounds.
    var shownAlbums = emptySet<String>()
    while (true) {
        val shelf = merged
            .filterNot { entry -> entry is RediscoverSongPick && entry.rawAlbumId() in shownAlbums }
            .take(limit)
        val albumsOnShelf = shelf.mapNotNullTo(HashSet()) { entry -> (entry as? RediscoverPick)?.rawAlbumId() }
        if (albumsOnShelf.all { it in shownAlbums }) return shelf
        shownAlbums = shownAlbums + albumsOnShelf
    }
}

/**
 * Where Rediscover's songs come from: [PlayHistoryDao.getRediscoverSongs]
 * for one profile and provider, songs not played since `playedBefore`, best
 * first, at most `limit`.
 */
fun interface RediscoverSongSource {
    suspend fun load(provider: String, profileId: String, playedBefore: Long, limit: Int): List<SongMemoryAggregate>

    companion object {
        /** No songs (previews, tests that don't need them). */
        val None = RediscoverSongSource { _, _, _, _ -> emptyList() }

        fun of(playHistoryDao: PlayHistoryDao) = RediscoverSongSource { provider, profileId, playedBefore, limit ->
            playHistoryDao.getRediscoverSongs(
                profileId = profileId,
                provider = provider,
                playedBefore = playedBefore,
                limit = limit,
            )
        }
    }
}

private fun RediscoverPick.rawAlbumId(): String = MediaId.storedRawId(candidate.provider, candidate.albumId)

private fun RediscoverSongPick.rawSongId(): String = MediaId.storedRawId(song.provider, song.songId)

private fun RediscoverSongPick.rawAlbumId(): String = MediaId.storedRawId(song.provider, song.albumId)
