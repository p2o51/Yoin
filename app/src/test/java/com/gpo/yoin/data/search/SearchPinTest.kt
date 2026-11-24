package com.gpo.yoin.data.search

import com.gpo.yoin.shared.search.SearchQuery
import com.gpo.yoin.shared.search.FoldedText
import com.gpo.yoin.shared.search.SearchScore
import com.gpo.yoin.shared.search.SearchFragment
import com.gpo.yoin.shared.search.fragment
import com.gpo.yoin.shared.search.ledgerMarkOf
import com.gpo.yoin.shared.search.NoteSearchHit
import com.gpo.yoin.shared.search.searchMemoryLedger
import com.gpo.yoin.shared.search.SEARCH_ALL_MAX_ROWS
import com.gpo.yoin.shared.search.SEARCH_ALL_MAX_WIDE
import com.gpo.yoin.shared.memory.MemoryLedgerAlbum
import com.gpo.yoin.shared.memory.MemoryLedgerEntry
import com.gpo.yoin.shared.memory.MemoryLedgerKind
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.ui.library.LibrarySearchFilter
import com.gpo.yoin.ui.library.LibrarySearchScope
import com.gpo.yoin.ui.library.searchFiltersFor
import com.gpo.yoin.ui.library.shownFor
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins what library search answers for fixed data (folding, matching, fragments, the ledger's hits, the
 * attachments, the All page, packing and the type chips), written by the code before its core moved to
 * :shared (parity phase 3). The move must leave every line as it is.
 *
 * File: app/src/test/resources/search/search-pin.txt. Regenerate only for an intentional change:
 * `YOIN_WRITE_SEARCH_PIN=1 ./gradlew :app:testDebugUnitTest --tests '*SearchPinTest*'`.
 */
class SearchPinTest {

    @Test
    fun should_answerAsPinned_when_searchingFixedData() {
        val actual = escapeLoneSurrogates(dump())
        val file = pinFile()
        if (System.getenv("YOIN_WRITE_SEARCH_PIN") == "1" || !file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(actual)
        }
        assertEquals(file.readText(), actual)
    }

    // A lone surrogate can't round-trip through UTF-8: written as \uXXXX.
    private fun escapeLoneSurrogates(text: String): String = buildString {
        text.forEachIndexed { i, c ->
            val paired = (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) ||
                (c.isLowSurrogate() && i > 0 && text[i - 1].isHighSurrogate())
            if (c.isSurrogate() && !paired) append("\\u%04X".format(c.code)) else append(c)
        }
    }

    private fun pinFile(): File {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app").isDirectory && File(it, "settings.gradle.kts").isFile }
        return File(root, "app/src/test/resources/search/search-pin.txt")
    }

    private fun dump(): String = buildString {
        fun line(vararg parts: Any?) = append(parts.joinToString(" ⟂ ")).append('\n')

        FOLDS.forEach { text -> line("fold", text, FoldedText.of(text).text) }
        MATCHES.forEach { (query, text) ->
            val q = SearchQuery(query)
            val m = q.match(text)
            line("match", query, text, m.strength, m.start, m.length, m.originalRange)
            line("fragment", query, text, q.fragment(m))
            line("fragment20", query, text, q.fragment(m, maxChars = 20))
        }
        val entries = ledger()
        QUERIES.forEach { query ->
            ACTIVE.forEach { active ->
                val matches = searchMemoryLedger(entries, SearchQuery(query), active, AI_TITLES)
                line("search", query, active)
                matches.memories.forEach { line("  memory", it) }
                matches.notes.forEach { line("  note", it) }
            }
        }
        val albums = serviceAlbums()
        ACTIVE.forEach { active ->
            val attached = memoryAttachments(albums, entries, active, AI_TITLES)
            line("attachments", active)
            attached.entries.sortedBy { it.key.toString() }.forEach { (id, a) -> line("  ", id, a) }
        }
        ledger().groupBy { it.album?.key }.entries.sortedBy { it.key.toString() }.forEach { (key, list) ->
            line("mark", key, ledgerMarkOf(list))
        }
        PAGES.forEach { (query, active, columns) ->
            val q = SearchQuery(query)
            val memories = searchMemoryLedger(entries, q, active, AI_TITLES)
            val attached = memoryAttachments(albums, entries, active, AI_TITLES)
            val withoutAttached = memories.withoutAttached(attached.values.mapTo(HashSet()) { it.albumKey })
            line("withoutAttached", query, active, withoutAttached.memories.map { it.album.key }, withoutAttached.isEmpty)
            listOf(serviceResults(), null).forEach { results ->
                listOf(SEARCH_ALL_MAX_ROWS to SEARCH_ALL_MAX_WIDE, 2 to 1, 0 to 0).forEach { (maxRows, maxWide) ->
                    val page = buildSearchPage(q, results, memories, attached, active, columns, maxRows, maxWide)
                    line("page", query, active, columns, maxRows, maxWide, results != null, page.isEmpty)
                    line("  best", page.bestMatch)
                    page.sections.forEach { section ->
                        line("  section", section.section, section.score, section.folded, section.more)
                        line("    tiles", section.tiles.map { it.key })
                        section.allRows.forEach { row -> line("    row", row.wideAtEnd, row.tiles.map { "${it.key}${if (it.wide) "[w]" else ""}" }) }
                        section.tiles.forEach { line("    tile", it) }
                    }
                    line("  memories", page.memories.map { it.key })
                }
            }
        }
        PACKS.forEach { (shape, columns, caps) ->
            val tiles = shape.mapIndexed { i, c -> if (c == 'w') wideTile("w$i") else singleTile("s$i") }
            val rows = packSearchTiles(tiles, columns, caps.first, caps.second)
            line("pack", shape, columns, caps, rows.map { row -> "${if (row.wideAtEnd) ">" else "<"}" + row.tiles.joinToString(",") { it.key.substringAfterLast(':') } })
        }
        val caps = listOf(
            emptySet(),
            setOf(Capability.SEARCH_PLAYLISTS),
            setOf(Capability.PLAYLISTS_READ),
            setOf(Capability.SEARCH_PLAYLISTS, Capability.PLAYLISTS_READ),
        )
        caps.forEach { set ->
            LibrarySearchScope.entries.forEach { scope ->
                line("filters", set.sorted(), scope, searchFiltersFor(set, scope))
            }
        }
        val many = SearchResults(
            tracks = List(45) { track("t$it", "Song $it") },
            albums = List(3) { serviceAlbum("a$it", "Album $it") },
            artists = List(41) { Artist(MediaId("subsonic", "r$it"), "Artist $it", albumCount = null, coverArt = null) },
        )
        LibrarySearchFilter.entries.forEach { filter ->
            val shown = many.shownFor(filter)
            line("shown", filter, shown.tracks.size, shown.albums.size, shown.artists.size, shown.playlists.size)
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────

    private fun singleTile(key: String) = SearchTile.ArtistRow(Artist(MediaId("subsonic", key), key, albumCount = null, coverArt = null), SearchScore.None, true)

    private fun wideTile(key: String) = SearchTile.Note(
        NoteSearchHit(
            entryId = key, profileId = SUB, provider = "subsonic", songId = key, trackTitle = key, album = null,
            positionMs = null, fragment = SearchFragment(key, IntRange.EMPTY), score = SearchScore.None, at = 0L,
            isOtherAccount = false,
        ),
    )

    private fun track(id: String, title: String?, artist: String? = "Someone", album: String? = "Record", provider: String = "subsonic") = Track(
        id = MediaId(provider, id), title = title, artist = artist, artistId = null, album = album, albumId = null,
        coverArt = null, durationSec = null, trackNumber = null, year = null, genre = null, userRating = null,
    )

    private fun serviceAlbum(id: String, name: String, artist: String? = "Someone", provider: String = "subsonic") = Album(
        id = MediaId(provider, id), name = name, artist = artist, artistId = null, coverArt = null,
        songCount = null, durationSec = null, year = null, genre = null,
    )

    private fun serviceAlbums(): List<Album> = listOf(
        serviceAlbum("al-1", "だから僕は音楽を辞めた", "ヨルシカ"),
        serviceAlbum("al-2", "Homogenic", "Björk"),
        serviceAlbum("al-4", "The Dark Side of the Moon", "Pink Floyd"),
        serviceAlbum("al-9", "Summer Rain", "Nobody"),
        serviceAlbum("al-6", "가나다", "아이유"),
        serviceAlbum("al-7", "ＦＵＬＬ ＷＩＤＴＨ", "ｶﾞｰﾙ"),
        serviceAlbum("al-1", "だから僕は音楽を辞めた (dup)", "ヨルシカ"),
        serviceAlbum("1440", "盗作", "ヨルシカ", provider = "applemusic"),
    )

    private fun serviceResults() = SearchResults(
        tracks = listOf(
            track("tr-1", "晴天", "周杰伦", "叶惠美"),
            track("tr-2", "Summer Nights", "The Band"),
            track("tr-3", "雨とカプチーノ", "ヨルシカ", "エルマ"),
            track("tr-4", "Moonlight", "Someone"),
            track("tr-5", null, null, null),
            track("tr-6", "Unrelated", "Other", "Else"),
            track("tr-7", "Rain On Me", "Lady Gaga", "Chromatica"),
        ),
        albums = serviceAlbums(),
        artists = listOf(
            Artist(MediaId("subsonic", "ar-1"), "ヨルシカ", albumCount = null, coverArt = null),
            Artist(MediaId("subsonic", "ar-2"), "Summer Salt", albumCount = null, coverArt = null),
            Artist(MediaId("subsonic", "ar-3"), "Björk", albumCount = null, coverArt = null),
        ),
        playlists = listOf(
            Playlist(MediaId("subsonic", "pl-1"), "Rainy Day", owner = "51", coverArt = null, songCount = null, durationSec = null),
            Playlist(MediaId("subsonic", "pl-2"), "夏の歌", owner = null, coverArt = null, songCount = null, durationSec = null),
            Playlist(MediaId("subsonic", "pl-3"), "Mix", owner = "Summer", coverArt = null, songCount = null, durationSec = null),
        ),
    )

    private fun ledger(): List<MemoryLedgerEntry> {
        val a1 = MemoryLedgerAlbum(SUB, "subsonic", "al-1", "だから僕は音楽を辞めた", "ヨルシカ", "c-1")
        val a2 = MemoryLedgerAlbum(SUB, "subsonic", "al-2", "Homogenic", "Björk", null)
        val a3 = MemoryLedgerAlbum(APPLE, "applemusic", "1440", "盗作", "ヨルシカ", "c-3")
        val a4 = MemoryLedgerAlbum(SUB, "subsonic", "al-4", "The Dark Side of the Moon", "Pink Floyd", null)
        val a5 = MemoryLedgerAlbum(SPOT, "spotify", "sp-5", "叶惠美", "周杰伦", "c-5")
        val a6 = MemoryLedgerAlbum(SUB, "subsonic", "al-6", "가나다", "아이유", null)
        val a7 = MemoryLedgerAlbum(SUB, "subsonic", "al-7", "ＦＵＬＬ ＷＩＤＴＨ", "ｶﾞｰﾙ", null)
        val a8 = MemoryLedgerAlbum(APPLE, "applemusic", "al-1", "だから僕は音楽を辞めた", "ヨルシカ", null)
        val a9 = MemoryLedgerAlbum(SUB, "subsonic", "al-9", "Summer Rain", "Nobody", null)
        val long = "Before the light came, I " + "kept walking along the river ".repeat(3) +
            "until the summer rain found me 🌧️ and then " + "the night went on ".repeat(4)
        val mention = "[@晴天](yoin:track/subsonic:tr-1?a=%E5%91%A8%E6%9D%B0%E4%BC%A6&al=%E5%8F%B6%E6%83%A0%E7%BE%8E)"
        return listOf(
            MemoryLedgerEntry("title:a1", MemoryLedgerKind.Title, 50, SUB, a1, text = "夏の終わり"),
            MemoryLedgerEntry("review:a1", MemoryLedgerKind.Review, 90, SUB, a1, text = "夏が来るたびに思い出す。**雨**の日も"),
            MemoryLedgerEntry("album-rating:a1", MemoryLedgerKind.AlbumRating, 70, SUB, a1, score = 8.5f),
            MemoryLedgerEntry("album-note:a1-1", MemoryLedgerKind.AlbumNote, 60, SUB, a1, text = long),
            MemoryLedgerEntry("album-note:a2-1", MemoryLedgerKind.AlbumNote, 40, SUB, a2, text = "Listened with $mention on *rainy* days\n- Björk's voice\n- [x] Bjork again"),
            MemoryLedgerEntry("track-rating:a2-t", MemoryLedgerKind.TrackRating, 41, SUB, a2, trackTitle = "Jóga", songId = "s-joga", songProvider = "subsonic", score = 9f),
            MemoryLedgerEntry("track-rating:a2-u", MemoryLedgerKind.TrackRating, 42, SUB, a2, trackTitle = "Bachelorette", songId = "s-bach", songProvider = "subsonic", score = 7f),
            MemoryLedgerEntry("album-note:a3-1", MemoryLedgerKind.AlbumNote, 80, APPLE, a3, text = "雨の日に聴いた。ヨルシカの夏"),
            MemoryLedgerEntry("review:a3", MemoryLedgerKind.Review, 85, APPLE, a3, text = "盗作 is about summer theft"),
            MemoryLedgerEntry("album-rating:a4", MemoryLedgerKind.AlbumRating, 30, SUB, a4, score = 10f),
            MemoryLedgerEntry("album-note:a5-1", MemoryLedgerKind.AlbumNote, 20, SPOT, a5, text = "晴天 always reminds me of 周杰伦's summer"),
            MemoryLedgerEntry("album-note:a6-1", MemoryLedgerKind.AlbumNote, 22, SUB, a6, text = "각 노래가 좋아요 가나"),
            MemoryLedgerEntry("title:a7", MemoryLedgerKind.Title, 23, SUB, a7, text = "ガールの夏"),
            MemoryLedgerEntry("album-note:a8-1", MemoryLedgerKind.AlbumNote, 24, APPLE, a8, text = "the same album, on Apple"),
            MemoryLedgerEntry("title:a9", MemoryLedgerKind.Title, 25, SUB, a9, text = "   "),
            MemoryLedgerEntry("album-rating:a9", MemoryLedgerKind.AlbumRating, 26, SUB, a9, score = 6f),
            MemoryLedgerEntry(
                "song-note:1", MemoryLedgerKind.SongNote, 100, SUB, a5.copy(profileId = SUB, provider = "subsonic", albumId = "al-5"),
                text = "the bridge at 1:24, $mention again", trackTitle = "晴天", songId = "tr-1", songProvider = "subsonic", positionMs = 84_000L,
            ),
            MemoryLedgerEntry(
                "song-note:2", MemoryLedgerKind.SongNote, 101, SUB, null,
                text = "summer nights, windows down", trackTitle = "Summer Nights", songId = "tr-2", songProvider = "subsonic", positionMs = 0L,
            ),
            MemoryLedgerEntry(
                "song-note:3", MemoryLedgerKind.SongNote, 102, SUB, null,
                text = "summer again, louder", trackTitle = "Summer Nights", songId = "tr-2", songProvider = "subsonic", positionMs = 12_000L,
            ),
            MemoryLedgerEntry(
                "song-note:4", MemoryLedgerKind.SongNote, 103, APPLE, a3,
                text = "the chorus", trackTitle = "雨とカプチーノ", songId = "am-3", songProvider = "applemusic", positionMs = null,
            ),
            MemoryLedgerEntry(
                "song-note:5", MemoryLedgerKind.SongNote, 104, SUB, a1,
                text = "雨の音が好き\nとても", trackTitle = "雨とカプチーノ", songId = "tr-3", songProvider = null, positionMs = 61_500L,
            ),
            MemoryLedgerEntry(
                "song-note:6", MemoryLedgerKind.SongNote, 105, SPOT, null,
                text = "", trackTitle = "Moonlight", songId = "sp-4", songProvider = "spotify", positionMs = 5_000L,
            ),
            MemoryLedgerEntry(
                "song-note:7", MemoryLedgerKind.SongNote, 106, SUB, null,
                text = "🎧 headphones on — ＲＡＤＩＯ edit", trackTitle = null, songId = null, songProvider = null, positionMs = null,
            ),
        )
    }

    private companion object {
        const val SUB = "p-sub"
        const val APPLE = "p-apple"
        const val SPOT = "p-spot"

        val ACTIVE = listOf(SUB, APPLE, null)

        val AI_TITLES = mapOf(
            "p-sub|subsonic|al-2" to "Icelandic winter",
            "p-sub|subsonic|al-4" to "Moon days",
            "p-apple|applemusic|1440" to "Summer theft",
            "p-sub|subsonic|al-9" to "Rainy summer",
        )

        val FOLDS = listOf(
            "Björk", "BJÖRK", "Ｒａｄｗｉｍｐｓ", "ｶﾞｰﾙ", "ガール", "が", "か", "가나다", "각", "  The   Band \n", "ﬁve", "ﷺ",
            "Ελληνικά Άλφα", "Москва Ёлка", "Crème Brûlée", "🎧 music 🌧️", "Ⅻ", "①②", "ﾊﾟﾋﾟﾌﾟﾍﾟﾎﾟ", "İstanbul", "ß", "Æsir",
            "\tTab\tseparated\t", "á", "\uD83C", "x\uDC00y", "",
        )

        val MATCHES = listOf(
            "ヨルシカ" to "ヨルシカ",
            "ヨル" to "ヨルシカ",
            "dark" to "The Dark Side of the Moon",
            "ark" to "The Dark Side",
            "シカ" to "ヨルシカ",
            "swim" to "ヨルシカ",
            "ＲＡＤＷＩＭＰＳ" to "radwimps",
            "ｶﾞｰﾙ" to "ガール",
            "  the   band " to "The Band",
            "落日" to "落日飛車 Sunset Rollercoaster",
            "bjork" to "Björk",
            "か" to "が",
            "か" to "かが",
            "が" to "かが",
            "가" to "각",
            "가" to "각 가",
            "radio" to "Ｍｙ ＲＡＤＩＯ song",
            "夏" to "あの夏の\n帰り道",
            "peter pan" to "a".repeat(100) + " Peter Pan " + "b".repeat(100),
            "end" to "x".repeat(120) + " the end",
            "start" to "start " + "y".repeat(120),
            "🌧" to "rain 🌧️ falls " + "z".repeat(90),
            "falls" to "😀".repeat(30) + " falls " + "😀".repeat(30),
            "fi" to "ﬁve ﬁngers",
            "moon" to "moon-moon moon",
            "on" to "moon-moon on",
            "-moon" to "moon-moon",
            "a" to null,
            " " to "anything",
            "e" to "Crème Brûlée",
            "brulee" to "Crème Brûlée",
            "ss" to "Straße",
            "の夏" to "ヨルシカの夏",
        )

        val QUERIES = listOf(
            "夏", "雨", "ヨルシカ", "bjork", "BJÖRK", "dark", "rain", "summer", "晴天", "周杰伦", "ｶﾞｰﾙ", "か", "가",
            "moon", "the", "radio", "🎧", "nights", "chorus", "盗作", "icelandic", "rainy", "Moonlight", "light", "   ", "zzz",
        )

        val PAGES = listOf(
            Triple("夏", SUB, 3),
            Triple("summer", SUB, 3),
            Triple("summer", APPLE, 4),
            Triple("雨", SUB, 3),
            Triple("晴天", SUB, 3),
            Triple("ヨルシカ", SUB, 2),
            Triple("bjork", SUB, 3),
            Triple("moon", null, 3),
            Triple("rain", SUB, 5),
            Triple("   ", SUB, 3),
            Triple("zzz", SUB, 3),
        )

        val PACKS = listOf(
            Triple("ssssww", 3, 0 to 0),
            Triple("sssssssw", 3, 0 to 0),
            Triple("wwwww", 3, 0 to 0),
            Triple("sssss", 3, 0 to 0),
            Triple("sswswsssw", 3, 0 to 0),
            Triple("ssssssssssssssssssss" + "wwwww", 3, 4 to 3),
            Triple("ssssssw", 4, 0 to 0),
            Triple("wsws", 2, 0 to 0),
            Triple("sssw", 1, 0 to 0),
            Triple("sssssssssw", 5, 2 to 1),
            Triple("wwsss", 3, 1 to 0),
            Triple("", 3, 0 to 0),
        )
    }
}
