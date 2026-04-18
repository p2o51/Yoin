package com.gpo.yoin.data.source

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.ArtistDetail
import com.gpo.yoin.data.model.ArtistIndex
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.Lyrics
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.PlaybackHandle
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.PlaylistItemRef
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track

/**
 * A backend that provides music to Yoin (Subsonic, Spotify, …).
 *
 * The interface is sliced by concern so implementations can selectively support
 * features and UI can gate affordances via [capabilities]. Adding a new
 * provider means implementing this interface and declaring supported
 * capabilities — nothing else in the Repository/ViewModel/Composable layers
 * should need to change.
 */
interface MusicSource {
    /** Provider id matching [MediaId.provider] for entities this source emits. */
    val id: String

    val capabilities: Set<Capability>

    fun library(): MusicLibrary
    fun metadata(): MusicMetadata
    fun writeActions(): MusicWriteActions
    fun playback(): MusicPlayback

    /** Resolve a [CoverRef] this source emitted into a loadable URL. */
    fun resolveCoverUrl(ref: CoverRef, size: Int? = null): String?

    /**
     * Provider-specific warm-up run after a successful `library().ping()` in
     * [com.gpo.yoin.data.profile.ProfileManager.switchTo]. Best-effort — errors
     * are swallowed by the caller, a cold cache does not count as a failed
     * switch. Default no-op so providers can opt out.
     */
    suspend fun prime() = Unit

    /** Release any long-lived resources (connection pools, open sockets). */
    fun dispose() = Unit
}

/**
 * Feature flags a [MusicSource] can declare. UI checks the active source's
 * capability set to decide whether to render entry points for features that
 * only some backends implement.
 *
 * Notes on what's *not* a capability:
 * - Favorites / liked songs: every provider maps `setFavorite` to its native
 *   concept (Subsonic star, Spotify saved-tracks). The UI affordance is
 *   always shown.
 * - 5-star ratings: local-first (`local_ratings` Room table). Every provider
 *   stores ratings locally; Subsonic additionally pushes them to the server,
 *   Spotify no-ops server-side. The UI control is always shown.
 */
enum class Capability {
    SEARCH,
    RANDOM_SONGS,
    PLAYLISTS_READ,
    PLAYLISTS_WRITE,
    LYRICS,
}

interface MusicLibrary {
    suspend fun ping(): Boolean

    suspend fun getAlbumList(type: String, size: Int = 20, offset: Int = 0): List<Album>
    suspend fun getAlbum(id: MediaId): Album?

    suspend fun getArtists(): List<ArtistIndex>
    suspend fun getArtist(id: MediaId): ArtistDetail?

    suspend fun getPlaylists(): List<Playlist>
    suspend fun getPlaylist(id: MediaId): Playlist?

    suspend fun getStarred(): Starred
    suspend fun getRandomSongs(size: Int = 20): List<Track>

    suspend fun search(query: String): SearchResults
}

interface MusicMetadata {
    suspend fun getLyrics(trackId: MediaId): Lyrics?
}

interface MusicWriteActions {
    /** Favourite / like / star — semantics vary, but the UI concept is boolean. */
    suspend fun setFavorite(id: MediaId, favorite: Boolean): Result<Unit>

    /** 0–5 star rating (Subsonic). Providers without this concept return failure. */
    suspend fun setRating(trackId: MediaId, rating: Int): Result<Unit>

    // ── Playlist mutation ───────────────────────────────────────────────
    //
    // Implementations MUST gate UI affordances via [Playlist.canWrite] — some
    // endpoints (e.g. Spotify `PUT /playlists/{id}`) will 403 on a followed-
    // but-not-owned playlist, which the caller cannot distinguish from a
    // transient network failure without the flag.

    /** Create a new playlist owned by the current profile. */
    suspend fun createPlaylist(name: String, description: String? = null): Result<Playlist>

    /** Rename (and optionally re-describe) an existing playlist. */
    suspend fun renamePlaylist(
        id: MediaId,
        name: String,
        description: String? = null,
    ): Result<Unit>

    /**
     * Delete a playlist. Spotify has no true delete — this is implemented as
     * "unfollow your own playlist", which behaves the same way from the
     * user's point of view (the playlist disappears from `/me/playlists`).
     */
    suspend fun deletePlaylist(id: MediaId): Result<Unit>

    /**
     * Append tracks to a playlist. Returns the new snapshot id when the
     * provider supports optimistic concurrency (Spotify), or `null`
     * (Subsonic). Callers should pass that id to the next mutation to
     * detect concurrent edits.
     */
    suspend fun addTracksToPlaylist(
        playlistId: MediaId,
        tracks: List<MediaId>,
    ): Result<String?>

    /**
     * Remove items from a playlist by [PlaylistItemRef.position]. Providers
     * that key removal by track uri (Spotify) will convert
     * [PlaylistItemRef.trackId] + [PlaylistItemRef.position] to their native
     * shape. Pass the [snapshotId] returned by a previous mutation (Spotify)
     * to detect concurrent edits; Subsonic ignores it. Returns the new
     * snapshot id where applicable.
     */
    suspend fun removeTracksFromPlaylist(
        playlistId: MediaId,
        items: List<PlaylistItemRef>,
        snapshotId: String? = null,
    ): Result<String?>
}

interface MusicPlayback {
    suspend fun handleFor(track: Track): PlaybackHandle
}
