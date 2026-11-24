package com.gpo.yoin.shared.notes

import com.gpo.yoin.shared.transfer.TrackMatcher
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The golden vectors other ports of [NoteMarkup] and [MentionMatcher] are tested against (HarmonyOS's Dart
 * port: harmony/test/data/notes/note_markup_golden_test.dart). Every expected value is what this Kotlin
 * computes; the test fails when the checked-in file drifts from it.
 *
 * File: shared/src/commonTest/resources/golden/note-markup.json. Offsets are UTF-16 code units (Kotlin
 * string indices). Regenerate after an intentional change:
 * `YOIN_WRITE_NOTE_GOLDEN=1 ./gradlew :shared:testAndroidHostTest --tests '*NoteMarkupGoldenTest*'`.
 *
 * Traditional → Simplified folding is ICU's on a device and a no-op on the JVM host, so the matcher cases
 * run with [TrackMatcher.traditionalFold] set to ICU's single-character mapping for the characters they use.
 */
class NoteMarkupGoldenTest {

    @Test
    fun should_matchCheckedInGolden_when_kotlinComputesEveryVector() {
        val json = goldenJson()
        val file = goldenFile()
        if (System.getenv("YOIN_WRITE_NOTE_GOLDEN") == "1" || !file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(json)
        }
        assertEquals(file.readText(), json)
    }

    private fun goldenFile(): File {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "shared").isDirectory && File(it, "settings.gradle.kts").isFile }
        return File(root, "shared/src/commonTest/resources/golden/note-markup.json")
    }

    /** One vector per line: small, and a drift shows as the lines that changed. */
    private fun goldenJson(): String {
        val sections = linkedMapOf(
            "parse" to parseCorpus().map(::parseVector),
            "serializeMention" to mentionCorpus().map { m -> obj("mention" to mentionJson(m), "link" to str(NoteMarkup.serializeMention(m))) },
            "serializeDocument" to documentCorpus().map(::documentVector),
            "rewriteMentions" to rewriteCorpus(),
            "toggleChecklist" to toggleCorpus(),
            "percent" to percentCorpus(),
            "matcher" to matcherCorpus(),
        )
        val about = "Golden vectors for NoteMarkup / MentionMatcher, generated from :shared Kotlin by " +
            "NoteMarkupGoldenTest (androidHostTest). Do not edit by hand."
        return buildString {
            append("{\n")
            append("  \"about\": ").append(Json.encodeToString(JsonElement.serializer(), str(about))).append(",\n")
            append("  \"offsets\": \"UTF-16 code units\"")
            sections.forEach { (name, vectors) ->
                append(",\n  \"").append(name).append("\": [\n")
                vectors.forEachIndexed { i, vector ->
                    append("    ").append(Json.encodeToString(JsonElement.serializer(), vector))
                    append(if (i == vectors.lastIndex) "\n" else ",\n")
                }
                append("  ]")
            }
            append("\n}\n")
        }
    }

    // ── corpora ──────────────────────────────────────────────────────

    private val andromeda = NoteMention(MentionKind.Track, "applemusic", "1445555555", "Andromeda", "Weyes Blood", "Titanic Rising")
    private val link = NoteMarkup.serializeMention(andromeda)
    private val qingtian = NoteMention(MentionKind.Track, "subsonic", "tr-晴天", "晴天 🌤️", "周杰伦", "叶惠美")
    private val qLink = NoteMarkup.serializeMention(qingtian)

    private fun parseCorpus(): List<String> {
        val fixed = listOf(
            // The Kotlin tests' texts.
            "# 现场版要记得\n- [x] 开头那段哼唱\n- [ ] 第二段副歌的和声\n- 一条\n**这一句**每次都会跟着唱 _轻_",
            "**像 $link 一样**",
            "后来在 [@Andromeda](yoin:song/applemusic:weyes.2) 里",
            "[@X](yoin:album/spotify:1?a=Weyes Blood&al=&zz=1)",
            "[@](yoin:track/spotify:1)",
            "[@Title](yoin:track/spotify:)",
            "[@Title](yoin:movie/spotify:1)",
            "[@Title](yoin:track/spo tify:1)",
            "[@Title](yoin:track/spotify:1",
            "[@Title](https://example.com)",
            "[@Title] (yoin:track/spotify:1)",
            "[Title](yoin:track/spotify:1)",
            "[@Tit\nle](yoin:track/spotify:1)",
            "**unclosed",
            "a * b * c",
            "snake_case_name",
            "2*3*4",
            "_ spaced _",
            "#nospace",
            "#### four",
            "-dash",
            "%E4%B8%AD 100% %zz",
            "- [y] not a box",
            "- [ ]x",
            "[@a\\b](yoin:artist/spotify:1)",
            "",
            "\n\n",
            "plain words",
            "  leading spaces and trailing  ",
            "# h\n## h2\n### h3\n* star item\n• dot item\n- [X] Upper done\n- [ ] ",
            "*star italic* and _under italic_ and **bold _nested_ $link**",
            "***x***",
            "__x__",
            "**a*b**",
            "[@not closed $link",
            "$link$link",
            "_${link}_",
            "a\r\nb",
            "- $link\n- [x] $link done",
            "# 天堂\n- 吉他像雾\n**后来**在 $link 里又听到了同样的温度。",
            "# 标题\n- [x] **做完** $link\n_斜_",
            "  $link & [@Movies](yoin:song/applemusic:999) **x**  ",
            "[@a [@b]$link",
            "**$link** _${link}_",
            "x\n$link\n[@y](yoin:track/s:1",
            "听到_副歌_的时候",
            "听到*副歌*的时候",
            "a*b*",
            "**粗**和_斜_",
            "x_1_",
            "a_b_c",
            "word*ital*ic and 中_文_",
            "# 光\n\n- [x] **光** $link 光  \n-   hi $link",
            "- [ ] a\n- b\n- [X] c",
            "- [ ]",
            "* [ ]",
            "- [x]",
            "前 " + NoteMarkup.serializeMention(NoteMention(MentionKind.Artist, "subsonic", "ar-1", "C:\\Users [x] 苏打绿 🎧", artist = "a=b&c", album = "100%")) + " 后",
            // CJK and emoji: offsets count UTF-16 units, so a surrogate pair is two.
            "🎧 $qLink _🎸_ 🎉",
            "**粗体🎉** 和 *斜体🌙*",
            "# 🎵 標題 🎵\n- [x] 🎶 听完 $qLink\n• 🌸 一条",
            "😀😀 [@😀](yoin:artist/spotify:%F0%9F%98%80) 😀",
            "[@周杰伦](yoin:artist/网易云:12) 网易云 provider",
            "全角＃ 不是标题\n＃ 也不是",
            "ideographic\u3000space: [@\u3000](yoin:track/s:1) _\u3000x_ _x\u3000_",
            "nbsp\u00A0[@\u00A0](yoin:track/s:1) and _x_\u00A0",
            "[@Shouting](YOIN:TRACK/spotify:1) [@Mixed](Yoin:Song/spotify:2)",
            "[@Dotted](yoİn:track/spotify:1) [@Dotless](YOıN:track/spotify:2)",
            "[@Encoded](yoin:album/applemusic:a%2Fb%3Fc%29d%20e?al=%E5%8F%B6%E6%83%A0%E7%BE%8E&a=%E5%91%A8)",
            "[@Bad utf8](yoin:track/s:%E4%B8x?a=%FF%FE&al=%E4) end",
            "[@Fullwidth hex](yoin:track/s:%ＥＡ%ｅａ%Ｅ%41)",
            "[@Esc \\] bracket \\[ and \\\\](yoin:track/s:1) after",
            "[@Trailing backslash\\](yoin:track/s:1)",
            "[@Inner [ bracket](yoin:track/s:1)",
            "[@T](yoin:track/s:1)(yoin:track/s:2)",
            "[@T](yoin:/s:1) [@T](yoin:track/:1) [@T](yoin:track/s) [@T](yoin:track) [@T](yoin:track/s:%20)",
            "[@T](yoin:track/s: id ?a= &al=x)",
            "**[@T](yoin:track/s:1)** _[@U](yoin:track/s:2)_ *[@V](yoin:track/s:3)*",
            "**a [@b**](yoin:track/s:1) c**",
            "_a [@b_](yoin:track/s:1) c_",
            "a__b__c **** ** x ** _x _ * x*",
            "\t- tab first\n - space first\n-  two spaces\n#  two",
            "- [x]  two spaces\n-[x] no space\n- [x]",
            "## \n### x\n# ",
            "…中文**粗**中文_斜_中文*星*中文",
            "x\n",
            "\r",
        )
        val pieces = listOf("a", " ", "*", "**", "_", "[", "]", "(", ")", "@", "[@", "#", "# ", "- ", "- [ ] ", "\n", "中", link, "\\", "yoin:", "🎧", qLink, "x]", "\u3000")
        var seed = 11L
        val random = List(120) {
            buildString {
                repeat(10) {
                    seed = seed * 6364136223846793005L + 1442695040888963407L
                    append(pieces[((seed ushr 33) % pieces.size).toInt()])
                }
            }
        }
        return fixed + random
    }

    private fun mentionCorpus(): List<NoteMention> = listOf(
        andromeda,
        NoteMention(MentionKind.Album, "spotify", "4aawyAB9vmqN3uQ7FjRGTy", "Detour [Rare N’ Deluxe] (Live)", "Kim Petras & Friends"),
        andromeda.copy(id = "library:l.AbC"),
        andromeda.copy(provider = "subsonic", id = "a/b?c)d e&f%"),
        NoteMention(MentionKind.Artist, "subsonic", "ar-1", "C:\\Users [x] 苏打绿 🎧", artist = "a=b&c", album = "100%"),
        qingtian,
        NoteMention(MentionKind.Artist, "spotify", "x", "Line\nbreak\rreturn"),
        NoteMention(MentionKind.Artist, "spotify", "x", "ends with \\"),
        NoteMention(MentionKind.Artist, "spotify", "x", "a\\b \\\\ \\[ \\x"),
        NoteMention(MentionKind.Album, "applemusic", "1", "Blank", artist = "  ", album = ""),
        NoteMention(MentionKind.Track, "subsonic", "~-._:x", "Only album", album = "A~B-C.D_E"),
        NoteMention(MentionKind.Track, "网易云", "😀", "😀 emoji", artist = "🎧"),
    )

    private fun documentCorpus(): List<NoteDocument> = listOf(
        NoteDocument(
            listOf(
                NoteBlock.Heading(2, listOf(NoteInline.Text("Side A"))),
                NoteBlock.Checklist(false, listOf(NoteInline.Text("listen to "), NoteInline.Mention(andromeda))),
                NoteBlock.Bullet(listOf(NoteInline.Bold(listOf(NoteInline.Text("best"))), NoteInline.Text(" "), NoteInline.Italic(listOf(NoteInline.Text("quiet"))))),
                NoteBlock.Paragraph(emptyList()),
                NoteBlock.Paragraph(listOf(NoteInline.Mention(andromeda.copy(kind = MentionKind.Artist, title = "Weyes Blood", album = null)))),
            ),
        ),
        NoteDocument(
            listOf(
                NoteBlock.Paragraph(
                    listOf(
                        NoteInline.Text("word"),
                        NoteInline.Italic(listOf(NoteInline.Text("ital"))),
                        NoteInline.Text("ic and 中"),
                        NoteInline.Italic(listOf(NoteInline.Text("文"))),
                    ),
                ),
            ),
        ),
        NoteDocument(
            listOf(
                NoteBlock.Heading(7, listOf(NoteInline.Text("clamped"))),
                NoteBlock.Heading(0, listOf(NoteInline.Text("clamped"))),
                NoteBlock.Bullet(listOf(NoteInline.Text("odd marker")), marker = '+'),
                NoteBlock.Bullet(listOf(NoteInline.Text("dot")), marker = '•'),
                NoteBlock.Checklist(true, listOf(NoteInline.Text("upper")), marker = '*', upperX = true),
                NoteBlock.Checklist(true, emptyList(), marker = '?'),
                NoteBlock.Paragraph(listOf(NoteInline.Italic(listOf(NoteInline.Text("a")), marker = '*'), NoteInline.Text("1"))),
                NoteBlock.Paragraph(listOf(NoteInline.Text("1"), NoteInline.Italic(listOf(NoteInline.Text("a"))), NoteInline.Bold(listOf(NoteInline.Text("b"))))),
                NoteBlock.Paragraph(listOf(NoteInline.Italic(listOf(NoteInline.Text("x"))), NoteInline.Mention(qingtian), NoteInline.Italic(listOf(NoteInline.Text("y")), marker = '*'))),
                NoteBlock.Paragraph(listOf(NoteInline.Text("🎧"), NoteInline.Italic(listOf(NoteInline.Text("🎸"))), NoteInline.Text("é"))),
            ),
        ),
        NoteDocument.Empty,
    )

    private fun rewriteCorpus(): List<JsonElement> {
        val text = "  $link & [@Movies](yoin:song/applemusic:999) **x** $qLink  "
        val cases = listOf(
            text to mapOf(andromeda.ref to MentionRef(MentionKind.Track, "spotify", "sp1")),
            text to mapOf(MentionRef(MentionKind.Track, "applemusic", "999") to MentionRef(MentionKind.Track, "spotify", "m")),
            text to mapOf(qingtian.ref to MentionRef(MentionKind.Album, "applemusic", "library:l.晴")),
            text to mapOf(andromeda.ref to andromeda.ref),
            text to emptyMap(),
            "no mentions **here**" to mapOf(andromeda.ref to MentionRef(MentionKind.Track, "spotify", "sp1")),
            "[@Andromeda](yoin:track/applemusic:1445555555)" to mapOf(andromeda.ref to MentionRef(MentionKind.Artist, "subsonic", "a b")),
        )
        return cases.map { (input, mapping) ->
            obj(
                "text" to str(input),
                "mapping" to JsonArray(mapping.map { (from, to) -> obj("from" to ref(from), "to" to ref(to)) }),
                "result" to str(NoteMarkup.rewriteMentions(input, mapping)),
            )
        }
    }

    private fun toggleCorpus(): List<JsonElement> {
        val texts = listOf("- [ ] a\n- b\n- [X] c", "* [x] 🎧 done\n- [ ]\n• [ ] dot", "plain")
        return texts.flatMap { text ->
            (-1..text.count { it == '\n' } + 1).map { line ->
                obj("text" to str(text), "line" to JsonPrimitive(line), "result" to str(NoteMarkup.toggleChecklist(text, line)))
            }
        }
    }

    private fun percentCorpus(): List<JsonElement> {
        val encodes = listOf("", "abc-._~", "苏 &", "a/b?c)d e&f%", "🎧", "100%", "a+b", ":")
        val decodes = listOf("%E8%8B%8F%E6%89%93%E7%BB%BF 100% %zz", "a+b", "%", "%4", "%41", "%e8%8b%8f", "%E4%B8", "%FF%FE", "%F0%9F%98%80", "%ＥＡ", "%C3%28", "x%E2%82")
        return encodes.map { obj("op" to str("encode"), "input" to str(it), "output" to str(PercentCoding.encode(it))) } +
            listOf(obj("op" to str("encodeKeepColon"), "input" to str("library:l.AbC/x"), "output" to str(PercentCoding.encode("library:l.AbC/x", keep = ":")))) +
            decodes.map { obj("op" to str("decode"), "input" to str(it), "output" to str(PercentCoding.decode(it))) }
    }

    private fun matcherCorpus(): List<JsonElement> {
        val previous = TrackMatcher.traditionalFold
        val icu = mapOf('蘇' to '苏', '綠' to '绿', '愛' to '爱', '楊' to '杨', '倫' to '伦', '傑' to '杰', '葉' to '叶', '惠' to '惠', '風' to '风')
        TrackMatcher.traditionalFold = { text -> text.map { icu[it] ?: it }.joinToString("") }
        try {
            return buildMatcherCorpus()
        } finally {
            TrackMatcher.traditionalFold = previous
        }
    }

    private fun buildMatcherCorpus(): List<JsonElement> {
        fun track(title: String, artist: String? = null, album: String? = null) =
            NoteMention(MentionKind.Track, "spotify", "sp1", title, artist, album)
        fun album(title: String, artist: String? = null) = NoteMention(MentionKind.Album, "applemusic", "1", title, artist)
        fun artist(name: String) = NoteMention(MentionKind.Artist, "spotify", "2", name)
        val out = ArrayList<JsonElement>()
        fun case(fn: String, mention: NoteMention?, args: List<String?>, result: JsonElement) {
            out += buildJsonObject {
                put("fn", fn)
                if (mention != null) put("mention", mentionJson(mention))
                put("args", JsonArray(args.map(::str)))
                put("result", result)
            }
        }
        listOf(
            Triple(track("Stay (with Justin Bieber)", "The Kid LAROI, Justin Bieber", "F*CK LOVE 3"), listOf("STAY", "The Kid LAROI & Justin Bieber", null), 0),
            Triple(track("他夏了夏天", "苏打绿"), listOf("他夏了夏天", "苏打绿", "夏/狂热"), 0),
            Triple(track("他夏了夏天", "蘇打綠"), listOf("他夏了夏天", "苏打绿", null), 0),
            Triple(track("雨愛", "楊丞琳"), listOf("雨爱", "杨丞琳", null), 0),
            Triple(track("晴天 🌤️", "周杰倫", "葉惠美"), listOf("晴天", "周杰伦", "叶惠美"), 0),
            Triple(track("Intro", "The xx"), listOf("Intro", "M83", null), 0),
            Triple(track("Song", "A", "Album One"), listOf("Song", "A", "Album Two"), 0),
            Triple(track("Song (Live)", "A"), listOf("Song", "A", null), 0),
            Triple(track("Song (Live)", "A"), listOf("Song - Live", "A", null), 0),
            Triple(track("Song - Remastered 2011", "A"), listOf("Song", "A", null), 0),
            Triple(track("Song (feat. B)", "A feat. B"), listOf("Song", "A", null), 0),
            Triple(track("ＳＴＡＹ", "Ｊｕｓｔｉｎ"), listOf("stay", "justin", null), 0),
            Triple(track("Café del Mar", "Beyoncé"), listOf("Cafe Del Mar", "Beyonce", null), 0),
            Triple(track("Hoppípolla", "Sigur Rós"), listOf("Hoppipolla", "Sigur Ros", null), 0),
            Triple(track("🎧"), listOf("🎧", null, null), 0),
            Triple(track("Song"), listOf("Song", "Anyone", "Any album"), 0),
            Triple(track("Song", "A"), listOf("", "A", null), 0),
            Triple(track("Song", "A"), listOf(null, "A", null), 0),
            Triple(album("Charm", "Clairo"), listOf("Charm", "Clairo", null), 0),
        ).forEach { (mention, args, _) ->
            case("namesMatchTrack", mention, args, JsonPrimitive(MentionMatcher.namesMatchTrack(mention, args[0], args[1], args[2])))
        }
        listOf(
            album("Heaven or Las Vegas", "Cocteau Twins") to listOf("Heaven Or Las Vegas", "Cocteau Twins"),
            album("Charm", "Clairo") to listOf("Charm (Deluxe)", "Clairo"),
            album("Charm", "Clairo") to listOf("Charm (Live)", "Clairo"),
            album("夏/狂热", "蘇打綠") to listOf("夏/狂热", "苏打绿"),
            album("葉惠美") to listOf("叶惠美", "周杰伦"),
            album("Titanic Rising", "Weyes Blood") to listOf("Titanic Rising", "Weyes Blood & Friends"),
            album("Titanic Rising", "Weyes Blood") to listOf("Titanic Rising", "Someone Else"),
            track("Charm", "Clairo") to listOf("Charm", "Clairo"),
        ).forEach { (mention, args) ->
            case("namesMatchAlbum", mention, args, JsonPrimitive(MentionMatcher.namesMatchAlbum(mention, args[0], args[1])))
        }
        listOf(
            artist("The Hannah Diamond") to "hannah diamond",
            artist("蘇打綠") to "苏打绿",
            artist("Beyoncé") to "BEYONCE",
            artist("ＡＢＢＡ") to "abba",
            artist("Weyes Blood") to "Weyes Blood & Friends",
            artist("🎧") to "🎧",
            artist("A") to null,
            artist("A") to "  ",
            track("A") to "A",
        ).forEach { (mention, name) ->
            case("namesMatchArtist", mention, listOf(name), JsonPrimitive(MentionMatcher.namesMatchArtist(mention, name)))
        }
        listOf(
            track("Andromeda", "Weyes Blood", "Titanic Rising") to listOf("Titanic Rising", "Weyes Blood"),
            track("Andromeda", "Weyes Blood") to listOf("Titanic Rising", "Weyes Blood"),
            track("晴天", "周杰倫", "葉惠美") to listOf("叶惠美", "周杰伦"),
            track("Andromeda", null, "Titanic Rising (Deluxe)") to listOf("Titanic Rising", "Weyes Blood"),
            album("Titanic Rising", "Weyes Blood") to listOf("Titanic Rising", "Weyes Blood"),
        ).forEach { (mention, args) ->
            case("trackMentionOnAlbum", mention, args, JsonPrimitive(MentionMatcher.trackMentionOnAlbum(mention, args[0], args[1])))
        }
        listOf(
            "Stay" to "STAY", "Song (Live)" to "Song (Live)", "Song (Live)" to "Song (Remix)", "Song" to null, null to null,
            "  " to "  ", "雨愛" to "雨爱", "🎵" to "🎶", "Song - Acoustic" to "Song (Acoustic)",
        ).forEach { (a, b) ->
            case("titlesMatch", null, listOf(a, b), JsonPrimitive(MentionMatcher.titlesMatch(a, b)))
        }
        listOf(
            "A & B" to "A", "A" to "B", null to "A", "A" to "", "蘇打綠" to "苏打绿", "A, B" to "B / C",
        ).forEach { (a, b) ->
            case("artistsMatch", null, listOf(a, b), JsonPrimitive(MentionMatcher.artistsMatch(a, b)))
        }
        listOf(
            NoteMention(MentionKind.Track, "spotify", "sp1", "x") to listOf("spotify", "sp1"),
            NoteMention(MentionKind.Track, "spotify", "sp1", "x") to listOf("applemusic", "sp1"),
            NoteMention(MentionKind.Track, "spotify", "sp1", "x") to listOf("spotify", "SP1"),
        ).forEach { (mention, args) ->
            case("matchesId", mention, args, JsonPrimitive(MentionMatcher.matchesId(mention, args[0], args[1])))
        }
        listOf("Stay (feat. Justin Bieber)", "   ", "晴天 (Live)", "Song - Remastered 2011", "(feat. X)", "🎧 Song ft. Y").forEach { title ->
            case("searchPrefix", null, listOf(title), str(MentionMatcher.searchPrefix(title)))
        }
        return out
    }

    // ── encoding ─────────────────────────────────────────────────────

    private fun parseVector(text: String): JsonElement {
        val doc = NoteMarkup.parse(text)
        val snippet = NoteMarkup.snippetWithMentions(text)
        return buildJsonObject {
            put("text", text)
            put("hasMentions", NoteMarkup.hasMentions(text))
            put("blocks", blocks(doc.blocks))
            put("serialized", NoteMarkup.serialize(doc))
            put("mentions", JsonArray(NoteMarkup.mentionSpans(text).map(::span)))
            put("plain", NoteMarkup.plainText(text))
            put("plainSpaced", NoteMarkup.plainText(text, " "))
            put("snippet", obj("text" to str(snippet.text), "mentions" to JsonArray(snippet.mentions.map(::span))))
            put("neoDb", NoteMarkup.toNeoDb(text))
            put("layout", JsonArray(NoteMarkup.layout(text).map(::line)))
        }
    }

    private fun documentVector(document: NoteDocument): JsonElement {
        val text = NoteMarkup.serialize(document)
        return obj("blocks" to blocks(document.blocks), "text" to str(text), "reparsed" to blocks(NoteMarkup.parse(text).blocks))
    }

    private fun blocks(blocks: List<NoteBlock>): JsonArray = JsonArray(blocks.map(::block))

    private fun block(block: NoteBlock): JsonElement = buildJsonObject {
        when (block) {
            is NoteBlock.Paragraph -> put("type", "paragraph")
            is NoteBlock.Heading -> {
                put("type", "heading")
                put("level", block.level)
            }
            is NoteBlock.Bullet -> {
                put("type", "bullet")
                put("marker", block.marker.toString())
            }
            is NoteBlock.Checklist -> {
                put("type", "checklist")
                put("checked", block.checked)
                put("marker", block.marker.toString())
                put("upperX", block.upperX)
            }
        }
        put("inlines", inlines(block.inlines))
    }

    private fun inlines(nodes: List<NoteInline>): JsonArray = JsonArray(
        nodes.map { node ->
            when (node) {
                is NoteInline.Text -> obj("text" to str(node.text))
                is NoteInline.Bold -> obj("bold" to inlines(node.children))
                is NoteInline.Italic -> obj("italic" to inlines(node.children), "marker" to str(node.marker.toString()))
                is NoteInline.Mention -> obj("mention" to mentionJson(node.mention))
            }
        },
    )

    private fun mentionJson(m: NoteMention): JsonElement = buildJsonObject {
        put("kind", m.kind.token)
        put("provider", m.provider)
        put("id", m.id)
        put("title", m.title)
        put("artist", str(m.artist))
        put("album", str(m.album))
    }

    private fun ref(r: MentionRef): JsonElement = obj("kind" to str(r.kind.token), "provider" to str(r.provider), "id" to str(r.id))

    private fun span(s: MentionSpan): JsonElement =
        obj("start" to JsonPrimitive(s.start), "end" to JsonPrimitive(s.end), "mention" to mentionJson(s.mention))

    private fun line(l: LineLayout): JsonElement = obj(
        "start" to JsonPrimitive(l.start),
        "end" to JsonPrimitive(l.end),
        "contentStart" to JsonPrimitive(l.contentStart),
        "inlines" to JsonArray(
            l.inlines.map { r ->
                obj(
                    "kind" to str(r.kind.name.lowercase()),
                    "start" to JsonPrimitive(r.start),
                    "end" to JsonPrimitive(r.end),
                    "innerStart" to JsonPrimitive(r.innerStart),
                    "innerEnd" to JsonPrimitive(r.innerEnd),
                )
            },
        ),
    )

    private fun str(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    private fun obj(vararg entries: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*entries))
}
