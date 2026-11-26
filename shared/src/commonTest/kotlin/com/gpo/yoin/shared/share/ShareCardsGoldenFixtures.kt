package com.gpo.yoin.shared.share

import com.gpo.yoin.shared.share.ShareCardsGolden.contentJson
import com.gpo.yoin.shared.share.ShareCardsGolden.entryJson
import com.gpo.yoin.shared.share.ShareCardsGolden.ints
import com.gpo.yoin.shared.share.ShareCardsGolden.lineJson
import com.gpo.yoin.shared.share.ShareCardsGolden.lyricsJson
import com.gpo.yoin.shared.share.ShareCardsGolden.noteJson
import com.gpo.yoin.shared.share.ShareCardsGolden.num
import com.gpo.yoin.shared.share.ShareCardsGolden.obj
import com.gpo.yoin.shared.share.ShareCardsGolden.sourcesJson
import com.gpo.yoin.shared.share.ShareCardsGolden.str
import com.gpo.yoin.shared.share.ShareCardsGolden.strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The inputs of every share-card golden vector (the outputs are computed: [ShareCardsGolden.outputs]). Each covers
 * one rule or edge; `name` says which. Labels are Android's own strings (en, zh-CN), pinned by the app's
 * `ShareCardsPinTest`.
 */
internal object ShareCardsGoldenFixtures {

    val labels: Map<String, ShareAboutLabels> = linkedMapOf(
        "zh" to ShareAboutLabels(
            lyricist = "作词",
            composer = "作曲",
            arranger = "编曲",
            producer = "制作人",
            creationTime = "创作时间",
            creationLocation = "创作地点",
            year = "年份",
            review = "关于",
        ),
        "en" to ShareAboutLabels(
            lyricist = "Lyricist",
            composer = "Composer",
            arranger = "Arranger",
            producer = "Producer",
            creationTime = "Creation Time",
            creationLocation = "Creation Location",
            year = "Year",
            review = "About",
        ),
    )

    private fun v(name: String, vararg fields: Pair<String, JsonElement>): JsonObject = obj("name" to str(name), *fields)

    private fun line(startMs: Long?, text: String, translation: String? = null) = ShareLyricLine(startMs, text, translation)

    private fun timed(vararg text: String) = text.mapIndexed { i, t -> line(60_000L + i * 4_000L, t) }

    private fun pick(lines: List<ShareLyricLine>, showTranslation: Boolean = false, passageStarts: List<Int> = emptyList()) =
        ShareLyrics(lines, passageStarts, showTranslation)

    private fun song(durationMs: Long = 300_000L, title: String = "晴天", artist: String = "周杰倫") =
        ShareSong(songId = "song-1", title = title, artist = artist, coverArtUrl = "https://example.com/c.jpg", albumName = "叶惠美", durationMs = durationMs)

    private val review = ShareAbout(
        credits = listOf(ShareCredit("作词", "周杰倫"), ShareCredit("年份", "2003", fact = true)),
        heading = "关于",
        paragraph = "《晴天》收录于 2003 年专辑《叶惠美》。",
    )
    private val creditsOnly = ShareAbout(credits = listOf(ShareCredit("作曲", "周杰倫")), heading = "关于", paragraph = null)
    private val yearOnly = ShareAbout(credits = listOf(ShareCredit("年份", "2003", fact = true)), heading = "关于", paragraph = null)
    private val anchoredNote = ShareNote("n1", "副歌那句 **刮风这天**", positionMs = 95_000L, createdAt = 1_700_000_000_000L)

    private val cjkPick = pick(timed("故事的小黄花", "从出生那年就飘着"))

    private fun content(
        name: String,
        c: ShareCardContent,
        remembered: Map<ShareTab, String?> = emptyMap(),
    ) = v(
        name,
        "content" to contentJson(c),
        "remembered" to JsonObject(ShareTab.entries.associate { it.storeKey to str(remembered[it]) }),
    )

    private val all = ShareCardContent(song(), cjkPick, review, anchoredNote)

    private val isCjk = listOf(
        "traditional Chinese" to "帶著笑容的睡意化成了彩虹",
        "simplified Chinese with punctuation" to "故事的小黄花，从出生那年就飘着。",
        "Japanese kana and kanji with a comma and digits" to "夜に駆ける、2019",
        "hiragana only" to "さよなら",
        "katakana only" to "サヨナラ",
        "katakana with the prolonged sound mark ー (CJK)" to "ラーメン",
        "ー alone" to "ーー",
        "halfwidth katakana" to "ｻﾖﾅﾗ",
        "halfwidth katakana with ｰ (CJK)" to "ﾗｰﾒﾝ",
        "halfwidth voiced marks ﾞ ﾟ" to "ｶﾞｷﾞﾊﾟ",
        "ー with Latin is still not CJK" to "ラーメン ramen",
        "iteration mark 々 is Han" to "時々",
        "〆 counts as CJK" to "締〆",
        "〆 alone" to "〆",
        "ideographic zero 〇 is not a letter (alone: not CJK)" to "〇",
        "〇 beside Han" to "二〇一九年",
        "Korean Hangul syllables" to "사랑해요",
        "Korean with a space and punctuation" to "보고 싶다, 정말!",
        "Hangul compatibility jamo" to "ㅋㅋㅋ",
        "Bopomofo" to "ㄅㄆㄇㄈ",
        "Chinese with an English word" to "我爱你 baby",
        "Japanese with romaji" to "君の名は Your Name",
        "Korean with Latin" to "Love 사랑",
        "Latin" to "If I could go back",
        "digits and punctuation only" to "1, 2, 3!",
        "empty" to "",
        "spaces only" to "   ",
        "CJK punctuation only" to "「」。、",
        "fullwidth Latin letters (Latin script)" to "ＡＢＣ",
        "fullwidth digits beside Han" to "２０１９年",
        "Greek" to "αγάπη",
        "Cyrillic" to "любовь",
        "Thai" to "ฉันรักเธอ",
        "Han with a BMP emoji" to "我爱你❤",
        "Han with a supplementary emoji" to "晴天😀",
        "CJK Extension B (supplementary Han)" to "𠀀𠀁𠀂",
        "mathematical bold A (Common letter)" to "我𝐀",
        "music notes around Han" to "♪ 夢 ♪",
        "zero-width space inside Han" to "中​文",
        "private-use character beside Han" to "中",
        "ideographic space between Han" to "中　文",
        "Han with a tab" to "中文\t日本語",
        "Chinese with a middle dot and English" to "中文·English",
        "a single Latin w" to "w",
        "Han with www" to "草 www",
        "Arabic" to "حبيبي",
    ).map { (name, text) -> v(name, "text" to str(text)) }

    private val contents = listOf(
        content("everything: CJK lines, About with a paragraph, an anchored note, a known length", all),
        content("Latin lines: no Vertical", all.copy(lyrics = pick(timed("If I could go back", "To the start")))),
        content("mixed CJK and Latin lines: no Vertical", all.copy(lyrics = pick(timed("帶著笑容", "Before I ever fell down")))),
        content("Japanese with ー: Vertical", all.copy(lyrics = pick(timed("ラーメンを食べた", "スーパーカー")))),
        content("Korean lines: Vertical", all.copy(lyrics = pick(timed("사랑해요", "보고 싶다")))),
        content("About credits only: no Quote", all.copy(about = creditsOnly)),
        content("About with only the year: no About tab", all.copy(about = yearOnly)),
        content("About with nothing at all: no About tab", all.copy(about = ShareAbout(emptyList(), "关于", null))),
        content("About paragraph without credits", all.copy(about = ShareAbout(emptyList(), "谁写的", "答案。"))),
        content("no About", all.copy(about = null)),
        content("an unanchored note: no Timeline", all.copy(note = anchoredNote.copy(positionMs = null))),
        content("an anchored note, the length unknown: no Timeline", all.copy(song = song(durationMs = 0L))),
        content("a blank note: no Note tab", all.copy(note = anchoredNote.copy(content = "  \n "))),
        content("no lyric lines: no Lyrics tab", all.copy(lyrics = pick(emptyList()))),
        content("blank lyric lines still count (the Poster skips them)", all.copy(lyrics = pick(listOf(line(null, " "))))),
        content("nothing to share", ShareCardContent(song(), null, null, null)),
        content(
            "remembered layouts it offers",
            all,
            mapOf(ShareTab.Lyrics to "LyricsVertical", ShareTab.About to "AboutLiner", ShareTab.Note to "NoteTimeline"),
        ),
        content(
            "remembered layouts it doesn't offer fall back to the first",
            all.copy(lyrics = pick(timed("Before I ever fell down")), about = creditsOnly, note = anchoredNote.copy(positionMs = null)),
            mapOf(ShareTab.Lyrics to "LyricsVertical", ShareTab.About to "AboutQuote", ShareTab.Note to "NoteTimeline"),
        ),
        content(
            "remembered values from another tab or a dropped layout read as none",
            all,
            mapOf(ShareTab.Lyrics to "NoteBig", ShareTab.About to "AboutPoster", ShareTab.Note to ""),
        ),
    )

    private val layoutStore = listOf(
        Triple("a lyrics layout", "Lyrics", "LyricsPoster"),
        Triple("an About layout", "About", "AboutLiner"),
        Triple("a note layout", "Note", "NoteClipping"),
        Triple("another tab's layout", "Lyrics", "AboutReceipt"),
        Triple("a dropped layout", "Note", "NoteSticker"),
        Triple("a different case", "Lyrics", "lyricsposter"),
        Triple("nothing stored", "About", null),
    ).map { (name, tab, value) -> v(name, "tab" to str(tab), "value" to str(value)) }

    private val currentLines = run {
        val lyrics = listOf(
            line(10_000L, "one", "一"),
            line(14_000L, ""),
            line(18_000L, "two", "二"),
            line(22_000L, "three"),
        )
        listOf(
            Triple("between two lines: the one started", lyrics, 19_500L),
            Triple("exactly at a line's start", lyrics, 22_000L),
            Triple("before the first line: the first", lyrics, 0L),
            Triple("after the last line: the last", lyrics, 600_000L),
            Triple("a blank line is never the current one", lyrics, 15_000L),
            Triple("untimed lyrics: the first", listOf(line(null, "a"), line(null, "b")), 50_000L),
            Triple("untimed lines first, then timed", listOf(line(null, "intro"), line(30_000L, "verse")), 31_000L),
            Triple("only blank lines: nothing", listOf(line(1_000L, " "), line(2_000L, "")), 5_000L),
            Triple("no lyrics: nothing", emptyList(), 0L),
        ).flatMap { (name, lines, at) ->
            listOf(false, true).map { show ->
                v(
                    "$name${if (show) " (translation shown)" else ""}",
                    "lines" to JsonArray(lines.map(::lineJson)),
                    "positionMs" to JsonPrimitive(at),
                    "showTranslation" to JsonPrimitive(show),
                )
            }
        }
    }

    private val passageStarts = listOf(
        Triple("nothing picked", emptyList<Int>(), 10),
        Triple("one line", listOf(3), 10),
        Triple("a run", listOf(1, 2, 3), 10),
        Triple("two gaps", listOf(1, 2, 5, 6, 9), 10),
        Triple("out of order", listOf(9, 1, 2), 10),
        Triple("out of range ignored", listOf(-1, 2, 3, 100), 10),
    ).map { (name, selected, count) -> v(name, "selected" to ints(selected), "lineCount" to JsonPrimitive(count)) }

    private val captions = listOf(
        Triple("title and artist", "晴天", "周杰倫"),
        Triple("trimmed", "  Hey Jude ", " The Beatles  "),
        Triple("no artist", "Untitled", null),
        Triple("blank artist", "Untitled", "  "),
        Triple("blank title: none", "  ", "Someone"),
        Triple("no title: none", null, "Someone"),
    ).map { (name, title, artist) -> v(name, "title" to str(title), "artist" to str(artist)) }

    private val cardLineSizes = listOf(
        "one short line" to listOf("故事的小黄花"),
        "four lines, 90 characters" to List(4) { "a".repeat(if (it == 0) 21 else 23) },
        "five short lines" to List(5) { "short" },
        "91 characters in two lines" to listOf("a".repeat(45), "b".repeat(46)),
        "200 characters" to listOf("a".repeat(100), "b".repeat(100)),
        "201 characters" to listOf("a".repeat(100), "b".repeat(101)),
        "nothing" to emptyList(),
    ).map { (name, lines) -> v(name, "lines" to strings(lines)) }

    private val posters = run {
        val six = (1..6).map { line(it * 1_000L, "Line $it", "第 $it 行") }
        listOf(
            "six lines: four shown, the translations on one line" to pick(six, showTranslation = true),
            "six lines, translation hidden" to pick(six),
            "blank lines skipped before the cap" to pick(
                listOf(line(1_000L, " "), line(2_000L, "a"), line(3_000L, ""), line(4_000L, "b"), line(5_000L, "c"), line(6_000L, "d"), line(7_000L, "e")),
            ),
            "some translations missing or blank" to pick(
                listOf(line(1_000L, "one", " 一 "), line(2_000L, "two", null), line(3_000L, "three", "  ")),
                showTranslation = true,
            ),
            "no translations at all, shown" to pick(timed("one", "two"), showTranslation = true),
            "a lead of 12 UTF-16 units" to pick(listOf(line(65_000L, "a".repeat(12)))),
            "a lead of 13" to pick(listOf(line(65_000L, "a".repeat(13)))),
            "a lead of 24" to pick(listOf(line(65_000L, "故".repeat(24)))),
            "a lead of 25" to pick(listOf(line(65_000L, "故".repeat(25)))),
            "a lead of 40" to pick(listOf(line(65_000L, "a".repeat(40)))),
            "a lead of 41" to pick(listOf(line(65_000L, "a".repeat(41)))),
            "an emoji counts two units" to pick(listOf(line(65_000L, "😀".repeat(6) + "a"))),
            "an untimed lead: no time" to pick(listOf(line(null, "untimed"), line(5_000L, "timed"))),
            "an hour in" to pick(listOf(line(3_725_000L, "late"))),
            "only blank lines: none" to pick(listOf(line(1L, " "))),
            "no lines: none" to pick(emptyList()),
        ).map { (name, p) -> v(name, "pick" to lyricsJson(p)) }
    }

    private val excerpts = listOf(
        Triple("one line: 6% of the bar", pick(timed("one")), 300_000L),
        Triple("at the very end: kept inside the bar", pick(listOf(line(299_000L, "x"))), 300_000L),
        Triple("at the very start", pick(listOf(line(0L, "x"))), 300_000L),
        Triple("a long span", pick((0 until 12).map { line(30_000L + it * 5_000L, "l$it") }), 240_000L),
        Triple("a span just under 6%", pick(listOf(line(10_000L, "a"), line(18_000L, "b"))), 240_000L),
        Triple("out of order times", pick(listOf(line(90_000L, "b"), line(60_000L, "a"))), 300_000L),
        Triple("mixed timed and untimed", pick(listOf(line(null, "a"), line(120_000L, "b"))), 300_000L),
        Triple("a time past the end", pick(listOf(line(400_000L, "x"))), 300_000L),
        Triple("a short song", pick(timed("x")), 20_000L),
        Triple("length unknown: none", pick(timed("one")), 0L),
        Triple("untimed: none", pick(listOf(line(null, "untimed"))), 300_000L),
    ).map { (name, p, d) -> v(name, "pick" to lyricsJson(p), "durationMs" to JsonPrimitive(d)) }

    private val verticalPlans = listOf(
        Triple("two lines, one column each", listOf("帶著笑容的睡意化成了彩虹", "在他夢中一口氣走上了星空"), 250f),
        Triple("twelve lines step the size down", (1..12).map { "第${it}行歌词在这里" }, 250f),
        Triple("one very long line carries on", listOf("故".repeat(40)), 250f),
        Triple("spaces set as ideographic spaces", listOf("보고 싶다 정말", "사랑해요"), 250f),
        Triple("blank lines dropped, ends trimmed", listOf("  晴天  ", " ", "", "雨天"), 250f),
        Triple("too many to fit at any size: the smallest", (1..30).map { "第${it}行" }, 250f),
        Triple("a narrow frame", listOf("帶著笑容", "在他夢中", "走上星空"), 120f),
        Triple("with the footnote's width taken", listOf("帶著笑容的睡意化成了彩虹", "在他夢中一口氣走上了星空"), 228f),
        Triple("nothing", emptyList(), 250f),
    ).map { (name, lines, width) ->
        v(name, "lines" to strings(lines), "columnHeightDp" to num(YoinShareCards.verticalColumnHeightDp), "widthDp" to num(width))
    }

    private val verticalLyrics = listOf(
        "translations shown: the footnote and a narrower frame" to pick(
            listOf(line(1L, "帶著笑容的睡意化成了彩虹", " With a smile "), line(2L, "在他夢中一口氣走上了星空", "In his dream")),
            showTranslation = true,
        ),
        "translations hidden" to pick(listOf(line(1L, "帶著笑容", "With a smile"))),
        "shown, but none present" to pick(listOf(line(1L, "帶著笑容"), line(2L, "在他夢中", " ")), showTranslation = true),
    ).map { (name, p) -> v(name, "pick" to lyricsJson(p)) }

    private val verticalTexts = listOf(
        Triple("a CJK title", "晴天", 14f),
        Triple("a long CJK title in two columns", "故".repeat(30), 14f),
        Triple("a Latin artist turns sideways", "Jay Chou", 12f),
        Triple("digits only stack", "1989", 14f),
        Triple("mixed turns sideways", "周杰倫 Jay", 12f),
        Triple("the footnote", "歌词大意：With a smile；In his dream", 11f),
        Triple("the Chinese footnote", "歌词大意：带着笑容的睡意化成了彩虹；在他梦中一口气走上了星空；" + "故".repeat(20), 11f),
        Triple("Japanese with ー stacks", "スーパーカー", 14f),
        Triple("halfwidth ｰ stacks", "ﾗｰﾒﾝ", 14f),
        Triple("empty", "", 14f),
    ).map { (name, text, size) ->
        v(name, "text" to str(text), "sizeSp" to num(size), "columnHeightDp" to num(YoinShareCards.verticalColumnHeightDp))
    }

    private val verticalForms = listOf(
        "Chinese punctuation" to "故事的小黄花，从出生那年就飘着。",
        "corner brackets and the comma" to "「夜に駆ける」、2019！",
        "white corner brackets and title marks" to "『晴天』《叶惠美》〈序〉",
        "brackets both widths" to "(笑)（笑）",
        "the colon, semicolon and question" to "问：真的；吗？",
        "ellipsis and dashes" to "等待……——－-ー",
        "tildes" to "～~",
        "untouched" to "Hello 世界 123",
    ).map { (name, text) -> v(name, "text" to str(text)) }

    private fun entry(kind: String, key: String, answer: String, title: String? = null, prompt: String? = title) =
        ShareAboutEntry(kind, key, prompt, title, answer)

    private val canonLyricist = entry("canonical", "lyricist", "Wu Qingfeng")
    private val canonReview = entry("canonical", "review", "The review.")
    private val ask = entry("ask", "who sampled it", "The answer.", title = "Who sampled it")
    private val own = entry("own", "own-1", "My own words.", title = "Mine")

    private fun about(
        name: String,
        entries: List<ShareAboutEntry>,
        sources: ShareAboutSources = ShareAboutSources(null, null, null, null, null, null),
        labels: String = "en",
        focus: ShareAboutEntry? = null,
    ) = v(
        name,
        "entries" to JsonArray(entries.map(::entryJson)),
        "sources" to sourcesJson(sources),
        "labels" to str(labels),
        "focusKind" to str(focus?.kind),
        "focusKey" to str(focus?.entryKey),
    )

    private val serverFacts = ShareAboutSources(null, null, null, null, serverComposer = "Wu Qingfeng", year = 2009)
    private val fullEntries = listOf(canonLyricist, canonReview, ask, own)

    private val abouts = listOf(
        about("AI lyricist, the server's composer, the year, the review", fullEntries, serverFacts),
        about("the same in Chinese labels", fullEntries, serverFacts, labels = "zh"),
        about("a long-pressed answer is the paragraph", fullEntries, serverFacts, focus = ask),
        about("a long-pressed own entry never is: the review", fullEntries, serverFacts, focus = own),
        about("the review long-pressed", fullEntries, serverFacts, focus = canonReview),
        about("an unknown focus: the review", fullEntries, serverFacts, focus = entry("ask", "gone", "x")),
        about(
            "an answer without a title is headed by its question",
            listOf(canonReview, entry("ask", "k", "Answer.", title = null, prompt = "What is it about?")),
            focus = entry("ask", "k", ""),
        ),
        about(
            "an answer with neither title nor question has an empty heading",
            listOf(entry("ask", "k", "Answer.", title = " ", prompt = null)),
            focus = entry("ask", "k", ""),
        ),
        about("the listener's own entry only: nothing", listOf(own)),
        about("the year alone: nothing", listOf(own), ShareAboutSources(null, null, null, null, null, 2009)),
        about("the server's composer and the year: no AI words", emptyList(), serverFacts),
        about(
            "the lyrics' credits win over the AI's; the AI's time and place follow",
            listOf(
                entry("canonical", "lyricist", "AI lyricist"),
                entry("canonical", "composer", "AI composer"),
                entry("canonical", "producer", "AI producer"),
                entry("canonical", "creation_time", "2003年"),
                entry("canonical", "creation_location", "台北"),
                canonReview,
            ),
            ShareAboutSources("方文山", "周杰倫", "林迈可", "周杰倫 / 钟兴民", serverComposer = "Server Composer", year = 2003),
            labels = "zh",
        ),
        about(
            "the AI's rows where the lyrics have no credit",
            listOf(
                entry("canonical", "lyricist", "AI lyricist"),
                entry("canonical", "composer", "AI composer"),
                entry("canonical", "producer", "AI producer"),
            ),
            ShareAboutSources(null, null, "Arranger Name", null, null, null),
        ),
        about(
            "whitespace in values folds to one space",
            listOf(entry("canonical", "creation_location", "  Taipei,\n\tTaiwan  "), entry("canonical", "review", "\n  Para\n\ngraph.  \n")),
            ShareAboutSources("  Fang   Wenshan ", null, null, null, null, null),
        ),
        about(
            "the AI's **bold** stays in the paragraph and values (drawn through boldRuns)",
            listOf(entry("canonical", "producer", "**周杰倫**"), entry("canonical", "review", "这首歌是**周杰倫**写给外婆的。")),
            labels = "zh",
        ),
        about("a blank AI row is skipped", listOf(entry("canonical", "lyricist", " \n "), canonReview)),
        about(
            "blank lyrics credits are no credits: the AI's rows show",
            listOf(entry("canonical", "lyricist", "AI lyricist"), entry("canonical", "producer", "AI producer")),
            ShareAboutSources(" ", null, "  ", "\t", null, null),
        ),
        about(
            "a blank server composer is no composer: the AI's shows",
            listOf(entry("canonical", "composer", "AI composer")),
            ShareAboutSources(null, null, null, null, serverComposer = "   ", year = null),
        ),
        about("a blank review: no paragraph", listOf(entry("canonical", "review", "  "), canonLyricist)),
        about("an answer alone, not long-pressed: nothing (only the review is the default)", listOf(ask)),
        about(
            "duplicate canonical rows: the last one counts",
            listOf(entry("canonical", "lyricist", "First"), entry("canonical", "lyricist", "Second")),
        ),
        about(
            "two reviews: the first non-blank",
            listOf(entry("canonical", "review", "First review."), entry("canonical", "review", "Second review.")),
        ),
        about(
            "five credits (Quote shows 3, Liner 4)",
            listOf(entry("canonical", "creation_time", "2003"), entry("canonical", "creation_location", "Taipei")),
            ShareAboutSources("A", "B", "C", "D", null, 2003),
        ),
        about("nothing at all", emptyList()),
    )

    private val aiSigns = listOf("en" to "By %1\$s", "zh" to "由 %1\$s 整理").flatMap { (lang, template) ->
        listOf("Gemini", "DeepSeek", "Qwen", "千问", "AI").map { provider ->
            v("$lang $provider", "template" to str(template), "provider" to str(provider))
        }
    } + listOf(
        v("a template without spaces", "template" to str("由%1\$s整理"), "provider" to str("千问")),
        v("a full-width name", "template" to str("由 %1\$s 整理"), "provider" to str("ＡＩ")),
    )

    private val boldRuns = listOf(
        "plain" to "收录于 2003 年专辑《叶惠美》。",
        "a bold run" to "这首歌是**周杰倫**写给外婆的。",
        "underscores" to "It opens the album __Ye Hui Mei__ (2003).",
        "two runs" to "**作词**方文山，**作曲**周杰倫",
        "an unclosed marker stays" to "**未闭合的粗体",
        "a single asterisk and underscore stay" to "a * b _ c",
        "single-delimited italics stay as written" to "_italic_ and *em*",
        "three asterisks" to "***x***",
        "an empty bold run is dropped" to "a****b",
        "the other marker inside" to "__a**b__",
        "across a line break" to "**第一行\n第二行**之后",
        "a bold run at the end" to "Recorded at **Alfa Studio**",
        "markers only" to "****",
        "empty" to "",
    ).map { (name, text) -> v(name, "text" to str(text)) }

    private fun noteVector(name: String, notes: List<ShareNote>, noteId: String?, positionMs: Long, durationMs: Long = 300_000L) = v(
        name,
        "notes" to JsonArray(notes.map(::noteJson)),
        "noteId" to str(noteId),
        "positionMs" to JsonPrimitive(positionMs),
        "durationMs" to JsonPrimitive(durationMs),
    )

    private fun note(id: String, at: Long?, created: Long, content: String = "note $id") = ShareNote(id, content, at, created)

    private val threeNotes = listOf(note("a", 10_000L, 1L), note("b", 50_000L, 2L), note("c", null, 3L))

    private val longNote = buildString {
        append("## 第一次听\n")
        append("那年夏天在 [@晴天](yoin:track/applemusic:1445555555?a=%E5%91%A8%E6%9D%B0%E5%80%AB&al=%E5%8F%B6%E6%83%A0%E7%BE%8E) 里，")
        append("**刮风这天**我试过握着你手。\n\n")
        append("- 前奏的吉他\n- _副歌_ 的鼓\n- [x] 学会弹\n\n")
        append("And then [@Hey Jude](yoin:track/spotify:0aym2LBJBk9DAYuHHutrIl?a=The%20Beatles) came on. ")
        append("故事的小黄花，从出生那年就飘着。".repeat(12))
    }

    private val notes = listOf(
        noteVector("the asked note", threeNotes, "a", 60_000L),
        noteVector("the note the playhead is in", threeNotes, null, 60_000L),
        noteVector("before every anchor: the newest", threeNotes, null, 1_000L),
        noteVector("an asked blank note: the playhead's", threeNotes + note("blank", 5_000L, 9L, content = " \n"), "blank", 60_000L),
        noteVector("an asked id not found: the playhead's", threeNotes, "zzz", 60_000L),
        noteVector("two at the same moment: the later in the list", listOf(note("x", 20_000L, 1L), note("y", 20_000L, 2L)), null, 30_000L),
        noteVector("all unanchored, the same age: the first", listOf(note("p", null, 5L), note("q", null, 5L)), null, 30_000L),
        noteVector("exactly at an anchor", threeNotes, null, 50_000L),
        noteVector("an anchor past the song's end: the marker at the end", listOf(note("late", 400_000L, 1L)), "late", 0L),
        noteVector("the song's length unknown: no timeline", threeNotes, "b", 0L, durationMs = 0L),
        noteVector("an unanchored note: no time chip, no timeline", threeNotes, "c", 0L),
        noteVector("a long note with a heading, mentions, bold, a list", listOf(note("long", 125_000L, 1L, content = longNote)), null, 130_000L),
        noteVector("no notes: none", emptyList(), null, 0L),
        noteVector("only blank notes: none", listOf(note("e", 1L, 1L, content = "   ")), null, 10L),
    )

    private val times = listOf(0L, 999L, 1_000L, 65_000L, 599_000L, 3_599_000L, 3_600_000L, 3_725_000L, -5_000L)
        .map { ms -> v("$ms ms", "ms" to JsonPrimitive(ms)) }

    private val tones = ShareRole.entries.flatMap { role ->
        ShareTone.entries.map { tone -> v("$tone for $role", "tone" to str(tone.name), "role" to str(role.name)) }
    }

    private val retones = listOf(
        "#FF6750A4" to 40, "#FF6750A4" to 90, "#FFB8C4FF" to 40, "#FFB8C4FF" to 90, "#FF8BD8B5" to 40, "#FF8BD8B5" to 90,
        "#FFFF0000" to 40, "#FF808080" to 40, "#FF000000" to 90, "#FFFFFFFF" to 40, "#FF3A5F0B" to 90, "#FFFFD8E4" to 40,
    ).map { (argb, tone) -> v("$argb at T$tone", "argb" to str(argb), "tone" to JsonPrimitive(tone)) }

    private val storyZooms = listOf(
        Triple("a card that fits even at 1.4", 1.4f, 2f),
        Triple("a card that fits up to 1.2", 1.4f, 1.2f),
        Triple("a card that fits exactly at 1", 1.4f, 1f),
        Triple("a card that fits from 0.62 down", 1.4f, 0.62f),
        Triple("a card that never fits: the minimum", 1.4f, 0f),
        Triple("a poster that fits", 1f, 1f),
        Triple("a poster that fits from 0.62 down", 1f, 0.62f),
        Triple("a poster that fits from 0.41 down", 1f, 0.41f),
        Triple("a poster that never fits", 1f, 0f),
    ).map { (name, max, limit) -> v(name, "maxZoom" to num(max), "fitsUpTo" to num(limit)) }

    private val storyFits = listOf(
        Triple(496, 1f, 496), Triple(497, 1f, 496), Triple(354, 1.4f, 496), Triple(355, 1.4f, 496), Triple(800, 0.62f, 496),
        Triple(1000, 0.4f, 400), Triple(1002, 0.4f, 400), Triple(1003, 0.4f, 400),
    ).map { (h, z, f) ->
        v("$h px at $z in $f", "heightPx" to JsonPrimitive(h), "zoom" to num(z), "frameHeightPx" to JsonPrimitive(f))
    }

    private val exportSizes = listOf(
        Triple("Card", 720f, 1000f), Triple("Card", 945f, 1260f), Triple("Card", 360f, 523.4f), Triple("Card", 1080f, 1.0f),
        Triple("Card", 333.3f, 777.7f), Triple("Story", 720f, 1280f), Triple("Story", 945f, 1680f),
    ).map { (f, w, h) -> v("$f laid out $w × $h", "format" to str(f), "width" to num(w), "height" to num(h)) }

    private val fitScales = listOf(
        listOf(300, 400, 600, Int.MAX_VALUE) to 1f,
        listOf(400, 400, 200, 1_000) to 1f,
        listOf(400, 800, 1_000, 200) to 1f,
        listOf(360, 640, 1040, 900) to 1.5f,
        listOf(360, 640, 300, 900) to 1.5f,
        listOf(0, 640, 300, 900) to 1f,
    ).map { (dims, maxScale) ->
        v(
            "${dims.joinToString("/")} up to $maxScale",
            "childWidth" to JsonPrimitive(dims[0]),
            "childHeight" to JsonPrimitive(dims[1]),
            "maxWidth" to JsonPrimitive(dims[2]),
            "maxHeight" to JsonPrimitive(dims[3]),
            "maxScale" to num(maxScale),
        )
    }

    private val fileNames = listOf(
        "forbidden characters as spaces" to "AC/DC: Back in Black?",
        "trimmed" to "  晴天 ",
        "only forbidden characters: no title" to " / ",
        "no title" to null,
        "quotes, pipes, angle brackets, star, backslash" to "a\"b|c<d>e*f\\g",
        "tabs and newlines fold" to "Line\tone\n\ntwo",
        "a DEL character" to "x\u007Fy",
        "no-break space is kept" to "a b",
        "an ideographic space is kept, ends trimmed" to "　晴天　雨天　",
        "capped at 48 units, then trimmed" to "The Quick Brown Fox Jumps Over The Lazy Dog Again And Again",
        "an emoji counts two units" to "😀".repeat(30),
        "a long Chinese title" to "故事的小黄花从出生那年就飘着童年的荡秋千随记忆一直晃到现在吹着前奏望着天空我想起花瓣试着掉落",
    ).map { (name, title) -> v(name, "title" to str(title), "nowMs" to JsonPrimitive(1_764_000_000_000L)) }

    val inputs: Map<String, List<JsonObject>> = mapOf(
        "isCjk" to isCjk,
        "content" to contents,
        "layoutStore" to layoutStore,
        "currentLine" to currentLines,
        "passageStarts" to passageStarts,
        "caption" to captions,
        "cardLineSize" to cardLineSizes,
        "poster" to posters,
        "playerExcerpt" to excerpts,
        "verticalPlan" to verticalPlans,
        "verticalLyrics" to verticalLyrics,
        "verticalText" to verticalTexts,
        "verticalForm" to verticalForms,
        "about" to abouts,
        "aiSign" to aiSigns,
        "boldRuns" to boldRuns,
        "note" to notes,
        "time" to times,
        "tones" to tones,
        "retone" to retones,
        "storyZoom" to storyZooms,
        "storyFits" to storyFits,
        "exportSize" to exportSizes,
        "fitScale" to fitScales,
        "fileName" to fileNames,
    )
}
