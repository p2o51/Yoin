package com.gpo.yoin.ui.home

import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.source.MusicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A tap on one of Home's song cards (Jump Back In's songs, Rediscover's, a
 * single in Activities): plays it on the active source at once. A snapshot
 * paints Home before the account's source is built (a cold start), so a tap
 * can come with none yet: it then waits for the source — bounded, as long
 * as Home holds its feed for one — and plays once it is in and of the song's
 * provider. The latest tap wins; with no source by then it does nothing, as
 * a tap without a source always did.
 */
internal class HomeSongTap(
    private val scope: CoroutineScope,
    private val currentSource: () -> MusicSource?,
    private val awaitSource: suspend (timeoutMs: Long) -> MusicSource?,
    private val play: (track: Track, source: MusicSource) -> Unit
) {
    // The tap waiting for a source; on the caller's (main) thread only.
    private var pending: Job? = null

    fun tap(song: Track) {
        pending?.cancel()
        pending = null
        currentSource()?.let { source ->
            play(song, source)
            return
        }
        pending = scope.launch {
            val source = awaitSource(SOURCE_WAIT_MS) ?: return@launch
            if (source.id == song.id.provider) play(song, source)
        }
    }

    companion object {
        // As long as Home holds its feed for a source on a cold start
        // (HomeViewModel's ACTIVE_SOURCE_WAIT_MS).
        const val SOURCE_WAIT_MS = 4_000L
    }
}
