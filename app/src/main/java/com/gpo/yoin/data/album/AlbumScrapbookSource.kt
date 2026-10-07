package com.gpo.yoin.data.album

import com.gpo.yoin.data.local.AlbumNote
import com.gpo.yoin.data.local.AlbumMemoryTitleDao
import com.gpo.yoin.data.local.AlbumNoteDao
import com.gpo.yoin.data.local.AlbumPlayStats
import com.gpo.yoin.data.local.LocalRatingDao
import com.gpo.yoin.data.local.MemoryCopyCache
import com.gpo.yoin.data.local.MemoryCopyCacheDao
import com.gpo.yoin.data.local.PlayHistoryDao
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.local.SongAboutEntryDao
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.local.SongNoteDao
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.MediaId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/*
 * The album page's second page (the scrapbook) reads everything the listener left on an album: track
 * scores, track notes, the Ask / About rows from Now Playing, per-track play counts and the album's plays.
 * Scores, notes and plays are scoped to the active profile and the track's provider (two profiles on the
 * same server never mix); About rows are shared reference material keyed by normalized title / artist /
 * album, so they are read by album key and matched back to the tracks here.
 *
 * An album has ONE album note (owner, 2026-10-05). Nothing writes `album_notes` any more (legacy rows
 * only), so the scrapbook reads just the latest row by updatedAt — the same row the Memories diary shows
 * ([latestAlbumNote]) — profile- and provider-scoped like the rest.
 */

/** What the scrapbook asks about: the album and its tracks in album order. */
data class AlbumScrapbookQuery(
    val albumId: MediaId,
    val albumName: String,
    val tracks: List<AlbumScrapbookTrackKey>,
)

/** One track as the About table knows it: its id plus the display title / artist its rows were keyed by. */
data class AlbumScrapbookTrackKey(
    val id: MediaId,
    val title: String,
    val artist: String,
)

data class AlbumScrapbookData(
    /** Track scores 0–10, rated tracks only (a stored 0 is "not rated"). */
    val ratings: Map<MediaId, Float> = emptyMap(),
    /** Non-blank notes per track, in timeline order ([timelineOrder]). */
    val notes: Map<MediaId, List<AlbumScrapbookNote>> = emptyMap(),
    /** Ask / About rows matched back to the tracks. */
    val about: Map<MediaId, AlbumScrapbookAbout> = emptyMap(),
    /** Plays in Yoin per track; unplayed tracks are absent. */
    val playCounts: Map<MediaId, Int> = emptyMap(),
    val plays: AlbumScrapbookPlays = AlbumScrapbookPlays(),
    /** The Memories AI title, only when Memories already generated it (never generated from here). */
    val memoryTitle: String? = null,
    /** The user's own name for the album's Memory (album_memory_titles); beats [memoryTitle]. */
    val memoryTitleUser: String? = null,
    /** The album's one album note (the latest legacy row), if any. */
    val albumNote: AlbumScrapbookAlbumNote? = null,
) {
    companion object {
        val Empty = AlbumScrapbookData()
    }
}

data class AlbumScrapbookNote(
    val id: String,
    val content: String,
    val positionMs: Long?,
    val createdAt: Long,
)

data class AlbumScrapbookAbout(
    /** Questions asked in Now Playing, newest first. */
    val asks: List<AlbumScrapbookAsk> = emptyList(),
    /** Non-blank canonical facts in [SongAboutEntry.CANONICAL_ORDER] (the "review" paragraph included). */
    val facts: List<AlbumScrapbookFact> = emptyList(),
)

/** The album's own note: one per album, shown as the opening's sticky. */
data class AlbumScrapbookAlbumNote(
    val id: String,
    /** Trimmed, never blank. */
    val content: String,
    val updatedAt: Long,
)

data class AlbumScrapbookAsk(
    val question: String,
    val title: String?,
    val answer: String,
)

data class AlbumScrapbookFact(
    /** One of the `SongAboutEntry.CANON_*` keys. */
    val key: String,
    val value: String,
)

data class AlbumScrapbookPlays(
    val count: Int = 0,
    val firstPlayedAt: Long? = null,
    val lastPlayedAt: Long? = null,
)

/** The album page's read of the scrapbook data; live (re-emits when a score, note, question or play lands). */
fun interface AlbumScrapbookSource {
    fun observe(query: AlbumScrapbookQuery): Flow<AlbumScrapbookData>

    companion object {
        /** No data at all (previews, hosts without a database). */
        val None = AlbumScrapbookSource { flowOf(AlbumScrapbookData.Empty) }

        fun from(database: YoinDatabase, activeProfileId: Flow<String?>): AlbumScrapbookSource =
            RoomAlbumScrapbookSource(
                activeProfileId = activeProfileId,
                ratingDao = database.localRatingDao(),
                noteDao = database.songNoteDao(),
                aboutDao = database.songAboutEntryDao(),
                playHistoryDao = database.playHistoryDao(),
                memoryCopyCacheDao = database.memoryCopyCacheDao(),
                albumNoteDao = database.albumNoteDao(),
                memoryTitleDao = database.albumMemoryTitleDao(),
            )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RoomAlbumScrapbookSource(
    private val activeProfileId: Flow<String?>,
    private val ratingDao: LocalRatingDao,
    private val noteDao: SongNoteDao,
    private val aboutDao: SongAboutEntryDao,
    private val playHistoryDao: PlayHistoryDao,
    private val memoryCopyCacheDao: MemoryCopyCacheDao,
    private val albumNoteDao: AlbumNoteDao,
    private val memoryTitleDao: AlbumMemoryTitleDao? = null,
) : AlbumScrapbookSource {

    override fun observe(query: AlbumScrapbookQuery): Flow<AlbumScrapbookData> =
        activeProfileId
            .distinctUntilChanged()
            .flatMapLatest { profileId ->
                if (profileId.isNullOrBlank()) flowOf(AlbumScrapbookData.Empty) else observeFor(profileId, query)
            }
            .distinctUntilChanged()

    private fun observeFor(profileId: String, query: AlbumScrapbookQuery): Flow<AlbumScrapbookData> {
        // One query per provider: a track's scores, notes and plays live under its own provider.
        val byProvider = query.tracks.map { it.id }.distinct().groupBy(MediaId::provider)
        val ratings = byProvider.flatCombine { provider, ids ->
            ratingDao.observeRatings(ids, provider, profileId).map { rows ->
                rows.filter { it.rating > 0f }.map { MediaId(it.provider, it.songId) to it.rating }
            }
        }
        val notes = byProvider.flatCombine { provider, ids ->
            noteDao.observeForTracks(ids, provider, profileId).map { rows -> rows.mapNotNull { it.toKeyed() } }
        }
        val counts = byProvider.flatCombine { provider, ids ->
            playHistoryDao.observeSongPlayCounts(ids, provider, profileId).map { rows ->
                rows.map { MediaId(provider, it.songId) to it.playCount }
            }
        }
        val about = aboutDao.observeByAlbum(SongAboutEntry.normalize(query.albumName))
            .map { rows -> matchAboutToTracks(rows, query.tracks) }
        val plays = playHistoryDao
            .observeAlbumPlayStats(query.albumId.rawId, query.albumId.provider, profileId)
            .map(AlbumPlayStats::toPlays)
        val memoryTitle = flow {
            emit(
                runCatching {
                    memoryCopyCacheDao.get(
                        profileId = profileId,
                        provider = query.albumId.provider,
                        entityType = MemoryCopyCache.ENTITY_ALBUM,
                        entityId = query.albumId.rawId,
                    )?.title?.takeIf(String::isNotBlank)
                }.getOrNull(),
            )
        }
        val albumNote = albumNoteDao
            .observeForAlbum(query.albumId.rawId, query.albumId.provider, profileId)
            .map(::latestAlbumNote)
        // Live: a rename on page 2 or in Memories shows at once.
        val userTitle = memoryTitleDao
            ?.observe(profileId, query.albumId.provider, query.albumId.rawId)
            ?.map { row -> row?.title?.takeIf(String::isNotBlank) }
            ?: flowOf(null)
        val titles = combine(memoryTitle, userTitle) { ai, user -> ai to user }
        val perTrack = combine(ratings, notes, counts) { r, n, c -> Triple(r, n, c) }
        return combine(perTrack, about, plays, titles, albumNote) { (r, n, c), byTrack, albumPlays, (title, user), note ->
            AlbumScrapbookData(
                ratings = r.toMap(),
                notes = n.groupBy({ it.first }, { it.second }).mapValues { (_, list) -> list.timelineOrder() },
                about = byTrack,
                playCounts = c.toMap(),
                plays = albumPlays,
                memoryTitle = title,
                memoryTitleUser = user,
                albumNote = note,
            )
        }
    }
}

/**
 * The album's one album note: the latest non-blank row by updatedAt. [rows] come in the DAO's createdAt
 * order, so a tie keeps the earlier-created row — the same pick as the Memories diary's `diaryAlbumNotes`.
 */
fun latestAlbumNote(rows: List<AlbumNote>): AlbumScrapbookAlbumNote? =
    rows
        .filter { it.content.isNotBlank() }
        .maxByOrNull(AlbumNote::updatedAt)
        ?.let { note ->
            AlbumScrapbookAlbumNote(id = note.id, content = note.content.trim(), updatedAt = note.updatedAt)
        }

/** Notes as the scrapbook lists them: anchored ones along the song, then the unanchored by when they were written. */
fun List<AlbumScrapbookNote>.timelineOrder(): List<AlbumScrapbookNote> =
    sortedWith(compareBy<AlbumScrapbookNote>({ it.positionMs == null }, { it.positionMs ?: 0L }, { it.createdAt }))

/**
 * About rows (every row filed under the album's key) matched to [tracks] by normalized title + artist; a
 * row whose artist matches no track falls back to the one track with that title (a featured credit spelt
 * differently). Rows of same-named albums by other artists match nothing and are dropped.
 */
fun matchAboutToTracks(
    rows: List<SongAboutEntry>,
    tracks: List<AlbumScrapbookTrackKey>,
): Map<MediaId, AlbumScrapbookAbout> {
    if (rows.isEmpty() || tracks.isEmpty()) return emptyMap()
    val byTitleArtist = HashMap<Pair<String, String>, MediaId>()
    val byTitle = HashMap<String, MutableList<MediaId>>()
    tracks.forEach { track ->
        val title = SongAboutEntry.normalize(track.title)
        byTitleArtist.putIfAbsent(title to SongAboutEntry.normalize(track.artist), track.id)
        byTitle.getOrPut(title) { mutableListOf() }.add(track.id)
    }
    val matched = rows.mapNotNull { row ->
        val id = byTitleArtist[row.titleKey to row.artistKey]
            ?: byTitle[row.titleKey]?.distinct()?.singleOrNull()
            ?: return@mapNotNull null
        id to row
    }
    return matched.groupBy({ it.first }, { it.second }).mapValues { (_, list) -> list.toAbout() }
}

private fun List<SongAboutEntry>.toAbout(): AlbumScrapbookAbout {
    val asks = filter { it.kind == SongAboutEntry.KIND_ASK && it.answerText.isNotBlank() }
        .sortedByDescending { it.updatedAt }
        .map { row ->
            AlbumScrapbookAsk(
                question = row.promptText?.takeIf(String::isNotBlank) ?: row.entryKey,
                title = row.titleText?.takeIf(String::isNotBlank),
                answer = row.answerText.trim(),
            )
        }
    val canonical = filter { it.kind == SongAboutEntry.KIND_CANONICAL && it.answerText.isNotBlank() }
        .associateBy { it.entryKey }
    val facts = SongAboutEntry.CANONICAL_ORDER.mapNotNull { key ->
        canonical[key]?.let { AlbumScrapbookFact(key = key, value = it.answerText.trim()) }
    }
    return AlbumScrapbookAbout(asks = asks, facts = facts)
}

private fun SongNote.toKeyed(): Pair<MediaId, AlbumScrapbookNote>? {
    val text = content.trim()
    if (text.isEmpty()) return null
    return MediaId(provider, trackId) to AlbumScrapbookNote(
        id = id,
        content = text,
        positionMs = positionMs,
        createdAt = createdAt,
    )
}

private fun AlbumPlayStats.toPlays(): AlbumScrapbookPlays =
    if (playCount <= 0) {
        AlbumScrapbookPlays()
    } else {
        AlbumScrapbookPlays(count = playCount, firstPlayedAt = firstPlayedAt, lastPlayedAt = lastPlayedAt)
    }

/** One flow per provider group, flattened; no groups → one empty emission. */
private fun <T> Map<String, List<MediaId>>.flatCombine(
    query: (provider: String, rawIds: List<String>) -> Flow<List<T>>,
): Flow<List<T>> {
    if (isEmpty()) return flowOf(emptyList())
    val flows = map { (provider, ids) -> query(provider, ids.map(MediaId::rawId)) }
    return if (flows.size == 1) flows.single() else combine(flows) { groups -> groups.flatMap { it } }
}
