package com.gpo.yoin.data.source

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.ArtistDetail
import com.gpo.yoin.data.model.ArtistIndex
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.Lyrics
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.PlaybackHandle
import com.gpo.yoin.data.model.Playlist
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

enum class Capability {
    SEARCH,
    RANDOM_SONGS,
    STAR_UNSTAR,
    RATING_FIVE_STAR,
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
}

interface MusicPlayback {
    suspend fun handleFor(track: Track): PlaybackHandle
}
