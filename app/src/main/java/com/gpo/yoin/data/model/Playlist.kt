package com.gpo.yoin.data.model

/**
 * A playlist returned by a [com.gpo.yoin.data.source.MusicSource].
 *
 * @property canWrite Whether the currently-active profile is allowed to
 *   modify this playlist. Subsonic's `getPlaylists` mixes in other users'
 *   public playlists, so it is `false` for those and for ones the server marks
 *   readonly; Spotify's `/me/playlists` can return owned + followed mixed, and
 *   write APIs will 403 on a followed-but-not-owned playlist — the mapper sets
 *   this to `owner.id == currentUser.id`. Apple Music has no playlist writes
 *   in Yoin, so it is always `false` there. UI must honour this flag before
 *   exposing rename / delete / add-track affordances.
 * @property snapshotId Optimistic-concurrency token used by Spotify's
 *   playlist-mutation endpoints (`POST /playlists/{id}/items` returns a new
 *   snapshot; `DELETE /playlists/{id}/items` accepts one). Subsonic leaves
 *   it `null`.
 * @property comment Free-form description authored on the server — Subsonic's
 *   `comment`, Spotify's `description`. `null` when the provider carries none.
 * @property libraryAddedAt When the playlist joined the user's library
 *   (ISO-8601): Subsonic's `created`, an Apple Music library playlist's
 *   `dateAdded`. Spotify's `/me/playlists` carries no date, so it stays `null`.
 * @property ownedByMe Whether the active profile's user made this playlist,
 *   for Library's By You; `null` when the service doesn't say. Kept apart from
 *   [canWrite], which gates edits. Spotify: `owner.id` against the user's id
 *   (its library cache keeps only `canWrite`, the same comparison, and reads
 *   it back as this). Apple Music: a library playlist's `canEdit`, as Apple
 *   names no owner; catalog playlists stay `null`. Subsonic: `owner` against
 *   the login name, ignoring case; no owner is `null`. A list-level fact: the
 *   detail page's disk cache doesn't keep it.
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
    val comment: String? = null,
    val libraryAddedAt: String? = null,
    val ownedByMe: Boolean? = null,
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
