package com.gpo.yoin.data.highlight

import com.gpo.yoin.data.local.HighlightSongRow
import com.gpo.yoin.data.local.LyricHighlightDao

/** Play history turned into [LyricHighlightSelector.SongSignal]s, keyed by raw song id. */
internal class LyricHighlightSignals(
    val rows: Map<String, HighlightSongRow>,
    val signals: Map<String, LyricHighlightSelector.SongSignal>,
) {
    fun bucketOf(songId: String, now: Long): LyricHighlightSelector.Bucket? =
        LyricHighlightSelector.bucketOf(signals[songId], now)

    companion object {
        /** Plays older than this add nothing measurable to heat (half-life 3 weeks). */
        const val HEAT_WINDOW_MS = 180L * LyricHighlightSelector.DAY_MS

        suspend fun load(dao: LyricHighlightDao, profileId: String, provider: String, now: Long): LyricHighlightSignals {
            val rows = dao.songSignals(profileId, provider)
            val heat = HashMap<String, Double>()
            for (play in dao.playsSince(profileId, provider, now - HEAT_WINDOW_MS)) {
                heat[play.songId] = (heat[play.songId] ?: 0.0) +
                    LyricHighlightSelector.heat(play.playedAt, play.completedPercent, now)
            }
            val signals = rows.associate {
                it.songId to LyricHighlightSelector.SongSignal(
                    songId = it.songId,
                    playCount = it.playCount,
                    lastPlayedAt = it.lastPlayedAt,
                    heat = heat[it.songId] ?: 0.0,
                    rating = it.rating,
                    noteCount = it.noteCount,
                )
            }
            return LyricHighlightSignals(rows.associateBy { it.songId }, signals)
        }
    }
}
