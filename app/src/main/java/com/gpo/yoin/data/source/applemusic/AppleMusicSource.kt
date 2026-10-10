package com.gpo.yoin.data.source.applemusic

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.ArtistDetail
import com.gpo.yoin.data.model.ArtistIndex
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.Lyrics
import com.gpo.yoin.data.model.LibraryMembership
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.PlaybackHandle
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.PlaylistItemRef
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiClient
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiException
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiFailure
import com.gpo.yoin.data.remote.applemusic.AppleMusicDeveloperTokenProvider
import com.gpo.yoin.data.remote.applemusic.AppleMusicSong
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.MusicLibrary
import com.gpo.yoin.data.source.MusicMetadata
import com.gpo.yoin.data.source.MusicPlayback
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.MusicWriteActions
import com.gpo.yoin.data.source.ServiceFeatureCatalog
import com.gpo.yoin.data.source.WebLinkKind
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    override val capabilities: Set<Capability> = ServiceFeatureCatalog.appleMusic.capabilities

    @Volatile var playbackDeveloperToken: String = ""
        private set
    private var storefront: String? = null
    private val libraryMutationMutex = Mutex()
    private val pendingLibraryAdds = ConcurrentHashMap.newKeySet<String>()

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

    private suspend fun page(
        path: List<String>,
        query: Map<String, String> = emptyMap(),
        next: String? = null,
        personal: Boolean = path.getOrNull(1) == "me"
    ) = api.resourcePage(path, personal, query, next)

    /**
     * Every page of [path]. With a [fallback], a first page Apple refuses as it may refuse an include
     * ([mayRefuseInclude]) is read again with [fallback], and so is every later page.
     */
    private suspend fun all(
        path: List<String>,
        query: Map<String, String> = emptyMap(),
        fallback: Map<String, String>? = null
    ): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        val visited = mutableSetOf<String>()
        var pageQuery = query
        var next: String? = null
        do {
            val response = try {
                page(path, pageQuery, next)
            } catch (error: AppleMusicApiException) {
                if (fallback == null || next != null || !error.mayRefuseInclude()) throw error
                pageQuery = fallback
                page(path, pageQuery, null)
            }
            result += response.resources()
            next = response.text("next")
            check(next == null || visited.add(next)) { "Apple Music repeated a pagination link" }
        } while (next != null)
        return result
    }

    /** recent/played mixes albums, playlists and stations, 10 a page: keep the albums. */
    override suspend fun getRecentlyPlayedAlbums(size: Int): List<Album> {
        if (size <= 0) return emptyList()
        val albums = mutableListOf<Album>()
        val visited = mutableSetOf<String>()
        var next: String? = null
        do {
            val response = page(listOf("v1", "me", "recent", "played"), mapOf("limit" to "10"), next)
            albums += response.resources().filter { it.text("type") in setOf("albums", "library-albums") }.map(::album)
            next = response.text("next")
            check(next == null || visited.add(next)) { "Apple Music repeated a pagination link" }
        } while (next != null && albums.size < size && visited.size < RecentPlayedMaxPages)
        return albums.distinctBy { it.id }.take(size)
    }

    override suspend fun getAlbumList(type: String, size: Int, offset: Int): List<Album> {
        if (size <= 0) return emptyList()
        // recently-added is a mixed, server-paginated collection. Filter before applying album offsets.
        val recent = type == "newest"
        val requestPath = if (recent) listOf("v1", "me", "library", "recently-added") else path("albums")
        // The catalog relationship lets a library album open as the full catalog album.
        var query = if (recent) {
            RecentlyAddedPageQuery
        } else {
            mapOf("include" to "catalog", "limit" to "100", "offset" to offset.coerceAtLeast(0).toString())
        }
        val albums = mutableListOf<Album>()
        val visited = mutableSetOf<String>()
        val skip = if (recent) offset.coerceAtLeast(0) else 0
        var next: String? = null
        do {
            val response = try {
                page(requestPath, query, next)
            } catch (error: AppleMusicApiException) {
                // recently-added does not document its page size: if Apple refuses ours, page at its default.
                val refused = (error.failure as? AppleMusicApiFailure.Http)?.status == 400
                if (!recent || next != null || query.isEmpty() || !refused) throw error
                query = emptyMap()
                page(requestPath, query, null)
            }
            albums += response.resources().filter { it.text("type") in setOf("albums", "library-albums") }.map(::album)
            next = response.text("next")
            check(next == null || visited.add(next)) { "Apple Music repeated a pagination link" }
        } while (next != null && albums.size < skip + size)
        return albums.drop(skip).take(size)
    }

    /**
     * A library album opens as its FULL catalog album (Spotify parity): the library
     * album's own tracks only mark which catalog tracks the user has added. Library
     * albums Apple cannot match to the catalog (imported music) keep their library
     * tracklist. Tracks are deduplicated by identity: Apple may hold two library songs
     * for one catalog song, which would otherwise collide as one list key.
     */
    override suspend fun getAlbum(id: MediaId): Album? {
        if (!id.rawId.startsWith("library:")) return catalogAlbum(id, libraryCopyTracks = null)
        val path = path("albums", id)
        return coroutineScope {
            // The user's own tracklist is read however the album opens (the catalog album's marks, or the
            // tracklist itself), and its path is known already: read it alongside the album, once.
            val libraryTracks = async { attempt { all(path + "tracks", LibraryTracksQuery) } }
            val resource = page(path, mapOf("include" to "artists,catalog")).resources().firstOrNull() ?: run {
                libraryTracks.cancel()
                return@coroutineScope null
            }
            val catalog = resource.related("catalog").firstOrNull { it.text("type") == "albums" }
            if (catalog != null) {
                try {
                    catalogAlbum(catalog.mediaId(), libraryTracks)?.let { return@coroutineScope it }
                } catch (error: AppleMusicApiException) {
                    // A catalog match the storefront no longer serves still has the user's own tracklist.
                    if (error.failsCatalogToo()) throw error
                }
            }
            // Unmatched, or matched to a catalog album that would not open: the library album is its own identity.
            val mappedAlbum = album(resource).copy(id = id)
            val tracks = libraryTracks.await().getOrThrow().songTracks()
                .map { track ->
                    track.copy(albumId = track.albumId ?: id, artistId = track.artistId ?: mappedAlbum.artistId)
                }
                .distinctBy { it.id }
            mappedAlbum.withTracks(tracks)
        }
    }

    /**
     * [libraryCopyTracks] is the read of the library album this catalog album was opened from, already in
     * flight; without it (opened from the catalog, e.g. search) the copy comes from the `library` relationship.
     */
    private suspend fun catalogAlbum(id: MediaId, libraryCopyTracks: TracklistRead?): Album? = coroutineScope {
        val path = path("albums", id)
        // `library` is the user's copy of this catalog album; it needs the Music User Token.
        var libraryKnown = true
        var tracklistRead: TracklistRead? = null
        val response = try {
            page(path, CatalogAlbumWithSongsQuery, personal = true)
        } catch (error: AppleMusicApiException) {
            if (error.failsCatalogToo()) throw error
            // Nothing read below asks for the songs' relationships, so the tracklist is a request of its own:
            // start it now instead of after the album.
            tracklistRead = async { attempt { catalogTracks(path) } }
            personalAlbumWithoutSongs(path, error) ?: run {
                // Membership only marks rows. Apple fails this personal lookup with 400, and with 500 for catalog
                // albums it serves fine without it (207192046 from search, 2026-10-09): open the album unmarked.
                libraryKnown = false
                page(path, mapOf("include" to "artists"))
            }
        }
        val resource = response.resources().firstOrNull() ?: run {
            tracklistRead?.cancel()
            return@coroutineScope null
        }
        val mappedAlbum = album(resource)
        // The user's copy and the catalog tracklist don't depend on each other: both are read at once. Each
        // outcome is then taken in the order a one-after-the-other read had, so the same failure wins.
        val libraryRead = libraryCopyTracks
            ?: resource.related("library").firstOrNull { it.text("type") == "library-albums" }?.let { copy ->
                async { attempt { all(path("albums", copy.mediaId()) + "tracks", LibraryTracksQuery) } }
            }
        val catalogRead = tracklistRead
            ?: resource.embeddedTracks()?.let { CompletableDeferred(Result.success(it)) }
            ?: async { attempt { catalogTracks(path) } }
        // First library copy wins when Apple holds two library songs for one catalog song.
        val libraryIdsByCatalogId = mutableMapOf<String, String>()
        var libraryTracksRead = true
        libraryRead?.await()?.onSuccess { libraryTracks ->
            libraryTracks.songTracks().forEach { track ->
                val catalogId = track.extras[AppleMusicSong.EXTRA_CATALOG_ID] ?: return@forEach
                val libraryId = track.extras[AppleMusicSong.EXTRA_LIBRARY_ID] ?: return@forEach
                libraryIdsByCatalogId.putIfAbsent(catalogId, libraryId)
            }
        }?.onFailure { error ->
            if (error !is AppleMusicApiException || error.failsCatalogToo()) throw error
            libraryTracksRead = false
        }
        val tracks = catalogRead.await().getOrThrow().songTracks()
            .distinctBy { it.id }
            .map { track ->
                val extras = track.extras.toMutableMap()
                libraryIdsByCatalogId[track.id.rawId]?.let { extras[AppleMusicSong.EXTRA_LIBRARY_ID] = it }
                if (libraryTracksRead && (libraryKnown || libraryCopyTracks != null)) {
                    extras[AppleMusicSong.EXTRA_LIBRARY_CHECKED] = "true"
                }
                track.copy(
                    albumId = track.albumId ?: id,
                    artistId = track.artistId ?: mappedAlbum.artistId,
                    extras = extras
                )
            }
        mappedAlbum.withTracks(tracks)
    }

    /**
     * The personal album read again without the songs' relationships, when [failure] could have been Apple
     * refusing that include (a 400, or a 5xx like those it answers some tracklists' includes with). Null when
     * that is not the failure, or the read fails again.
     */
    private suspend fun personalAlbumWithoutSongs(path: List<String>, failure: AppleMusicApiException): JsonObject? {
        if (!failure.mayRefuseInclude()) return null
        return try {
            page(path, CatalogAlbumQuery, personal = true)
        } catch (error: AppleMusicApiException) {
            if (error.failsCatalogToo()) throw error
            null
        }
    }

    /** The album's catalog tracklist, with its albums and artists when Apple will include them. */
    private suspend fun catalogTracks(path: List<String>): List<JsonObject> = try {
        all(path + "tracks", mapOf("include" to SongRelationshipsInclude))
    } catch (error: AppleMusicApiException) {
        // Apple 500s some tracklists' included relationships (catalog 207192046 from search, 2026-10-09)
        // while serving the bare tracks; those fall back to this album and its artist below.
        if (error.failure !is AppleMusicApiFailure.Http) throw error
        all(path + "tracks", emptyMap())
    }

    /**
     * [block]'s outcome as a Result, so one of two parallel reads failing doesn't cancel the other before the
     * caller has weighed both. Cancellation still propagates.
     */
    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    /**
     * What Apple answers a request whose include it won't serve with: a 400, or a 5xx like those it answers
     * some tracklists' includes with. A token, access or rate-limit failure is never that.
     */
    private fun AppleMusicApiException.mayRefuseInclude(): Boolean {
        val status = (failure as? AppleMusicApiFailure.Http)?.status ?: return false
        return status == 400 || status >= 500
    }

    /** A token or rate-limit failure fails the plain catalog request as well; any other failure is the lookup's. */
    private fun AppleMusicApiException.failsCatalogToo(): Boolean = when (failure) {
        AppleMusicApiFailure.DeveloperTokenRequired,
        AppleMusicApiFailure.DeveloperTokenRejected,
        AppleMusicApiFailure.RateLimited -> true
        else -> false
    }

    private fun Album.withTracks(tracks: List<Track>) = copy(
        tracks = tracks,
        songCount = tracks.size,
        durationSec = tracks.sumOf { it.durationSec ?: 0 }
    )

    // An include Apple refuses costs the portraits, never the list: the artists are read again without it.
    override suspend fun getArtists(): List<ArtistIndex> =
        all(path("artists"), LibraryArtistsQuery, fallback = PageLimitQuery).map(::artist)
            .groupBy { it.name.firstOrNull()?.uppercase() ?: "#" }.toSortedMap()
            .map { (letter, artists) -> ArtistIndex(letter, artists) }

    override suspend fun getArtist(id: MediaId): ArtistDetail? {
        val path = path("artists", id)
        // A library artist's portrait is its catalog artist's; a catalog artist carries its own.
        val query = if (id.rawId.startsWith("library:")) LibraryArtistQuery else emptyMap()
        val response = try {
            page(path, query)
        } catch (error: AppleMusicApiException) {
            // Refused, the include costs the portrait only: the page opens on its first release's cover.
            if (query.isEmpty() || !error.mayRefuseInclude()) throw error
            page(path)
        }
        val resource = response.resources().firstOrNull() ?: return null
        val artist = artist(resource)
        // The albums relationship pages 25 by default, 100 at most.
        val albums = all(path + "albums", PageLimitQuery).map(::album)
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

    override suspend fun searchLibrary(query: String): SearchResults {
        if (query.isBlank()) return SearchResults()
        val results = page(
            listOf("v1", "me", "library", "search"),
            mapOf(
                "term" to query,
                "types" to "library-songs,library-albums,library-artists,library-playlists",
                "limit" to "25"
            )
        )["results"]?.jsonObject ?: return SearchResults()
        // Two library songs (or albums) can share one catalog identity; the result lists key rows by id.
        return SearchResults(
            tracks = results["library-songs"]?.jsonObject?.resources().orEmpty().songTracks().distinctBy { it.id },
            albums = results["library-albums"]?.jsonObject?.resources().orEmpty().map(::album).distinctBy { it.id },
            artists = results["library-artists"]?.jsonObject?.resources().orEmpty().map(::artist),
            playlists = results["library-playlists"]?.jsonObject?.resources().orEmpty().map(::playlist)
        )
    }

    override suspend fun getLibrarySongs(size: Int, offset: Int): List<Track> {
        if (size <= 0) return emptyList()
        val tracks = mutableListOf<Track>()
        val visited = mutableSetOf<String>()
        var next: String? = null
        do {
            val response = page(
                path("songs"),
                mapOf(
                    "include" to "catalog,albums,artists",
                    "limit" to minOf(size, 100).toString(),
                    "offset" to offset.coerceAtLeast(0).toString()
                ),
                next
            )
            tracks += response.resources().songTracks()
            next = response.text("next")
            check(next == null || visited.add(next)) { "Apple Music repeated a pagination link" }
        } while (next != null && tracks.distinctBy { it.id }.size < size)
        return tracks.distinctBy { it.id }.take(size)
    }

    override suspend fun libraryMembership(trackId: MediaId): Result<LibraryMembership> = cancellableResult {
        require(trackId.provider == id)
        if (trackId.rawId.startsWith("library:")) {
            LibraryMembership.Added
        } else {
            membership(catalogSongId(trackId))
        }
    }

    override suspend fun addToLibrary(trackId: MediaId): Result<LibraryMembership> = cancellableResult {
        require(trackId.provider == id)
        if (trackId.rawId.startsWith("library:")) {
            return@cancellableResult LibraryMembership.Added
        }
        val catalogId = catalogSongId(trackId)
        libraryMutationMutex.withLock {
            if (membership(catalogId) == LibraryMembership.Added) {
                return@withLock LibraryMembership.Added
            }
            // Rechecking an accepted addition must not send a second mutation.
            if (catalogId !in pendingLibraryAdds) {
                api.addSongToLibrary(catalogId)
                pendingLibraryAdds += catalogId
            }
            for (waitMs in listOf(0L, 500L, 1_000L, 2_000L, 4_000L)) {
                delay(waitMs)
                val confirmed = try {
                    membership(catalogId) == LibraryMembership.Added
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Apple accepted the write; failed confirmation cannot turn it into success.
                    return@withLock LibraryMembership.Pending
                }
                if (confirmed) return@withLock LibraryMembership.Added
            }
            LibraryMembership.Pending
        }
    }

    private suspend fun membership(catalogId: String): LibraryMembership {
        if (api.librarySongId(storefront(), catalogId) != null) {
            pendingLibraryAdds -= catalogId
            return LibraryMembership.Added
        }
        return if (catalogId in pendingLibraryAdds) LibraryMembership.Pending else LibraryMembership.NotAdded
    }

    private fun catalogSongId(trackId: MediaId): String {
        require(trackId.rawId.matches(Regex("[0-9]+"))) { "A catalog song ID is required" }
        return trackId.rawId
    }

    private suspend fun <T> cancellableResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    // Library membership is not an Apple Music favorite. Unsupported mutations never fake success.
    override suspend fun getStarred() = Starred()
    override suspend fun getRandomSongs(size: Int) = emptyList<Track>()
    override suspend fun getLyrics(trackId: MediaId): Lyrics? = null

    // Catalog ids only (digits; "pl." for curated playlists): a library id
    // ("l.", "i.", "p.") has no public page.
    override suspend fun webUrl(kind: WebLinkKind, id: MediaId): String? {
        if (id.provider != MediaId.PROVIDER_APPLE_MUSIC) return null
        val rawId = id.rawId
        val catalog = rawId.all(Char::isDigit) || (kind == WebLinkKind.Playlist && rawId.startsWith("pl."))
        if (rawId.isBlank() || !catalog) return null
        val shop = runCatching { storefront() }.getOrNull() ?: return null
        return "https://music.apple.com/$shop/${kind.path}/$rawId"
    }
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
        private fun JsonObject.related(name: String) =
            get("relationships")?.jsonObject?.get(name)?.jsonObject?.resources().orEmpty()

        /**
         * The album's whole tracklist as its own response embeds it (Albums `tracks` includes objects, 300 at
         * most), or null when the tracklist must be read on its own: the relationship has a `next` page, is
         * missing or empty, or a song lacks its attributes or the album and artist relationships the
         * tracklist request includes.
         */
        private fun JsonObject.embeddedTracks(): List<JsonObject>? {
            val tracks = (get("relationships") as? JsonObject)?.get("tracks") as? JsonObject ?: return null
            if ((tracks["next"] as? JsonPrimitive)?.contentOrNull != null) return null
            val data = (tracks["data"] as? JsonArray)?.map { it as? JsonObject ?: return null }
            if (data.isNullOrEmpty()) return null
            val songsComplete = data.filter { it.text("type") == "songs" }.all { song ->
                val relationships = song["relationships"] as? JsonObject
                song["attributes"] is JsonObject &&
                    SongRelationships.all { name ->
                        (relationships?.get(name) as? JsonObject)?.get("data") is JsonArray
                    }
            }
            return data.takeIf { songsComplete }
        }
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
            val artist = resource.related("artists").firstOrNull()
            val catalog = resource.related("catalog").firstOrNull { it.text("type") == "albums" }
            return Album(
                catalog?.mediaId() ?: resource.mediaId(), a.text("name").orEmpty(), a.text("artistName"), artist?.mediaId(), a.cover(),
                a["trackCount"]?.jsonPrimitive?.intOrNull, null, a.text("releaseDate")?.take(4)?.toIntOrNull(),
                a["genreNames"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull, addedAt = a.text("dateAdded")
            )
        }

        /**
         * A library artist keeps its library id (it opens its library artist page), but has no artwork of its
         * own: its portrait is the catalog artist's, when the request included the `catalog` relationship.
         */
        internal fun artist(resource: JsonObject): Artist {
            val a = resource.attributes()
            val portrait = a.cover() ?: resource.related("catalog")
                .firstOrNull { it.text("type") == "artists" }
                ?.attributes()
                ?.cover()
            return Artist(resource.mediaId(), a.text("name").orEmpty(), null, portrait)
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

/** One tracklist read in flight, its failure kept as a Result for the caller to weigh (`attempt`). */
private typealias TracklistRead = Deferred<Result<List<JsonObject>>>

/** recent/played pages (10 each) read at most: Apple keeps only a short history anyway. */
private const val RecentPlayedMaxPages = 5

/** Apple's largest page for library collections and the artist albums relationship (default 25). */
private val PageLimitQuery = mapOf("limit" to "100")

/** A library artist with its catalog artist, the only one of the two that has a portrait. */
private val LibraryArtistQuery = mapOf("include" to "catalog")

/** Library › Artists: full pages, each artist with its catalog artist ([LibraryArtistQuery]). */
private val LibraryArtistsQuery = PageLimitQuery + LibraryArtistQuery

/** A library album's tracks, each with its catalog song: what marks the catalog album's rows. */
private val LibraryTracksQuery = mapOf("include" to "catalog")

/** The relationships a tracklist's songs carry: their album and artist, as navigation targets. */
private val SongRelationships = listOf("albums", "artists")
private val SongRelationshipsInclude = SongRelationships.joinToString(",")

/** The catalog album with its artists and the user's copy (`library` needs the Music User Token). */
private val CatalogAlbumQuery = mapOf("include" to "artists,library")

/**
 * [CatalogAlbumQuery] with the songs' relationships as well. A type-scoped include applies to every
 * resource of that type in the response (Apple's "Scoping Parameters"), so the songs embedded in the
 * album's `tracks` arrive as the tracklist request would return them, and that request is skipped.
 */
private val CatalogAlbumWithSongsQuery = CatalogAlbumQuery + ("include[songs]" to SongRelationshipsInclude)

/**
 * recently-added pages 10 at a time by default, so filling Library › Albums took ~15 serial
 * requests. Its page limit is undocumented: ask for 25, and [getAlbumList] falls back to Apple's
 * default page if the size is refused.
 */
private val RecentlyAddedPageQuery = mapOf("limit" to "25")
