package com.gpo.yoin.player

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.model.isUnplayableAppleImport

/** The queue [PlaybackManager.play] actually hands to a player. */
internal data class PlayableQueue(val tracks: List<Track>, val startIndex: Int)

/** Apple caps GET catalog/{storefront}/songs?ids= at 300; MusicKit 1.1.1 does not batch that request. */
internal const val APPLE_MUSIC_CATALOG_MAX_IDS = 300

/**
 * The playable queue for a start, after unavailable songs are removed. Large Apple Music lists use
 * the same 100-song window as Library Songs and artist starts: the SDK otherwise requests every
 * catalog id at once and the entire start fails above Apple's limit. The selected song and order
 * within the window stay intact; this does not queue the rest of a large playlist.
 * An explicit unavailable target still reaches the provider's error path without substituting a song.
 */
internal fun playableStartQueue(
    tracks: List<Track>,
    startIndex: Int,
    explicitStart: Boolean,
    sourceId: String
): PlayableQueue {
    val queue = playableQueue(tracks, startIndex, explicitStart)
    return if (
        sourceId == MediaId.PROVIDER_APPLE_MUSIC && queue.tracks.size > APPLE_MUSIC_CATALOG_MAX_IDS &&
        !queue.tracks[queue.startIndex].isUnplayableAppleImport
    ) {
        startWindowQueue(queue.tracks, queue.startIndex)
    } else {
        queue
    }
}

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

/**
 * What a ▾ queue action hands the queue from a page's [tracks]: all but the
 * Apple Music songs MusicKit can't play, which addToQueue leaves out (so the
 * count does too). Null while the page has no songs, and the tap says nothing;
 * a page of nothing but those still says so ("Added 0 songs to the queue"),
 * where its tap used to do nothing without a word.
 */
internal fun queueableSongs(tracks: List<Track>): List<Track>? =
    if (tracks.isEmpty()) null else tracks.filterNot { it.isUnplayableAppleImport }
