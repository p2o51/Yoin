package com.gpo.yoin.data.source.applemusic

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
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
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiClient
import com.gpo.yoin.data.remote.applemusic.AppleMusicDeveloperTokenProvider
import com.gpo.yoin.data.remote.applemusic.AppleMusicSong
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.MusicLibrary
import com.gpo.yoin.data.source.MusicMetadata
import com.gpo.yoin.data.source.MusicPlayback
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.MusicWriteActions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Profile-scoped REST content; DRM playback is delegated to the official MusicKit SDK. */
class AppleMusicSource(
    val credentials: ProfileCredentials.AppleMusic,
    private val tokenProvider: AppleMusicDeveloperTokenProvider =
        AppleMusicDeveloperTokenProvider(credentials.endpoint.toHttpUrl()),
    private val api: AppleMusicApiClient = AppleMusicApiClient(tokenProvider::token, { credentials.musicUserToken })
) : MusicSource, MusicLibrary, MusicMetadata, MusicWriteActions, MusicPlayback {
    override val id = MediaId.PROVIDER_APPLE_MUSIC
    override val capabilities = setOf(Capability.SEARCH, Capability.PLAYLISTS_READ)

    @Volatile var playbackDeveloperToken: String = ""
        private set
    private var storefront: String? = null

    suspend fun refreshPlaybackToken() {
        playbackDeveloperToken = tokenProvider.token()
    }
    private suspend fun storefront(): String = storefront ?: api.storefront().also { storefront = it }
    override fun library() = this
    override fun metadata() = this
    override fun writeActions() = this
    override fun playback() = this
    override suspend fun ping(): Boolean {
        storefront()
        return true
    }
    override fun resolveCoverUrl(ref: CoverRef, size: Int?): String? = (ref as? CoverRef.Url)?.url

    private suspend fun path(kind: String, mediaId: MediaId? = null): List<String> {
        require(mediaId == null || mediaId.provider == id)
        val library = mediaId == null || mediaId.rawId.startsWith("library:")
        return (if (library) listOf("v1", "me", "library", kind) else listOf("v1", "catalog", storefront(), kind)) +
            listOfNotNull(mediaId?.rawId?.removePrefix("library:"))
    }

    private suspend fun page(path: List<String>, query: Map<String, String> = emptyMap(), next: String? = null) =
        api.resourcePage(path, path.getOrNull(1) == "me", query, next)

    private suspend fun all(path: List<String>, query: Map<String, String> = emptyMap()): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        val visited = mutableSetOf<String>()
        var next: String? = null
        do {
            val response = page(path, query, next)
            result += response.resources()
            next = response.text("next")
            check(next == null || visited.add(next)) { "Apple Music repeated a pagination link" }
        } while (next != null)
        return result
    }

    override suspend fun getAlbumList(type: String, size: Int, offset: Int): List<Album> {
        if (size <= 0) return emptyList()
        // recently-added is a mixed, server-paginated collection. Filter before applying album offsets.
        val recent = type == "newest"
        val requestPath = if (recent) listOf("v1", "me", "library", "recently-added") else path("albums")
        val query = if (recent) emptyMap() else mapOf("limit" to "100", "offset" to offset.coerceAtLeast(0).toString())
        val albums = mutableListOf<Album>()
        val visited = mutableSetOf<String>()
        val skip = if (recent) offset.coerceAtLeast(0) else 0
        var next: String? = null
        do {
            val response = page(requestPath, query, next)
            albums += response.resources().filter { it.text("type") in setOf("albums", "library-albums") }.map(::album)
            next = response.text("next")
            check(next == null || visited.add(next)) { "Apple Music repeated a pagination link" }
        } while (next != null && albums.size < skip + size)
        return albums.drop(skip).take(size)
    }

    override suspend fun getAlbum(id: MediaId): Album? {
        val path = path("albums", id)
        val resource = page(path, mapOf("include" to "artists")).resources().firstOrNull() ?: return null
        val mappedAlbum = album(resource)
        val tracks = all(path + "tracks", trackQuery(id)).songTracks().map { track ->
            track.copy(albumId = track.albumId ?: id, artistId = track.artistId ?: mappedAlbum.artistId)
        }
        return mappedAlbum.copy(
            tracks = tracks,
            songCount = tracks.size,
            durationSec = tracks.sumOf {
                it.durationSec ?: 0
            }
        )
    }

    override suspend fun getArtists(): List<ArtistIndex> = all(path("artists")).map(::artist)
        .groupBy { it.name.firstOrNull()?.uppercase() ?: "#" }.toSortedMap()
        .map { (letter, artists) -> ArtistIndex(letter, artists) }

    override suspend fun getArtist(id: MediaId): ArtistDetail? {
        val path = path("artists", id)
        val resource = page(path).resources().firstOrNull() ?: return null
        val artist = artist(resource)
        val albums = all(path + "albums").map(::album)
        return ArtistDetail(artist.id, artist.name, albums.size, artist.coverArt, albums = albums)
    }

    override suspend fun getPlaylists(): List<Playlist> = all(path("playlists")).map(::playlist)
    override suspend fun getPlaylist(id: MediaId): Playlist? {
        val path = path("playlists", id)
        val resource = page(path).resources().firstOrNull() ?: return null
        val tracks = all(path + "tracks", trackQuery(id)).songTracks()
        return playlist(resource).copy(
            tracks = tracks,
            songCount = tracks.size,
            durationSec = tracks.sumOf {
                it.durationSec ?: 0
            }
        )
    }

    override suspend fun search(query: String): SearchResults {
        if (query.isBlank()) return SearchResults()
        val results = page(
            listOf("v1", "catalog", storefront(), "search"),
            mapOf(
                "term" to query,
                "types" to "songs,albums,artists,playlists",
                "limit" to "25",
                "include[songs]" to "albums,artists"
            )
        )["results"]?.jsonObject ?: return SearchResults()
        return SearchResults(
            tracks = results["songs"]?.jsonObject?.resources().orEmpty().songTracks(),
            albums = results["albums"]?.jsonObject?.resources().orEmpty().map(::album),
            artists = results["artists"]?.jsonObject?.resources().orEmpty().map(::artist),
            playlists = results["playlists"]?.jsonObject?.resources().orEmpty().map(::playlist)
        )
    }

    // Library membership is not an Apple Music favorite. Unsupported mutations never fake success.
    override suspend fun getStarred() = Starred()
    override suspend fun getRandomSongs(size: Int) = emptyList<Track>()
    override suspend fun getLyrics(trackId: MediaId): Lyrics? = null
    private fun <T> unsupported(): Result<T> = Result.failure(
        UnsupportedOperationException("Manage this in Apple Music.")
    )
    override suspend fun setFavorite(id: MediaId, favorite: Boolean): Result<Unit> = unsupported()
    override suspend fun setRating(trackId: MediaId, rating: Int): Result<Unit> = unsupported()
    override suspend fun createPlaylist(name: String, description: String?): Result<Playlist> = unsupported()
    override suspend fun renamePlaylist(id: MediaId, name: String, description: String?): Result<Unit> = unsupported()
    override suspend fun deletePlaylist(id: MediaId): Result<Unit> = unsupported()
    override suspend fun addTracksToPlaylist(playlistId: MediaId, tracks: List<MediaId>): Result<String?> =
        unsupported()
    override suspend fun removeTracksFromPlaylist(
        playlistId: MediaId,
        items: List<PlaylistItemRef>,
        snapshotId: String?
    ): Result<String?> = unsupported()
    override suspend fun handleFor(track: Track): PlaybackHandle {
        require(track.id.provider == id)
        val catalogId = track.extras["appleMusicCatalogId"] ?: track.id.rawId.takeIf { it.matches(Regex("[0-9]+")) }
        requireNotNull(catalogId) { "This imported song has no Apple Music catalog version. Play it in Apple Music." }
        return PlaybackHandle.ExternalController(PlaybackHandle.ControllerType.APPLE_MUSIC_KIT, catalogId)
    }

    private fun trackQuery(id: MediaId) = if (id.rawId.startsWith(
            "library:"
        )
    ) {
        mapOf("include" to "catalog")
    } else {
        mapOf("include" to "albums,artists")
    }
    private fun List<JsonObject>.songTracks() = filter {
        it.text("type") in setOf("songs", "library-songs")
    }.map { AppleMusicSong.fromJson(it).toTrack() }

    companion object {
        private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.contentOrNull
        private fun JsonObject.resources() = get("data")?.jsonArray.orEmpty().map { it.jsonObject }
        private fun JsonObject.attributes() = get("attributes")?.jsonObject ?: JsonObject(emptyMap())
        private fun JsonObject.mediaId() = MediaId(
            MediaId.PROVIDER_APPLE_MUSIC,
            (if (text("type")?.startsWith("library-") == true) "library:" else "") + requireNotNull(text("id"))
        )
        private fun JsonObject.cover() = get(
            "artwork"
        )?.jsonObject?.text("url")?.replace("{w}", "600")?.replace("{h}", "600")?.let(CoverRef::Url)
        internal fun album(resource: JsonObject): Album {
            val a = resource.attributes()
            val artist = resource["relationships"]?.jsonObject?.get("artists")?.jsonObject?.resources()?.firstOrNull()
            return Album(
                resource.mediaId(), a.text("name").orEmpty(), a.text("artistName"), artist?.mediaId(), a.cover(),
                a["trackCount"]?.jsonPrimitive?.intOrNull, null, a.text("releaseDate")?.take(4)?.toIntOrNull(),
                a["genreNames"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull, addedAt = a.text("dateAdded")
            )
        }
        internal fun artist(resource: JsonObject): Artist {
            val a = resource.attributes()
            return Artist(resource.mediaId(), a.text("name").orEmpty(), null, a.cover())
        }
        internal fun playlist(resource: JsonObject): Playlist {
            val a = resource.attributes()
            return Playlist(
                resource.mediaId(),
                a.text("name").orEmpty(),
                a.text("curatorName"),
                a.cover(),
                null,
                null,
                canWrite = false,
                comment = a["description"]?.jsonObject?.text("standard")
            )
        }
    }
}
