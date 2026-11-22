package com.gpo.yoin.data.notes

import com.gpo.yoin.data.local.AlbumNote
import com.gpo.yoin.data.local.LedgerSongNoteRow
import com.gpo.yoin.data.local.MentionReviewRow
import com.gpo.yoin.data.local.NoteMentionDao
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.shared.notes.MentionKind
import com.gpo.yoin.shared.notes.MentionMatcher
import com.gpo.yoin.shared.notes.NoteMarkup
import com.gpo.yoin.shared.notes.NoteMention
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn

/*
 * 被提及 (backlinks, notes revamp): for an album, a track or an artist, every song note, album note and album
 * review of the same account that mentions it, newest first. A mention on the target's own provider counts
 * by id (and, on Spotify / Apple Music, by name when the id differs); one on another provider by name
 * (MentionMatcher). A track mention
 * counts toward its album; writings on the album itself (its review, its album notes, its tracks' notes)
 * are left out — they are already on that page.
 */

/** What a 被提及 section is for. */
sealed interface BacklinkTarget {
    val id: MediaId

    /** [tracks]: the album's tracks, so a mention of one of them counts toward the album. */
    data class Album(
        override val id: MediaId,
        val title: String,
        val artist: String?,
        val tracks: List<BacklinkTrack> = emptyList(),
    ) : BacklinkTarget

    data class Track(
        override val id: MediaId,
        val title: String,
        val artist: String?,
        val album: String? = null,
    ) : BacklinkTarget

    data class Artist(override val id: MediaId, val name: String) : BacklinkTarget
}

data class BacklinkTrack(val id: MediaId, val title: String, val artist: String?)

/** Where a backlink was written. Tapping it opens that item's scrapbook (phase B). */
sealed interface BacklinkSource {
    /** [albumId]: the album the track belongs to, when Yoin knows it (a play or a track→album link). */
    data class SongNote(
        val noteId: String,
        val trackId: MediaId,
        val trackTitle: String,
        val trackArtist: String,
        val positionMs: Long?,
        val albumId: MediaId?,
    ) : BacklinkSource

    data class AlbumNote(
        val noteId: String,
        val albumId: MediaId,
        val albumName: String,
        val albumArtist: String,
    ) : BacklinkSource

    /** [albumName] / [albumArtist]: as Yoin's plays or album notes know them; null when neither does. */
    data class Review(
        val albumId: MediaId,
        val albumName: String?,
        val albumArtist: String?,
    ) : BacklinkSource
}

/** One writing as the backlink query reads it. */
data class BacklinkWriting(val source: BacklinkSource, val text: String, val at: Long)

/**
 * One 被提及 clipping: the writing, a one-line [snippet] (plain words, " · " between lines) and when it was
 * written. [mentions]: the mentions in it that point at the target (what the clipping marks).
 */
data class NoteBacklink(
    val source: BacklinkSource,
    val text: String,
    val snippet: String,
    val at: Long,
    val mentions: List<NoteMention>,
)

/** The writings among [writings] that mention [target], newest first. Pure. */
fun findBacklinks(writings: List<BacklinkWriting>, target: BacklinkTarget): List<NoteBacklink> =
    writings.asSequence()
        .filterNot { target.owns(it.source) }
        .mapNotNull { writing ->
            val hits = NoteMarkup.mentions(writing.text).filter(target::isMentionedBy)
            if (hits.isEmpty()) {
                null
            } else {
                NoteBacklink(writing.source, writing.text, NoteMarkup.snippet(writing.text), writing.at, hits)
            }
        }
        .sortedWith(compareByDescending<NoteBacklink> { it.at }.thenBy { it.source.toString() })
        .toList()

/**
 * Whether [mention] points at this target: by id on the target's provider, by name on any other. On Spotify
 * and Apple Music a same-service mention whose id differs may still be this item (an Apple Music `library:`
 * id against a catalogue id, a deluxe or regional reissue), so names are compared there too; a Subsonic id
 * is the server's own and stays id-only. (Tapping a mention, MentionResolver, stays id-first.)
 */
fun BacklinkTarget.isMentionedBy(mention: NoteMention): Boolean {
    val sameProvider = mention.provider == id.provider
    val idMatches = sameProvider && when (this) {
        is BacklinkTarget.Album -> when (mention.kind) {
            MentionKind.Album -> mention.id == id.rawId
            MentionKind.Track -> tracks.any { it.id.provider == mention.provider && it.id.rawId == mention.id }
            MentionKind.Artist -> false
        }
        is BacklinkTarget.Track -> mention.kind == MentionKind.Track && mention.id == id.rawId
        is BacklinkTarget.Artist -> mention.kind == MentionKind.Artist && mention.id == id.rawId
    }
    if (idMatches) return true
    if (sameProvider && id.provider !in BacklinkNameMatchedServices) return false
    return when (this) {
        is BacklinkTarget.Album -> when (mention.kind) {
            MentionKind.Album -> MentionMatcher.namesMatchAlbum(mention, title, artist)
            MentionKind.Track -> MentionMatcher.trackMentionOnAlbum(mention, title, artist) ||
                tracks.any { MentionMatcher.namesMatchTrack(mention, it.title, it.artist ?: artist, title) }
            MentionKind.Artist -> false
        }
        is BacklinkTarget.Track -> MentionMatcher.namesMatchTrack(mention, title, artist, album)
        is BacklinkTarget.Artist -> MentionMatcher.namesMatchArtist(mention, name)
    }
}

/** Services whose same-service mentions with another id are also matched by name (catalogue vs library ids, reissues). */
internal val BacklinkNameMatchedServices: Set<String> = setOf(MediaId.PROVIDER_SPOTIFY, MediaId.PROVIDER_APPLE_MUSIC)

/** A writing that already sits on the target's own page. */
private fun BacklinkTarget.owns(source: BacklinkSource): Boolean = when (this) {
    is BacklinkTarget.Album -> when (source) {
        is BacklinkSource.AlbumNote -> source.albumId == id
        is BacklinkSource.Review -> source.albumId == id
        is BacklinkSource.SongNote -> source.albumId == id || tracks.any { it.id == source.trackId }
    }
    is BacklinkTarget.Track -> source is BacklinkSource.SongNote && source.trackId == id
    is BacklinkTarget.Artist -> false
}

/** The account in use's backlinks, live: re-emits when a note, album note or review changes. */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteBacklinkRepository(
    private val dao: NoteMentionDao,
    private val activeProfileId: Flow<String?>,
) {
    fun observe(target: BacklinkTarget): Flow<List<NoteBacklink>> =
        activeProfileId
            .distinctUntilChanged()
            .flatMapLatest { profileId ->
                if (profileId.isNullOrBlank()) {
                    flowOf(emptyList())
                } else {
                    combine(
                        dao.observeSongNotesWithMentions(profileId),
                        dao.observeAlbumNotesWithMentions(profileId),
                        dao.observeReviewsWithMentions(profileId),
                    ) { songNotes, albumNotes, reviews ->
                        findBacklinks(backlinkWritings(songNotes, albumNotes, reviews), target)
                    }.flowOn(Dispatchers.Default)
                }
            }
            .distinctUntilChanged()
}

/** The DAO's rows as writings (blank ones dropped). */
fun backlinkWritings(
    songNotes: List<LedgerSongNoteRow>,
    albumNotes: List<AlbumNote>,
    reviews: List<MentionReviewRow>,
): List<BacklinkWriting> = buildList {
    songNotes.forEach { row ->
        val note = row.note
        if (note.content.isBlank()) return@forEach
        val trackId = storedMediaId(note.provider, note.trackId) ?: return@forEach
        val albumId = storedMediaId(note.provider, row.linkedAlbumId)
        add(
            BacklinkWriting(
                source = BacklinkSource.SongNote(
                    noteId = note.id,
                    trackId = trackId,
                    trackTitle = note.title,
                    trackArtist = note.artist,
                    positionMs = note.positionMs,
                    albumId = albumId,
                ),
                text = note.content,
                at = note.createdAt,
            ),
        )
    }
    albumNotes.forEach { note ->
        if (note.content.isBlank()) return@forEach
        val albumId = storedMediaId(note.provider, note.albumId) ?: return@forEach
        add(
            BacklinkWriting(
                source = BacklinkSource.AlbumNote(
                    noteId = note.id,
                    albumId = albumId,
                    albumName = note.albumName,
                    albumArtist = note.artist,
                ),
                text = note.content,
                at = note.createdAt,
            ),
        )
    }
    reviews.forEach { row ->
        val review = row.rating.review?.takeIf(String::isNotBlank) ?: return@forEach
        val rating = row.rating
        val albumId = storedMediaId(rating.provider, rating.albumId) ?: return@forEach
        add(
            BacklinkWriting(
                source = BacklinkSource.Review(
                    albumId = albumId,
                    albumName = row.albumName?.takeIf(String::isNotBlank),
                    albumArtist = row.albumArtist?.takeIf(String::isNotBlank),
                ),
                text = review,
                at = rating.reviewUpdatedAt ?: rating.updatedAt,
            ),
        )
    }
}

/** A stored id column (bare or legacy `provider:rawId`) as a MediaId; null when blank. */
private fun storedMediaId(provider: String, stored: String?): MediaId? {
    if (provider.isBlank() || ':' in provider || stored.isNullOrBlank()) return null
    return MediaId.storedRawId(provider, stored).takeIf(String::isNotEmpty)?.let { MediaId(provider, it) }
}
