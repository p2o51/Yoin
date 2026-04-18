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
) : MusicSource {

    private val api: SubsonicApi = apiFactory { credentials }

    override val id: String = MediaId.PROVIDER_SUBSONIC

    override val capabilities: Set<Capability> = setOf(
        Capability.SEARCH,
        Capability.RANDOM_SONGS,
        Capability.STAR_UNSTAR,
        Capability.RATING_FIVE_STAR,
        Capability.PLAYLISTS_READ,
        Capability.PLAYLISTS_WRITE,
        Capability.LYRICS,
    )

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

        override suspend fun getArtists(): List<ArtistIndex> =
            unwrap(api.getArtists()).artists?.index.orEmpty().map { it.toArtistIndex() }

        override suspend fun getArtist(id: MediaId): ArtistDetail? =
            unwrap(api.getArtist(id.requireSubsonic())).artist?.toArtistDetail()

        override suspend fun getPlaylists(): List<Playlist> =
            unwrap(api.getPlaylists(username = null))
                .playlists?.playlist.orEmpty().map { it.toPlaylist() }

        override suspend fun getPlaylist(id: MediaId): Playlist? =
            unwrap(api.getPlaylist(id.requireSubsonic())).playlist?.toPlaylist()

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
    }

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
            created.toPlaylist()
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
            // Subsonic ignores snapshotId — its updatePlaylist.view is
            // last-writer-wins. Positions must be sorted descending when
            // removing multiple indices in one call so earlier removals
            // don't shift later indices.
            unwrap(
                api.updatePlaylist(
                    playlistId = playlistId.requireSubsonic(),
                    songIndexToRemove = items
                        .map { it.position }
                        .sortedDescending(),
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
    }

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
        /** Convert a UI-level 0..5 float rating to a Subsonic integer rating. */
        fun toServerRating(rating: Float): Int = rating.roundToInt().coerceIn(0, 5)
    }
}
