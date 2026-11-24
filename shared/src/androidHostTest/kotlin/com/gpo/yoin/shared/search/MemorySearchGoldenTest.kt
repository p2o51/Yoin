package com.gpo.yoin.shared.search

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The golden vectors other ports of the Memories & notes search core are tested against (HarmonyOS's Dart
 * port; iOS calls the same Kotlin through [YoinMemorySearch]). Every expected value is what this Kotlin
 * computes through the facade; the test fails when the checked-in file drifts from it. The data is the one
 * Android's SearchPinTest pins (app/src/test/resources/search/search-pin.txt).
 *
 * File: shared/src/commonTest/resources/golden/memory-search.json. Offsets are UTF-16 code units, ranges
 * [start, end) with -1 / -1 for none. Scores are 32-bit floats. Regenerate after an intentional change:
 * `YOIN_WRITE_SEARCH_GOLDEN=1 ./gradlew :shared:testAndroidHostTest --tests '*MemorySearchGoldenTest*'`.
 */
class MemorySearchGoldenTest {

    private val search = YoinMemorySearch(
        object : YoinMemorySearchLedger {
            override fun entries(): List<YoinLedgerEntry> = LEDGER
            override fun aiTitles(): List<YoinAiTitle> = AI_TITLES
        },
    )

    @Test
    fun should_matchCheckedInGolden_when_kotlinComputesEveryVector() {
        val json = goldenJson()
        val file = goldenFile()
        if (System.getenv("YOIN_WRITE_SEARCH_GOLDEN") == "1" || !file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(json)
        }
        assertEquals(file.readText(), json)
    }

    /** A port reads its inputs from the file: they must say everything the Kotlin was given. */
    @Test
    fun should_carryEveryInput_when_theLedgerAndRowsAreReadBackFromJson() {
        assertEquals(LEDGER, LEDGER.map { entryJson(it) }.map(::entryOf))
        assertEquals(AI_TITLES, AI_TITLES.map { aiTitleJson(it) }.map(::aiTitleOf))
        assertEquals(SONGS, SONGS.map { songJson(it) }.map(::songOf))
        assertEquals(ALBUMS, ALBUMS.map { albumRowJson(it) }.map(::albumRowOf))
    }

    private fun goldenFile(): File {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "shared").isDirectory && File(it, "settings.gradle.kts").isFile }
        return File(root, "shared/src/commonTest/resources/golden/memory-search.json")
    }

    /** One vector per line: small, and a drift shows as the lines that changed. */
    private fun goldenJson(): String {
        val sections = linkedMapOf(
            "fold" to FOLDS.map { obj("text" to str(it), "folded" to str(search.fold(it))) },
            "match" to MATCHES.map(::matchVector),
            "fragment" to MATCHES.flatMap { (q, t) -> listOf(80, 20).map { fragmentVector(q, t, it) } },
            "plainWords" to PLAIN.map { obj("text" to str(it), "words" to str(search.plainWords(it))) },
            "ledger" to LEDGER.map(::entryJson),
            "aiTitles" to AI_TITLES.map(::aiTitleJson),
            "search" to QUERIES.flatMap { q -> ACTIVE.map { a -> searchVector(q, a) } },
            "attachments" to ACTIVE.map(::attachmentsVector),
            "songs" to SONGS.map(::songJson),
            "albums" to ALBUMS.map(::albumRowJson),
            "artists" to ARTISTS.map { obj("provider" to str(it.provider), "rawId" to str(it.rawId), "name" to str(it.name)) },
            "playlists" to PLAYLISTS.map {
                obj("provider" to str(it.provider), "rawId" to str(it.rawId), "name" to str(it.name), "owner" to str(it.owner))
            },
            "pages" to PAGES.flatMap { (q, a, columns) ->
                listOf(true, false).flatMap { withService ->
                    listOf(4 to 3, 2 to 1, 0 to 0).map { (rows, wide) -> pageVector(q, a, columns, rows, wide, withService) }
                }
            },
            "pack" to PACKS.map(::packVector),
            "filters" to listOf(false, true).flatMap { s -> listOf(false, true).flatMap { r -> listOf(false, true).map { l -> filtersVector(s, r, l) } } },
            "shownRows" to YoinSearchFilter.entries.flatMap { f ->
                YoinSearchSection.entries.flatMap { s -> listOf(0, 7, 40, 45).map { n -> shownVector(f, s, n) } }
            },
            "noteTargets" to NOTE_TARGETS.map { (note, returned, active) -> noteTargetVector(note, returned, active) },
            "noteSeekReady" to SEEKS.map { (current, duration, target) ->
                obj(
                    "currentRawId" to str(current),
                    "durationMs" to JsonPrimitive(duration),
                    "targetRawId" to str(target),
                    "ready" to JsonPrimitive(search.noteSeekReady(current, duration, target)),
                )
            },
        )
        val about = "Golden vectors for the Memories & notes search core (com.gpo.yoin.shared.search: fold, match, " +
            "fragment, the ledger search across accounts, attachments, the All page and its packing, the type chips, " +
            "play from a note's second), generated from :shared Kotlin through YoinMemorySearch by " +
            "MemorySearchGoldenTest (androidHostTest). Do not edit by hand. Each \"search\" runs over \"ledger\" + " +
            "\"aiTitles\"; each \"pages\" vector lays out \"songs\"/\"albums\"/\"artists\"/\"playlists\" (or none, " +
            "withService false) with the \"search\" result for its query and account and the attachments of \"albums\"."
        val constants = obj(
            "fragmentMaxChars" to JsonPrimitive(search.fragmentMaxChars),
            "allMaxRows" to JsonPrimitive(search.allMaxRows),
            "allMaxWide" to JsonPrimitive(search.allMaxWide),
            "allRowsPerType" to JsonPrimitive(search.allRowsPerType),
            "noteSeekTimeoutMs" to JsonPrimitive(search.noteSeekTimeoutMs),
        )
        return buildString {
            append("{\n")
            append("  \"about\": ").append(encode(str(about))).append(",\n")
            append("  \"offsets\": \"UTF-16 code units; ranges are [start, end), -1/-1 for none; scores are 32-bit floats\",\n")
            append("  \"constants\": ").append(encode(constants))
            sections.forEach { (name, vectors) ->
                append(",\n  \"").append(name).append("\": [\n")
                vectors.forEachIndexed { i, vector ->
                    append("    ").append(encode(vector))
                    append(if (i == vectors.lastIndex) "\n" else ",\n")
                }
                append("  ]")
            }
            append("\n}\n")
        }
    }

    // ── vectors ──────────────────────────────────────────────────────

    private fun matchVector(pair: Pair<String, String?>): JsonElement {
        val (query, text) = pair
        val m = search.match(query, text)
        return obj(
            "query" to str(query),
            "text" to str(text),
            "strength" to str(m.strength.name),
            "start" to JsonPrimitive(m.start),
            "end" to JsonPrimitive(m.end),
        )
    }

    private fun fragmentVector(query: String, text: String?, maxChars: Int): JsonElement = obj(
        "query" to str(query),
        "text" to str(text),
        "maxChars" to JsonPrimitive(maxChars),
        "fragment" to (search.fragment(query, text, maxChars)?.let(::fragmentJson) ?: JsonNull),
    )

    private fun searchVector(query: String, active: String?): JsonElement = obj(
        "query" to str(query),
        "activeProfileId" to str(active),
        "result" to resultJson(search.search(query, active)),
    )

    private fun attachmentsVector(active: String?): JsonElement = obj(
        "activeProfileId" to str(active),
        "result" to JsonArray(search.attachments(ALBUMS, active).map(::attachmentJson)),
    )

    private fun pageVector(query: String, active: String?, columns: Int, maxRows: Int, maxWide: Int, withService: Boolean): JsonElement {
        val page = search.page(
            query = query,
            songs = if (withService) SONGS else emptyList(),
            albums = if (withService) ALBUMS else emptyList(),
            artists = if (withService) ARTISTS else emptyList(),
            playlists = if (withService) PLAYLISTS else emptyList(),
            result = search.search(query, active),
            attachments = search.attachments(ALBUMS, active),
            activeProfileId = active,
            columns = columns,
            maxRows = maxRows,
            maxWide = maxWide,
        )
        return obj(
            "query" to str(query),
            "activeProfileId" to str(active),
            "columns" to JsonPrimitive(columns),
            "maxRows" to JsonPrimitive(maxRows),
            "maxWide" to JsonPrimitive(maxWide),
            "withService" to JsonPrimitive(withService),
            "page" to obj(
                "best" to (page.best?.let { obj("kind" to str(it.kind.name), "index" to JsonPrimitive(it.index)) } ?: JsonNull),
                "sections" to JsonArray(
                    page.sections.map { s ->
                        obj(
                            "section" to str(s.section.name),
                            "tier" to str(s.tier.name),
                            "strength" to str(s.strength.name),
                            "folded" to JsonPrimitive(s.folded),
                            "more" to JsonPrimitive(s.more),
                            "tiles" to JsonArray(s.tiles.map(::tileJson)),
                            "rows" to JsonArray(
                                s.rows.map { row ->
                                    obj("keys" to JsonArray(row.tiles.map { str(it.key) }), "wideAtEnd" to JsonPrimitive(row.wideAtEnd))
                                },
                            ),
                        )
                    },
                ),
                "memories" to JsonArray(page.memories.map { str(it.key) }),
            ),
        )
    }

    private fun packVector(case: Triple<String, Int, Pair<Int, Int>>): JsonElement {
        val (shape, columns, caps) = case
        val wide = shape.map { it == 'w' }
        val rows = packSearchTiles(wide.indices.toList(), columns, caps.first, caps.second) { wide[it] }
        return obj(
            "wide" to JsonArray(wide.map(::JsonPrimitive)),
            "columns" to JsonPrimitive(columns),
            "maxRows" to JsonPrimitive(caps.first),
            "maxWide" to JsonPrimitive(caps.second),
            "rows" to JsonArray(
                rows.map { row -> obj("tiles" to JsonArray(row.tiles.map(::JsonPrimitive)), "wideAtEnd" to JsonPrimitive(row.wideAtEnd)) },
            ),
        )
    }

    private fun filtersVector(canSearch: Boolean, canRead: Boolean, saved: Boolean): JsonElement = obj(
        "canSearchPlaylists" to JsonPrimitive(canSearch),
        "canReadPlaylists" to JsonPrimitive(canRead),
        "savedLibrary" to JsonPrimitive(saved),
        "filters" to JsonArray(search.filters(canSearch, canRead, saved).map { str(it.name) }),
    )

    private fun shownVector(filter: YoinSearchFilter, section: YoinSearchSection, available: Int): JsonElement = obj(
        "filter" to str(filter.name),
        "section" to str(section.name),
        "available" to JsonPrimitive(available),
        "shown" to JsonPrimitive(search.shownRows(filter, section, available)),
    )

    private fun noteTargetVector(note: YoinNoteHit, returned: Pair<String, String>?, active: String?): JsonElement {
        val target = search.noteTarget(note, returned?.first, returned?.second, active)
        return obj(
            "note" to noteJson(note),
            "returnedSong" to (returned?.let { obj("provider" to str(it.first), "rawId" to str(it.second)) } ?: JsonNull),
            "activeProfileId" to str(active),
            "target" to obj(
                "action" to str(target.action.name),
                "songProvider" to str(target.songProvider),
                "songId" to str(target.songId),
                "albumProvider" to str(target.albumProvider),
                "albumId" to str(target.albumId),
                "profileId" to str(target.profileId),
                "switchAccount" to JsonPrimitive(target.switchAccount),
                "seekToMs" to num(target.seekToMs),
                "subject" to str(target.subject),
            ),
        )
    }

    // ── JSON shapes ──────────────────────────────────────────────────

    private fun albumJson(a: YoinLedgerAlbum?): JsonElement = if (a == null) {
        JsonNull
    } else {
        obj(
            "profileId" to str(a.profileId),
            "provider" to str(a.provider),
            "albumId" to str(a.albumId),
            "name" to str(a.name),
            "artist" to str(a.artist),
            "coverKey" to str(a.coverKey),
        )
    }

    private fun entryJson(e: YoinLedgerEntry): JsonElement = obj(
        "id" to str(e.id),
        "kind" to str(e.kind.name),
        "at" to JsonPrimitive(e.at),
        "profileId" to str(e.profileId),
        "album" to albumJson(e.album),
        "text" to str(e.text),
        "trackTitle" to str(e.trackTitle),
        "songId" to str(e.songId),
        "songProvider" to str(e.songProvider),
        "positionMs" to num(e.positionMs),
        "score" to num(e.score),
    )

    private fun aiTitleJson(t: YoinAiTitle): JsonElement = obj(
        "profileId" to str(t.profileId),
        "provider" to str(t.provider),
        "albumId" to str(t.albumId),
        "title" to str(t.title),
    )

    private fun songJson(s: YoinSearchSong): JsonElement = obj(
        "provider" to str(s.provider),
        "rawId" to str(s.rawId),
        "title" to str(s.title),
        "artist" to str(s.artist),
        "album" to str(s.album),
    )

    private fun albumRowJson(a: YoinSearchAlbum): JsonElement =
        obj("provider" to str(a.provider), "rawId" to str(a.rawId), "name" to str(a.name), "artist" to str(a.artist))

    private fun fragmentJson(f: YoinSearchFragment): JsonElement = obj(
        "text" to str(f.text),
        "highlightStart" to JsonPrimitive(f.highlightStart),
        "highlightEnd" to JsonPrimitive(f.highlightEnd),
    )

    private fun markJson(m: YoinLedgerMark?): JsonElement = if (m == null) {
        JsonNull
    } else {
        obj("kind" to str(m.kind.name), "score" to num(m.score), "noteCount" to JsonPrimitive(m.noteCount))
    }

    private fun memoryJson(h: YoinMemoryHit): JsonElement = obj(
        "albumKey" to str(h.album.key),
        "album" to albumJson(h.album),
        "field" to str(h.field.name),
        "entryId" to str(h.entryId),
        "fragment" to fragmentJson(h.fragment),
        "tier" to str(h.tier.name),
        "strength" to str(h.strength.name),
        "title" to str(h.title),
        "albumScore" to num(h.albumScore),
        "lastAt" to JsonPrimitive(h.lastAt),
        "isOtherAccount" to JsonPrimitive(h.isOtherAccount),
        "mark" to markJson(h.mark),
    )

    private fun noteJson(n: YoinNoteHit): JsonElement = obj(
        "entryId" to str(n.entryId),
        "profileId" to str(n.profileId),
        "provider" to str(n.provider),
        "songId" to str(n.songId),
        "trackTitle" to str(n.trackTitle),
        "album" to albumJson(n.album),
        "positionMs" to num(n.positionMs),
        "fragment" to fragmentJson(n.fragment),
        "tier" to str(n.tier.name),
        "strength" to str(n.strength.name),
        "at" to JsonPrimitive(n.at),
        "isOtherAccount" to JsonPrimitive(n.isOtherAccount),
    )

    private fun resultJson(r: YoinMemorySearchResult): JsonElement = obj(
        "memories" to JsonArray(r.memories.map(::memoryJson)),
        "notes" to JsonArray(r.notes.map(::noteJson)),
    )

    private fun attachmentJson(a: YoinMemoryAttachment): JsonElement = obj(
        "provider" to str(a.provider),
        "albumId" to str(a.albumId),
        "albumKey" to str(a.albumKey),
        "title" to str(a.title),
        "albumScore" to num(a.albumScore),
        "lastAt" to JsonPrimitive(a.lastAt),
        "mark" to markJson(a.mark),
    )

    /** A tile: its own fields, and the hit it carries by id (the hit itself is in the "search" vector). */
    private fun tileJson(t: YoinSearchTile): JsonElement = obj(
        "kind" to str(t.kind.name),
        "key" to str(t.key),
        "index" to JsonPrimitive(t.index),
        "wide" to JsonPrimitive(t.wide),
        "relevant" to JsonPrimitive(t.relevant),
        "tier" to str(t.tier.name),
        "strength" to str(t.strength.name),
        "note" to str(t.note?.entryId),
        "memory" to str(t.memory?.album?.key),
        "attachment" to str(t.attachment?.albumKey),
    )

    // ── reading inputs back ──────────────────────────────────────────

    private fun JsonElement.strOrNull(): String? = if (this is JsonNull) null else jsonPrimitive.content

    private fun albumOf(e: JsonElement): YoinLedgerAlbum? = if (e is JsonNull) {
        null
    } else {
        val o = e.jsonObject
        YoinLedgerAlbum(
            o.getValue("profileId").jsonPrimitive.content,
            o.getValue("provider").jsonPrimitive.content,
            o.getValue("albumId").jsonPrimitive.content,
            o.getValue("name").jsonPrimitive.content,
            o.getValue("artist").jsonPrimitive.content,
            o.getValue("coverKey").strOrNull(),
        )
    }

    private fun entryOf(e: JsonElement): YoinLedgerEntry {
        val o = e.jsonObject
        return YoinLedgerEntry(
            id = o.getValue("id").jsonPrimitive.content,
            kind = YoinLedgerKind.valueOf(o.getValue("kind").jsonPrimitive.content),
            at = o.getValue("at").jsonPrimitive.long,
            profileId = o.getValue("profileId").jsonPrimitive.content,
            album = albumOf(o.getValue("album")),
            text = o.getValue("text").strOrNull(),
            trackTitle = o.getValue("trackTitle").strOrNull(),
            songId = o.getValue("songId").strOrNull(),
            songProvider = o.getValue("songProvider").strOrNull(),
            positionMs = o.getValue("positionMs").let { if (it is JsonNull) null else it.jsonPrimitive.long },
            score = o.getValue("score").let { if (it is JsonNull) null else it.jsonPrimitive.float },
        )
    }

    private fun aiTitleOf(e: JsonElement): YoinAiTitle {
        val o = e.jsonObject
        return YoinAiTitle(
            o.getValue("profileId").jsonPrimitive.content,
            o.getValue("provider").jsonPrimitive.content,
            o.getValue("albumId").jsonPrimitive.content,
            o.getValue("title").jsonPrimitive.content,
        )
    }

    private fun songOf(e: JsonElement): YoinSearchSong {
        val o = e.jsonObject
        return YoinSearchSong(
            o.getValue("provider").jsonPrimitive.content,
            o.getValue("rawId").jsonPrimitive.content,
            o.getValue("title").strOrNull(),
            o.getValue("artist").strOrNull(),
            o.getValue("album").strOrNull(),
        )
    }

    private fun albumRowOf(e: JsonElement): YoinSearchAlbum {
        val o = e.jsonObject
        return YoinSearchAlbum(
            o.getValue("provider").jsonPrimitive.content,
            o.getValue("rawId").jsonPrimitive.content,
            o.getValue("name").jsonPrimitive.content,
            o.getValue("artist").strOrNull(),
        )
    }

    // A lone surrogate (a fragment can start on one: Android cuts \uD83D\uDE00 there too) goes out as a JSON
    // \uXXXX escape: valid JSON, and it survives UTF-8.
    private fun encode(e: JsonElement): String = buildString {
        val text = Json.encodeToString(JsonElement.serializer(), e)
        text.forEachIndexed { i, c ->
            val paired = (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) ||
                (c.isLowSurrogate() && i > 0 && text[i - 1].isHighSurrogate())
            if (c.isSurrogate() && !paired) append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
        }
    }

    private fun str(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    private fun num(value: Number?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    private fun obj(vararg entries: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*entries))

    // ── fixtures (Android's SearchPinTest data) ─────────────────────

    private companion object {
        const val SUB = "p-sub"
        const val APPLE = "p-apple"
        const val SPOT = "p-spot"

        val ACTIVE = listOf(SUB, APPLE, null)

        fun entry(
            id: String,
            kind: YoinLedgerKind,
            at: Long,
            profileId: String,
            album: YoinLedgerAlbum?,
            text: String? = null,
            trackTitle: String? = null,
            songId: String? = null,
            songProvider: String? = null,
            positionMs: Long? = null,
            score: Float? = null,
        ) = YoinLedgerEntry(id, kind, at, profileId, album, text, trackTitle, songId, songProvider, positionMs, score)

        val A1 = YoinLedgerAlbum(SUB, "subsonic", "al-1", "だから僕は音楽を辞めた", "ヨルシカ", "c-1")
        val A2 = YoinLedgerAlbum(SUB, "subsonic", "al-2", "Homogenic", "Björk", null)
        val A3 = YoinLedgerAlbum(APPLE, "applemusic", "1440", "盗作", "ヨルシカ", "c-3")
        val A4 = YoinLedgerAlbum(SUB, "subsonic", "al-4", "The Dark Side of the Moon", "Pink Floyd", null)
        val A5 = YoinLedgerAlbum(SPOT, "spotify", "sp-5", "叶惠美", "周杰伦", "c-5")
        val A5_SUB = YoinLedgerAlbum(SUB, "subsonic", "al-5", "叶惠美", "周杰伦", "c-5")
        val A6 = YoinLedgerAlbum(SUB, "subsonic", "al-6", "가나다", "아이유", null)
        val A7 = YoinLedgerAlbum(SUB, "subsonic", "al-7", "ＦＵＬＬ ＷＩＤＴＨ", "ｶﾞｰﾙ", null)
        val A8 = YoinLedgerAlbum(APPLE, "applemusic", "al-1", "だから僕は音楽を辞めた", "ヨルシカ", null)
        val A9 = YoinLedgerAlbum(SUB, "subsonic", "al-9", "Summer Rain", "Nobody", null)

        val LONG = "Before the light came, I " + "kept walking along the river ".repeat(3) +
            "until the summer rain found me 🌧️ and then " + "the night went on ".repeat(4)
        const val MENTION = "[@晴天](yoin:track/subsonic:tr-1?a=%E5%91%A8%E6%9D%B0%E4%BC%A6&al=%E5%8F%B6%E6%83%A0%E7%BE%8E)"

        val LEDGER = listOf(
            entry("title:a1", YoinLedgerKind.Title, 50, SUB, A1, text = "夏の終わり"),
            entry("review:a1", YoinLedgerKind.Review, 90, SUB, A1, text = "夏が来るたびに思い出す。**雨**の日も"),
            entry("album-rating:a1", YoinLedgerKind.AlbumRating, 70, SUB, A1, score = 8.5f),
            entry("album-note:a1-1", YoinLedgerKind.AlbumNote, 60, SUB, A1, text = LONG),
            entry("album-note:a2-1", YoinLedgerKind.AlbumNote, 40, SUB, A2, text = "Listened with $MENTION on *rainy* days\n- Björk's voice\n- [x] Bjork again"),
            entry("track-rating:a2-t", YoinLedgerKind.TrackRating, 41, SUB, A2, trackTitle = "Jóga", songId = "s-joga", songProvider = "subsonic", score = 9f),
            entry("track-rating:a2-u", YoinLedgerKind.TrackRating, 42, SUB, A2, trackTitle = "Bachelorette", songId = "s-bach", songProvider = "subsonic", score = 7f),
            entry("album-note:a3-1", YoinLedgerKind.AlbumNote, 80, APPLE, A3, text = "雨の日に聴いた。ヨルシカの夏"),
            entry("review:a3", YoinLedgerKind.Review, 85, APPLE, A3, text = "盗作 is about summer theft"),
            entry("album-rating:a4", YoinLedgerKind.AlbumRating, 30, SUB, A4, score = 10f),
            entry("album-note:a5-1", YoinLedgerKind.AlbumNote, 20, SPOT, A5, text = "晴天 always reminds me of 周杰伦's summer"),
            entry("album-note:a6-1", YoinLedgerKind.AlbumNote, 22, SUB, A6, text = "각 노래가 좋아요 가나"),
            entry("title:a7", YoinLedgerKind.Title, 23, SUB, A7, text = "ガールの夏"),
            entry("album-note:a8-1", YoinLedgerKind.AlbumNote, 24, APPLE, A8, text = "the same album, on Apple"),
            entry("title:a9", YoinLedgerKind.Title, 25, SUB, A9, text = "   "),
            entry("album-rating:a9", YoinLedgerKind.AlbumRating, 26, SUB, A9, score = 6f),
            entry(
                "song-note:1", YoinLedgerKind.SongNote, 100, SUB, A5_SUB, text = "the bridge at 1:24, $MENTION again",
                trackTitle = "晴天", songId = "tr-1", songProvider = "subsonic", positionMs = 84_000L,
            ),
            entry(
                "song-note:2", YoinLedgerKind.SongNote, 101, SUB, null, text = "summer nights, windows down",
                trackTitle = "Summer Nights", songId = "tr-2", songProvider = "subsonic", positionMs = 0L,
            ),
            entry(
                "song-note:3", YoinLedgerKind.SongNote, 102, SUB, null, text = "summer again, louder",
                trackTitle = "Summer Nights", songId = "tr-2", songProvider = "subsonic", positionMs = 12_000L,
            ),
            entry(
                "song-note:4", YoinLedgerKind.SongNote, 103, APPLE, A3, text = "the chorus",
                trackTitle = "雨とカプチーノ", songId = "am-3", songProvider = "applemusic",
            ),
            entry(
                "song-note:5", YoinLedgerKind.SongNote, 104, SUB, A1, text = "雨の音が好き\nとても",
                trackTitle = "雨とカプチーノ", songId = "tr-3", positionMs = 61_500L,
            ),
            entry(
                "song-note:6", YoinLedgerKind.SongNote, 105, SPOT, null, text = "",
                trackTitle = "Moonlight", songId = "sp-4", songProvider = "spotify", positionMs = 5_000L,
            ),
            entry("song-note:7", YoinLedgerKind.SongNote, 106, SUB, null, text = "🎧 headphones on — ＲＡＤＩＯ edit"),
        )

        val AI_TITLES = listOf(
            YoinAiTitle(SUB, "subsonic", "al-2", "Icelandic winter"),
            YoinAiTitle(SUB, "subsonic", "al-4", "Moon days"),
            YoinAiTitle(APPLE, "applemusic", "1440", "Summer theft"),
            YoinAiTitle(SUB, "subsonic", "al-9", "Rainy summer"),
        )

        val SONGS = listOf(
            YoinSearchSong("subsonic", "tr-1", "晴天", "周杰伦", "叶惠美"),
            YoinSearchSong("subsonic", "tr-2", "Summer Nights", "The Band", "Record"),
            YoinSearchSong("subsonic", "tr-3", "雨とカプチーノ", "ヨルシカ", "エルマ"),
            YoinSearchSong("subsonic", "tr-4", "Moonlight", "Someone", "Record"),
            YoinSearchSong("subsonic", "tr-5", null, null, null),
            YoinSearchSong("subsonic", "tr-6", "Unrelated", "Other", "Else"),
            YoinSearchSong("subsonic", "tr-7", "Rain On Me", "Lady Gaga", "Chromatica"),
        )

        val ALBUMS = listOf(
            YoinSearchAlbum("subsonic", "al-1", "だから僕は音楽を辞めた", "ヨルシカ"),
            YoinSearchAlbum("subsonic", "al-2", "Homogenic", "Björk"),
            YoinSearchAlbum("subsonic", "al-4", "The Dark Side of the Moon", "Pink Floyd"),
            YoinSearchAlbum("subsonic", "al-9", "Summer Rain", "Nobody"),
            YoinSearchAlbum("subsonic", "al-6", "가나다", "아이유"),
            YoinSearchAlbum("subsonic", "al-7", "ＦＵＬＬ ＷＩＤＴＨ", "ｶﾞｰﾙ"),
            YoinSearchAlbum("subsonic", "al-1", "だから僕は音楽を辞めた (dup)", "ヨルシカ"),
            YoinSearchAlbum("applemusic", "1440", "盗作", "ヨルシカ"),
        )

        val ARTISTS = listOf(
            YoinSearchArtist("subsonic", "ar-1", "ヨルシカ"),
            YoinSearchArtist("subsonic", "ar-2", "Summer Salt"),
            YoinSearchArtist("subsonic", "ar-3", "Björk"),
        )

        val PLAYLISTS = listOf(
            YoinSearchPlaylist("subsonic", "pl-1", "Rainy Day", "51"),
            YoinSearchPlaylist("subsonic", "pl-2", "夏の歌", null),
            YoinSearchPlaylist("subsonic", "pl-3", "Mix", "Summer"),
        )

        // Lone surrogates are left out: they don't survive UTF-8, nor every port's string type.
        val FOLDS = listOf(
            "Björk", "BJÖRK", "Ｒａｄｗｉｍｐｓ", "ｶﾞｰﾙ", "ガール", "が", "か", "가나다", "각", "  The   Band \n", "ﬁve", "ﷺ",
            "Ελληνικά Άλφα", "Москва Ёлка", "Crème Brûlée", "🎧 music 🌧️", "Ⅻ", "①②", "ﾊﾟﾋﾟﾌﾟﾍﾟﾎﾟ", "İstanbul", "ß", "Æsir",
            "\tTab\tseparated\t", "a\u0301", "",
        )

        val MATCHES: List<Pair<String, String?>> = listOf(
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

        val PLAIN = listOf(
            "plain words",
            "Listened with $MENTION on *rainy* days\n- Björk's voice\n- [x] Bjork again",
            "# Heading\n**bold** and *italic*",
            "[@C:\\Users \\[x\\] 苏打绿 🎧](yoin:artist/subsonic:ar-1?a=a%3Db%26c&al=100%25) live",
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

        private fun noteHit(
            entryId: String,
            profileId: String,
            provider: String?,
            songId: String?,
            album: YoinLedgerAlbum?,
            positionMs: Long?,
            trackTitle: String?,
        ) = YoinNoteHit(
            entryId, profileId, provider, songId, trackTitle, album, positionMs, YoinSearchFragment("…", -1, -1),
            YoinSearchTier.Body, YoinSearchStrength.Contains, 0L, false,
        )

        val NOTE_TARGETS: List<Triple<YoinNoteHit, Pair<String, String>?, String?>> = listOf(
            Triple(noteHit("n1", SUB, "subsonic", "tr-1", A5_SUB, 84_000L, "晴天"), "subsonic" to "tr-1", SUB),
            Triple(noteHit("n2", SUB, "subsonic", "tr-2", null, 0L, "Summer Nights"), "subsonic" to "tr-2", SUB),
            Triple(noteHit("n3", SUB, "subsonic", "tr-2", null, null, "Summer Nights"), "subsonic" to "tr-2", SUB),
            Triple(noteHit("n4", APPLE, "applemusic", "am-3", A3, null, "雨とカプチーノ"), null, SUB),
            Triple(noteHit("n5", SUB, "subsonic", "tr-3", A1, 61_500L, null), null, SUB),
            Triple(noteHit("n6", SUB, null, null, A1, 1_000L, null), null, SUB),
            Triple(noteHit("n7", SPOT, "spotify", "sp-4", null, 5_000L, "Moonlight"), null, SUB),
            Triple(noteHit("n8", APPLE, "applemusic", "am-3", A3, 3_000L, "雨とカプチーノ"), null, null),
            Triple(noteHit("n9", SUB, "subsonic", "tr-1", A5_SUB, -5L, "晴天"), "subsonic" to "tr-1", APPLE),
        )

        val SEEKS: List<Triple<String?, Long, String>> = listOf(
            Triple("tr-1", 200_000L, "tr-1"),
            Triple("tr-1", 0L, "tr-1"),
            Triple("tr-2", 200_000L, "tr-1"),
            Triple(null, 200_000L, "tr-1"),
        )
    }
}
