package com.gpo.yoin.shared.albumabout

import com.gpo.yoin.shared.albumabout.AlbumAboutGolden.creditsJson
import com.gpo.yoin.shared.albumabout.AlbumAboutGolden.groundingJson
import com.gpo.yoin.shared.albumabout.AlbumAboutGolden.obj
import com.gpo.yoin.shared.albumabout.AlbumAboutGolden.rowJson
import com.gpo.yoin.shared.albumabout.AlbumAboutGolden.str
import com.gpo.yoin.shared.albumabout.AlbumAboutGolden.strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The inputs of every golden vector ([AlbumAboutGolden]), section by section. */
internal object AlbumAboutGoldenFixtures {

    /** Android's string resources (values / values-zh-rCN), as the app's AlbumAboutPinTest checks. */
    val labels: Map<String, AlbumCreditLabels> = linkedMapOf(
        "zh" to AlbumCreditLabels("厂牌", "制作", "母带", "混音", "录制", "原作", "首版", "、", "%1\$s，%2\$s"),
        "en" to AlbumCreditLabels("Label", "Producer", "Mastering", "Mix", "Recorded", "Source", "First released", ", ", "%1\$s, %2\$s"),
    )

    // ── groundings ───────────────────────────────────────────────────

    val reissue = AlbumAboutGrounding(
        album = "Tide Tables (10th Anniversary)",
        artist = "Morikawa",
        version = "10th Anniversary",
        releaseDate = "2024-03-08",
        originalReleaseDate = "2014-05-20",
        labels = listOf("Northern Lights Records"),
        releaseType = "Album",
        genre = "Folk",
        tracks = listOf("Dawn Chorus", "Glass House"),
        totalDurationSec = 2_460,
    )

    val minimal = AlbumAboutGrounding(album = "A", artist = "B")

    /** Apple Music: its own notes are the paragraph (the credits-only prompt). */
    val apple = AlbumAboutGrounding(
        album = "Blonde",
        artist = "Frank Ocean",
        releaseDate = "2016-08-20",
        labels = listOf("Boys Don't Cry"),
        releaseType = "Album",
        genre = "R&B/Soul",
        tracks = listOf("Nikes", "Ivy", "Pink + White"),
        totalDurationSec = 3_605,
        serviceNotes = "Blonde arrived after four years.\n\nIts songs & interludes\n\ndrift.   Then “Nights” — the hinge.",
    )

    val soundtrackJa = AlbumAboutGrounding(
        album = "もののけ姫 サウンドトラック",
        artist = "久石譲",
        releaseDate = "1997-07-02",
        // The same as the release date: not written twice.
        originalReleaseDate = "1997-07-02",
        labels = listOf("徳間ジャパンコミュニケーションズ"),
        releaseType = "Album",
        genre = "Soundtrack",
        tracks = listOf("アシタカせっ記", "タタリ神", "もののけ姫"),
        totalDurationSec = 3_600,
    )

    val chinese = AlbumAboutGrounding(
        album = "范特西",
        artist = "周杰伦",
        version = "  ",
        releaseDate = "2001-09",
        releaseType = "Album",
        genre = " ",
        tracks = listOf("爱在西元前", "双截棍", "简单爱"),
        totalDurationSec = 0,
    )

    val korean = AlbumAboutGrounding(
        album = "화양연화 pt.1",
        artist = "방탄소년단",
        releaseDate = "2015",
        labels = listOf("Big Hit Entertainment", "  "),
        releaseType = "EP",
        tracks = listOf("I NEED U", "쩔어"),
    )

    val single = AlbumAboutGrounding(
        album = "Running Up That Hill",
        artist = "Kate Bush",
        version = "2012 Remix",
        releaseDate = "2012-11",
        originalReleaseDate = "1985-08-05",
        labels = listOf("Fish People", "Parlophone"),
        releaseType = "Single",
        totalDurationSec = 300,
    )

    val long = AlbumAboutGrounding(
        album = "The Complete Sessions",
        artist = "Bill Evans Trio",
        releaseType = "Compilation",
        tracks = (1..55).map { "Take $it" },
        serviceNotes = "Lorem ipsum dolor sit amet. ".repeat(60).trim(),
    )

    private val groundings = listOf(reissue, minimal, apple, soundtrackJa, chinese, korean, single, long)

    private const val BUILT_IN = "Use the search tool to find accurate information."
    private const val PAGES = "Use the search results provided below to find accurate information."
    private const val NO_SEARCH = "Answer from what you reliably know; write \"N/A\" for anything you are not sure of."

    // ── replies ──────────────────────────────────────────────────────

    private val fullReply = """
        [CREDITS]
        {
          "label": null,
          "producers": {"names": ["Kenji Arai", "Mei Lin"], "confidence": "high"},
          "mastering": {"names": ["Bob Ludwig"], "confidence": "medium"},
          "mixing": {"names": ["Someone"], "confidence": "HIGH"},
          "recorded": {"time": "Sep–Dec 2013", "studios": ["Studio Mare, Kamakura"], "confidence": "high"},
          "source": null,
          "first_released": {"date": "May 2014", "confidence": "high"}
        }
        [/CREDITS]
        [ABOUT]
        Tide Tables was Morikawa's second album.
        It was cut by the sea.
        [/ABOUT]
    """.trimIndent()

    private val replies: List<Pair<String, String>> = listOf(
        "valid" to fullReply,
        "lowAndMissingConfidence" to """[CREDITS]{"producers": {"names": ["Guess"], "confidence": "low"},
            "mastering": {"names": ["No confidence"]},
            "mixing": {"names": ["Maybe"], "confidence": "medium-low"},
            "label": {"names": ["ECM"], "confidence": "high"}}[/CREDITS][ABOUT]N/A[/ABOUT]""",
        "placeholders" to """[CREDITS]{"producers": {"names": ["N/A", "unknown", " ", "Manfred Eicher", "Manfred Eicher"], "confidence": "high"},
            "mastering": {"names": ["未知"], "confidence": "high"},
            "mixing": {"names": ["Various Artists", "n/a.", "Unknown。"], "confidence": "high"},
            "recorded": {"time": "不详", "studios": [], "confidence": "high"},
            "source": {"work": "None", "confidence": "high"},
            "first_released": {"date": null, "confidence": "high"}}[/CREDITS]
            [ABOUT]
            unknown
            [/ABOUT]""",
        "fencedWithProse" to "[CREDITS]\nHere you go:\n```json\n{\"producers\": {\"names\": \"A、B; C；D, E\", \"confidence\": \"high\"},}\n```\n[/CREDITS]\n[ABOUT]\n**Short.**\n[/ABOUT]",
        "brokenJson" to "[CREDITS]\n{\"producers\": \n[/CREDITS]\n[ABOUT]\nStill a paragraph.\n[/ABOUT]",
        "jsonArrayRoot" to "[CREDITS][{\"producers\": {\"names\": [\"X\"], \"confidence\": \"high\"}}][/CREDITS]",
        "knownEmpty" to "[CREDITS]{}[/CREDITS]\n[ABOUT]N/A[/ABOUT]",
        "knownEmptyNulls" to "[CREDITS]\n{\"label\": null, \"producers\": null, \"mastering\": null, \"mixing\": null, \"recorded\": null, \"source\": null, \"first_released\": null}\n[/CREDITS]",
        "refused" to "I'm sorry, I can't help with that.",
        "empty" to "",
        "whitespace" to "  \n\t ",
        "aboutOnly" to "[ABOUT]\nThe album was recorded live.\n[/ABOUT]",
        "aboutOnlyNA" to "[ABOUT] N/A. [/ABOUT]",
        "creditsOnlyApple" to "[CREDITS]{\"producers\":{\"names\":[\"Frank Ocean\"],\"confidence\":\"high\"},\"recorded\":{\"time\":null,\"studios\":[\"Henson Recording Studios, Los Angeles\"],\"confidence\":\"medium\"}}[/CREDITS]",
        "truncatedAbout" to "[CREDITS]{}[/CREDITS]\n[ABOUT]\nCut off mid",
        "truncatedCredits" to "[CREDITS]\n{\"producers\": {\"names\": [\"P\"], \"confidence\": \"high\"}}",
        "tagsLooselyWritten" to "[ credits ]{\"source\": {\"work\": \"Final Fantasy VII\", \"confidence\": \"High\"}}[ / Credits ]\n[about]Square's score.[/about]",
        "tooManyNames" to "[CREDITS]{\"producers\": {\"names\": [\"1\", \"2\", \"2\", \"3\", \"4\", \"5\", \"6\"], \"confidence\": \"high\"}}[/CREDITS]",
        "nonStringNames" to "[CREDITS]{\"producers\": {\"names\": [\"A\", 2, null, true, \"B\"], \"confidence\": \"high\"}, \"source\": {\"work\": 1984, \"confidence\": \"high\"}}[/CREDITS]",
        "quotedValues" to "[CREDITS]{\"producers\": {\"names\": [\"“Rainbow Studio”\", \"  \\\"Quoted\\\"  \"], \"confidence\": \" Medium \"}}[/CREDITS]",
        "lenientUnquotedKeys" to "[CREDITS]{producers: {names: [\"Rick Rubin\"], confidence: high}, mastering: {names: [Unquoted], confidence: \"high\"}}[/CREDITS]",
        "unknownKeys" to "[CREDITS]{\"producers\": {\"names\": [\"P\"], \"confidence\": \"high\", \"source_url\": \"x\"}, \"arranger\": {\"names\": [\"Q\"], \"confidence\": \"high\"}}[/CREDITS]",
        "cjkParagraph" to "[CREDITS]{\"first_released\": {\"date\": \"1975年\", \"confidence\": \"medium\"}}[/CREDITS]\n[ABOUT]\n这是**第一句**。\n第二句。\nThird line in English.\n日本語の文。\n[/ABOUT]",
        "recordedTimeOnly" to "[CREDITS]{\"recorded\": {\"time\": \"1975年1月\", \"studios\": null, \"confidence\": \"high\"}}[/CREDITS]",
        "recordedEmpty" to "[CREDITS]{\"recorded\": {\"time\": null, \"studios\": [], \"confidence\": \"high\"}}[/CREDITS]",
        "secondBlockIgnored" to "[ABOUT]First.[/ABOUT]\n[ABOUT]Second.[/ABOUT]",
    )

    private val parseCreditsInputs: List<String?> = listOf(
        null,
        "",
        "no object here",
        "{\"producers\": ",
        "prose {\"label\": {\"names\": [\"ECM\"], \"confidence\": \"high\"}} more prose",
        "{\"producers\": {\"names\": \"A、B\", \"confidence\": \"high\"},}",
        "{\"label\": {\"names\": [\"Sony\"], \"confidence\": \"low\"}}",
    )

    // ── credits line ─────────────────────────────────────────────────

    private class Line(
        val name: String,
        val labels: String,
        val ai: AlbumAiCredits,
        val sourceLabels: List<String> = emptyList(),
        val releaseDate: String? = null,
        val originalReleaseDate: String? = null,
        val dateText: String? = null,
    )

    private val full = AlbumAiCredits(
        label = listOf("AI Label"),
        producers = listOf("Manfred Eicher"),
        mastering = listOf("Jan Erik Kongshaug"),
        mixing = listOf("Mixer"),
        recordedTime = "1975年1月",
        recordedStudios = listOf("Rainbow Studio, Oslo"),
        source = "《攻壳机动队》",
        firstReleased = "1975年",
    )

    private val ecm = AlbumAiCredits(
        producers = listOf("Manfred Eicher", "Steve Lake"),
        recordedTime = "1975年1月",
        recordedStudios = listOf("Rainbow Studio"),
    )

    private val spirited = AlbumAiCredits(
        producers = listOf("久石譲"),
        mastering = listOf("小島康弘"),
        recordedTime = "2001年3月",
        recordedStudios = listOf("ビクタースタジオ, 東京", "東京芸術劇場"),
        source = "映画『千と千尋の神隠し』",
    )

    private val longLatin = AlbumAiCredits(
        producers = listOf("Jean-Michel Jarre", "Sir George Martin", "Kamaal Ibn John Fareed", "Rick Rubin"),
        mastering = listOf("Bob Ludwig"),
        recordedTime = "Sep–Dec 2013",
        recordedStudios = listOf("Studio Mare, Kamakura", "Electric Lady Studios, New York"),
    )

    private val longChinese = AlbumAiCredits(
        producers = listOf("林夕", "陈辉阳", "雷颂德", "欧阳慧珊"),
        mixing = listOf("Jerry Chu"),
        recordedTime = "2003年6月至9月",
        recordedStudios = listOf("大溪地录音室，台北", "Avon Studio, Hong Kong"),
    )

    private val lines = listOf(
        Line("zh basic", "zh", ecm, listOf("ECM"), "1975"),
        Line("en basic", "en", ecm, listOf("ECM"), "1975"),
        Line("zh full, AI label, mastering over mix, undated release", "zh", full),
        Line("en full, AI label, mastering over mix, undated release", "en", full),
        Line(
            "zh device check: label, dash, comma, film",
            "zh",
            full.copy(recordedTime = "Sep–Dec 1975", recordedStudios = listOf("大溪地录音室，台北"), source = "电影《夜航》"),
            listOf("林间工作室"),
        ),
        Line("en soundtrack", "en", AlbumAiCredits(producers = listOf("Nobuo Uematsu"), source = "Final Fantasy VII"), listOf("DigiCube"), "1997-03-10"),
        Line("ja soundtrack, zh labels", "zh", spirited, listOf("スタジオジブリ・レコード"), "2001-07-18"),
        Line("ja soundtrack, en labels", "en", spirited, listOf("スタジオジブリ・レコード"), "2001-07-18"),
        Line("en tribute", "en", AlbumAiCredits(producers = listOf("Hal Willner"), source = "Thelonious Monk"), releaseDate = "1984"),
        Line(
            "zh reissue, service date",
            "zh",
            AlbumAiCredits(producers = listOf("Kenji Arai"), firstReleased = "May 2014"),
            listOf("Northern Lights Records"),
            "2024-03-08",
            "2014-05-20",
            "2014年5月20日",
        ),
        Line(
            "en reissue, service date",
            "en",
            AlbumAiCredits(producers = listOf("Kenji Arai"), firstReleased = "May 2014"),
            listOf("Northern Lights Records"),
            "2024-03-08",
            "2014-05-20",
            "May 20, 2014",
        ),
        Line("en reissue, service month", "en", AlbumAiCredits.None, emptyList(), "2024", "2014-05", "May 2014"),
        Line("zh reissue, service month", "zh", AlbumAiCredits.None, emptyList(), "2024", "2014-05", "2014年5月"),
        Line("en reissue, service date without its text", "en", AlbumAiCredits.None, emptyList(), "2024-03-08", "2014-05-20"),
        Line(
            "en service first release not earlier: no reissue, AI ignored",
            "en",
            AlbumAiCredits(firstReleased = "2010"),
            emptyList(),
            "2014",
            "2014-05",
        ),
        Line("en AI first release in the same year: dropped", "en", AlbumAiCredits(firstReleased = "August 2016"), releaseDate = "2016"),
        Line("zh AI first release earlier: kept", "zh", AlbumAiCredits(firstReleased = "1997年5月"), releaseDate = "2016-01-01"),
        Line("en AI first release, years either side: earliest counts", "en", AlbumAiCredits(firstReleased = "1997 (Japan), 2017 (worldwide)"), releaseDate = "2016"),
        Line("en AI first release, undated release", "en", AlbumAiCredits(firstReleased = "1997")),
        Line("en AI first release without a year: kept", "en", AlbumAiCredits(firstReleased = "the original pressing"), releaseDate = "2016"),
        Line("en mix when mastering is unknown", "en", AlbumAiCredits(mixing = listOf("Tom Elmhirst", "Frank Ocean"))),
        Line(
            "en service labels trimmed and deduplicated, AI label ignored",
            "en",
            AlbumAiCredits(label = listOf("Other")),
            listOf(" ECM ", "ECM", "", "ECM Records"),
        ),
        Line("en long Latin names", "en", longLatin, listOf("Domino Recording Co."), "2014"),
        Line("zh long Latin names", "zh", longLatin, listOf("Domino Recording Co."), "2014"),
        Line("zh long Chinese names", "zh", longChinese, listOf("环球唱片（香港）有限公司"), "2003"),
        Line("en long Chinese names", "en", longChinese, listOf("环球唱片（香港）有限公司"), "2003"),
        Line("en time only", "en", AlbumAiCredits(recordedTime = "1975")),
        Line("en place only", "en", AlbumAiCredits(recordedStudios = listOf("A", "B"))),
        Line("en Latin studio breaks between words", "en", AlbumAiCredits(recordedStudios = listOf("Sunset Sound, Los Angeles"))),
        Line("en slash and hyphen hold", "en", AlbumAiCredits(producers = listOf("Jay-Z", "A/B Team")), listOf("Roc-A-Fella / Def Jam")),
        Line("en Korean label", "en", AlbumAiCredits(producers = listOf("방시혁", "Pdogg")), listOf("빅히트 엔터테인먼트")),
        Line("en nothing known", "en", AlbumAiCredits.None, releaseDate = "2020"),
        Line("zh nothing known", "zh", AlbumAiCredits.None),
    )

    // ── content ──────────────────────────────────────────────────────

    private fun row(kind: String, key: String, answer: String, createdAt: Long = 1L, prompt: String? = null, title: String? = null) =
        AlbumAboutRow(kind, key, prompt, title, answer, createdAt)

    private val contents: List<List<AlbumAboutRow>> = listOf(
        emptyList(),
        listOf(
            row("canonical", "credits", AlbumAiCredits(producers = listOf("P")).encode()),
            row("canonical", "blurb", "  The paragraph.  "),
            row("ask", "old?", "Old answer.", createdAt = 10, prompt = "Old?", title = "Old"),
            row("ask", "new?", " New answer. ", createdAt = 20, prompt = "New?", title = " "),
            row("ask", "removed?", "  ", createdAt = 30, prompt = "Removed?"),
            row("ask", "no prompt", "Kept.", createdAt = 5, prompt = null, title = null),
        ),
        listOf(row("canonical", "credits", "{}"), row("canonical", "blurb", "")),
        listOf(row("canonical", "credits", "not json")),
        listOf(row("ask", "only a question", "Answer.", createdAt = 3, prompt = "Only a question")),
    )

    // ── all inputs, by section ───────────────────────────────────────

    val inputs: Map<String, List<JsonObject>> = mapOf(
        "searchLeads" to listOf(true to 0, true to 3, false to 2, false to 0).map { (builtIn, pages) ->
            obj("builtInSearch" to JsonPrimitive(builtIn), "searchedPages" to JsonPrimitive(pages))
        },
        "blurbLength" to listOf(
            "English", "Simplified Chinese", "Traditional Chinese", "Chinese", "chinese (Hong Kong)", "中文", "Japanese",
            "Korean", "French", "japanese",
        ).map { obj("targetLanguage" to str(it)) },
        "searchQueries" to groundings.flatMap { g ->
            listOf(2, 1).map { count -> obj("grounding" to groundingJson(g), "count" to JsonPrimitive(count)) }
        } + obj("grounding" to groundingJson(minimal), "count" to JsonPrimitive(0)),
        "prompts" to listOf(
            Prompt(reissue, "English", BUILT_IN, "Who played the organ?"),
            Prompt(reissue, "Simplified Chinese", PAGES, "这张专辑是在哪里录的？"),
            Prompt(soundtrackJa, "Japanese", NO_SEARCH, "主題歌を歌ったのは誰？"),
            Prompt(chinese, "Traditional Chinese", BUILT_IN, null),
            Prompt(korean, "Korean", PAGES, null),
            Prompt(minimal, "English", NO_SEARCH, "Is it any good?"),
            Prompt(apple, "English", BUILT_IN, "What is Nikes about?"),
            Prompt(apple, "Simplified Chinese", NO_SEARCH, null),
            Prompt(single, "French", PAGES, null),
            Prompt(long, "English", BUILT_IN, null),
        ).map { p ->
            obj(
                "grounding" to groundingJson(p.grounding),
                "targetLanguage" to str(p.language),
                "searchLead" to str(p.lead),
                "question" to str(p.question),
            )
        },
        "replies" to replies.flatMap { (name, raw) ->
            listOf(false, true).map { notes -> obj("name" to str(name), "raw" to str(raw), "hasServiceNotes" to JsonPrimitive(notes)) }
        },
        "credits" to parseCreditsInputs.map { obj("text" to str(it)) },
        "cleanValue" to listOf(
            " N/A ", "n/a.", "NA", "未知。", "不詳", "“Rainbow Studio”", "\"Quoted\"", "Various Artists", "  a \n  b  ", "", "-", "—",
            "?", "TBD", "none", "Null", "不详", "暂无", "Rainbow Studio",
        ).map { obj("value" to str(it)) },
        "cleanBlurb" to listOf(
            null, "", "  ", "N/A", "n/a.", "第一句。\n第二句。", "**Bold** text\nhere.", "Line one\n\n  Line two  ",
            "日本語の文。\nEnglish next.", "English.\n中文。", "（括弧）\n「引用」",
        ).map { obj("text" to str(it)) },
        "encodeCredits" to listOf(
            AlbumAiCredits.None,
            full,
            AlbumAiCredits(producers = listOf("A"), recordedTime = "1975", source = "Akira"),
            AlbumAiCredits(recordedStudios = listOf("Studio \"Q\", Tōkyō")),
        ).map { obj("credits" to creditsJson(it)) },
        "decodeCredits" to listOf(
            null, "", "not json", "{}", "{\"producers\":[\"A\"],\"extra\":1}", "{\"recordedTime\":null,\"label\":[\"L\"]}",
            "{\"producers\":\"A\"}", full.encode(),
        ).map { obj("text" to str(it)) },
        "content" to contents.map { rows -> obj("rows" to JsonArray(rows.map(::rowJson))) },
        "questionKey" to listOf(
            "  Who played the Organ?  ", "Fake Love (feat. X)", "Fake Love (FEAT. X) remix", "多个   空格\t问题", "ÄÖ Big", "",
        ).map { obj("question" to str(it)) },
        "creditsLine" to lines.map { l ->
            obj(
                "name" to str(l.name),
                "labels" to str(l.labels),
                "dateText" to str(l.dateText),
                "sourceLabels" to strings(l.sourceLabels),
                "releaseDate" to str(l.releaseDate),
                "originalReleaseDate" to str(l.originalReleaseDate),
                "ai" to creditsJson(l.ai),
            )
        },
        "unbreakable" to listOf("First released", "录制", "Label", "原作 名", "Mix-down").map { obj("label" to str(it)) },
        "editorialNotes" to listOf(
            "<i>Blonde</i> arrived after four years.<br />It shows." to "Short.",
            " " to "Short &amp; sweet.",
            null to "<p>Only short.</p>",
            "<br/> <p></p>" to null,
            null to null,
        ).map { (standard, short) -> obj("standard" to str(standard), "short" to str(short)) },
        "plainTextFromHtml" to listOf(
            "<i>Blonde</i> arrived after four years.<br /><br /><br />Its songs &amp; interludes<p>drift.</p>Then &#8220;Nights&#8221; &mdash; the hinge.",
            "  a&nbsp;  b   &amp;lt; c ",
            "<br/> <p></p>",
            "<P CLASS=\"x\">One</P><BR>Two<br/>Three",
            "Emoji &#x1F3B5; and &#127925; and &#99999999999; and &#xZZ;",
            "Tabs\tand\u00A0no-break\u00A0spaces",
            "&lsquo;a&rsquo; &ldquo;b&rdquo; &hellip; &ndash; &apos; &#39; &quot; &lt;b&gt;",
            "<a href=\"https://x\">Link</a> <b>bold</b>\r\nnext",
        ).map { obj("html" to str(it)) },
        "aiCopy" to listOf("Ask %1\$s", "问 %1\$s", "%1\$s 正在写…", "用 %1\$s 重新生成", "%1\$s 没能回答，再试一次", "缺少 %1\$s API 密钥", "%1\$s is writing…")
            .flatMap { template -> listOf("Gemini", "DeepSeek", "千问", "Qwen", "AI").map { template to it } }
            .map { (template, name) -> obj("template" to str(template), "name" to str(name)) },
    )

    private class Prompt(val grounding: AlbumAboutGrounding, val language: String, val lead: String, val question: String?)
}
