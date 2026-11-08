package com.gpo.yoin.data.highlight

import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Which songs get a lyric highlight, and which highlight shows next.
 *
 * Three kinds, mixed [HEAVY]:[REDISCOVER]:[FRESH] = 5:3:2 both when choosing what Nano works on and when
 * choosing what to show:
 * - **Heavy rotation** ([Bucket.HEAVY]): what you've had on lately. Every play heard past halfway scores
 *   0.5^(age / 3 weeks), so last week's loop outranks last year's favourite.
 * - **Rediscover** ([Bucket.REDISCOVER]): songs you loved (3+ plays, rated 7+, or a note) and haven't played
 *   for 60 days. Score = how much you loved it × how long it's been (capped at 180 days). The same signals as
 *   Home's Rediscover.
 * - **Fresh** ([Bucket.FRESH]): tracks you haven't played yet from albums you rated, best-rated albums first.
 *
 * Pure Kotlin; times are epoch millis passed in, randomness is injected.
 */
object LyricHighlightSelector {

    enum class Bucket { HEAVY, REDISCOVER, FRESH }

    /** One played song, aggregated over its history. */
    data class SongSignal(
        val songId: String,
        val playCount: Int,
        val lastPlayedAt: Long,
        /** Sum of 0.5^(age / [HEAT_HALF_LIFE_MS]) over plays heard past [HEARD_FRACTION]; see [heat]. */
        val heat: Double,
        /** Track rating 0–10, null when unrated. */
        val rating: Float?,
        val noteCount: Int,
    )

    /** A track not yet played, offered for [Bucket.FRESH]; [albumRating] is its album's 0–10 rating. */
    data class FreshTrack(val songId: String, val albumRating: Float)

    /** A song queued for Nano, with the kind it was chosen as. */
    data class Candidate(val songId: String, val bucket: Bucket, val score: Double)

    /** A stored highlight as the display pick sees it. */
    data class Shown(val songId: String, val lastShownAt: Long?)

    const val DAY_MS: Long = 24L * 60L * 60L * 1000L
    const val HEAT_HALF_LIFE_MS: Long = 21L * DAY_MS
    const val HEARD_FRACTION: Float = 0.5f

    /** Heat at which a song counts as heavy rotation: about two plays within the last three weeks. */
    const val HEAVY_MIN_HEAT: Double = 1.5
    const val HEAVY_POOL: Int = 40

    const val REDISCOVER_GAP_MS: Long = 60L * DAY_MS
    const val REDISCOVER_FULL_GAP_MS: Long = 180L * DAY_MS
    const val REDISCOVER_MIN_PLAYS: Int = 3
    const val REDISCOVER_MIN_RATING: Float = 7f

    /** A highlight shown within this window is held back while others are available. */
    const val RESHOW_COOLDOWN_MS: Long = 7L * DAY_MS

    /** Target mix, in parts. */
    val MIX: Map<Bucket, Int> = mapOf(Bucket.HEAVY to 5, Bucket.REDISCOVER to 3, Bucket.FRESH to 2)

    /** One play's contribution to [SongSignal.heat]. Plays stopped early (skips) count for nothing. */
    fun heat(playedAt: Long, completedPercent: Float, now: Long): Double {
        if (completedPercent < HEARD_FRACTION) return 0.0
        val age = (now - playedAt).coerceAtLeast(0L)
        return 0.5.pow(age.toDouble() / HEAT_HALF_LIFE_MS)
    }

    /** How strongly a song was loved, for Rediscover. */
    fun affinity(signal: SongSignal): Double =
        ln(1.0 + signal.playCount) + (signal.rating ?: 0f) / 2.5 + if (signal.noteCount > 0) 1.0 else 0.0

    fun isRediscover(signal: SongSignal, now: Long): Boolean {
        if (now - signal.lastPlayedAt < REDISCOVER_GAP_MS) return false
        return signal.playCount >= REDISCOVER_MIN_PLAYS ||
            (signal.rating ?: 0f) >= REDISCOVER_MIN_RATING ||
            signal.noteCount > 0
    }

    /** The kind a song is today; null for a song that is none of them (played once, a while ago). */
    fun bucketOf(signal: SongSignal?, now: Long): Bucket? = when {
        signal == null || signal.playCount == 0 -> Bucket.FRESH
        signal.heat >= HEAVY_MIN_HEAT -> Bucket.HEAVY
        isRediscover(signal, now) -> Bucket.REDISCOVER
        else -> null
    }

    /** Each kind's candidates, best first. */
    fun pools(signals: List<SongSignal>, fresh: List<FreshTrack>, now: Long): Map<Bucket, List<Candidate>> {
        val heavy = signals.filter { it.heat >= HEAVY_MIN_HEAT }
            .sortedByDescending(SongSignal::heat)
            .take(HEAVY_POOL)
            .map { Candidate(it.songId, Bucket.HEAVY, it.heat) }
        val heavyIds = heavy.mapTo(HashSet(), Candidate::songId)
        val rediscover = signals.filter { it.songId !in heavyIds && isRediscover(it, now) }
            .map {
                val gap = min(now - it.lastPlayedAt, REDISCOVER_FULL_GAP_MS).toDouble() / REDISCOVER_FULL_GAP_MS
                Candidate(it.songId, Bucket.REDISCOVER, affinity(it) * gap)
            }
            .sortedByDescending(Candidate::score)
        val played = signals.filter { it.playCount > 0 }.mapTo(HashSet(), SongSignal::songId)
        val freshCandidates = fresh.filter { it.songId !in played }
            .distinctBy(FreshTrack::songId)
            .sortedByDescending(FreshTrack::albumRating)
            .map { Candidate(it.songId, Bucket.FRESH, it.albumRating.toDouble()) }
        return mapOf(Bucket.HEAVY to heavy, Bucket.REDISCOVER to rediscover, Bucket.FRESH to freshCandidates)
    }

    /**
     * The next songs for Nano, interleaved to the [MIX] (a kind that runs dry gives its turns to the others),
     * skipping [skip] (already highlighted, or tried and found unusable).
     */
    fun processingQueue(pools: Map<Bucket, List<Candidate>>, skip: Set<String>, limit: Int): List<Candidate> {
        val remaining = pools.mapValues { (_, list) -> ArrayDeque(list.filter { it.songId !in skip }) }
        val taken = HashSet<String>()
        val credit = Bucket.entries.associateWith { 0.0 }.toMutableMap()
        val out = ArrayList<Candidate>()
        while (out.size < limit) {
            val live = Bucket.entries.filter { remaining[it]?.isNotEmpty() == true }
            if (live.isEmpty()) break
            // Smooth weighted round robin over the kinds that still have songs.
            val total = live.sumOf { MIX.getValue(it) }.toDouble()
            for (b in live) credit[b] = credit.getValue(b) + MIX.getValue(b)
            val bucket = live.maxBy { credit.getValue(it) }
            credit[bucket] = credit.getValue(bucket) - total
            val next = remaining.getValue(bucket).removeFirst()
            if (taken.add(next.songId)) out += next
        }
        return out
    }

    /**
     * The highlight to show next. Draws a kind by the [MIX] among kinds that have something to show, then the
     * least recently shown highlight of that kind; anything shown in the last [RESHOW_COOLDOWN_MS] waits while
     * something else is available. [exclude] is the song playing right now.
     */
    fun nextToShow(
        shown: List<Shown>,
        bucketOf: (String) -> Bucket?,
        now: Long,
        exclude: String? = null,
        random: Random = Random.Default,
    ): Shown? {
        val pool = shown.filter { it.songId != exclude }
        if (pool.isEmpty()) return null
        val rested = pool.filter { it.lastShownAt == null || now - it.lastShownAt >= RESHOW_COOLDOWN_MS }
        val eligible = rested.ifEmpty { pool }
        val byBucket = eligible.groupBy { bucketOf(it.songId) }
        val kinds = Bucket.entries.filter { byBucket[it]?.isNotEmpty() == true }
        val group = if (kinds.isEmpty()) {
            eligible
        } else {
            val total = kinds.sumOf { MIX.getValue(it) }
            var roll = random.nextInt(total)
            val kind = kinds.first { roll -= MIX.getValue(it); roll < 0 }
            byBucket.getValue(kind)
        }
        // Never shown first, then the longest ago; a random pick among equals.
        val oldest = group.minOf { it.lastShownAt ?: Long.MIN_VALUE }
        val ties = group.filter { (it.lastShownAt ?: Long.MIN_VALUE) == oldest }
        return ties[random.nextInt(ties.size)]
    }
}
