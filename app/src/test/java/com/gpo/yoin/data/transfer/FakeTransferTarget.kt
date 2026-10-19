package com.gpo.yoin.data.transfer

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
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.MusicLibrary
import com.gpo.yoin.data.source.MusicMetadata
import com.gpo.yoin.data.source.MusicPlayback
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.MusicWriteActions
import java.io.IOException

/** A target service for transfer tests: ISRC lookups and searches from maps, writes recorded. */
internal class FakeTransferTarget(
    val isrc: Map<String, List<Track>> = emptyMap(),
    val search: Map<String, List<Track>> = emptyMap(),
    val searchFails: Boolean = false,
    val failAddAfterBatches: Int = Int.MAX_VALUE,
    val playlists: Map<MediaId, Playlist> = emptyMap(),
    override val id: String = "target",
) : MusicSource, MusicLibrary, MusicWriteActions, MusicMetadata, MusicPlayback {
    val searches = mutableListOf<String>()
    val isrcLookups = mutableListOf<List<String>>()
    val created = mutableListOf<Pair<String, String?>>()
    val added = mutableListOf<MediaId>()
    private var addBatches = 0

    override val capabilities = setOf(Capability.SEARCH, Capability.PLAYLIST_IMPORT)
    override fun library() = this
    override fun metadata() = this
    override fun writeActions() = this
    override fun playback() = this
    override fun resolveCoverUrl(ref: CoverRef, size: Int?): String? = null

    override suspend fun tracksByIsrc(isrcs: List<String>): Map<String, List<Track>> {
        isrcLookups += isrcs
        return isrc.filterKeys { it in isrcs }
    }

    override suspend fun search(query: String): SearchResults {
        searches += query
        if (searchFails) throw IOException("offline")
        return SearchResults(tracks = search[query].orEmpty())
    }

    override suspend fun createPlaylist(name: String, description: String?): Result<Playlist> {
        created += name to description
        return Result.success(Playlist(MediaId(id, "new"), name, null, null, 0, null, canWrite = true))
    }

    override suspend fun addTracksToPlaylist(playlistId: MediaId, tracks: List<MediaId>): Result<String?> {
        if (addBatches++ >= failAddAfterBatches) return Result.failure(IOException("rate limited"))
        added += tracks
        return Result.success(null)
    }

    override suspend fun ping() = true
    override suspend fun getAlbumList(type: String, size: Int, offset: Int) = emptyList<Album>()
    override suspend fun getAlbum(id: MediaId): Album? = null
    override suspend fun getArtists() = emptyList<ArtistIndex>()
    override suspend fun getArtist(id: MediaId): ArtistDetail? = null
    override suspend fun getPlaylists() = emptyList<Playlist>()
    override suspend fun getPlaylist(id: MediaId): Playlist? = playlists[id]
    override suspend fun getStarred() = Starred()
    override suspend fun getRandomSongs(size: Int) = emptyList<Track>()
    override suspend fun getLyrics(trackId: MediaId): Lyrics? = null
    override suspend fun setFavorite(id: MediaId, favorite: Boolean) = Result.success(Unit)
    override suspend fun setRating(trackId: MediaId, rating: Int) = Result.success(Unit)
    override suspend fun renamePlaylist(id: MediaId, name: String, description: String?) = Result.success(Unit)
    override suspend fun deletePlaylist(id: MediaId) = Result.success(Unit)
    override suspend fun removeTracksFromPlaylist(
        playlistId: MediaId,
        items: List<PlaylistItemRef>,
        snapshotId: String?,
    ): Result<String?> = Result.success(null)
    override suspend fun handleFor(track: Track): PlaybackHandle = throw UnsupportedOperationException()
}
