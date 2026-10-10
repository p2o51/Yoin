package com.gpo.yoin.player

import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.model.isUnplayableAppleImport

/** The queue [PlaybackManager.play] actually hands to a player. */
internal data class PlayableQueue(val tracks: List<Track>, val startIndex: Int)

/**
 * Drops the Apple Music imports MusicKit can't play ([isUnplayableAppleImport])
 * from [tracks] and moves [startIndex] to where the started track lands. One
 * import used to fail the whole start, even when the tapped song was playable.
 *
 * A started track that is itself an import is left as given, so the provider's
 * own error still reaches the user instead of another song quietly playing.
 * Other providers' tracks are never imports: their lists pass through as is.
 */
internal fun playableQueue(tracks: List<Track>, startIndex: Int): PlayableQueue {
    val unplayable = tracks.map { it.isUnplayableAppleImport }
    if (unplayable[startIndex] || true !in unplayable) return PlayableQueue(tracks, startIndex)
    return PlayableQueue(
        tracks = tracks.filterIndexed { index, _ -> !unplayable[index] },
        startIndex = (0 until startIndex).count { !unplayable[it] }
    )
}
