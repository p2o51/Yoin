package com.gpo.yoin.ui.notes.editor

import androidx.compose.runtime.Immutable
import com.gpo.yoin.data.local.PlayHistory
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.shared.notes.MentionKind
import com.gpo.yoin.shared.notes.NoteMention
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** One row of the @ popup: what it inserts, and how it's shown. */
@Immutable
data class MentionCandidate(
    val mention: NoteMention,
    val coverUrl: String?,
    /** The second line: the artist (and album for a song); null for an artist. */
    val detail: String?,
)

/** The @ popup's rows, by kind (songs, albums, artists), in the order shown. */
@Immutable
data class MentionCandidates(
    val tracks: List<MentionCandidate> = emptyList(),
    val albums: List<MentionCandidate> = emptyList(),
    val artists: List<MentionCandidate> = emptyList(),
) {
    val all: List<MentionCandidate> get() = tracks + albums + artists

    val isEmpty: Boolean get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty()

    companion object {
        val Empty = MentionCandidates()
    }
}

/** Where the @ popup and the full search sheet get their rows. */
fun interface MentionCandidateSource {
    /** [query] blank: what's playing (song, album, artist) and recent plays; otherwise search results. */
    suspend fun candidates(query: String, limit: Int): MentionCandidates

    companion object {
        val None = MentionCandidateSource { _, _ -> MentionCandidates.Empty }
    }
}

/**
 * The active account's rows: an empty query offers the song playing, its album and artist, then recent
 * plays; a query searches the account's service (library + catalogue, as Library search does) and puts
 * matching recent plays first. A failed or slow search falls back to the recent plays that match.
 */
class RepositoryMentionCandidateSource(
    private val repository: YoinRepository,
    private val nowPlaying: () -> Track?,
) : MentionCandidateSource {

    override suspend fun candidates(query: String, limit: Int): MentionCandidates {
        val q = query.trim()
        val recents = recentTracks()
        if (q.isEmpty()) {
            val current = nowPlaying()
            val tracks = (listOfNotNull(current?.let(::trackCandidate)) + recents)
                .distinctBy { it.mention.ref }
                .take(limit)
            val album = current?.takeIf { it.albumId != null && !it.album.isNullOrBlank() }?.let { track ->
                candidate(MentionKind.Album, track.albumId!!, track.album!!, track.artist, null, track.coverArt)
            }
            val artist = current?.takeIf { it.artistId != null && !it.artist.isNullOrBlank() }?.let { track ->
                candidate(MentionKind.Artist, track.artistId!!, track.artist!!, null, null, null)
            }
            return MentionCandidates(tracks = tracks, albums = listOfNotNull(album), artists = listOfNotNull(artist))
        }
        val folded = q.lowercase()
        val recentHits = recents.filter { it.mention.title.lowercase().contains(folded) }
        val results = try {
            withTimeoutOrNull(SEARCH_TIMEOUT_MS) { repository.search(q) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
        val tracks = (recentHits + results?.tracks.orEmpty().map(::trackCandidate)).distinctBy { it.mention.ref }
        return MentionCandidates(
            tracks = tracks.take(limit),
            albums = results?.albums.orEmpty().map(::albumCandidate).take(limit / 2 + 1),
            artists = results?.artists.orEmpty().map(::artistCandidate).take(limit / 2 + 1),
        )
    }

    private suspend fun recentTracks(): List<MentionCandidate> = try {
        repository.getRecentHistory(RECENT_LIMIT).first()
            .distinctBy { it.songId }
            .mapNotNull(::historyCandidate)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        emptyList()
    }

    private fun historyCandidate(row: PlayHistory): MentionCandidate? {
        if (row.songId.isBlank() || row.title.isBlank() || row.provider.isBlank()) return null
        return candidate(
            MentionKind.Track,
            MediaId(row.provider, MediaId.storedRawId(row.provider, row.songId)),
            row.title,
            row.artist,
            row.album,
            CoverRef.fromStorageKey(row.coverArtId),
        )
    }

    private fun trackCandidate(track: Track) =
        candidate(MentionKind.Track, track.id, track.title.orEmpty(), track.artist, track.album, track.coverArt)

    private fun albumCandidate(album: Album) =
        candidate(MentionKind.Album, album.id, album.name, album.artist, null, album.coverArt)

    private fun artistCandidate(artist: Artist) =
        candidate(MentionKind.Artist, artist.id, artist.name, null, null, artist.coverArt)

    private fun candidate(
        kind: MentionKind,
        id: MediaId,
        title: String,
        artist: String?,
        album: String?,
        cover: CoverRef?,
    ) = MentionCandidate(
        mention = NoteMention(
            kind = kind,
            provider = id.provider,
            id = id.rawId,
            title = title.ifBlank { id.rawId },
            artist = artist?.takeIf { kind != MentionKind.Artist && it.isNotBlank() },
            album = album?.takeIf { kind == MentionKind.Track && it.isNotBlank() },
        ),
        coverUrl = repository.resolveCoverUrl(cover, COVER_SIZE),
        detail = when (kind) {
            MentionKind.Track -> listOfNotNull(artist?.takeIf(String::isNotBlank), album?.takeIf(String::isNotBlank))
                .joinToString(" · ").ifBlank { null }
            MentionKind.Album -> artist?.takeIf(String::isNotBlank)
            MentionKind.Artist -> null
        },
    )

    private companion object {
        const val RECENT_LIMIT = 40
        const val SEARCH_TIMEOUT_MS = 6_000L
        const val COVER_SIZE = 120
    }
}
