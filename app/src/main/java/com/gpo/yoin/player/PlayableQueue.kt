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
 * [explicitStart] says the user picked the started track (a row tap): if that
 * track is itself an import, the list is left as given, so the provider's own
 * error reaches the user instead of another song quietly playing. A Play or
 * Shuffle button picks no song, so an import there gives way to the next song
 * that plays (the last one before it, at the end of the list). A list with
 * nothing playable is left as given. Other providers' tracks are never
 * imports: their lists pass through as is.
 */
internal fun playableQueue(tracks: List<Track>, startIndex: Int, explicitStart: Boolean): PlayableQueue {
    val unplayable = tracks.map { it.isUnplayableAppleImport }
    if (true !in unplayable || false !in unplayable) return PlayableQueue(tracks, startIndex)
    if (explicitStart && unplayable[startIndex]) return PlayableQueue(tracks, startIndex)
    val playable = tracks.filterIndexed { index, _ -> !unplayable[index] }
    return PlayableQueue(
        tracks = playable,
        // The playable songs ahead of the start: its own new index, or for an
        // import start, the next playable song's.
        startIndex = (0 until startIndex).count { !unplayable[it] }.coerceAtMost(playable.lastIndex)
    )
}
