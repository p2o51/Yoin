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
    /** The second line's artist (a song's, an album's); null for an artist. */
    val artist: String? = null,
    /** The second line's album (a song's), muted after the artist. */
    val album: String? = null,
    /** Where the row came from: what is playing and recent plays are listed before search results. */
    val source: Source = Source.Search,
) {
    /**
     * In listing order: the song playing, recent plays, the service's search for the words typed, then rows an
     * earlier search offered (kept only while they still match).
     */
    enum class Source { Current, Recent, Search, Seen }
}

/** The @ popup's rows, by kind (songs, albums, artists), in the order shown. */
@Immutable
data class MentionCandidates(
    val tracks: List<MentionCandidate> = emptyList(),
    val albums: List<MentionCandidate> = emptyList(),
    val artists: List<MentionCandidate> = emptyList(),
    /** The kinds in the order they're listed (the best match's kind first once something is typed). */
    val order: List<MentionKind> = DefaultOrder,
) {
    fun of(kind: MentionKind): List<MentionCandidate> = when (kind) {
        MentionKind.Track -> tracks
        MentionKind.Album -> albums
        MentionKind.Artist -> artists
    }

    /** Every row in listing order: what ↑ ↓ walk, and the first is what Enter picks. */
    val all: List<MentionCandidate> get() = order.flatMap(::of)

    val isEmpty: Boolean get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty()

    /** These rows and [other]'s, each item once (the first seen wins, so earlier sources keep their place). */
    fun merge(other: MentionCandidates): MentionCandidates = MentionCandidates(
        tracks = (tracks + other.tracks).distinctBy { it.mention.ref },
        albums = (albums + other.albums).distinctBy { it.mention.ref },
        artists = (artists + other.artists).distinctBy { it.mention.ref },
    )

    companion object {
        // Before [Empty], which reads it as its default.
        val DefaultOrder = listOf(MentionKind.Track, MentionKind.Album, MentionKind.Artist)
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
    private val romanizer: MentionRomanizer = IcuMentionRomanizer,
) : MentionCandidateSource {

    override suspend fun candidates(query: String, limit: Int): MentionCandidates {
        val q = query.trim()
        val recents = recentTracks()
        if (q.isEmpty()) {
            val current = nowPlaying()
            val tracks = (listOfNotNull(current?.let { trackCandidate(it, MentionCandidate.Source.Current) }) + recents)
                .distinctBy { it.mention.ref }
                .take(limit)
            val album = current?.takeIf { it.albumId != null && !it.album.isNullOrBlank() }?.let { track ->
                candidate(
                    MentionKind.Album, track.albumId!!, track.album!!, track.artist, null, track.coverArt,
                    MentionCandidate.Source.Current,
                )
            }
            val artist = current?.takeIf { it.artistId != null && !it.artist.isNullOrBlank() }?.let { track ->
                candidate(
                    MentionKind.Artist, track.artistId!!, track.artist!!, null, null, null,
                    MentionCandidate.Source.Current,
                )
            }
            return MentionCandidates(tracks = tracks, albums = listOfNotNull(album), artists = listOfNotNull(artist))
        }
        val results = try {
            withTimeoutOrNull(SEARCH_TIMEOUT_MS) { repository.search(q) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
        // Recent plays that match (by name, initials or pinyin) go first, then the search, ranked together.
        val ranked = MentionRanking.rank(
            q,
            MentionCandidates(
                tracks = (recents + results?.tracks.orEmpty().map { trackCandidate(it) }).distinctBy { it.mention.ref },
                albums = results?.albums.orEmpty().map(::albumCandidate),
                artists = results?.artists.orEmpty().map(::artistCandidate),
            ),
            romanizer,
        )
        return ranked.copy(
            tracks = ranked.tracks.take(limit),
            albums = ranked.albums.take(limit / 2 + 1),
            artists = ranked.artists.take(limit / 2 + 1),
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
            MentionCandidate.Source.Recent,
        )
    }

    private fun trackCandidate(track: Track, source: MentionCandidate.Source = MentionCandidate.Source.Search) =
        candidate(MentionKind.Track, track.id, track.title.orEmpty(), track.artist, track.album, track.coverArt, source)

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
        source: MentionCandidate.Source = MentionCandidate.Source.Search,
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
        artist = artist?.takeIf { kind != MentionKind.Artist && it.isNotBlank() },
        album = album?.takeIf { kind == MentionKind.Track && it.isNotBlank() },
        source = source,
    )

    private companion object {
        const val RECENT_LIMIT = 40
        const val SEARCH_TIMEOUT_MS = 6_000L
        const val COVER_SIZE = 120
    }
}
