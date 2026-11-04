package com.gpo.yoin.data.source.subsonic

import com.gpo.yoin.data.repository.SubsonicException
import com.gpo.yoin.player.scrobble.PendingScrobble
import com.gpo.yoin.player.scrobble.PlaybackReportState
import com.gpo.yoin.player.scrobble.ScrobbleRejectedException
import com.gpo.yoin.player.scrobble.ScrobbleTarget

/** [ScrobbleTarget] for the Subsonic account [profileId], through its [source]. */
class SubsonicScrobbleTarget(
    override val profileId: String,
    private val source: SubsonicMusicSource,
) : ScrobbleTarget {
    override suspend fun nowPlaying(songId: String) {
        source.scrobble(listOf(songId), timesMs = null, submission = false)
    }

    override suspend fun submit(plays: List<PendingScrobble>) {
        try {
            source.scrobble(plays.map { it.songId }, plays.map { it.timeMs }, submission = true)
        } catch (error: SubsonicException) {
            // 10 a parameter it won't take, 70 a song it no longer has: sending again won't help.
            // Anything else (auth, a server hiccup) keeps the plays for later.
            if (error.code == 10 || error.code == 70) throw ScrobbleRejectedException(error.message, error)
            throw error
        }
    }

    override suspend fun supportsPlaybackReport(): Boolean = source.supportsPlaybackReport()

    override suspend fun reportPlayback(songId: String, state: PlaybackReportState, positionMs: Long) {
        source.reportPlayback(songId, state.wire, positionMs)
    }
}
