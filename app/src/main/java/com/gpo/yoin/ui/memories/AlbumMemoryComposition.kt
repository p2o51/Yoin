package com.gpo.yoin.ui.memories

import com.gpo.yoin.data.local.AlbumRating
import com.gpo.yoin.data.local.LocalRating
import com.gpo.yoin.data.memory.AlbumMemoryCandidate
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.ui.memories.copy.MemoryCopyInput
import com.gpo.yoin.ui.memories.copy.MemoryDates
import com.gpo.yoin.ui.memories.copy.MemoryProseLanguage
import com.gpo.yoin.ui.memories.copy.MemoryTitleKind
import com.gpo.yoin.ui.memories.copy.MemoryVoice
import com.gpo.yoin.ui.memories.copy.MemoryVoiceCopy
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

// One album Memory's copy, built one way for every surface: the Memories deck ([MemoriesDeckCoordinator]) and
// everything that shows the same title elsewhere ([AlbumMemoryTitleResolver]: Home's Jump Back In card, the
// album page's second page). The steps are split so each caller only swaps what it must: where Yoin's AI title
// comes from (the deck may generate it, nobody else ever does) and where the candidate-level facts come from (a
// built candidate, or the album's own rows when there is none).

/**
 * The album's own rows behind one Memory's copy, read once per resolve ([loadAlbumMemoryFacts]): the album
 * detail (the repository's cached path), the track ratings, the album rating row with its review, and every
 * album / song note.
 *
 * [albumId] is the id the Memory is keyed on (provider + stored raw id), the same one the user's title is
 * stored under.
 */
internal class AlbumMemoryFacts(
    val albumId: MediaId,
    /** Null when the detail could not be loaded: the copy then leans on [AlbumMemoryBasis]' fallbacks. */
    val album: Album?,
    val ratings: Map<MediaId, LocalRating>,
    val ratingRow: AlbumRating?,
    /** The album review as a writing; null without a non-blank review. */
    val review: MemoryWriting?,
    /** Album and song notes, non-blank, newest first ([loadAlbumWritings]). */
    val writings: List<MemoryWriting>,
) {
    val songs: List<Track> get() = album?.tracks.orEmpty()

    /** The album's rated tracks (a stored 0 is "not rated"), in album order. */
    val rated: List<LocalRating> = songs.mapNotNull { song ->
        ratings[song.id]?.takeIf { localRating -> localRating.rating > 0f }
    }

    /** The writing Yoin's AI title is drawn from (design.md 拟题豁免): the review, else the newest note. */
    val titleOccupant: MemoryWriting? get() = review ?: writings.firstOrNull()
}

/**
 * Reads [AlbumMemoryFacts] for [albumId]. A failed album, rating-row or notes read degrades to nothing (the
 * deck has always done so); a failed track-ratings read throws, as it did in the deck.
 */
internal suspend fun loadAlbumMemoryFacts(repository: YoinRepository, albumId: MediaId): AlbumMemoryFacts {
    val album = runCatching { repository.getAlbum(albumId) }.getOrNull()
    val songs = album?.tracks.orEmpty()
    val ratings = repository.getRatings(songs.map(Track::id))
    // 用户的字：乐评行 + album/song 笔记，deck 每卡一次快照读取。
    val ratingRow = runCatching { repository.getAlbumRatingRow(albumId) }.getOrNull()
    val review = ratingRow?.review?.takeIf(String::isNotBlank)?.let { text ->
        MemoryWriting(
            kind = MemoryWriting.Kind.REVIEW,
            text = text,
            // When the words were written (v30), not the row's last touch (a rating or a sync bumps that).
            writtenAt = ratingRow.reviewUpdatedAt ?: ratingRow.updatedAt,
        )
    }
    val writings = runCatching { loadAlbumWritings(repository, albumId, songs) }.getOrDefault(emptyList())
    return AlbumMemoryFacts(
        albumId = albumId,
        album = album,
        ratings = ratings,
        ratingRow = ratingRow,
        review = review,
        writings = writings,
    )
}

/**
 * album/song 笔记合并成 newest-first 的一条流。UI 只画前几条，但这里
 * 不截断 —— 「全部 N 条笔记」sheet 要列全量，截断是渲染侧的事。
 */
internal suspend fun loadAlbumWritings(
    repository: YoinRepository,
    albumId: MediaId,
    songs: List<Track>,
): List<MemoryWriting> {
    val albumNotes = repository.getAlbumNotesOnce(albumId).map { note ->
        MemoryWriting(
            kind = MemoryWriting.Kind.ALBUM_NOTE,
            text = note.content,
            writtenAt = note.updatedAt,
            noteId = note.id,
        )
    }
    val titlesByRawId = songs.associate { song -> song.id.rawId to song.title }
    val songNotes = repository.getSongNotesOnce(songs.map(Track::id)).map { note ->
        MemoryWriting(
            kind = MemoryWriting.Kind.SONG_NOTE,
            text = note.content,
            writtenAt = note.updatedAt,
            trackTitle = titlesByRawId[note.trackId] ?: note.title,
            positionMs = note.positionMs,
            trackId = note.trackId,
            noteId = note.id,
        )
    }
    return (albumNotes + songNotes)
        .filter { writing -> writing.text.isNotBlank() }
        .sortedByDescending(MemoryWriting::writtenAt)
}

/**
 * Yoin's AI title for the album (Gemini, cached in memory_copy_cache), keyed on the loaded album's own id (an
 * Apple Music library album resolves to its catalog album), else on [AlbumMemoryFacts.albumId].
 *
 * [generate] is the Memories deck's alone: with a loaded album it goes through
 * [YoinRepository.getOrGenerateAlbumMemoryTitle], which may ask Gemini when the occupant writing changed.
 * Without it (every other surface), or without a loaded album, only the cached row is read — never a request.
 * Null on a miss or a failed read: the album name takes the slot.
 */
internal suspend fun albumMemoryAiTitle(
    repository: YoinRepository,
    facts: AlbumMemoryFacts,
    generate: Boolean,
): String? {
    val album = facts.album
    if (generate && album != null) {
        val occupant = facts.titleOccupant
        return runCatching {
            repository.getOrGenerateAlbumMemoryTitle(
                album = album,
                writingKind = when (occupant?.kind) {
                    MemoryWriting.Kind.REVIEW -> "album review"
                    MemoryWriting.Kind.ALBUM_NOTE, MemoryWriting.Kind.SONG_NOTE -> "note"
                    null -> null
                },
                writingText = occupant?.text,
            )
        }.getOrNull()
    }
    return try {
        repository.getCachedAlbumMemoryTitle(album?.id ?: facts.albumId)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }
}

/**
 * What a Memory's copy takes from beyond the album's own rows: the candidate's fallbacks for an album whose
 * detail did not load, the user's album score and the play history. [AlbumMemoryCandidate.memoryBasis] for a
 * built candidate; [AlbumMemoryTitleResolver] builds one from the album's rows when there is no candidate.
 */
internal data class AlbumMemoryBasis(
    /** Stands in for the album's name when the detail did not load. */
    val albumName: String,
    /** The user's album score (> 0), or null: the copy then reads no album score. */
    val albumRating: Float?,
    /** Stand-ins for the rated / total track counts when the detail did not load. */
    val ratedTrackCount: Int,
    val totalTracks: Int,
    /** Plays in Yoin from play_history only ([memoryPlayHistory]); null when never played. */
    val history: MemoryPlayHistory?,
)

/**
 * This candidate's [AlbumMemoryBasis]. Bug 5 (PLAN §2): every "heard" fact comes from play_history only, never
 * from VISITED events: the candidate's *FromHistory fields, null / 0 for an album with no history row.
 */
internal fun AlbumMemoryCandidate.memoryBasis(): AlbumMemoryBasis = AlbumMemoryBasis(
    albumName = albumName,
    albumRating = albumRating,
    ratedTrackCount = ratedTrackCount,
    totalTracks = totalTracks,
    history = memoryPlayHistory(playCountFromHistory, firstPlayedFromHistoryAt, lastPlayedFromHistoryAt),
)

/** The id an album Memory is keyed on: the candidate's provider and its stored raw id (legacy prefix dropped). */
internal fun AlbumMemoryCandidate.memoryAlbumId(): MediaId = MediaId(provider, MediaId.storedRawId(provider, albumId))

/** [albumId] keyed the way a Memory is: the stored raw id, any legacy "provider:" prefix dropped. */
internal fun memoryAlbumId(albumId: MediaId): MediaId =
    MediaId(albumId.provider, MediaId.storedRawId(albumId.provider, albumId.rawId))

/** Yoin's words for one album Memory ([composeAlbumMemoryCopy]) and the rows they were built from. */
internal class AlbumMemoryCopy(
    /** Every song as a diary / playback row, album order. */
    val tracks: List<MemoryTrack>,
    val input: MemoryCopyInput,
    val voice: MemoryVoiceCopy,
    /** The day the copy was written for (the date grammar's "today"). */
    val today: LocalDate,
) {
    /** Yoin's own title (the AI title); null when the album name stands in. */
    val yoinTitle: YoinMemoryTitle?
        get() = voice.title.takeIf { voice.titleKind != MemoryTitleKind.ALBUM }
            ?.let { title -> YoinMemoryTitle(title, voice.titleKind) }
}

/**
 * Yoin's title, narration and question for one album Memory, from [facts], [basis] and Yoin's [aiTitle]
 * ([albumMemoryAiTitle]). Pure: [now], [zone] and [appLanguage] are the caller's clock, zone and app language.
 * The deck and every other surface call this one function, so their titles cannot drift.
 */
internal fun composeAlbumMemoryCopy(
    facts: AlbumMemoryFacts,
    basis: AlbumMemoryBasis,
    aiTitle: String?,
    now: Long,
    zone: ZoneId,
    appLanguage: MemoryProseLanguage,
): AlbumMemoryCopy {
    val songs = facts.songs
    val rawAlbumId = facts.albumId.rawId
    val tracks = songs.mapIndexed { index, song ->
        MemoryTrack(
            stableId = "album:$rawAlbumId:song:${song.id}",
            title = song.title.orEmpty(),
            artist = song.artist.orEmpty(),
            durationSeconds = song.durationSec,
            rating = facts.ratings[song.id]?.rating?.takeIf { rating -> rating > 0f },
            number = song.trackNumber ?: (index + 1),
            trackId = song.id.rawId,
            playbackIndex = index,
        ).withIndexFallback(index)
    }
    val today = MemoryDates.localDate(now, zone)
    // 正文槽阶梯①②的占用者决定拟题输入（design.md 拟题豁免）。没有 AI 拟题时
    // 标题就是专辑名（owner 2026-10-06：计数出来的动机短句不算标题，已取消）。
    val input = memoryCopyInput(
        albumName = facts.album?.name ?: basis.albumName,
        aiTitle = aiTitle,
        review = facts.review,
        writings = facts.writings,
        tracks = tracks,
        ratedTracks = if (songs.isNotEmpty()) facts.rated.size else basis.ratedTrackCount,
        totalTracks = songs.size.takeIf { count -> count > 0 } ?: basis.totalTracks,
        albumScore = basis.albumRating,
        history = basis.history,
        zone = zone,
    )
    val voice = MemoryVoice.compose(input, today, appLanguage)
    return AlbumMemoryCopy(tracks = tracks, input = input, voice = voice, today = today)
}
