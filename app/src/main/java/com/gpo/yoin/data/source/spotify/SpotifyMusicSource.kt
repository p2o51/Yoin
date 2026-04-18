package com.gpo.yoin.data.source.spotify

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
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.MusicLibrary
import com.gpo.yoin.data.source.MusicMetadata
import com.gpo.yoin.data.source.MusicPlayback
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.MusicWriteActions
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class SpotifyMusicSource(
    initialCredentials: ProfileCredentials.Spotify,
    clientIdProvider: () -> String,
    onCredentialsPersisted: suspend (ProfileCredentials.Spotify) -> Unit,
    httpClient: OkHttpClient = defaultHttpClient(),
    authService: SpotifyAuthService = SpotifyAuthService(httpClient),
) : MusicSource {

    private val apiClient = SpotifyApiClient(
        httpClient = httpClient,
        authService = authService,
        initialCredentials = initialCredentials,
        clientIdProvider = clientIdProvider,
        onCredentialsRefreshed = onCredentialsPersisted,
    )

    override val id: String = MediaId.PROVIDER_SPOTIFY

    override val capabilities: Set<Capability> = setOf(
        Capability.SEARCH,
        Capability.RANDOM_SONGS,
        Capability.PLAYLISTS_READ,
        Capability.PLAYLISTS_WRITE,
        // No LYRICS: Spotify Web API does not expose lyrics.
    )

    private var savedTracksCache: List<SpotifySavedTrackObject>? = null
    private var savedAlbumsCache: List<SpotifySavedAlbumObject>? = null
    private var playlistsCache: List<SpotifyPlaylistObject>? = null
    private var followedArtistsCache: List<SpotifyArtistObject>? = null

    private val library = object : MusicLibrary {
        override suspend fun ping(): Boolean {
            apiClient.getMe()
            return true
        }

        override suspend fun getAlbumList(type: String, size: Int, offset: Int): List<Album> {
            val albums = savedAlbums()
            val savedAlbumIds = savedAlbumIds()
            val mapped = when (type) {
                "alphabeticalByName" -> albums.sortedBy { it.album?.name?.lowercase().orEmpty() }
                "recent" -> albums.sortedByDescending { it.addedAt.orEmpty() }
                "random" -> albums.shuffled()
                else -> albums
            }.mapNotNull { savedAlbum ->
                savedAlbum.album?.toSimplifiedAlbum()?.toAlbum(savedAlbumIds = savedAlbumIds)
            }
            return mapped.drop(offset.coerceAtLeast(0)).take(size.coerceAtLeast(0))
        }

        override suspend fun getAlbum(id: MediaId): Album? = withSpotifyId(id) { rawId ->
            val savedAlbumIds = savedAlbumIds()
            val savedTrackIds = savedTrackIds()
            val album = runCatching { apiClient.getAlbum(rawId) }
                .getOrElse { error ->
                    if (error.isSpotifyNotFound()) return@withSpotifyId null
                    throw error
                }
            val tracks = apiClient.getAlbumTracks(rawId)
            album.toAlbum(
                savedAlbumIds = savedAlbumIds,
                savedTrackIds = savedTrackIds,
                tracksOverride = tracks,
            )
        }

        override suspend fun getArtists(): List<ArtistIndex> =
            libraryArtists().toArtistIndices()

        override suspend fun getArtist(id: MediaId): ArtistDetail? = withSpotifyId(id) { rawId ->
            val savedAlbumIds = savedAlbumIds()
            val followedArtistIds = followedArtistIds()
            val artist = runCatching { apiClient.getArtist(rawId) }
                .getOrElse { error ->
                    if (error.isSpotifyNotFound()) return@withSpotifyId null
                    throw error
                }
            val albums = apiClient.getArtistAlbums(rawId)
                .distinctBy(SpotifySimplifiedAlbumObject::id)
                .map { it.toAlbum(savedAlbumIds = savedAlbumIds) }
                .sortedWith(compareByDescending<Album> { it.year ?: Int.MIN_VALUE }.thenBy { it.name.lowercase() })
            artist.toArtistDetail(
                albums = albums,
                isStarred = rawId in followedArtistIds,
            )
        }

        override suspend fun getPlaylists(): List<Playlist> {
            val meId = apiClient.getCurrentUserId()
            return currentUserPlaylists()
                .sortedBy { it.name.lowercase() }
                .map { playlist -> playlist.toPlaylist(canWrite = playlist.owner?.id == meId) }
        }

        override suspend fun getPlaylist(id: MediaId): Playlist? = withSpotifyId(id) { rawId ->
            val savedTrackIds = savedTrackIds()
            val meId = apiClient.getCurrentUserId()
            val playlist = runCatching { apiClient.getPlaylist(rawId) }
                .getOrElse { error ->
                    if (error.isSpotifyNotFound()) return@withSpotifyId null
                    throw error
                }
            val tracks = apiClient.getPlaylistItems(rawId)
                .mapNotNull(SpotifyPlaylistItemObject::track)
                .mapNotNull { track ->
                    runCatching { track.toTrack(savedTrackIds = savedTrackIds) }.getOrNull()
                }
            playlist.toPlaylist(
                tracks = tracks,
                canWrite = playlist.owner?.id == meId,
            )
        }

        override suspend fun getStarred(): Starred {
            val savedTrackIds = savedTrackIds()
            val savedAlbumIds = savedAlbumIds()
            val followedArtistIds = followedArtistIds()
            val tracks = savedTracks()
                .mapNotNull { savedTrack -> savedTrack.track?.toTrack(savedTrackIds = savedTrackIds) }
            val albums = savedAlbums()
                .mapNotNull { savedAlbum ->
                    savedAlbum.album?.toSimplifiedAlbum()?.toAlbum(savedAlbumIds = savedAlbumIds)
                }
            val artists = followedArtists()
                .map { artist -> artist.toArtist(isStarred = artist.id in followedArtistIds) }
            return toStarred(tracks = tracks, albums = albums, artists = artists)
        }

        override suspend fun getRandomSongs(size: Int): List<Track> =
            savedTracks()
                .mapNotNull { savedTrack -> savedTrack.track?.let { track ->
                    track.toTrack(savedTrackIds = setOf(track.id).filterNotNull().toSet())
                } }
                .shuffled()
                .take(size.coerceAtLeast(0))

        override suspend fun search(query: String): SearchResults =
            apiClient.search(query = query).toSearchResults(
                savedTrackIds = savedTrackIds(),
                savedAlbumIds = savedAlbumIds(),
                followedArtistIds = followedArtistIds(),
            )
    }

    private val metadata = object : MusicMetadata {
        override suspend fun getLyrics(trackId: MediaId): Lyrics? = null
    }

    private val writeActions = object : MusicWriteActions {
        override suspend fun setFavorite(id: MediaId, favorite: Boolean): Result<Unit> = runCatching {
            val rawId = requireSpotify(id).rawId
            // Current callsites only toggle favorite for tracks. If future UI
            // starts starring albums/artists/playlists, this interface needs an
            // explicit entity type rather than a bare MediaId.
            val uri = "spotify:track:$rawId"
            if (favorite) {
                apiClient.saveToLibrary(uri)
            } else {
                apiClient.removeFromLibrary(uri)
            }
            savedTracksCache = null
        }

        override suspend fun setRating(trackId: MediaId, rating: Int): Result<Unit> =
            Result.failure(UnsupportedOperationException("Spotify has no 5-star rating"))

        override suspend fun createPlaylist(
            name: String,
            description: String?,
        ): Result<Playlist> = runCatching {
            val created = apiClient.createPlaylist(name = name, description = description)
            playlistsCache = null
            // Freshly-created playlists are owned by the caller → canWrite = true.
            created.toPlaylist(canWrite = true)
        }

        override suspend fun renamePlaylist(
            id: MediaId,
            name: String,
            description: String?,
        ): Result<Unit> = runCatching {
            val rawId = requireSpotify(id).rawId
            apiClient.renamePlaylist(id = rawId, name = name, description = description)
            playlistsCache = null
        }

        override suspend fun deletePlaylist(id: MediaId): Result<Unit> = runCatching {
            val rawId = requireSpotify(id).rawId
            // Spotify has no real delete — unfollowing your own playlist removes
            // it from /me/playlists, which is the product-level "delete".
            apiClient.unfollowPlaylist(rawId)
            playlistsCache = null
        }

        override suspend fun addTracksToPlaylist(
            playlistId: MediaId,
            tracks: List<MediaId>,
        ): Result<String?> = runCatching {
            if (tracks.isEmpty()) return@runCatching null
            val rawId = requireSpotify(playlistId).rawId
            val uris = tracks.map { "spotify:track:${requireSpotify(it).rawId}" }
            val snapshot = apiClient.addTracksToPlaylist(id = rawId, uris = uris)
            playlistsCache = null
            snapshot
        }

        override suspend fun removeTracksFromPlaylist(
            playlistId: MediaId,
            items: List<PlaylistItemRef>,
            snapshotId: String?,
        ): Result<String?> = runCatching {
            if (items.isEmpty()) return@runCatching snapshotId
            val rawId = requireSpotify(playlistId).rawId
            // Spotify removes by uri + positions; group positions by uri so
            // duplicate tracks at different positions each get targeted.
            val grouped = items
                .groupBy { item -> "spotify:track:${requireSpotify(item.trackId).rawId}" }
                .map { (uri, refs) ->
                    SpotifyRemoveTrackItem(
                        uri = uri,
                        positions = refs.map { it.position }.sorted(),
                    )
                }
            val newSnapshot = apiClient.removeTracksFromPlaylist(
                id = rawId,
                items = grouped,
                snapshotId = snapshotId,
            )
            playlistsCache = null
            newSnapshot
        }
    }

    private val playback = object : MusicPlayback {
        override suspend fun handleFor(track: Track): PlaybackHandle {
            val rawId = track.id.rawId
            return PlaybackHandle.ExternalController(
                type = PlaybackHandle.ControllerType.SPOTIFY_APP_REMOTE,
                payload = "spotify:track:$rawId",
            )
        }
    }

    override fun library(): MusicLibrary = library
    override fun metadata(): MusicMetadata = metadata
    override fun writeActions(): MusicWriteActions = writeActions
    override fun playback(): MusicPlayback = playback

    override suspend fun prime() {
        runCatching { library.getAlbumList(type = "recent", size = 20, offset = 0) }
        runCatching { library.getArtists() }
    }

    override fun resolveCoverUrl(ref: CoverRef, size: Int?): String? = when (ref) {
        is CoverRef.Url -> ref.url
        // Spotify entities never emit SourceRelative — defensive.
        is CoverRef.SourceRelative -> null
    }

    private suspend fun savedTracks(): List<SpotifySavedTrackObject> =
        savedTracksCache ?: apiClient.getSavedTracks().also { savedTracksCache = it }

    private suspend fun savedAlbums(): List<SpotifySavedAlbumObject> =
        savedAlbumsCache ?: apiClient.getSavedAlbums().also { savedAlbumsCache = it }

    private suspend fun currentUserPlaylists(): List<SpotifyPlaylistObject> =
        playlistsCache ?: apiClient.getCurrentUserPlaylists().also { playlistsCache = it }

    private suspend fun followedArtists(): List<SpotifyArtistObject> =
        followedArtistsCache ?: apiClient.getFollowedArtists().also { followedArtistsCache = it }

    private suspend fun savedTrackIds(): Set<String> =
        savedTracks().mapNotNullTo(linkedSetOf()) { savedTrack -> savedTrack.track?.id }

    private suspend fun savedAlbumIds(): Set<String> =
        savedAlbums().mapNotNullTo(linkedSetOf()) { savedAlbum -> savedAlbum.album?.id }

    private suspend fun followedArtistIds(): Set<String> =
        followedArtists().mapTo(linkedSetOf(), SpotifyArtistObject::id)

    private suspend fun libraryArtists(): List<com.gpo.yoin.data.model.Artist> {
        val followed = followedArtists()
        val followedArtistIds = followed.mapTo(linkedSetOf(), SpotifyArtistObject::id)
        val merged = linkedMapOf<MediaId, com.gpo.yoin.data.model.Artist>()

        fun absorb(artist: com.gpo.yoin.data.model.Artist) {
            val existing = merged[artist.id]
            merged[artist.id] = if (existing == null) {
                artist
            } else {
                existing.copy(
                    albumCount = existing.albumCount ?: artist.albumCount,
                    coverArt = existing.coverArt ?: artist.coverArt,
                    isStarred = existing.isStarred || artist.isStarred,
                )
            }
        }

        followed.forEach { artist ->
            absorb(artist.toArtist(isStarred = true))
        }

        savedAlbums().forEach { savedAlbum ->
            val album = savedAlbum.album ?: return@forEach
            val coverArt = album.bestImageUrl()?.let(CoverRef::Url)
            album.artists.forEach { artist ->
                absorb(
                    com.gpo.yoin.data.model.Artist(
                        id = MediaId.spotify(artist.id),
                        name = artist.name,
                        albumCount = null,
                        coverArt = coverArt,
                        isStarred = artist.id in followedArtistIds,
                    ),
                )
            }
        }

        savedTracks().forEach { savedTrack ->
            val track = savedTrack.track ?: return@forEach
            val coverArt = track.album?.bestImageUrl()?.let(CoverRef::Url)
            track.artists.forEach { artist ->
                absorb(
                    com.gpo.yoin.data.model.Artist(
                        id = MediaId.spotify(artist.id),
                        name = artist.name,
                        albumCount = null,
                        coverArt = coverArt,
                        isStarred = artist.id in followedArtistIds,
                    ),
                )
            }
        }

        return merged.values.toList()
    }

    private fun requireSpotify(id: MediaId): MediaId {
        require(id.provider == MediaId.PROVIDER_SPOTIFY) {
            "SpotifyMusicSource received a non-Spotify MediaId: $id"
        }
        return id
    }

    private suspend fun <T> withSpotifyId(id: MediaId, block: suspend (String) -> T): T =
        block(requireSpotify(id).rawId)

    private fun Throwable.isSpotifyNotFound(): Boolean =
        this is SpotifyAuthException && code == HTTP_NOT_FOUND

    companion object {
        private const val HTTP_NOT_FOUND = 404

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
