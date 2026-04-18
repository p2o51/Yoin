package com.gpo.yoin.data.model

/**
 * A playlist returned by a [com.gpo.yoin.data.source.MusicSource].
 *
 * @property canWrite Whether the currently-active profile is allowed to
 *   modify this playlist. Subsonic returns only the user's own playlists
 *   so it's always `true`; Spotify's `/me/playlists` can return
 *   owned + followed mixed, and write APIs will 403 on a followed-but-not-
 *   owned playlist — the mapper sets this to `owner.id == currentUser.id`.
 *   UI must honour this flag before exposing rename / delete / add-track
 *   affordances.
 * @property snapshotId Optimistic-concurrency token used by Spotify's
 *   playlist-mutation endpoints (`POST /playlists/{id}/items` returns a new
 *   snapshot; `DELETE /playlists/{id}/items` accepts one). Subsonic leaves
 *   it `null`.
 */
data class Playlist(
    val id: MediaId,
    val name: String,
    val owner: String?,
    val coverArt: CoverRef?,
    val songCount: Int?,
    val durationSec: Int?,
    val tracks: List<Track> = emptyList(),
    val canWrite: Boolean = false,
    val snapshotId: String? = null,
)

/**
 * Identifies a single track occurrence inside a playlist for a remove
 * operation. [position] is the zero-based index in server order, necessary
 * because Subsonic's `updatePlaylist.view` removes by index and because
 * Spotify's `DELETE /playlists/{id}/items` needs `positions` to
 * disambiguate duplicate tracks.
 */
data class PlaylistItemRef(
    val trackId: MediaId,
    val position: Int,
)
