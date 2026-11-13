package com.gpo.yoin.data.source.subsonic

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
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.remote.SubsonicApi
import com.gpo.yoin.data.remote.SubsonicApiFactory
import com.gpo.yoin.data.remote.SubsonicResponse
import com.gpo.yoin.data.remote.SubsonicResponseBody
import com.gpo.yoin.data.repository.SubsonicException
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.MusicLibrary
import com.gpo.yoin.data.source.MusicMetadata
import com.gpo.yoin.data.source.MusicPlayback
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.MusicWriteActions
import com.gpo.yoin.data.source.PlayReport
import com.gpo.yoin.data.source.PlayReportRejectedException
import com.gpo.yoin.data.source.ServiceFeatureCatalog
import com.gpo.yoin.data.source.WebLinkKind
import retrofit2.HttpException
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * [MusicSource] backed by a single Subsonic / OpenSubsonic server. The previous
 * [com.gpo.yoin.data.repository.YoinRepository] was Subsonic-shaped; this class
 * now owns all Subsonic specifics.
 */
class SubsonicMusicSource(
    private val credentials: ServerCredentials,
    private val apiFactory: (() -> ServerCredentials) -> SubsonicApi = { provider ->
        SubsonicApiFactory.create(credentialsProvider = provider, loggingEnabled = false)
    },
    override val profileId: String? = null,
) : MusicSource {

    private val api: SubsonicApi = apiFactory { credentials }

    override val id: String = MediaId.PROVIDER_SUBSONIC

    override val capabilities: Set<Capability> = ServiceFeatureCatalog.subsonic.capabilities

    private val library = object : MusicLibrary {
        override suspend fun ping(): Boolean {
            unwrap(api.ping())
            return true
        }

        override suspend fun getAlbumList(type: String, size: Int, offset: Int): List<Album> =
            unwrap(api.getAlbumList2(type, size, offset))
                .albumList2?.album.orEmpty().map { it.toAlbum() }

        override suspend fun getAlbum(id: MediaId): Album? =
            unwrap(api.getAlbum(id.requireSubsonic())).album?.toAlbum()

        // The server's own "recently played" list (getAlbumList2 type=recent).
        override suspend fun getRecentlyPlayedAlbums(size: Int): List<Album> =
            getAlbumList(type = "recent", size = size, offset = 0)

        override suspend fun getArtists(): List<ArtistIndex> =
            unwrap(api.getArtists()).artists?.index.orEmpty().map { it.toArtistIndex() }

        override suspend fun getArtist(id: MediaId): ArtistDetail? =
            unwrap(api.getArtist(id.requireSubsonic())).artist?.toArtistDetail()

        override suspend fun getPlaylists(): List<Playlist> =
            unwrap(api.getPlaylists(username = null))
                .playlists?.playlist.orEmpty().map { it.toPlaylist(currentUsername = credentials.username) }

        override suspend fun getPlaylist(id: MediaId): Playlist? =
            unwrap(api.getPlaylist(id.requireSubsonic())).playlist?.toPlaylist(currentUsername = credentials.username)

        override suspend fun getStarred(): Starred =
            unwrap(api.getStarred2()).starred2?.toStarred() ?: Starred()

        override suspend fun getRandomSongs(size: Int): List<Track> =
            unwrap(api.getRandomSongs(size))
                .randomSongs?.song.orEmpty().map { it.toTrack() }

        override suspend fun search(query: String): SearchResults =
            unwrap(api.search3(query)).searchResult3?.toSearchResults() ?: SearchResults()
    }

    private val metadata = object : MusicMetadata {
        override suspend fun getLyrics(trackId: MediaId): Lyrics? =
            unwrap(api.getLyricsBySongId(trackId.requireSubsonic()))
                .lyricsList?.toLyrics()

        // createShare takes song and album ids; a playlist id only on some
        // servers (Navidrome), so a playlist the server won't take is shared
        // as its songs. A server that refused for good — sharing off, no share
        // permission, or no createShare at all (gonic) — isn't asked again
        // this session: Share then sends the name alone, without the notice
        // each time. A network failure or a server hiccup (5xx) doesn't count.
        override suspend fun createShareLink(kind: WebLinkKind, id: MediaId, description: String?): String? {
            if (sharesRefused || kind == WebLinkKind.Artist || id.provider != MediaId.PROVIDER_SUBSONIC) return null
            try {
                val body = try {
                    unwrap(api.createShare(id = listOf(id.rawId), description = description))
                } catch (refused: SubsonicException) {
                    if (kind != WebLinkKind.Playlist) throw refused
                    val songIds = library.getPlaylist(id)?.tracks.orEmpty().map { it.id.rawId }
                    if (songIds.isEmpty()) throw refused
                    unwrap(api.createShare(id = songIds, description = description))
                }
                return body.shares?.share?.firstOrNull()?.url?.takeIf { it.isNotBlank() }
                    ?: throw SubsonicException(code = -1, message = "createShare.view returned no share")
            } catch (error: Exception) {
                if (isPermanentShareRefusal(error)) sharesRefused = true
                throw error
            }
        }
    }

    /** A refusal that won't change by asking again: a Subsonic error, or no such endpoint. */
    private fun isPermanentShareRefusal(error: Exception): Boolean = when (error) {
        is SubsonicException -> true
        is HttpException -> error.code() in setOf(400, 404, 405, 501)
        else -> false
    }

    @Volatile
    private var sharesRefused = false

    private val writeActions = object : MusicWriteActions {
        override suspend fun setFavorite(id: MediaId, favorite: Boolean): Result<Unit> =
            runCatching {
                val rawId = id.requireSubsonic()
                if (favorite) {
                    unwrap(api.star(id = rawId))
                } else {
                    unwrap(api.unstar(id = rawId))
                }
            }

        // Subsonic has no "follow" — starring an artist is its equivalent, but it
        // MUST go through the artistId param (not the song-oriented id param) or
        // spec-strict servers won't star the artist.
        override suspend fun setArtistFollowed(id: MediaId, followed: Boolean): Result<Unit> =
            runCatching {
                val rawId = id.requireSubsonic()
                if (followed) {
                    unwrap(api.star(artistId = rawId))
                } else {
                    unwrap(api.unstar(artistId = rawId))
                }
            }

        override suspend fun setRating(trackId: MediaId, rating: Int): Result<Unit> =
            runCatching {
                val clamped = rating.coerceIn(0, 5)
                unwrap(api.setRating(trackId.requireSubsonic(), clamped))
            }

        override suspend fun createPlaylist(
            name: String,
            description: String?,
        ): Result<Playlist> = runCatching {
            val body = unwrap(api.createPlaylist(name = name))
            val created = body.playlist
                ?: throw SubsonicException(
                    code = -1,
                    message = "createPlaylist.view returned no playlist",
                )
            // Subsonic's createPlaylist has no comment/description parameter.
            // If description is non-null, follow up with updatePlaylist to set
            // the comment field — Subsonic reuses `comment` as description.
            if (!description.isNullOrBlank()) {
                unwrap(api.updatePlaylist(playlistId = created.id, comment = description))
            }
            created.toPlaylist(currentUsername = credentials.username)
        }

        override suspend fun renamePlaylist(
            id: MediaId,
            name: String,
            description: String?,
        ): Result<Unit> = runCatching {
            unwrap(
                api.updatePlaylist(
                    playlistId = id.requireSubsonic(),
                    name = name,
                    comment = description,
                ),
            )
        }

        override suspend fun deletePlaylist(id: MediaId): Result<Unit> = runCatching {
            unwrap(api.deletePlaylist(id.requireSubsonic()))
        }

        override suspend fun addTracksToPlaylist(
            playlistId: MediaId,
            tracks: List<MediaId>,
        ): Result<String?> = runCatching {
            if (tracks.isEmpty()) return@runCatching null
            unwrap(
                api.updatePlaylist(
                    playlistId = playlistId.requireSubsonic(),
                    songIdToAdd = tracks.map { it.requireSubsonic() },
                ),
            )
            null // Subsonic has no snapshot/etag concurrency token.
        }

        override suspend fun removeTracksFromPlaylist(
            playlistId: MediaId,
            items: List<PlaylistItemRef>,
            snapshotId: String?,
        ): Result<String?> = runCatching {
            if (items.isEmpty()) return@runCatching null
            val rawPlaylistId = playlistId.requireSubsonic()
            // updatePlaylist.view removes by index and checks nothing, so each
            // index is found in the playlist as the server holds it now, never
            // taken from the page: another client may have edited it since,
            // and index 1 of a stale [A, B] is A on the server's [X, A, B].
            val current = unwrap(api.getPlaylist(rawPlaylistId)).playlist?.entry.orEmpty().map { it.id }
            val indexes = items.mapNotNullTo(HashSet()) { item ->
                playlistIndexNearest(current, item.trackId.requireSubsonic(), item.position)
            }
            // Already gone on the server: nothing to send.
            if (indexes.isEmpty()) return@runCatching null
            // Subsonic ignores snapshotId — its updatePlaylist.view is
            // last-writer-wins. Positions must be sorted descending when
            // removing multiple indices in one call so earlier removals
            // don't shift later indices.
            unwrap(
                api.updatePlaylist(
                    playlistId = rawPlaylistId,
                    songIndexToRemove = indexes.sortedDescending(),
                ),
            )
            null
        }
    }

    private val playback = object : MusicPlayback {
        override suspend fun handleFor(track: Track): PlaybackHandle {
            val rawId = track.id.requireSubsonic()
            val uri = SubsonicApiFactory.buildStreamUrl(credentials, rawId)
            return PlaybackHandle.DirectStream(uri = uri)
        }

        override suspend fun reportNowPlaying(trackId: MediaId): Result<Unit> = runCatching {
            unwrap(api.scrobble(id = listOf(trackId.requireSubsonic()), submission = false))
        }

        override suspend fun submitPlays(plays: List<PlayReport>): Result<Unit> = runCatching {
            try {
                unwrap(
                    api.scrobble(
                        id = plays.map { it.trackId.requireSubsonic() },
                        time = plays.map { it.timeMs },
                        submission = true,
                    ),
                )
            } catch (error: SubsonicException) {
                // 10 a parameter it won't take, 70 a song it no longer has: sending again won't help.
                // Anything else (auth, a server hiccup) is worth retrying.
                if (error.code == 10 || error.code == 70) throw PlayReportRejectedException(error.message, error)
                throw error
            }
        }

        override suspend fun supportsPlaybackStateReport(): Boolean {
            playbackReportSupported?.let { return it }
            // A server that answers without the extension (plain Subsonic: an error or a 404) is
            // remembered as without; no answer at all (offline) is asked again next time.
            val supported = try {
                unwrap(api.getOpenSubsonicExtensions()).openSubsonicExtensions.orEmpty()
                    .any { it.name == "playbackReport" }
            } catch (error: SubsonicException) {
                false
            } catch (error: retrofit2.HttpException) {
                false
            }
            playbackReportSupported = supported
            return supported
        }

        override suspend fun reportPlaybackState(trackId: MediaId, state: String, positionMs: Long): Result<Unit> =
            runCatching {
                unwrap(
                    api.reportPlayback(
                        mediaId = trackId.requireSubsonic(),
                        mediaType = "song",
                        positionMs = positionMs.coerceAtLeast(0L),
                        state = state,
                        // Plays are counted through scrobble only, so online and offline plays count once.
                        ignoreScrobble = true,
                    ),
                )
            }
    }

    // Asked once per source: see [MusicPlayback.supportsPlaybackStateReport].
    @Volatile private var playbackReportSupported: Boolean? = null

    override fun library(): MusicLibrary = library
    override fun metadata(): MusicMetadata = metadata
    override fun writeActions(): MusicWriteActions = writeActions
    override fun playback(): MusicPlayback = playback

    /**
     * Warm two slices of library data Home/Library land on first after a
     * switch. Errors are ignored — [com.gpo.yoin.data.profile.ProfileManager]
     * wraps this in `runCatching`.
     */
    override suspend fun prime() {
        runCatching { library.getAlbumList(type = "recent", size = 20, offset = 0) }
        runCatching { library.getArtists() }
    }

    override fun resolveCoverUrl(ref: CoverRef, size: Int?): String? = when (ref) {
        is CoverRef.Url -> ref.url
        is CoverRef.SourceRelative ->
            SubsonicApiFactory.buildCoverArtUrl(credentials, ref.coverArtId, size)
    }

    /** Helper for callers that still have loose Subsonic ids (e.g. coverArtId). */
    fun buildCoverArtUrl(coverArtId: String, size: Int? = null): String =
        SubsonicApiFactory.buildCoverArtUrl(credentials, coverArtId, size)

    /** Helper for legacy stream-url callers; prefer [MusicPlayback.handleFor]. */
    fun buildStreamUrl(songRawId: String): String =
        SubsonicApiFactory.buildStreamUrl(credentials, songRawId)

    val rawCredentials: ServerCredentials get() = credentials

    /**
     * Rough proxy for "can this source hit its server right now". We derive it
     * from having a non-blank URL — the real test is [MusicLibrary.ping].
     */
    val isConfigured: Boolean get() = credentials.serverUrl.isNotBlank()

    private fun MediaId.requireSubsonic(): String {
        require(provider == MediaId.PROVIDER_SUBSONIC) {
            "SubsonicMusicSource received a non-Subsonic MediaId: $this"
        }
        return rawId
    }

    private fun unwrap(response: SubsonicResponse): SubsonicResponseBody {
        val body = response.subsonicResponse
        if (body.status != "ok") {
            val error = body.error
            throw SubsonicException(code = error?.code ?: -1, message = error?.message)
        }
        return body
    }

    companion object {
        fun fromProfileCredentials(
            credentials: ProfileCredentials.Subsonic,
        ): SubsonicMusicSource = SubsonicMusicSource(
            credentials = ServerCredentials(
                serverUrl = credentials.serverUrl,
                username = credentials.username,
                password = credentials.password,
            ),
        )

        /** Convert a UI-level 0..5 float rating to a Subsonic integer rating. */
        fun toServerRating(rating: Float): Int = rating.roundToInt().coerceIn(0, 5)
    }
}

/**
 * Where [songId] sits in [songIds], a playlist's songs as the server lists
 * them: at [position] when it is there, else its entry nearest to it (a song
 * can be listed twice); null when the playlist no longer has it.
 */
internal fun playlistIndexNearest(songIds: List<String>, songId: String, position: Int): Int? =
    songIds.indices.filter { songIds[it] == songId }.minByOrNull { abs(it - position) }
