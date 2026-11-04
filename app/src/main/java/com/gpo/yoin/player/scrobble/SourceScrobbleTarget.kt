package com.gpo.yoin.player.scrobble

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.PlayReport
import com.gpo.yoin.data.source.PlayReportRejectedException

/** [ScrobbleTarget] over any [MusicSource] that declares [Capability.PLAY_REPORTING]. */
class SourceScrobbleTarget private constructor(
    override val profileId: String,
    private val source: MusicSource,
) : ScrobbleTarget {
    private val playback get() = source.playback()

    private fun idOf(songId: String) = MediaId(source.id, songId)

    override suspend fun nowPlaying(songId: String) {
        playback.reportNowPlaying(idOf(songId)).getOrThrow()
    }

    override suspend fun submit(plays: List<PendingScrobble>) {
        playback.submitPlays(plays.map { PlayReport(idOf(it.songId), it.timeMs) }).onFailure { error ->
            if (error is PlayReportRejectedException) throw ScrobbleRejectedException(error.message, error)
            throw error
        }
    }

    override suspend fun supportsPlaybackReport(): Boolean = playback.supportsPlaybackStateReport()

    override suspend fun reportPlayback(songId: String, state: PlaybackReportState, positionMs: Long) {
        playback.reportPlaybackState(idOf(songId), state.wire, positionMs).getOrThrow()
    }

    companion object {
        /**
         * The target for [source], or null when it takes no play reports. Keyed on the
         * source's own profile: the active profile id can disagree with it for a beat
         * during a switch, and a play must never reach another account's server.
         */
        fun of(source: MusicSource?): SourceScrobbleTarget? {
            if (source == null || Capability.PLAY_REPORTING !in source.capabilities) return null
            val profileId = source.profileId ?: return null
            return SourceScrobbleTarget(profileId, source)
        }
    }
}
