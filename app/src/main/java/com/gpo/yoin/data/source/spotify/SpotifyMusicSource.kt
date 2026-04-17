package com.gpo.yoin.data.source.spotify

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
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.MusicLibrary
import com.gpo.yoin.data.source.MusicMetadata
import com.gpo.yoin.data.source.MusicPlayback
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.MusicWriteActions
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * B2 skeleton: validates the [MusicSource] abstraction against a second
 * provider. Only [MusicLibrary.ping] does real work today (hits Spotify Web
 * API `/v1/me`). Everything else throws `NotImplementedError` so phase-2 code
 * lights up implementations one method at a time.
 *
 * [capabilities] is deliberately empty — gating UI on capability sets should
 * stay truthful. Phase 2 adds entries as methods become real.
 */
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

    override val capabilities: Set<Capability> = emptySet()

    private val library = object : MusicLibrary {
        override suspend fun ping(): Boolean {
            apiClient.getMe()
            return true
        }

        override suspend fun getAlbumList(type: String, size: Int, offset: Int): List<Album> =
            throw NotImplementedError(NOT_IMPLEMENTED_MESSAGE)

        override suspend fun getAlbum(id: MediaId): Album? =
            throw NotImplementedError(NOT_IMPLEMENTED_MESSAGE)

        override suspend fun getArtists(): List<ArtistIndex> =
            throw NotImplementedError(NOT_IMPLEMENTED_MESSAGE)

        override suspend fun getArtist(id: MediaId): ArtistDetail? =
            throw NotImplementedError(NOT_IMPLEMENTED_MESSAGE)

        override suspend fun getPlaylists(): List<Playlist> =
            throw NotImplementedError(NOT_IMPLEMENTED_MESSAGE)

        override suspend fun getPlaylist(id: MediaId): Playlist? =
            throw NotImplementedError(NOT_IMPLEMENTED_MESSAGE)

        override suspend fun getStarred(): Starred =
            throw NotImplementedError(NOT_IMPLEMENTED_MESSAGE)

        override suspend fun getRandomSongs(size: Int): List<Track> =
            throw NotImplementedError(NOT_IMPLEMENTED_MESSAGE)

        override suspend fun search(query: String): SearchResults =
            throw NotImplementedError(NOT_IMPLEMENTED_MESSAGE)
    }

    private val metadata = object : MusicMetadata {
        override suspend fun getLyrics(trackId: MediaId): Lyrics? =
            throw NotImplementedError(NOT_IMPLEMENTED_MESSAGE)
    }

    private val writeActions = object : MusicWriteActions {
        override suspend fun setFavorite(id: MediaId, favorite: Boolean): Result<Unit> =
            Result.failure(NotImplementedError(NOT_IMPLEMENTED_MESSAGE))

        override suspend fun setRating(trackId: MediaId, rating: Int): Result<Unit> =
            Result.failure(UnsupportedOperationException("Spotify has no 5-star rating"))
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

    override fun resolveCoverUrl(ref: CoverRef, size: Int?): String? = when (ref) {
        is CoverRef.Url -> ref.url
        // Spotify entities never emit SourceRelative — defensive.
        is CoverRef.SourceRelative -> null
    }

    companion object {
        private const val NOT_IMPLEMENTED_MESSAGE =
            "Spotify provider is a B2 skeleton — this method lands in phase 2"

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
