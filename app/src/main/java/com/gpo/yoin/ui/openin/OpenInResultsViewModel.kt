package com.gpo.yoin.ui.openin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.openin.MusicLinkLookup
import com.gpo.yoin.data.openin.MusicLinkMetadata
import com.gpo.yoin.data.openin.OpenInMatcher
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.transfer.TrackMatcher
import com.gpo.yoin.shared.openin.YoinMusicLinkKind
import com.gpo.yoin.shared.openin.YoinMusicLinkParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One row of the results page, with its cover already resolved. */
sealed interface OpenInResultRow {
    val key: String
    val coverUrl: String?

    data class Song(val track: Track, override val coverUrl: String?) : OpenInResultRow {
        override val key: String get() = "t:${track.id}"
    }

    data class AlbumRow(val album: Album, override val coverUrl: String?) : OpenInResultRow {
        override val key: String get() = "al:${album.id}"
    }

    data class ArtistRow(val artist: Artist, override val coverUrl: String?) : OpenInResultRow {
        override val key: String get() = "ar:${artist.id}"
    }

    data class PlaylistRow(val playlist: Playlist, override val coverUrl: String?) : OpenInResultRow {
        override val key: String get() = "pl:${playlist.id}"
    }
}

sealed interface OpenInResultsState {
    data object Loading : OpenInResultsState

    /** [provider]: the account in use's service, which the description names. [exact]: see [OpenInMatcher]. */
    data class Results(val rows: List<OpenInResultRow>, val exact: Boolean, val provider: String?) : OpenInResultsState

    data class Empty(val provider: String?) : OpenInResultsState
}

/**
 * A found link searched on the account in use (docs/open-in-yoin.md): the link's title (read without login
 * when the card hadn't already), the account's search, then [OpenInMatcher]'s ranking. Nothing plays by
 * itself here: the user picks.
 */
class OpenInResultsViewModel(
    private val linkUrl: String,
    private val initialMetadata: MusicLinkMetadata?,
    private val lookup: MusicLinkLookup,
    private val source: () -> MusicSource?,
) : ViewModel() {
    private val _state = MutableStateFlow<OpenInResultsState>(OpenInResultsState.Loading)
    val state: StateFlow<OpenInResultsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = try {
                load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                OpenInResultsState.Empty(source()?.id)
            }
        }
    }

    private suspend fun load(): OpenInResultsState {
        val target = source() ?: return OpenInResultsState.Empty(null)
        val empty = OpenInResultsState.Empty(target.id)
        val parsed = YoinMusicLinkParser.parse(linkUrl) ?: return empty
        val link = lookup.resolve(parsed) ?: return empty
        val metadata = initialMetadata?.takeUnless { it.isEmpty || it.kind != link.kind }
            ?: lookup.metadata(link)
            ?: return empty
        val title = metadata.title?.takeIf(String::isNotBlank) ?: return empty
        val library = target.library()
        val cover = { ref: com.gpo.yoin.data.model.CoverRef? -> ref?.let { target.resolveCoverUrl(it, COVER_PX) } }
        val (rows, exact) = when (link.kind) {
            YoinMusicLinkKind.Track -> {
                val track = OpenInMatcher.sourceTrack(metadata)
                val byIsrc = metadata.isrc?.let { isrc -> quietly { library.tracksByIsrc(listOf(isrc))[isrc] } }.orEmpty()
                val query = TrackMatcher.searchQuery(track) ?: title
                var searched = search(target, query).tracks
                val titleOnly = TrackMatcher.titleQuery(track)
                if (searched.isEmpty() && titleOnly != null && titleOnly != query) searched = search(target, titleOnly).tracks
                val ranking = OpenInMatcher.rankTracks(track, byIsrc, searched)
                ranking.items.map { OpenInResultRow.Song(it, cover(it.coverArt)) } to ranking.exact
            }
            YoinMusicLinkKind.Album -> {
                val artist = metadata.artist?.takeIf(String::isNotBlank)
                var albums = search(target, listOfNotNull(title, artist).joinToString(" ")).albums
                if (albums.isEmpty() && artist != null) albums = search(target, title).albums
                val ranking = OpenInMatcher.rankAlbums(title, artist, albums)
                ranking.items.map { OpenInResultRow.AlbumRow(it, cover(it.coverArt)) } to ranking.exact
            }
            YoinMusicLinkKind.Artist -> {
                val ranking = OpenInMatcher.rankArtists(title, search(target, title).artists)
                ranking.items.map { OpenInResultRow.ArtistRow(it, cover(it.coverArt)) } to ranking.exact
            }
            YoinMusicLinkKind.Playlist -> {
                val ranking = OpenInMatcher.rankPlaylists(title, metadata.artist, search(target, title).playlists)
                ranking.items.map { OpenInResultRow.PlaylistRow(it, cover(it.coverArt)) } to ranking.exact
            }
            YoinMusicLinkKind.ShortLink -> return empty
        }
        return if (rows.isEmpty()) empty else OpenInResultsState.Results(rows, exact, target.id)
    }

    private suspend fun search(target: MusicSource, query: String): SearchResults =
        quietly { target.library().search(query) } ?: SearchResults()

    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    class Factory(
        private val container: AppContainer,
        private val linkUrl: String,
        private val metadata: MusicLinkMetadata?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = OpenInResultsViewModel(
            linkUrl = linkUrl,
            initialMetadata = metadata,
            lookup = container.musicLinkLookup,
            source = { container.profileManager.activeSource.value },
        ) as T
    }

    private companion object {
        const val COVER_PX = 300
    }
}
