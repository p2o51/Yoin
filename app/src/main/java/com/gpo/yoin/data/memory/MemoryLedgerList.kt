package com.gpo.yoin.data.memory

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/*
 * All Memories under the grid (owner 2026-11-09): one button switches three views, no filters.
 *  - Timeline: by month, newest first, nothing else to pick. An album is one whole card a month (its
 *    memory title, its score, its review, the songs scored on it); song notes are small cards between
 *    them. Months with nothing fold into one quiet line so a sparse year doesn't scroll forever.
 *  - Cards: every album and every noted song once, two a row.
 *  - List: the same items, a line each, in the order picked (recent, score, name).
 */

enum class MemoryLedgerView { Timeline, Cards, List }

enum class MemoryLedgerSort { Recent, Score, Name }

/** A song scored on an album, as its card lists it. */
data class MemoryLedgerSongScore(val title: String?, val score: Float, val at: Long)

/** One album or one song, with everything kept on it among the entries it was built from. */
sealed interface MemoryLedgerItem {
    val key: String

    /** Its newest mark. */
    val at: Long
    val profileId: String
    val album: MemoryLedgerAlbum?
    val score: Float?

    /** What it's called (a song's name can be unknown: empty). */
    val name: String

    data class Album(
        override val album: MemoryLedgerAlbum,
        /** The memory title given to it. */
        val title: String?,
        override val score: Float?,
        val review: String?,
        /** Best first. */
        val songs: List<MemoryLedgerSongScore>,
        override val at: Long,
    ) : MemoryLedgerItem {
        override val key: String get() = "album:${album.key}"
        override val profileId: String get() = album.profileId
        override val name: String get() = album.name
    }

    data class Song(
        override val key: String,
        override val profileId: String,
        val title: String?,
        override val album: MemoryLedgerAlbum?,
        override val score: Float?,
        /** The newest note on it; null for a song only scored whose album isn't known. */
        val note: MemoryLedgerEntry?,
        val notes: Int,
        override val at: Long,
    ) : MemoryLedgerItem {
        override val name: String get() = title.orEmpty()
    }
}

private val newestFirst = compareByDescending<MemoryLedgerEntry> { it.at }.thenBy { it.id }

/** One song in one account: its id, else (an old row) its name. */
private fun MemoryLedgerEntry.songKey(): String = "song:$profileId|" + (songId ?: "title:${trackTitle ?: id}")

/** [group]: one album's entries other than song notes (newest first or not). */
private fun albumItem(album: MemoryLedgerAlbum, group: List<MemoryLedgerEntry>): MemoryLedgerItem.Album {
    val sorted = group.sortedWith(newestFirst)
    fun newest(kind: MemoryLedgerKind) = sorted.firstOrNull { it.kind == kind }
    val songs = sorted.filter { it.kind == MemoryLedgerKind.TrackRating && it.score != null }
        .distinctBy { it.songKey() }
        .map { MemoryLedgerSongScore(it.trackTitle, it.score!!, it.at) }
        .sortedWith(compareByDescending<MemoryLedgerSongScore> { it.score }.thenByDescending { it.at })
    return MemoryLedgerItem.Album(
        album = album,
        title = newest(MemoryLedgerKind.Title)?.text?.takeIf { it.isNotBlank() },
        score = newest(MemoryLedgerKind.AlbumRating)?.score,
        // An album note is from before album notes went away: it reads as the review when there's none.
        review = (newest(MemoryLedgerKind.Review) ?: newest(MemoryLedgerKind.AlbumNote))
            ?.text
            ?.takeIf { it.isNotBlank() },
        songs = songs,
        at = sorted.first().at,
    )
}

/**
 * The Cards and List views' items, newest first: an album for every album with a title, score, review or
 * scored song; a song for every song with a note (with its score, if scored). A score whose album Yoin never
 * learned stands as a song of its own, so it isn't lost.
 */
fun memoryLedgerItems(entries: List<MemoryLedgerEntry>): List<MemoryLedgerItem> {
    val out = ArrayList<MemoryLedgerItem>()
    entries.filter { it.kind != MemoryLedgerKind.SongNote && it.album != null }
        .groupBy { it.album!!.key }
        .forEach { (_, group) -> out += albumItem(group.first().album!!, group) }
    val scores = entries.filter { it.kind == MemoryLedgerKind.TrackRating && it.score != null }
        .sortedWith(newestFirst)
        .distinctBy { it.songKey() }
        .associateBy { it.songKey() }
    val noted = HashSet<String>()
    entries.filter { it.kind == MemoryLedgerKind.SongNote }
        .groupBy { it.songKey() }
        .forEach { (key, notes) ->
            noted += key
            val newest = notes.sortedWith(newestFirst).first()
            val score = scores[key]
            out += MemoryLedgerItem.Song(
                key = key,
                profileId = newest.profileId,
                title = newest.trackTitle ?: score?.trackTitle,
                album = newest.album ?: score?.album,
                score = score?.score,
                note = newest,
                notes = notes.size,
                at = maxOf(newest.at, score?.at ?: Long.MIN_VALUE),
            )
        }
    scores.values.filter { it.album == null && it.songKey() !in noted }.forEach { score ->
        out += MemoryLedgerItem.Song(
            key = score.songKey(),
            profileId = score.profileId,
            title = score.trackTitle,
            album = null,
            score = score.score,
            note = null,
            notes = 0,
            at = score.at,
        )
    }
    return out.sortedFor(MemoryLedgerSort.Recent, naturalOrder<String>())
}

/** [items] in [sort]'s order; [names] compares names (a locale's collator). Unscored items go last by score. */
fun List<MemoryLedgerItem>.sortedFor(sort: MemoryLedgerSort, names: Comparator<in String>): List<MemoryLedgerItem> {
    val recent = compareByDescending<MemoryLedgerItem> { it.at }.thenBy { it.key }
    return when (sort) {
        MemoryLedgerSort.Recent -> sortedWith(recent)
        MemoryLedgerSort.Score -> sortedWith(
            compareBy<MemoryLedgerItem> { it.score == null }
                .thenByDescending { it.score ?: 0f }
                .then(recent),
        )
        MemoryLedgerSort.Name -> sortedWith(
            // Nameless (a song never named) after the named.
            compareBy<MemoryLedgerItem> { it.name.isBlank() }
                .thenBy(names) { it.name }
                .then(recent),
        )
    }
}

sealed interface MemoryTimelineRow {
    val key: String

    data class Month(val month: YearMonth, val count: Int) : MemoryTimelineRow {
        override val key: String get() = "month:$month"
    }

    /** An album's card for [month]: what the month kept on it. [end]: it sits at the far side (they alternate). */
    data class Album(val month: YearMonth, val item: MemoryLedgerItem.Album, val end: Boolean) : MemoryTimelineRow {
        override val key: String get() = "album:$month:${item.album.key}"
    }

    /** A small card: a song note (its album known or not). */
    data class Note(val entry: MemoryLedgerEntry) : MemoryTimelineRow {
        override val key: String get() = "note:${entry.id}"
    }

    /**
     * The month's song scores whose album nobody could name, together on one card (owner 2026-11-12: a run of
     * identical 「歌曲评分 · 不知道是哪张专辑」 rows was tiring to read). Newest first.
     */
    data class Scores(val month: YearMonth, val entries: List<MemoryLedgerEntry>) : MemoryTimelineRow {
        override val key: String get() = "scores:$month"
    }

    /** Empty months, newest first: [from] is the later month, [to] the earlier (the same for one month). */
    data class Quiet(val from: YearMonth, val to: YearMonth) : MemoryTimelineRow {
        override val key: String get() = "quiet:$from:$to"
    }
}

/**
 * The timeline for [entries], from [today]'s month back to the oldest entry's. In a month, an album's card
 * stands where its newest mark falls; song notes stand on their own between the cards.
 */
fun memoryTimelineRows(entries: List<MemoryLedgerEntry>, today: LocalDate, zone: ZoneId): List<MemoryTimelineRow> {
    if (entries.isEmpty()) return emptyList()
    val byMonth = entries.groupBy { YearMonth.from(it.localDate(zone)) }
    val newest = maxOf(YearMonth.from(today), byMonth.keys.max())
    val oldest = byMonth.keys.min()
    val rows = ArrayList<MemoryTimelineRow>()
    var quietFrom: YearMonth? = null
    var quietTo: YearMonth? = null
    fun flushQuiet() {
        val from = quietFrom ?: return
        rows += MemoryTimelineRow.Quiet(from, quietTo ?: from)
        quietFrom = null
        quietTo = null
    }
    var cards = 0
    var month = newest
    while (month >= oldest) {
        val inMonth = byMonth[month].orEmpty().sortedWith(newestFirst)
        if (inMonth.isEmpty()) {
            if (quietFrom == null) quietFrom = month
            quietTo = month
        } else {
            flushQuiet()
            rows += MemoryTimelineRow.Month(month, inMonth.size)
            val albums = inMonth.filter { it.kind != MemoryLedgerKind.SongNote && it.album != null }
                .groupBy { it.album!!.key }
            val shown = HashSet<String>()
            val orphanScores = inMonth.filter { it.kind == MemoryLedgerKind.TrackRating && it.album == null }
            inMonth.forEach { entry ->
                val album = entry.album
                if (entry.kind == MemoryLedgerKind.TrackRating && album == null) {
                    // One card for all of them, where the newest falls.
                    if (entry === orphanScores.first()) rows += MemoryTimelineRow.Scores(month, orphanScores)
                } else if (entry.kind == MemoryLedgerKind.SongNote || album == null) {
                    rows += MemoryTimelineRow.Note(entry)
                } else if (shown.add(album.key)) {
                    val item = albumItem(album, albums.getValue(album.key))
                    rows += MemoryTimelineRow.Album(month, item, end = cards++ % 2 == 1)
                }
            }
        }
        month = month.minusMonths(1)
    }
    flushQuiet()
    return rows
}
