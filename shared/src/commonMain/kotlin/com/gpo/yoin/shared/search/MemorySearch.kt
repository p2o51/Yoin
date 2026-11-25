package com.gpo.yoin.shared.search

import com.gpo.yoin.shared.data.model.MediaRef
import com.gpo.yoin.shared.memory.MemoryLedgerAlbum
import com.gpo.yoin.shared.memory.MemoryLedgerEntry
import com.gpo.yoin.shared.memory.MemoryLedgerKind
import com.gpo.yoin.shared.memory.ledgerAlbumKey
import com.gpo.yoin.shared.notes.NoteMarkup

/*
 * Memories and notes in library search (docs/search.md). They are read from the memory ledger, every
 * account's at once (#178, owner 2026-11-08): a search on one account finds what was written on another,
 * and "no results" never depends on the account in use. Read-only; local; no service is asked.
 */

/** Which of a memory's own words the query met. */
enum class MemoryHitField(val tier: MatchTier) {
    /** The memory's title: the one the listener gave it, else the AI's. */
    Title(MatchTier.Primary),

    /** The album's name or artist: shown on the album row when it is there ([memoryAttachments]). */
    Album(MatchTier.Secondary),

    /** The album review. */
    Review(MatchTier.Body),

    /** One of the album's notes (its diary). */
    AlbumNote(MatchTier.Body),
}

/** What kind of mark an album's cover wears (Home's rule, HomeAlbumMemoryMark.kt): your score, else your tracks' average, else notes. */
enum class LedgerMarkKind { AlbumRating, TrackAverage, NotesOnly }

/** An album's memory mark: what Home's HomeAlbumMemoryMark draws. Reviews and titles alone wear none. */
data class LedgerMark(val kind: LedgerMarkKind, val score: Float?, val noteCount: Int)

/** The mark for one album's ledger [entries] (any kinds), or null when nothing on it earns one. */
fun ledgerMarkOf(entries: List<MemoryLedgerEntry>): LedgerMark? {
    val notes = entries.count { it.kind == MemoryLedgerKind.AlbumNote || it.kind == MemoryLedgerKind.SongNote }
    entries.filter { it.kind == MemoryLedgerKind.AlbumRating }.maxByOrNull { it.at }?.score?.let {
        return LedgerMark(LedgerMarkKind.AlbumRating, it, notes)
    }
    val tracks = entries.filter { it.kind == MemoryLedgerKind.TrackRating }.mapNotNull { it.score }
    if (tracks.isNotEmpty()) return LedgerMark(LedgerMarkKind.TrackAverage, tracks.average().toFloat(), notes)
    return if (notes > 0) LedgerMark(LedgerMarkKind.NotesOnly, null, notes) else null
}

/** An album memory the query met: one row per album in one account, at its best hit. */
data class MemorySearchHit(
    val album: MemoryLedgerAlbum,
    val field: MemoryHitField,
    /** The ledger entry the hit is in ([MemoryLedgerEntry.id]): the diary entry to open at. Null for a hit on the album's name or an AI title. */
    val entryId: String?,
    /** What to show, with the hit marked. */
    val fragment: SearchFragment,
    val score: SearchScore,
    /** The memory's title when it has one (the listener's, else the AI's). */
    val title: String?,
    /** The album's score, 0–10, when it has one. */
    val albumScore: Float?,
    /** The memory's newest mark (epoch ms). */
    val lastAt: Long,
    /** Kept on an account other than the one in use: what earns a source badge. */
    val isOtherAccount: Boolean,
    /** The cover's memory mark. */
    val mark: LedgerMark? = null,
)

/** A song note the query met. Opening it plays the song from [positionMs] ([noteOpenTarget]). */
data class NoteSearchHit(
    val entryId: String,
    val profileId: String,
    /** The song's provider and raw id: what plays it. */
    val provider: String?,
    val songId: String?,
    val trackTitle: String?,
    val album: MemoryLedgerAlbum?,
    /** Where in the song it was written (ms); null for an unanchored note. */
    val positionMs: Long?,
    val fragment: SearchFragment,
    val score: SearchScore,
    val at: Long,
    val isOtherAccount: Boolean,
)

/** Everything the ledger answered for one query, each list best first. */
data class MemorySearchMatches(
    val memories: List<MemorySearchHit> = emptyList(),
    val notes: List<NoteSearchHit> = emptyList(),
) {
    val isEmpty: Boolean get() = memories.isEmpty() && notes.isEmpty()

    /**
     * Without the memories whose only hit is their album's name, where that album is a row of the
     * results already ([attached] keys, [MemoryLedgerAlbum.key]): it carries the memory there.
     * A hit in the memory's own words stays even then (docs/search.md: both can happen).
     */
    fun withoutAttached(attached: Set<String>): MemorySearchMatches =
        if (attached.isEmpty()) {
            this
        } else {
            copy(memories = memories.filterNot { it.field == MemoryHitField.Album && it.album.key in attached })
        }

    companion object {
        val Empty = MemorySearchMatches()
    }
}

/** What an album row shows of the memory kept on it. */
data class MemoryAttachment(
    val albumKey: String,
    val title: String?,
    val albumScore: Float?,
    val lastAt: Long,
    /** The cover's memory mark; null when only a review or title is kept (they wear none, as on Home). */
    val mark: LedgerMark? = null,
)

/** The ledger kinds that make an album a memory: any writing, or an album score. A song's score alone doesn't. */
private val MemoryLedgerEntry.makesMemory: Boolean
    get() = kind.wrote || kind == MemoryLedgerKind.AlbumRating

/**
 * Searches the ledger's [entries] (every account's) for [query]. [aiTitles] holds AI-written memory titles
 * by album key; the listener's own title (a [MemoryLedgerKind.Title] entry) wins over it. Same-strength hits
 * from [activeProfileId] come first, then newer ones; the account never lifts a weaker hit over a stronger one.
 */
fun searchMemoryLedger(
    entries: List<MemoryLedgerEntry>,
    query: SearchQuery,
    activeProfileId: String?,
    aiTitles: Map<String, String> = emptyMap(),
): MemorySearchMatches {
    if (query.isBlank || entries.isEmpty()) return MemorySearchMatches.Empty

    val byAlbum = LinkedHashMap<String, MutableList<MemoryLedgerEntry>>()
    val everything = HashMap<String, MutableList<MemoryLedgerEntry>>()
    val notes = ArrayList<NoteSearchHit>()
    for (entry in entries) {
        if (entry.kind == MemoryLedgerKind.SongNote) {
            noteHit(entry, query, activeProfileId)?.let(notes::add)
        }
        val album = entry.album ?: continue
        everything.getOrPut(album.key) { ArrayList() } += entry
        if (entry.makesMemory && entry.kind != MemoryLedgerKind.SongNote) {
            byAlbum.getOrPut(album.key) { ArrayList() } += entry
        }
    }

    val memories = byAlbum.mapNotNull { (key, albumEntries) ->
        memoryHit(albumEntries, query, activeProfileId, aiTitles)?.copy(mark = ledgerMarkOf(everything[key].orEmpty()))
    }
    return MemorySearchMatches(
        memories = memories.sortedWith(
            compareByDescending<MemorySearchHit> { it.score }
                .thenBy { it.isOtherAccount }
                .thenByDescending { it.lastAt }
                .thenBy { it.album.key },
        ),
        notes = notes.sortedWith(
            compareByDescending<NoteSearchHit> { it.score }
                .thenBy { it.isOtherAccount }
                .thenByDescending { it.at }
                .thenBy { it.entryId },
        ),
    )
}

private fun memoryHit(
    entries: List<MemoryLedgerEntry>,
    query: SearchQuery,
    activeProfileId: String?,
    aiTitles: Map<String, String>,
): MemorySearchHit? {
    val album = entries.first().album ?: return null
    val userTitle = entries.filter { it.kind == MemoryLedgerKind.Title }.maxByOrNull { it.at }
    val title = userTitle?.text?.takeIf { it.isNotBlank() } ?: aiTitles[album.key]?.takeIf { it.isNotBlank() }

    data class Candidate(val field: MemoryHitField, val entryId: String?, val match: FieldMatch, val at: Long)

    val candidates = buildList {
        add(Candidate(MemoryHitField.Title, userTitle?.id, query.match(title), userTitle?.at ?: 0L))
        val name = query.match(album.name)
        val artist = query.match(album.artist)
        add(Candidate(MemoryHitField.Album, null, if (name.strength >= artist.strength) name else artist, 0L))
        entries.forEach { entry ->
            val field = when (entry.kind) {
                MemoryLedgerKind.Review -> MemoryHitField.Review
                MemoryLedgerKind.AlbumNote -> MemoryHitField.AlbumNote
                else -> return@forEach
            }
            add(Candidate(field, entry.id, query.match(entry.text?.let(::noteWords)), entry.at))
        }
    }
    // Best tier and strength; between equals, the newer writing.
    val best = candidates
        .filter { it.match.strength != MatchStrength.None }
        .maxWithOrNull(
            compareBy<Candidate> { SearchScore(it.field.tier, it.match.strength) }.thenBy { it.at },
        ) ?: return null
    val fragment = query.fragment(best.match) ?: return null
    return MemorySearchHit(
        album = album,
        field = best.field,
        entryId = best.entryId,
        fragment = fragment,
        score = SearchScore(best.field.tier, best.match.strength),
        title = title,
        albumScore = entries.filter { it.kind == MemoryLedgerKind.AlbumRating }.maxByOrNull { it.at }?.score,
        lastAt = entries.maxOf { it.at },
        isOtherAccount = activeProfileId != null && album.profileId != activeProfileId,
    )
}

private fun noteHit(entry: MemoryLedgerEntry, query: SearchQuery, activeProfileId: String?): NoteSearchHit? {
    val body = query.match(entry.text?.let(::noteWords))
    // The song's name and artist are weaker than any hit in the note's own words, yet a note on the
    // song typed is still worth showing.
    val title = query.match(entry.trackTitle)
    val artist = query.match(entry.album?.artist)
    val (match, tier) = when {
        body.strength != MatchStrength.None -> body to MatchTier.Body
        title.strength >= artist.strength && title.strength != MatchStrength.None -> title to MatchTier.Incidental
        artist.strength != MatchStrength.None -> artist to MatchTier.Incidental
        else -> return null
    }
    // A hit on the song's name shows the note itself, unmarked: the name is on the row already.
    val fragment = if (tier == MatchTier.Body) {
        query.fragment(match)
    } else {
        query.fragment(FieldMatch(MatchStrength.Contains, FoldedText.of(entry.text?.let(::noteWords).orEmpty())))
            ?.copy(highlight = IntRange.EMPTY)
    } ?: return null
    return NoteSearchHit(
        entryId = entry.id,
        profileId = entry.profileId,
        provider = entry.songProvider ?: entry.album?.provider,
        songId = entry.songId,
        trackTitle = entry.trackTitle,
        album = entry.album,
        positionMs = entry.positionMs,
        fragment = fragment,
        score = SearchScore(tier, match.strength),
        at = entry.at,
        isOtherAccount = activeProfileId != null && entry.profileId != activeProfileId,
    )
}

/**
 * The memories kept on [albums] (a search's album rows, as provider + raw id), by album. Phase 1 attaches
 * only on the exact album in the account in use, `(profileId, provider, albumId)`: never by a similar title,
 * since a wrong attachment is worse than a missing one (docs/search.md). [aiTitles] as in [searchMemoryLedger].
 */
fun memoryAttachments(
    albums: List<MediaRef>,
    entries: List<MemoryLedgerEntry>,
    activeProfileId: String?,
    aiTitles: Map<String, String> = emptyMap(),
): Map<MediaRef, MemoryAttachment> {
    if (activeProfileId == null || albums.isEmpty()) return emptyMap()
    val wanted = albums.associateBy { ledgerAlbumKey(activeProfileId, it.provider, it.rawId) }
    val found = HashMap<String, MutableList<MemoryLedgerEntry>>()
    val everything = HashMap<String, MutableList<MemoryLedgerEntry>>()
    entries.forEach { entry ->
        val key = entry.album?.key ?: return@forEach
        if (key !in wanted) return@forEach
        everything.getOrPut(key) { ArrayList() } += entry
        if (entry.makesMemory && entry.kind != MemoryLedgerKind.SongNote) {
            found.getOrPut(key) { ArrayList() } += entry
        }
    }
    // A memory, or anything that marks the cover on Home (a song's note or score counts there).
    return everything.mapNotNull { (key, all) ->
        val album = wanted[key] ?: return@mapNotNull null
        val kept = found[key].orEmpty()
        val mark = ledgerMarkOf(all)
        if (kept.isEmpty() && mark == null) return@mapNotNull null
        val title = kept.filter { it.kind == MemoryLedgerKind.Title }.maxByOrNull { it.at }?.text
            ?: aiTitles[key]
        album to MemoryAttachment(
            albumKey = key,
            title = title?.takeIf { it.isNotBlank() },
            albumScore = kept.filter { it.kind == MemoryLedgerKind.AlbumRating }.maxByOrNull { it.at }?.score,
            lastAt = all.maxOf { it.at },
            mark = mark,
        )
    }.toMap()
}

/** A note's or review's words for search: mention names, no Markdown marks, no `yoin:` links (NoteMarkup). */
fun noteWords(text: String): String = NoteMarkup.plainText(text)
