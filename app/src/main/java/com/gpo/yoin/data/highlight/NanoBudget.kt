package com.gpo.yoin.data.highlight

/**
 * How much on-device inference the lyric highlights may spend (pure; the state is persisted by
 * [LyricHighlightProcessor]).
 *
 * - At most [DAILY_LIMIT] songs per local day, [PER_VISIT_LIMIT] per visit to the app, [GAP_MS] apart.
 * - AICore says BUSY → back off 30 s, 2 min, 10 min (then stays at 10 min) before trying again.
 * - AICore says the app's long-term battery quota is used up → nothing more until the next day.
 */
data class NanoBudget(
    /** The local day ([dayOf]) [usedToday] counts for. */
    val day: Long = 0L,
    val usedToday: Int = 0,
    /** No inference before this time (BUSY backoff). */
    val pausedUntil: Long = 0L,
    /** Consecutive BUSY answers; picks the next backoff. */
    val busyStreak: Int = 0,
    /** The day AICore reported the battery quota exhausted; nothing more that day. */
    val quotaExhaustedDay: Long = -1L,
) {
    fun rolled(today: Long): NanoBudget =
        if (today == day) this else copy(day = today, usedToday = 0, busyStreak = 0)

    /** Whether one more song may run now. */
    fun allows(now: Long, today: Long): Boolean {
        val current = rolled(today)
        return current.quotaExhaustedDay != today &&
            current.usedToday < DAILY_LIMIT &&
            now >= current.pausedUntil
    }

    fun afterSuccess(today: Long): NanoBudget =
        rolled(today).let { it.copy(usedToday = it.usedToday + 1, busyStreak = 0) }

    fun afterBusy(now: Long, today: Long): NanoBudget {
        val current = rolled(today)
        val wait = BUSY_BACKOFF_MS[current.busyStreak.coerceAtMost(BUSY_BACKOFF_MS.lastIndex)]
        return current.copy(pausedUntil = now + wait, busyStreak = current.busyStreak + 1)
    }

    fun afterQuotaExhausted(today: Long): NanoBudget = rolled(today).copy(quotaExhaustedDay = today)

    companion object {
        const val DAILY_LIMIT = 40
        const val PER_VISIT_LIMIT = 5
        const val GAP_MS = 8_000L
        val BUSY_BACKOFF_MS = longArrayOf(30_000L, 120_000L, 600_000L)

        /** Local calendar day number for [epochMs] at [utcOffsetMs]. */
        fun dayOf(epochMs: Long, utcOffsetMs: Int): Long = Math.floorDiv(epochMs + utcOffsetMs, 86_400_000L)
    }
}
