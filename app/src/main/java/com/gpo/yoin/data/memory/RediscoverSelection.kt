package com.gpo.yoin.data.memory

import com.gpo.yoin.data.model.MediaId

/** Lowest score (album rating, or a covered track average) that qualifies. */
const val REDISCOVER_MIN_SCORE = 8f

/** How long an album must go unplayed in Yoin before it comes back: 90 days. */
const val REDISCOVER_AWAY_MS = 90L * 24 * 60 * 60 * 1000

/** Track-average coverage gate; same value as the builder's Memory gate. */
const val REDISCOVER_COVERAGE_GATE = 0.6f

/** Compact shelf maximum; Medium / Wide show fewer. */
const val REDISCOVER_LIMIT = 6

/**
 * The album's Rediscover score: its album rating when set, otherwise the
 * track average once at least [REDISCOVER_COVERAGE_GATE] of tracks are rated.
 */
fun rediscoverScore(candidate: AlbumMemoryCandidate): Float? =
    candidate.albumRating?.takeIf { rating -> rating > 0f }
        ?: candidate.averageSongRating?.takeIf {
            candidate.ratingCoverage >= REDISCOVER_COVERAGE_GATE
        }

data class RediscoverPick(
    val candidate: AlbumMemoryCandidate,
    val score: Float,
    /** play_history MAX(playedAt); visits never count. */
    val lastPlayedAt: Long,
)

/**
 * Rated ≥ [REDISCOVER_MIN_SCORE] and not played in Yoin for
 * [REDISCOVER_AWAY_MS], best score first, then longest away. Visited-only
 * albums never qualify. Deduped by raw album id (legacy `provider:raw` seeds
 * would otherwise collide as LazyRow keys), then [excludeRawAlbumIds] — the
 * albums already shown elsewhere on Home — are dropped.
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
            val score = rediscoverScore(candidate)
                ?.takeIf { score -> score >= REDISCOVER_MIN_SCORE - SCORE_EPSILON }
                ?: return@mapNotNull null
            if (nowMillis - lastPlayedAt < REDISCOVER_AWAY_MS) {
                null
            } else {
                RediscoverPick(candidate = candidate, score = score, lastPlayedAt = lastPlayedAt)
            }
        }
        .sortedWith(
            compareByDescending<RediscoverPick> { pick -> pick.score }
                .thenBy { pick -> pick.lastPlayedAt },
        )
        .distinctBy { pick -> pick.rawAlbumId() }
        .filterNot { pick -> pick.rawAlbumId() in excludeRawAlbumIds }
        .take(limit)

private fun RediscoverPick.rawAlbumId(): String = MediaId.storedRawId(candidate.provider, candidate.albumId)

/** Float averages land a hair under 8.0; still a pass. */
private const val SCORE_EPSILON = 1e-4f
