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
import com.gpo.yoin.data.source.WebLinkKind
import com.gpo.yoin.data.source.MusicPlayback
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.FavoriteStatesIncompleteException
import com.gpo.yoin.data.source.MusicWriteActions
import com.gpo.yoin.data.source.ServiceFeatureCatalog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class SpotifyMusicSource(
    initialCredentials: ProfileCredentials.Spotify,
    clientIdProvider: () -> String,
    onCredentialsPersisted: suspend (ProfileCredentials.Spotify) -> Unit,
    /**
     * Invoked exactly once when the API client detects the refresh token
     * is dead (`error: "invalid_grant"`). Owners typically forward this
     * to [com.gpo.yoin.data.profile.ProfileManager] so the active
     * profile gets a `revoked = true` marker and UI surfaces a Reconnect
     * affordance. Defaults to no-op for tests.
     */
    onCredentialsRevoked: suspend () -> Unit = {},
    httpClient: OkHttpClient = defaultHttpClient(),
    authService: SpotifyAuthService = SpotifyAuthService(httpClient),
    val profileId: String? = null,
    rateLimitGate: SpotifyRateLimitGate? = null,
    apiBaseUrl: HttpUrl = "https://${SpotifyAuthConfig.API_HOST}/".toHttpUrl(),
    private val clock: () -> Long = System::currentTimeMillis,
) : MusicSource {

    private val apiClient = SpotifyApiClient(
        httpClient = httpClient,
        authService = authService,
        initialCredentials = initialCredentials,
        clientIdProvider = clientIdProvider,
        onCredentialsRefreshed = onCredentialsPersisted,
        onCredentialsRevoked = onCredentialsRevoked,
        apiBaseUrl = apiBaseUrl,
        rateLimitGate = rateLimitGate,
        rateLimitProfileId = profileId,
    )

    override val id: String = MediaId.PROVIDER_SPOTIFY

    override val capabilities: Set<Capability> = ServiceFeatureCatalog.spotify.capabilities

    // Library lists, each read by one request at a time and kept until
    // invalidateLibraryCaches (the library sync) or a write that changes it.
    private val savedTracksCache = SingleFlightValue<List<SpotifySavedTrackObject>>(clock = clock)
    private val savedAlbumsCache = SingleFlightValue<List<SpotifySavedAlbumObject>>(clock = clock)
    private val playlistsCache = SingleFlightValue<List<SpotifyPlaylistObject>>(clock = clock)
    private val followedArtistsCache = SingleFlightValue<List<SpotifyArtistObject>>(clock = clock)
    private val savedTrackDelta = SavedTrackDelta(clock)

    // Album saves and removals written through this source, laid over the
    // cached saved-albums list the same way (an album id in place of a track's).
    private val savedAlbumDelta = SavedTrackDelta(clock)
    private val recentlyPlayedCache =
        SingleFlightValue<List<SpotifyPlayHistoryObject>>(RECENTLY_PLAYED_MAX_AGE_MS, clock)
    private val playlistTrackOffsetsById = ConcurrentHashMap<String, List<Int>>()

    /** The signed-in account's Spotify profile picture, if it has one. */
    suspend fun profilePictureUrl(): String? = apiClient.getMe().avatarUrl

    private val library = object : MusicLibrary {
        override suspend fun ping(): Boolean {
            apiClient.getMe()
            return true
        }

        // recently-played tracks' albums, newest first, once each. Shares its
        // one request with Home's Activities (getRecentlyPlayed). The shelf
        // shows no saved state, so the saved-albums list isn't read for it.
        override suspend fun getRecentlyPlayedAlbums(size: Int): List<Album> = recentlyPlayed()
            .mapNotNull { play -> play.track?.album?.toAlbum() }
            .distinctBy { it.id }
            .take(size.coerceAtLeast(0))

        override suspend fun getAlbumList(type: String, size: Int, offset: Int): List<Album> {
            val savedAlbumIds = savedAlbumIds()
            // An album removed through Yoin that Spotify's list still carries is no longer saved.
            val albums = savedAlbums().filter { savedAlbum -> savedAlbum.album?.id in savedAlbumIds }
            val mapped = when (type) {
                "alphabeticalByName" -> albums.sortedBy { it.album?.name?.lowercase().orEmpty() }
                "recent" -> albums.sortedByDescending { it.addedAt.orEmpty() }
                "random" -> albums.shuffled()
                else -> albums
            }.mapNotNull { savedAlbum ->
                savedAlbum.album?.toSimplifiedAlbum()?.toAlbum(savedAlbumIds = savedAlbumIds)
                    ?.copy(addedAt = savedAlbum.addedAt, libraryAddedAt = savedAlbum.addedAt)
            }
            return mapped.drop(offset.coerceAtLeast(0)).take(size.coerceAtLeast(0))
        }

        // No savedAlbumIds(): neither the album page nor anything else reads
        // an album's own saved state from here — it cost up to 4 pages cold.
        override suspend fun getAlbum(id: MediaId): Album? = withSpotifyId(id) { rawId ->
            supervisorScope {
                val savedTrackIds = async { savedTrackIds() }
                val album = orNullIfNotFound { apiClient.getAlbum(rawId) }
                    ?: return@supervisorScope cancelAndReturnNull()
                // The album object embeds its first page of tracks: usually all of them.
                val tracks = apiClient.getAlbumTracks(rawId, firstPage = album.tracks)
                album.toAlbum(
                    savedTrackIds = savedTrackIds.await(),
                    tracksOverride = tracks,
                )
            }
        }

        override suspend fun getArtists(): List<ArtistIndex> =
            libraryArtists().toArtistIndices()

        // Releases carry no saved state (the artist page never shows one), so
        // no savedAlbumIds(); the artist, its releases and the follow list
        // are read side by side.
        override suspend fun getArtist(id: MediaId): ArtistDetail? = withSpotifyId(id) { rawId ->
            supervisorScope {
                val followedArtistIds = async { followedArtistIds() }
                val releases = async { apiClient.getArtistAlbums(rawId) }
                val artist = orNullIfNotFound { apiClient.getArtist(rawId) }
                    ?: return@supervisorScope cancelAndReturnNull()
                val albums = releases.await()
                    .distinctBy(SpotifySimplifiedAlbumObject::id)
                    .map { it.toAlbum() }
                    .sortedWith(compareByDescending<Album> { it.year ?: Int.MIN_VALUE }.thenBy { it.name.lowercase() })
                artist.toArtistDetail(
                    albums = albums,
                    isStarred = rawId in followedArtistIds.await(),
                )
            }
        }

        override suspend fun getPlaylists(): List<Playlist> {
            val meId = apiClient.getCurrentUserId()
            return currentUserPlaylists()
                .sortedBy { it.name.lowercase() }
                .map { playlist ->
                    playlist.toPlaylist(canWrite = playlist.owner?.id == meId, ownedByMe = playlist.ownedBy(meId))
                }
        }

        override suspend fun getPlaylist(id: MediaId): Playlist? = withSpotifyId(id) { rawId ->
            supervisorScope {
                val savedTrackIds = async { savedTrackIds() }
                val meId = async { apiClient.getCurrentUserId() }
                val playlist = orNullIfNotFound { apiClient.getPlaylist(rawId) }
                if (playlist == null) {
                    playlistTrackOffsetsById.remove(rawId)
                    return@supervisorScope cancelAndReturnNull()
                }
                // Starts from the first page the playlist object embeds, when it does.
                val indexedTracks = apiClient.getPlaylistItems(rawId, firstPage = playlist.entries)
                    .toTracksWithPlaylistOffsets(savedTrackIds = savedTrackIds.await())
                playlistTrackOffsetsById[rawId] = indexedTracks.map { it.first }
                playlist.toPlaylist(
                    tracks = indexedTracks.map { it.second },
                    canWrite = playlist.owner?.id == meId.await(),
                    ownedByMe = playlist.ownedBy(meId.await()),
                )
            }
        }

        override suspend fun getStarred(): Starred {
            val savedTrackIds = savedTrackIds()
            val savedAlbumIds = savedAlbumIds()
            val followedArtistIds = followedArtistIds()
            val tracks = savedTracks()
                .mapNotNull { savedTrack ->
                    savedTrack.track?.toTrack(savedTrackIds = savedTrackIds)
                        ?.copy(addedAt = savedTrack.addedAt)
                }
            val albums = savedAlbums()
                .mapNotNull { savedAlbum ->
                    savedAlbum.album?.toSimplifiedAlbum()?.toAlbum(savedAlbumIds = savedAlbumIds)
                        ?.copy(addedAt = savedAlbum.addedAt)
                }
            val artists = followedArtists()
                .map { artist -> artist.toArtist(isStarred = artist.id in followedArtistIds) }
            return toStarred(tracks = tracks, albums = albums, artists = artists)
        }

        override suspend fun getRandomSongs(size: Int): List<Track> =
            savedTracks()
                .mapNotNull { savedTrack -> savedTrack.track?.let { track ->
                    track.toTrack(savedTrackIds = setOf(track.id).filterNotNull().toSet())
                        .copy(addedAt = savedTrack.addedAt)
                } }
                .shuffled()
                .take(size.coerceAtLeast(0))

        // No getLibrarySongs here: LIBRARY_SONGS (Liked Songs) is served by
        // YoinRepository.getLibrarySongs from the synced cache, which also
        // holds the likes written since the last sync. A list of the source's
        // own would page /me/tracks again and miss those likes.

        override suspend fun search(query: String): SearchResults =
            apiClient.search(query = query).toSearchResults(
                savedTrackIds = savedTrackIds(),
                savedAlbumIds = savedAlbumIds(),
                followedArtistIds = followedArtistIds(),
            )
    }

    private val metadata = object : MusicMetadata {
        override suspend fun getLyrics(trackId: MediaId): Lyrics? = null

        override suspend fun webUrl(kind: WebLinkKind, id: MediaId): String? =
            id.takeIf { it.provider == MediaId.PROVIDER_SPOTIFY && it.rawId.isNotBlank() }
                ?.let { "https://open.spotify.com/${kind.path}/${it.rawId}" }
    }

    private val writeActions = object : MusicWriteActions {
        // Asked of Spotify, since the saved-tracks list stops at 200. One
        // contains read per 40 tracks, one after another. Episodes and local
        // files App Remote reported have no track id to ask about. A batch
        // failing after others answered (a 429) keeps their answers. The
        // albums go first, so the page's own album is in the first request.
        override suspend fun favoriteStates(tracks: List<Track>, albums: List<MediaId>): Result<Map<MediaId, Boolean>> {
            val askedAlbums = albums.mapNotNull { id -> spotifyAlbumUriOrNull(id)?.let { uri -> id to uri } }
            val askedTracks = tracks
                .mapNotNull { track -> spotifyTrackUriOrNull(track)?.let { uri -> track.id to uri } }
            val asked = (askedAlbums + askedTracks).distinctBy { (id, _) -> id }
            if (asked.isEmpty()) return Result.success(emptyMap())
            val answered = LinkedHashMap<MediaId, Boolean>()
            return try {
                apiClient.libraryContains(asked.map { (_, uri) -> uri }) { from, saved ->
                    saved.forEachIndexed { offset, isSaved -> answered[asked[from + offset].first] = isSaved }
                }
                Result.success(answered.toMap())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Result.failure(
                    if (answered.isEmpty()) error else FavoriteStatesIncompleteException(answered.toMap(), error)
                )
            }
        }

        override suspend fun setFavorite(id: MediaId, favorite: Boolean): Result<Unit> = runCatching {
            val rawId = requireSpotify(id).rawId
            // setFavorite is the TRACK like (saved-tracks library). Artist follow
            // goes through setArtistFollowed below, which hits a different endpoint.
            val uri = "spotify:track:$rawId"
            if (favorite) {
                apiClient.saveToLibrary(uri)
            } else {
                apiClient.removeFromLibrary(uri)
            }
            // Laid over the cached saved-tracks list rather than dropping it.
            savedTrackDelta.record(rawId, favorite)
        }

        // PUT / DELETE /v1/me/library with the album's URI, like a track's like.
        override suspend fun setAlbumSaved(album: Album, saved: Boolean): Result<Unit> = runCatching {
            val rawId = requireSpotify(album.id).rawId
            val uri = "spotify:album:$rawId"
            if (saved) {
                apiClient.saveToLibrary(uri)
            } else {
                apiClient.removeFromLibrary(uri)
            }
            // Laid over the cached saved-albums list rather than dropping it (up to 4 pages).
            savedAlbumDelta.record(rawId, saved)
        }

        override suspend fun setArtistFollowed(id: MediaId, followed: Boolean): Result<Unit> =
            runCatching {
                val rawId = requireSpotify(id).rawId
                apiClient.setArtistFollowed(rawId, followed)
                // Re-derive follow state on the next artist read.
                followedArtistsCache.invalidate()
            }

        override suspend fun setRating(trackId: MediaId, rating: Int): Result<Unit> =
            Result.failure(UnsupportedOperationException("Spotify has no 5-star rating"))

        override suspend fun createPlaylist(
            name: String,
            description: String?,
        ): Result<Playlist> = runCatching {
            val created = apiClient.createPlaylist(name = name, description = description)
            playlistsCache.invalidate()
            // Freshly-created playlists are owned by the caller → canWrite = true.
            created.toPlaylist(canWrite = true, ownedByMe = true)
        }

        override suspend fun renamePlaylist(
            id: MediaId,
            name: String,
            description: String?,
        ): Result<Unit> = runCatching {
            val rawId = requireSpotify(id).rawId
            apiClient.renamePlaylist(id = rawId, name = name, description = description)
            playlistsCache.invalidate()
        }

        override suspend fun deletePlaylist(id: MediaId): Result<Unit> = runCatching {
            val rawId = requireSpotify(id).rawId
            // Spotify has no real delete — unfollowing your own playlist removes
            // it from /me/playlists, which is the product-level "delete".
            apiClient.unfollowPlaylist(rawId)
            playlistTrackOffsetsById.remove(rawId)
            playlistsCache.invalidate()
        }

        override suspend fun addTracksToPlaylist(
            playlistId: MediaId,
            tracks: List<MediaId>,
        ): Result<String?> = runCatching {
            if (tracks.isEmpty()) return@runCatching null
            val rawId = requireSpotify(playlistId).rawId
            val uris = tracks.map { "spotify:track:${requireSpotify(it).rawId}" }
            val snapshot = apiClient.addTracksToPlaylist(id = rawId, uris = uris)
            playlistTrackOffsetsById.remove(rawId)
            playlistsCache.invalidate()
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
            playlistTrackOffsetsById.remove(rawId)
            playlistsCache.invalidate()
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

    /**
     * Web API `PUT /me/player/play` — used by [com.gpo.yoin.player.PlaybackManager]
     * when the user taps a track inside an album / playlist / artist page. The
     * Web API preserves the *context* (unlike App Remote's `play(uri)` which
     * only takes a bare URI and always starts at position 0), so Spotify's own
     * UI shows "playing from <album>" / "playing from <playlist>" and
     * recommendations get the right signal.
     *
     * [contextUri] is a `spotify:album:...` / `spotify:playlist:...` /
     * Liked Songs collection URI; without one, [uris] is played as a
     * temporary context (nothing goes into the user's queue). The start
     * track is [offsetUri] when known, else the zero-based [offsetPosition].
     * Throws [SpotifyAuthException] — callers should fall back to App
     * Remote's bare-URI path on failure (typically 404 NO_ACTIVE_DEVICE on a
     * cold Spotify app).
     */
    suspend fun startPlayback(
        contextUri: String? = null,
        uris: List<String>? = null,
        offsetPosition: Int? = null,
        offsetUri: String? = null,
        deviceId: String? = null,
    ) {
        apiClient.startPlayback(
            contextUri = contextUri,
            uris = uris,
            offsetPosition = offsetPosition,
            offsetUri = offsetUri,
            deviceId = deviceId,
        )
    }

    /**
     * This phone's Spotify Connect device id, found by name among the
     * account's devices ([localNames]: the user-set device name, the model).
     * Without it the Web API plays on whichever device is ACTIVE — on device
     * QA a tap in Yoin started the song on the owner's other phone.
     */
    suspend fun localDeviceId(localNames: List<String>): String? =
        pickLocalSpotifyDevice(apiClient.listDevices(), localNames)?.id


    suspend fun listDevices(): List<SpotifyDevice> = apiClient.listDevices()

    suspend fun transferPlayback(deviceId: String, play: Boolean = true) {
        apiClient.transferPlayback(deviceId = deviceId, play = play)
    }

    /**
     * Recently played tracks (newest first) for the Spotify activity feed.
     * Home loads this and its Recently Played shelf together; both are served
     * by one request, kept [RECENTLY_PLAYED_MAX_AGE_MS].
     */
    suspend fun getRecentlyPlayed(limit: Int): List<SpotifyPlayHistoryObject> =
        recentlyPlayed().take(limit.coerceAtLeast(0))

    /**
     * One artist's portrait for Home's Activities (recently-played names its
     * artists without images): one GET /artists/{id}, through the rate-limit
     * gate like every read. An artist Spotify no longer has reads as one
     * without a portrait; any other failure — a 429 or a closed gate
     * included — throws.
     */
    suspend fun getArtistPortrait(artistId: String): SpotifyArtistPortrait {
        val artist = orNullIfNotFound { apiClient.getArtist(artistId) }
            ?: return SpotifyArtistPortrait(name = null, url = null)
        return SpotifyArtistPortrait(name = artist.name, url = artist.bestImageUrl())
    }

    /**
     * Translate the visible row index in Yoin's filtered playlist view back
     * to Spotify's raw playlist offset. Returns null when the source no
     * longer has a trustworthy mapping (e.g. stale cache after a mutation),
     * so callers can safely fall back to bare App Remote playback.
     */
    fun resolvePlaylistContextOffset(
        playlistId: MediaId,
        visibleStartIndex: Int,
    ): Int? {
        val rawId = requireSpotify(playlistId).rawId
        val offsets = playlistTrackOffsetsById[rawId] ?: return null
        return offsets.getOrNull(visibleStartIndex)
    }

    override fun resolveCoverUrl(ref: CoverRef, size: Int?): String? = when (ref) {
        is CoverRef.Url -> ref.url
        // Spotify entities never emit SourceRelative — defensive.
        is CoverRef.SourceRelative -> null
    }

    private suspend fun savedTracks(): List<SpotifySavedTrackObject> = savedTracksCache.get {
        val readCheckpoint = savedTrackDelta.checkpoint()
        apiClient.getSavedTracks().also { saved ->
            savedTrackDelta.reconcile(saved.mapNotNullTo(HashSet()) { it.track?.id }, readCheckpoint)
        }
    }

    private suspend fun savedAlbums(): List<SpotifySavedAlbumObject> = savedAlbumsCache.get {
        val readCheckpoint = savedAlbumDelta.checkpoint()
        apiClient.getSavedAlbums().also { saved ->
            savedAlbumDelta.reconcile(saved.mapNotNullTo(HashSet()) { it.album?.id }, readCheckpoint)
        }
    }

    private suspend fun currentUserPlaylists(): List<SpotifyPlaylistObject> =
        playlistsCache.get { apiClient.getCurrentUserPlaylists() }

    private suspend fun followedArtists(): List<SpotifyArtistObject> =
        followedArtistsCache.get { apiClient.getFollowedArtists() }

    private suspend fun recentlyPlayed(): List<SpotifyPlayHistoryObject> =
        recentlyPlayedCache.get { apiClient.getRecentlyPlayed(limit = RECENTLY_PLAYED_LIMIT) }

    /**
     * Whether a like or unlike (or an album save or removal) written through
     * this source still waits for a saved-tracks (saved-albums) read made
     * after it; until then only the saved ids carry it, not the cached list.
     */
    fun hasUnsettledFavoriteWrites(): Boolean = savedTrackDelta.isUnsettled() || savedAlbumDelta.isUnsettled()

    /**
     * Drops in-memory library caches so the next read pulls fresh network
     * data. Likes written since stay laid over the re-read saved tracks until
     * that read reflects them (see [SavedTrackDelta]).
     *
     * [keepRecentLoads]: a list read already out that started within
     * [LIBRARY_LOAD_JOIN_WINDOW_MS] is kept and joined rather than read again
     * beside it — a TTL re-sync landing on the saved-tracks read an album
     * open just started. Only when no write can predate those reads: a
     * playlist or follow write drops its list's read in flight as it lands,
     * but a like is only laid over the list, so not while one is unsettled.
     */
    fun invalidateLibraryCaches(keepRecentLoads: Boolean = false) {
        val keepLoadStartedWithinMs = LIBRARY_LOAD_JOIN_WINDOW_MS.takeIf { keepRecentLoads }
        savedTracksCache.invalidate(keepLoadStartedWithinMs)
        savedAlbumsCache.invalidate(keepLoadStartedWithinMs)
        playlistsCache.invalidate(keepLoadStartedWithinMs)
        followedArtistsCache.invalidate(keepLoadStartedWithinMs)
        playlistTrackOffsetsById.clear()
    }

    /**
     * Populate the four independent library caches CONCURRENTLY. Each fetch
     * writes a different cache field (no shared state), so parallelising is
     * safe — and it turns a cold full sync from four serial round-trips into
     * roughly one. Already-warm caches (e.g. from [prime]) return instantly.
     * Call before the derived [library] reads so they hit warm caches.
     */
    suspend fun warmLibraryCaches(): Unit = coroutineScope {
        val tracks = async { savedTracks() }
        val albums = async { savedAlbums() }
        val playlists = async { currentUserPlaylists() }
        val artists = async { followedArtists() }
        tracks.await()
        albums.await()
        playlists.await()
        artists.await()
    }

    private suspend fun savedTrackIds(): Set<String> {
        val listed = savedTracks().mapNotNullTo(linkedSetOf()) { savedTrack -> savedTrack.track?.id }
        return savedTrackDelta.applyTo(listed)
    }

    private suspend fun savedAlbumIds(): Set<String> {
        val listed = savedAlbums().mapNotNullTo(linkedSetOf()) { savedAlbum -> savedAlbum.album?.id }
        return savedAlbumDelta.applyTo(listed)
    }

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

    private suspend fun <T> orNullIfNotFound(fetch: suspend () -> T): T? = runCatching { fetch() }.getOrElse { error ->
        if (error.isSpotifyNotFound()) null else throw error
    }

    /**
     * Stops the reads started beside a lookup that found nothing. Detail
     * reads run them in a supervisorScope, so one that fails anyway (a
     * missing artist's releases 404 too) doesn't turn the null into its
     * error: a side read's failure only surfaces where it is awaited.
     */
    private fun CoroutineScope.cancelAndReturnNull(): Nothing? {
        coroutineContext.cancelChildren()
        return null
    }

    companion object {
        private const val HTTP_NOT_FOUND = 404
        private const val RECENTLY_PLAYED_LIMIT = 50
        private const val RECENTLY_PLAYED_MAX_AGE_MS = 30_000L

        /** How recently a library list read must have started for a re-sync to join it. */
        const val LIBRARY_LOAD_JOIN_WINDOW_MS = 10_000L

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

/** The device in [devices] named like this phone; with several, the active one wins. */
internal fun pickLocalSpotifyDevice(devices: List<SpotifyDevice>, localNames: List<String>): SpotifyDevice? {
    val names = localNames.map(String::trim).filter(String::isNotEmpty)
    val named = devices.filter { device -> device.id != null && names.any { it.equals(device.name.trim(), ignoreCase = true) } }
    return named.firstOrNull(SpotifyDevice::isActive) ?: named.firstOrNull()
}
