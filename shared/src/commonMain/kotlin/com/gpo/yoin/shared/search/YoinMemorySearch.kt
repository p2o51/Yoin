package com.gpo.yoin.shared.search

import com.gpo.yoin.shared.data.model.MediaRef
import com.gpo.yoin.shared.memory.MemoryLedgerAlbum
import com.gpo.yoin.shared.memory.MemoryLedgerEntry
import com.gpo.yoin.shared.memory.MemoryLedgerKind
import com.gpo.yoin.shared.memory.ledgerAlbumKey

/*
 * The Swift facade over the Memories & notes search core (docs/search.md; Android 0.6.3: the Memories
 * filter, the tiled All page, play from a note's second). Flat data classes and enums only, no default
 * arguments, no sealed types, no generics, no IntRange; offsets are UTF-16 code units (NSString / String.utf16)
 * and a highlight is [start, end) with -1 / -1 for none. Calls that read the platform's ledger are `throws` in Swift.
 *
 * iOS keeps the views, playback, the account switch and its ledger (Data/Memories); it hands the facade its
 * ledger through [YoinMemorySearchLedger] and the service's rows by name, and gets back indexes into those rows.
 */

enum class YoinLedgerKind { AlbumNote, SongNote, Review, Title, AlbumRating, TrackRating }

/** An album in one account, as the ledger names it. [key] = `profileId|provider|albumId`. */
data class YoinLedgerAlbum(
    val profileId: String,
    val provider: String,
    val albumId: String,
    val name: String,
    val artist: String,
    val coverKey: String?,
) {
    val key: String get() = ledgerAlbumKey(profileId, provider, albumId)
}

/** One ledger entry (Android's MemoryLedgerEntry, iOS's LedgerEntry). */
data class YoinLedgerEntry(
    val id: String,
    val kind: YoinLedgerKind,
    val at: Long,
    val profileId: String,
    val album: YoinLedgerAlbum?,
    /** A note's or review's text (NoteMarkup; searched as its plain words), or the title itself. */
    val text: String?,
    val trackTitle: String?,
    val songId: String?,
    val songProvider: String?,
    val positionMs: Long?,
    val score: Float?,
)

/** An AI-written memory title for one album in one account. */
data class YoinAiTitle(val profileId: String, val provider: String, val albumId: String, val title: String)

/** Where search reads the platform's ledger. Swift implements it; a throw ends the call with that error. */
interface YoinMemorySearchLedger {
    /** Every counted account's entries (a removed account's left out). */
    @Throws(Throwable::class)
    fun entries(): List<YoinLedgerEntry>

    @Throws(Throwable::class)
    fun aiTitles(): List<YoinAiTitle>
}

/** How well a query met a field, weakest first. */
enum class YoinSearchStrength { None, Contains, WordPrefix, Prefix, Exact }

/** What kind of field a hit was in, weakest first; compared before strength. */
enum class YoinSearchTier { Incidental, Body, Secondary, Primary }

/** A query against one text: [start] until [end] in the text as given; -1 / -1 without a hit. */
data class YoinSearchMatch(val strength: YoinSearchStrength, val start: Int, val end: Int)

/** Text to show for a hit, [highlightStart] until [highlightEnd] marked; -1 / -1 for none. */
data class YoinSearchFragment(val text: String, val highlightStart: Int, val highlightEnd: Int)

enum class YoinMemoryHitField { Title, Album, Review, AlbumNote }

enum class YoinLedgerMarkKind { AlbumRating, TrackAverage, NotesOnly }

/** The cover's memory mark (Home's rule). */
data class YoinLedgerMark(val kind: YoinLedgerMarkKind, val score: Float?, val noteCount: Int)

/** An album memory the query met, at its best hit. */
data class YoinMemoryHit(
    val album: YoinLedgerAlbum,
    val field: YoinMemoryHitField,
    /** The entry the hit is in (the diary entry to open at); null for the album's name or an AI title. */
    val entryId: String?,
    val fragment: YoinSearchFragment,
    val tier: YoinSearchTier,
    val strength: YoinSearchStrength,
    val title: String?,
    val albumScore: Float?,
    val lastAt: Long,
    val isOtherAccount: Boolean,
    val mark: YoinLedgerMark?,
)

/** A song note the query met. */
data class YoinNoteHit(
    val entryId: String,
    val profileId: String,
    val provider: String?,
    val songId: String?,
    val trackTitle: String?,
    val album: YoinLedgerAlbum?,
    val positionMs: Long?,
    val fragment: YoinSearchFragment,
    val tier: YoinSearchTier,
    val strength: YoinSearchStrength,
    val at: Long,
    val isOtherAccount: Boolean,
)

/** What the ledger answered, each list best first. */
data class YoinMemorySearchResult(val memories: List<YoinMemoryHit>, val notes: List<YoinNoteHit>) {
    val isEmpty: Boolean get() = memories.isEmpty() && notes.isEmpty()
}

/** The memory kept on a returned album ([provider] + [albumId]) in the account in use. */
data class YoinMemoryAttachment(
    val provider: String,
    val albumId: String,
    val albumKey: String,
    val title: String?,
    val albumScore: Float?,
    val lastAt: Long,
    val mark: YoinLedgerMark?,
)

/** The service's rows, by name, in its order. */
data class YoinSearchSong(val provider: String, val rawId: String, val title: String?, val artist: String?, val album: String?)

data class YoinSearchAlbum(val provider: String, val rawId: String, val name: String, val artist: String?)

data class YoinSearchArtist(val provider: String, val rawId: String, val name: String)

data class YoinSearchPlaylist(val provider: String, val rawId: String, val name: String, val owner: String?)

enum class YoinSearchSection { Songs, Albums, Artists, Playlists }

enum class YoinSearchTileKind { Song, Note, Album, Memory, Artist, Playlist }

/**
 * One tile. [index] points into the rows given to [YoinMemorySearch.page] for its kind (songs, albums,
 * artists, playlists); -1 for [YoinSearchTileKind.Note] and [YoinSearchTileKind.Memory].
 */
data class YoinSearchTile(
    val kind: YoinSearchTileKind,
    val key: String,
    val index: Int,
    val wide: Boolean,
    val relevant: Boolean,
    val tier: YoinSearchTier,
    val strength: YoinSearchStrength,
    /** Song: this account's note on it the query met (shown beside the cover); Note: the note. */
    val note: YoinNoteHit?,
    /** Album: its memory's words met the query; Memory: the hit. */
    val memory: YoinMemoryHit?,
    /** Album: the memory kept on it (its mark). */
    val attachment: YoinMemoryAttachment?,
)

/** At most one wide tile; [wideAtEnd] puts it after the singles. */
data class YoinSearchTileRow(val tiles: List<YoinSearchTile>, val wideAtEnd: Boolean)

data class YoinSearchSectionPage(
    val section: YoinSearchSection,
    /** Every tile, best first: what the section's own chip lists (folded rows last). */
    val tiles: List<YoinSearchTile>,
    /** What All shows of it. */
    val rows: List<YoinSearchTileRow>,
    val folded: Int,
    val more: Int,
    val tier: YoinSearchTier,
    val strength: YoinSearchStrength,
)

/** The best-match card: [index] into the rows of [kind] (Song, Album, Artist or Playlist). */
data class YoinSearchBestMatch(val kind: YoinSearchTileKind, val index: Int)

data class YoinSearchPage(
    val sections: List<YoinSearchSectionPage>,
    val best: YoinSearchBestMatch?,
    /** What the Memories chip lists, one per row, best first. */
    val memories: List<YoinSearchTile>,
) {
    val isEmpty: Boolean get() = sections.isEmpty() && memories.isEmpty()
}

/** The type chips, in chip order. */
enum class YoinSearchFilter { All, Songs, Albums, Memories, Artists, Playlists }

enum class YoinNoteOpenAction { PlaySong, OpenAlbum, None }

/**
 * Where opening a note leads. PlaySong: play [songProvider]/[songId], then seek to [seekToMs] once it is
 * current ([YoinMemorySearch.noteSeekReady], within [YoinMemorySearch.noteSeekTimeoutMs]). OpenAlbum: open
 * [albumProvider]/[albumId]'s scrapbook in [profileId]'s account (switch first when [switchAccount] and that
 * account still exists; the prompt names [subject]), at [songProvider]/[songId] when known.
 */
data class YoinNoteOpenTarget(
    val action: YoinNoteOpenAction,
    val songProvider: String?,
    val songId: String?,
    val albumProvider: String?,
    val albumId: String?,
    val profileId: String,
    val switchAccount: Boolean,
    val seekToMs: Long?,
    val subject: String?,
)

class YoinMemorySearch(private val ledger: YoinMemorySearchLedger) {

    // ── reading the platform's ledger ────────────────────────────────

    /** The memories and notes [query] meets across every account, best first. Blank reads nothing. */
    @Throws(Throwable::class)
    fun search(query: String, activeProfileId: String?): YoinMemorySearchResult {
        val parsed = SearchQuery(query)
        if (parsed.isBlank) return YoinMemorySearchResult(emptyList(), emptyList())
        return searchEntries(ledger.entries(), ledger.aiTitles(), query, activeProfileId)
    }

    /** The memories kept on [albums] (returned rows) in the account in use, in [albums]' order. */
    @Throws(Throwable::class)
    fun attachments(albums: List<YoinSearchAlbum>, activeProfileId: String?): List<YoinMemoryAttachment> {
        if (albums.isEmpty() || activeProfileId == null) return emptyList()
        return attachmentsOf(albums, ledger.entries(), ledger.aiTitles(), activeProfileId)
    }

    /**
     * Everything that doesn't read the ledger: the same answers for the same input on every platform. Swift
     * calls it without a ledger: `YoinMemorySearch.companion.filters(…)`. A screen that searches and lays out
     * in one go can read the ledger once and pass the snapshot to [searchEntries] and [attachmentsOf].
     */
    companion object {
        fun searchEntries(
            entries: List<YoinLedgerEntry>,
            aiTitles: List<YoinAiTitle>,
            query: String,
            activeProfileId: String?,
        ): YoinMemorySearchResult =
            searchMemoryLedger(entries.map { it.toCore() }, SearchQuery(query), activeProfileId, aiTitleMap(aiTitles)).toFlat()

        fun attachmentsOf(
            albums: List<YoinSearchAlbum>,
            entries: List<YoinLedgerEntry>,
            aiTitles: List<YoinAiTitle>,
            activeProfileId: String?,
        ): List<YoinMemoryAttachment> {
            val refs = albums.map { MediaRef(it.provider, it.rawId) }
            val found = memoryAttachments(refs, entries.map { it.toCore() }, activeProfileId, aiTitleMap(aiTitles))
            return refs.distinct().mapNotNull { ref -> found[ref]?.toFlat(ref) }
        }

        /** The All page and the Memories list for [query]; tiles point back into the rows given. */
        fun page(
            query: String,
            songs: List<YoinSearchSong>,
            albums: List<YoinSearchAlbum>,
            artists: List<YoinSearchArtist>,
            playlists: List<YoinSearchPlaylist>,
            result: YoinMemorySearchResult,
            attachments: List<YoinMemoryAttachment>,
            activeProfileId: String?,
            columns: Int,
            maxRows: Int,
            maxWide: Int,
        ): YoinSearchPage {
            val layout = layoutSearchPage(
                query = SearchQuery(query),
                rows = SearchServiceRows(
                    songs = songs.map { SearchSongRow(MediaRef(it.provider, it.rawId), it.title, it.artist, it.album) },
                    albums = albums.map { SearchAlbumRow(MediaRef(it.provider, it.rawId), it.name, it.artist) },
                    artists = artists.map { SearchArtistRow(MediaRef(it.provider, it.rawId), it.name) },
                    playlists = playlists.map { SearchPlaylistRow(MediaRef(it.provider, it.rawId), it.name, it.owner) },
                ),
                memories = result.toCore(),
                attachments = attachments.associate { MediaRef(it.provider, it.albumId) to it.toCore() },
                activeProfileId = activeProfileId,
                columns = columns,
                maxRows = maxRows,
                maxWide = maxWide,
            )
            val attachmentByRef = attachments.associateBy { MediaRef(it.provider, it.albumId) }
            fun tile(t: SearchPageTile) = YoinSearchTile(
                kind = t.kind.toFlat(),
                key = t.key,
                index = t.index,
                wide = t.wide,
                relevant = t.relevant,
                tier = t.score.tier.toFlat(),
                strength = t.score.strength.toFlat(),
                note = t.note?.toFlat(),
                memory = t.hit?.toFlat(),
                attachment = if (t.kind == SearchTileKind.Album) attachmentByRef[MediaRef(albums[t.index].provider, albums[t.index].rawId)] else null,
            )
            return YoinSearchPage(
                sections = layout.sections.map { s ->
                    YoinSearchSectionPage(
                        section = YoinSearchSection.valueOf(s.section.name),
                        tiles = s.tiles.map(::tile),
                        rows = s.allRows.map { row -> YoinSearchTileRow(row.tiles.map(::tile), row.wideAtEnd) },
                        folded = s.folded,
                        more = s.more,
                        tier = s.score.tier.toFlat(),
                        strength = s.score.strength.toFlat(),
                    )
                },
                best = layout.best?.let { YoinSearchBestMatch(it.kind.toFlat(), it.index) },
                memories = layout.memories.map(::tile),
            )
        }

        /** The chips offered ([searchFilters]). */
        fun filters(canSearchPlaylists: Boolean, canReadPlaylists: Boolean, savedLibrary: Boolean): List<YoinSearchFilter> =
            searchFilters(canSearchPlaylists, canReadPlaylists, savedLibrary).map { YoinSearchFilter.valueOf(it.name) }

        /** How many of [available] service rows of [section] [filter] lists, from the top. */
        fun shownRows(filter: YoinSearchFilter, section: YoinSearchSection, available: Int): Int =
            SearchFilter.valueOf(filter.name).shownRows(SearchSection.valueOf(section.name), available)

        /** Where opening [note] leads; [returnedSongProvider]/[returnedSongId] = the returned song its tile is on, if any. */
        fun noteTarget(note: YoinNoteHit, returnedSongProvider: String?, returnedSongId: String?, activeProfileId: String?): YoinNoteOpenTarget {
            val returned = if (returnedSongProvider != null && returnedSongId != null) MediaRef(returnedSongProvider, returnedSongId) else null
            val target = noteOpenTarget(note.toCore(), returned, activeProfileId)
            return YoinNoteOpenTarget(
                action = YoinNoteOpenAction.valueOf(target.action.name),
                songProvider = target.song?.provider,
                songId = target.song?.rawId,
                albumProvider = target.album?.provider,
                albumId = target.album?.rawId,
                profileId = target.profileId,
                switchAccount = target.switchAccount,
                seekToMs = target.seekToMs,
                subject = target.subject,
            )
        }

        fun noteSeekReady(currentRawId: String?, durationMs: Long, targetRawId: String): Boolean =
            com.gpo.yoin.shared.search.noteSeekReady(currentRawId, durationMs, targetRawId)

        val noteSeekTimeoutMs: Long get() = NOTE_SEEK_TIMEOUT_MS
        val allMaxRows: Int get() = SEARCH_ALL_MAX_ROWS
        val allMaxWide: Int get() = SEARCH_ALL_MAX_WIDE
        val allRowsPerType: Int get() = SEARCH_ALL_ROWS_PER_TYPE
        val fragmentMaxChars: Int get() = FRAGMENT_MAX_CHARS

        /** [text] folded the way search compares it (case, width, Latin accents, whitespace). */
        fun fold(text: String): String = FoldedText.of(text).text

        fun match(query: String, text: String?): YoinSearchMatch {
            val m = SearchQuery(query).match(text)
            val range = m.originalRange
            return if (range.isEmpty()) {
                YoinSearchMatch(m.strength.toFlat(), -1, -1)
            } else {
                YoinSearchMatch(m.strength.toFlat(), range.first, range.last + 1)
            }
        }

        /** What a row shows of [text] for [query] (the hit and a little before it, up to [maxChars]); null without a hit. */
        fun fragment(query: String, text: String?, maxChars: Int): YoinSearchFragment? {
            val q = SearchQuery(query)
            val m = q.match(text)
            if (m.strength == MatchStrength.None) return null
            return q.fragment(m, maxChars)?.toFlat()
        }

        /** A note's or review's words as search reads them (NoteMarkup's plain text). */
        fun plainWords(text: String): String = noteWords(text)

        fun albumKey(profileId: String, provider: String, albumId: String): String = ledgerAlbumKey(profileId, provider, albumId)
    }
}

// ── conversions ──────────────────────────────────────────────────

private fun aiTitleMap(titles: List<YoinAiTitle>): Map<String, String> =
    titles.associate { ledgerAlbumKey(it.profileId, it.provider, it.albumId) to it.title }

private fun YoinLedgerAlbum.toCore() = MemoryLedgerAlbum(profileId, provider, albumId, name, artist, coverKey)

private fun MemoryLedgerAlbum.toFlat() = YoinLedgerAlbum(profileId, provider, albumId, name, artist, coverKey)

private fun YoinLedgerEntry.toCore() = MemoryLedgerEntry(
    id = id,
    kind = MemoryLedgerKind.valueOf(kind.name),
    at = at,
    profileId = profileId,
    album = album?.toCore(),
    text = text,
    trackTitle = trackTitle,
    songId = songId,
    songProvider = songProvider,
    positionMs = positionMs,
    score = score,
)

private fun MatchStrength.toFlat() = YoinSearchStrength.valueOf(name)

private fun MatchTier.toFlat() = YoinSearchTier.valueOf(name)

private fun SearchTileKind.toFlat() = YoinSearchTileKind.valueOf(name)

private fun score(tier: YoinSearchTier, strength: YoinSearchStrength) =
    SearchScore(MatchTier.valueOf(tier.name), MatchStrength.valueOf(strength.name))

private fun SearchFragment.toFlat() =
    if (highlight.isEmpty()) YoinSearchFragment(text, -1, -1) else YoinSearchFragment(text, highlight.first, highlight.last + 1)

private fun YoinSearchFragment.toCore() =
    SearchFragment(text, if (highlightStart < 0 || highlightEnd <= highlightStart) IntRange.EMPTY else highlightStart until highlightEnd)

private fun LedgerMark.toFlat() = YoinLedgerMark(YoinLedgerMarkKind.valueOf(kind.name), score, noteCount)

private fun YoinLedgerMark.toCore() = LedgerMark(LedgerMarkKind.valueOf(kind.name), score, noteCount)

private fun MemorySearchHit.toFlat() = YoinMemoryHit(
    album = album.toFlat(),
    field = YoinMemoryHitField.valueOf(field.name),
    entryId = entryId,
    fragment = fragment.toFlat(),
    tier = score.tier.toFlat(),
    strength = score.strength.toFlat(),
    title = title,
    albumScore = albumScore,
    lastAt = lastAt,
    isOtherAccount = isOtherAccount,
    mark = mark?.toFlat(),
)

private fun YoinMemoryHit.toCore() = MemorySearchHit(
    album = album.toCore(),
    field = MemoryHitField.valueOf(field.name),
    entryId = entryId,
    fragment = fragment.toCore(),
    score = score(tier, strength),
    title = title,
    albumScore = albumScore,
    lastAt = lastAt,
    isOtherAccount = isOtherAccount,
    mark = mark?.toCore(),
)

private fun NoteSearchHit.toFlat() = YoinNoteHit(
    entryId = entryId,
    profileId = profileId,
    provider = provider,
    songId = songId,
    trackTitle = trackTitle,
    album = album?.toFlat(),
    positionMs = positionMs,
    fragment = fragment.toFlat(),
    tier = score.tier.toFlat(),
    strength = score.strength.toFlat(),
    at = at,
    isOtherAccount = isOtherAccount,
)

private fun YoinNoteHit.toCore() = NoteSearchHit(
    entryId = entryId,
    profileId = profileId,
    provider = provider,
    songId = songId,
    trackTitle = trackTitle,
    album = album?.toCore(),
    positionMs = positionMs,
    fragment = fragment.toCore(),
    score = score(tier, strength),
    at = at,
    isOtherAccount = isOtherAccount,
)

private fun MemorySearchMatches.toFlat() = YoinMemorySearchResult(memories.map { it.toFlat() }, notes.map { it.toFlat() })

private fun YoinMemorySearchResult.toCore() = MemorySearchMatches(memories.map { it.toCore() }, notes.map { it.toCore() })

private fun MemoryAttachment.toFlat(ref: MediaRef) =
    YoinMemoryAttachment(ref.provider, ref.rawId, albumKey, title, albumScore, lastAt, mark?.toFlat())

private fun YoinMemoryAttachment.toCore() = MemoryAttachment(albumKey, title, albumScore, lastAt, mark?.toCore())
