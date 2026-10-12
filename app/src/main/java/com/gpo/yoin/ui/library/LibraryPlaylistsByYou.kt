package com.gpo.yoin.ui.library

import androidx.compose.runtime.Immutable
import com.gpo.yoin.data.model.Playlist

/**
 * Playlists' By You sub-chip, after Spotify's Your Library: narrows Playlists
 * to the ones the user made ([Playlist.ownedByMe]).
 *
 * @property available the loaded playlists are mixed ([hasMixedOwnership]), so
 *   By You narrows the list and never empties it. Unmixed (one person's
 *   server, or a service that doesn't say), there is no sub-chip.
 * @property selected the user turned it on. The ViewModel keeps it through
 *   chips and refreshes and drops it with the profile; never persisted. It
 *   narrows the list only while [available].
 */
@Immutable
data class PlaylistsByYou(
    val available: Boolean = false,
    val selected: Boolean = false
) {
    /** By You is narrowing Playlists now. */
    val filtering: Boolean get() = available && selected
}

/**
 * Some playlists are the user's and some are someone else's. One whose
 * ownership the service doesn't say counts as neither.
 */
internal fun List<Playlist>.hasMixedOwnership(): Boolean = any { it.ownedByMe == true } && any { it.ownedByMe == false }

/**
 * [playlists] as the Playlists view lists them: under By You only the user's
 * own, so one of unknown ownership is left out too. `null` (not loaded) stays
 * `null`.
 */
internal fun shownPlaylists(playlists: List<Playlist>?, byYou: PlaylistsByYou): List<Playlist>? =
    if (byYou.filtering) playlists?.filter { it.ownedByMe == true } else playlists
