package com.gpo.yoin.shared.share

import com.gpo.yoin.shared.notes.YoinNoteLine
import kotlin.math.round
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The golden vectors of the share-card rules (shared/src/commonTest/resources/golden/share-cards.json). Each vector
 * is its inputs followed by what [YoinShareCards] answers for them ([outputs]); the file is written by
 * `ShareCardsGoldenTest` (androidHostTest) from [ShareCardsGoldenFixtures], and [replay] reads every vector back
 * and answers it again, on the JVM and on the iOS simulator (through [SHARE_CARDS_GOLDEN], the file as a Kotlin
 * string). A port (HarmonyOS's Dart) does what [outputs] does, section by section.
 */
internal object ShareCardsGolden {

    const val FILE = "shared/src/commonTest/resources/golden/share-cards.json"

    /** Input fields first, then the output fields [outputs] adds. */
    val sections: List<String> = listOf(
        "isCjk", "content", "layoutStore", "currentLine", "passageStarts", "caption", "cardLineSize", "poster",
        "playerExcerpt", "verticalPlan", "verticalLyrics", "verticalText", "verticalForm", "about", "aiSign", "boldRuns", "note", "time",
        "tones", "retone", "storyZoom", "storyFits", "exportSize", "fitScale", "fileName",
    )

    /** What the facade answers for one vector's inputs (the vector's own output fields are ignored). */
    fun outputs(section: String, v: JsonObject, labels: Map<String, ShareAboutLabels>): JsonObject = when (section) {
        "isCjk" -> obj("cjk" to JsonPrimitive(YoinShareCards.isCjk(v.string("text")!!)))
        "content" -> {
            val content = contentOf(v.obj("content"))
            val remembered = v.obj("remembered")
            obj(
                "tabs" to JsonArray(YoinShareCards.tabs(content).map { str(it.name) }),
                "layouts" to JsonObject(
                    ShareTab.entries.associate { tab ->
                        tab.name to JsonArray(YoinShareCards.layouts(content, tab).map { str(it.storeValue) })
                    },
                ),
                "opens" to JsonObject(
                    ShareTab.entries.associate { tab ->
                        val stored = YoinShareCards.layoutForStoreValue(tab, remembered.string(tab.storeKey))
                        tab.name to str(YoinShareCards.resolveLayout(content, tab, stored)?.storeValue)
                    },
                ),
            )
        }
        "layoutStore" -> {
            val tab = ShareTab.valueOf(v.string("tab")!!)
            obj("layout" to str(YoinShareCards.layoutForStoreValue(tab, v.string("value"))?.storeValue))
        }
        "currentLine" -> obj(
            "pick" to (
                YoinShareCards.currentLine(
                    v.array("lines").map { lineOf(it.jsonObject) },
                    v.long("positionMs"),
                    v.bool("showTranslation"),
                )?.let(::lyricsJson) ?: JsonNull
                ),
        )
        "passageStarts" -> obj(
            "starts" to ints(YoinShareCards.passageStarts(v.array("selected").map { it.jsonPrimitive.int }, v.int("lineCount"))),
        )
        "caption" -> obj("caption" to str(YoinShareCards.caption(v.string("title"), v.string("artist"))))
        "cardLineSize" -> obj("sizeSp" to num(YoinShareCards.cardLineSizeSp(v.strings("lines"))))
        "poster" -> obj("poster" to (YoinShareCards.poster(lyricsOf(v.obj("pick")))?.let(::posterJson) ?: JsonNull))
        "playerExcerpt" -> obj(
            "excerpt" to (YoinShareCards.playerExcerpt(lyricsOf(v.obj("pick")), v.long("durationMs"))?.let(::excerptJson) ?: JsonNull),
        )
        "verticalPlan" -> obj(
            "plan" to planJson(YoinShareCards.verticalPlan(v.strings("lines"), v.float("columnHeightDp"), v.float("widthDp"))),
        )
        "verticalLyrics" -> {
            val pick = lyricsOf(v.obj("pick"))
            obj(
                "gist" to strings(YoinShareCards.verticalGist(pick)),
                "plan" to planJson(YoinShareCards.verticalLyricsPlan(pick)),
            )
        }
        "verticalText" -> obj(
            "columns" to (
                YoinShareCards.verticalTextColumns(v.string("text")!!, v.float("sizeSp"), v.float("columnHeightDp"))
                    ?.let(::strings) ?: JsonNull
                ),
        )
        "verticalForm" -> obj("vertical" to str(YoinShareCards.verticalText(v.string("text")!!)))
        "about" -> {
            val about = YoinShareCards.aboutPick(
                entries = v.array("entries").map { entryOf(it.jsonObject) },
                sources = sourcesOf(v.obj("sources")),
                labels = labels.getValue(v.string("labels")!!),
                focusKind = v.string("focusKind"),
                focusKey = v.string("focusKey"),
            )
            obj(
                "about" to (about?.let(::aboutJson) ?: JsonNull),
                "aiContent" to JsonPrimitive(about?.aiContent == true),
                "hasTab" to JsonPrimitive(
                    YoinShareCards.has(ShareCardContent(GoldenSong, lyrics = null, about = about, note = null), ShareTab.About),
                ),
            )
        }
        "aiSign" -> obj("sign" to str(YoinShareCards.aiSign(v.string("template")!!, v.string("provider")!!)))
        "boldRuns" -> obj(
            "runs" to JsonArray(
                YoinShareCards.boldRuns(v.string("text")!!).map { obj("text" to str(it.text), "bold" to JsonPrimitive(it.bold)) },
            ),
        )
        "note" -> {
            val note = YoinShareCards.notePick(v.array("notes").map { noteOf(it.jsonObject) }, v.string("noteId"), v.long("positionMs"))
            val duration = v.long("durationMs")
            obj(
                "note" to (note?.let(::noteJson) ?: JsonNull),
                "time" to str(note?.positionMs?.let(YoinShareCards::time)),
                "timelineFraction" to (note?.let { YoinShareCards.timelineFraction(it.positionMs, duration) }?.let(::num) ?: JsonNull),
                "lines" to (note?.let { JsonArray(YoinShareCards.noteLines(it).map(::noteLineJson)) } ?: JsonNull),
            )
        }
        "time" -> obj("text" to str(YoinShareCards.time(v.long("ms"))))
        "tones" -> {
            val colors = YoinShareCards.toneColors(ShareTone.valueOf(v.string("tone")!!), ShareRole.valueOf(v.string("role")!!))
            obj("card" to inkJson(colors.card), "content" to inkJson(colors.content), "backdrop" to inkJson(colors.backdrop))
        }
        "retone" -> obj("retoned" to str(hex(YoinShareCards.retone(argbOf(v.string("argb")!!), v.int("tone")))))
        "storyZoom" -> {
            val limit = v.float("fitsUpTo")
            val tried = mutableListOf<Float>()
            val zoom = YoinShareCards.storyZoom(v.float("maxZoom")) { z ->
                tried += z
                z <= limit
            }
            obj("zoom" to num(zoom), "tried" to JsonArray(tried.map(::num)))
        }
        "storyFits" -> obj(
            "fits" to JsonPrimitive(YoinShareCards.storyFits(v.int("heightPx"), v.float("zoom"), v.int("frameHeightPx"))),
        )
        "exportSize" -> {
            val size = YoinShareCards.exportSize(ShareFormat.valueOf(v.string("format")!!), v.float("width"), v.float("height"))
            obj("exportWidth" to JsonPrimitive(size.width), "exportHeight" to JsonPrimitive(size.height))
        }
        "fitScale" -> obj(
            "scale" to num(
                YoinShareCards.fitScale(v.int("childWidth"), v.int("childHeight"), v.int("maxWidth"), v.int("maxHeight"), v.float("maxScale")),
            ),
        )
        "fileName" -> obj(
            "fileTitle" to str(YoinShareCards.fileTitle(v.string("title"))),
            "fileName" to str(YoinShareCards.fileName(v.string("title"), v.long("nowMs"))),
        )
        else -> error("unknown section $section")
    }

    /** The whole file: one vector per line, so a drift shows as the lines that changed. */
    fun render(): String {
        val labels = ShareCardsGoldenFixtures.labels
        return buildString {
            append("{\n")
            // The file's description: not "about", which is the About section's key (a JSON reader that keeps the
            // first of two equal keys read the description in its place).
            append("  \"description\": ").append(encode(str(DESCRIPTION))).append(",\n")
            append("  \"constants\": ").append(encode(constants())).append(",\n")
            append("  \"labels\": {\n")
            labels.entries.forEachIndexed { i, (key, value) ->
                append("    \"").append(key).append("\": ").append(encode(labelsJson(value)))
                append(if (i == labels.size - 1) "\n" else ",\n")
            }
            append("  }")
            sections.forEach { section ->
                val inputs = ShareCardsGoldenFixtures.inputs.getValue(section)
                append(",\n  \"").append(section).append("\": [\n")
                inputs.forEachIndexed { i, input ->
                    val vector = JsonObject(input + outputs(section, input, labels))
                    append("    ").append(encode(vector))
                    append(if (i == inputs.lastIndex) "\n" else ",\n")
                }
                append("  ]")
            }
            append("\n}\n")
        }
    }

    /** Every vector of [text] answered again; one line per vector whose answer differs. */
    fun replay(text: String): List<String> {
        val root = Json.parseToJsonElement(text).jsonObject
        val labels = root.getValue("labels").jsonObject.mapValues { labelsOf(it.value.jsonObject) }
        val mismatches = mutableListOf<String>()
        if (root["constants"] != constants()) mismatches += "constants: expected ${root["constants"]} but was ${constants()}"
        sections.forEach { section ->
            val vectors = root[section]?.jsonArray ?: return@forEach run { mismatches += "missing section $section" }
            vectors.forEachIndexed { i, element ->
                val vector = element.jsonObject
                outputs(section, vector, labels).forEach { (key, expected) ->
                    if (vector[key] != expected) {
                        mismatches += "$section[$i] ${vector["name"] ?: ""} $key: expected ${vector[key]} but was $expected"
                    }
                }
            }
        }
        return mismatches
    }

    /** The rules' fixed numbers and tables, for a port to copy and check. */
    fun constants(): JsonObject = obj(
        "tabs" to JsonArray(
            ShareTab.entries.map { tab ->
                obj(
                    "tab" to str(tab.name),
                    "role" to str(tab.role.name),
                    "storeKey" to str(tab.storeKey),
                    "layouts" to JsonArray(YoinShareCards.allLayouts(tab).map { str(it.storeValue) }),
                )
            },
        ),
        "layouts" to JsonArray(
            ShareLayout.entries.map { layout ->
                obj(
                    "layout" to str(layout.storeValue),
                    "tab" to str(layout.tab.name),
                    "storyIsPoster" to JsonPrimitive(layout.storyIsPoster),
                    "storyMaxZoom" to num(YoinShareCards.storyMaxZoom(layout)),
                )
            },
        ),
        "roleTones" to JsonObject(ShareRole.entries.associate { role -> role.name to JsonArray(role.tones().map { str(it.name) }) }),
        "cardDesignWidthDp" to JsonPrimitive(YoinShareCards.cardDesignWidthDp),
        "storyDesignWidthDp" to JsonPrimitive(YoinShareCards.storyDesignWidthDp),
        "storyDesignHeightDp" to JsonPrimitive(YoinShareCards.storyDesignHeightDp),
        "cardExportWidthPx" to JsonPrimitive(YoinShareCards.cardExportWidthPx),
        "storyExportWidthPx" to JsonPrimitive(YoinShareCards.storyExportWidthPx),
        "storyExportHeightPx" to JsonPrimitive(YoinShareCards.storyExportHeightPx),
        "storyMarginHorizontalDp" to JsonPrimitive(YoinShareCards.storyMarginHorizontalDp),
        "storyMarginVerticalDp" to JsonPrimitive(YoinShareCards.storyMarginVerticalDp),
        "storyMinZoom" to num(YoinShareCards.storyMinZoom),
        "storyMaxZoom" to num(YoinShareCards.storyMaxZoom),
        "storyZoomSteps" to JsonPrimitive(ShareZoomSearch.STEPS),
        "storyFitSlackPx" to num(ShareZoomSearch.FIT_SLACK_PX),
        "captionSeparator" to str(YoinShareCards.captionSeparator),
        "posterMaxLines" to JsonPrimitive(YoinShareCards.posterMaxLines),
        "posterStoryLeadBoostSp" to num(YoinShareCards.posterStoryLeadBoostSp),
        "posterTranslationSeparator" to str(YoinShareCards.posterTranslationSeparator),
        "playerExcerptMinFraction" to num(YoinShareCards.playerExcerptMinFraction),
        "playerExcerptLineMs" to JsonPrimitive(YoinShareCards.playerExcerptLineMs),
        "verticalSizesSp" to JsonArray(YoinShareCards.verticalSizesSp.map(::num)),
        "verticalCharAdvance" to num(YoinShareCards.verticalCharAdvance),
        "verticalColumnAdvance" to num(YoinShareCards.verticalColumnAdvance),
        "verticalColumnHeightDp" to num(YoinShareCards.verticalColumnHeightDp),
        "verticalAvailableWidthDp" to num(YoinShareCards.verticalAvailableWidthDp),
        "verticalFootnoteWidthDp" to num(YoinShareCards.verticalFootnoteWidthDp),
        "verticalGistSeparator" to str(YoinShareCards.verticalGistSeparator),
        "quoteCredits" to JsonPrimitive(YoinShareCards.quoteCredits),
        "linerCredits" to JsonPrimitive(YoinShareCards.linerCredits),
        "secondaryAlpha" to num(YoinShareCards.secondaryAlpha),
        "paperInkTone" to JsonPrimitive(YoinShareCards.paperInkTone),
        "paperBackdropTone" to JsonPrimitive(YoinShareCards.paperBackdropTone),
        "fileTitleMaxChars" to JsonPrimitive(YoinShareCards.fileTitleMaxChars),
        "galleryFolder" to str(YoinShareCards.galleryFolder),
        "cjkCommonLetters" to JsonArray(ShareScripts.cjkCommonLetters.sorted().map { str("U+" + it.toString(16).uppercase().padStart(4, '0')) }),
        "kindCanonical" to str(YoinShareCards.KIND_CANONICAL),
        "kindAsk" to str(YoinShareCards.KIND_ASK),
        "kindOwn" to str(YoinShareCards.KIND_OWN),
        "canonLyricist" to str(YoinShareCards.CANON_LYRICIST),
        "canonComposer" to str(YoinShareCards.CANON_COMPOSER),
        "canonProducer" to str(YoinShareCards.CANON_PRODUCER),
        "canonCreationTime" to str(YoinShareCards.CANON_CREATION_TIME),
        "canonCreationLocation" to str(YoinShareCards.CANON_CREATION_LOCATION),
        "canonReview" to str(YoinShareCards.CANON_REVIEW),
    )

    const val DESCRIPTION = "Golden vectors for the share-card rules (com.gpo.yoin.shared.share, Android PR #335): which " +
        "tabs a song has, the layouts each tab offers and opens on, the lyric lines (current line, Poster, Player " +
        "excerpt, Vertical columns and the CJK gate), About's credits and paragraph, the note, times, the tones' " +
        "colour roles, the Story zoom, export sizes and file names. Generated from :shared Kotlin through " +
        "YoinShareCards by ShareCardsGoldenTest (androidHostTest); do not edit by hand. Every vector is its inputs " +
        "followed by the answer. Strings are UTF-16 (Kotlin / Swift String.utf16 / Dart): lengths, take and chunked " +
        "count UTF-16 units. Fractional numbers are 32-bit floats written rounded to 6 decimals: compare within " +
        "1e-5. Colours are ARGB hex. isCjk reads each code point's Unicode Script (not Script_Extensions) and " +
        "general category L* as Java 21 (Unicode 15.0) does, plus ー ｰ ﾞ ﾟ 〆 (constants.cjkCommonLetters). storyZoom.tried is every zoom the search measured, " +
        "in order, for a predicate that fits exactly when zoom <= fitsUpTo."

    // ── codecs ───────────────────────────────────────────────────────

    val GoldenSong = ShareSong(songId = "s", title = "T", artist = "A", coverArtUrl = null, albumName = "", durationMs = 0L)

    fun songJson(s: ShareSong) = obj(
        "songId" to str(s.songId),
        "title" to str(s.title),
        "artist" to str(s.artist),
        "coverArtUrl" to str(s.coverArtUrl),
        "albumName" to str(s.albumName),
        "durationMs" to JsonPrimitive(s.durationMs),
    )

    fun songOf(o: JsonObject) = ShareSong(
        songId = o.string("songId")!!,
        title = o.string("title")!!,
        artist = o.string("artist")!!,
        coverArtUrl = o.string("coverArtUrl"),
        albumName = o.string("albumName")!!,
        durationMs = o.long("durationMs"),
    )

    fun lineJson(l: ShareLyricLine) = obj(
        "startMs" to (l.startMs?.let(::JsonPrimitive) ?: JsonNull),
        "text" to str(l.text),
        "translation" to str(l.translation),
    )

    fun lineOf(o: JsonObject) = ShareLyricLine(startMs = o.longOrNull("startMs"), text = o.string("text")!!, translation = o.string("translation"))

    fun lyricsJson(p: ShareLyrics) = obj(
        "lines" to JsonArray(p.lines.map(::lineJson)),
        "passageStarts" to ints(p.passageStarts),
        "showTranslation" to JsonPrimitive(p.showTranslation),
    )

    fun lyricsOf(o: JsonObject) = ShareLyrics(
        lines = o.array("lines").map { lineOf(it.jsonObject) },
        passageStarts = o.array("passageStarts").map { it.jsonPrimitive.int },
        showTranslation = o.bool("showTranslation"),
    )

    fun creditJson(c: ShareCredit) = obj("label" to str(c.label), "value" to str(c.value), "ai" to JsonPrimitive(c.ai), "fact" to JsonPrimitive(c.fact))

    fun creditOf(o: JsonObject) = ShareCredit(o.string("label")!!, o.string("value")!!, ai = o.bool("ai"), fact = o.bool("fact"))

    fun aboutJson(a: ShareAbout) = obj(
        "credits" to JsonArray(a.credits.map(::creditJson)),
        "heading" to str(a.heading),
        "paragraph" to str(a.paragraph),
    )

    fun aboutOf(o: JsonObject) = ShareAbout(o.array("credits").map { creditOf(it.jsonObject) }, o.string("heading")!!, o.string("paragraph"))

    fun noteJson(n: ShareNote) = obj(
        "noteId" to str(n.noteId),
        "content" to str(n.content),
        "positionMs" to (n.positionMs?.let(::JsonPrimitive) ?: JsonNull),
        "createdAt" to JsonPrimitive(n.createdAt),
    )

    fun noteOf(o: JsonObject) = ShareNote(o.string("noteId")!!, o.string("content")!!, o.longOrNull("positionMs"), o.long("createdAt"))

    fun contentJson(c: ShareCardContent) = obj(
        "song" to songJson(c.song),
        "lyrics" to (c.lyrics?.let(::lyricsJson) ?: JsonNull),
        "about" to (c.about?.let(::aboutJson) ?: JsonNull),
        "note" to (c.note?.let(::noteJson) ?: JsonNull),
    )

    fun contentOf(o: JsonObject) = ShareCardContent(
        song = songOf(o.obj("song")),
        lyrics = o.objOrNull("lyrics")?.let(::lyricsOf),
        about = o.objOrNull("about")?.let(::aboutOf),
        note = o.objOrNull("note")?.let(::noteOf),
    )

    fun entryJson(e: ShareAboutEntry) = obj(
        "kind" to str(e.kind),
        "entryKey" to str(e.entryKey),
        "promptText" to str(e.promptText),
        "titleText" to str(e.titleText),
        "answerText" to str(e.answerText),
    )

    fun entryOf(o: JsonObject) = ShareAboutEntry(o.string("kind")!!, o.string("entryKey")!!, o.string("promptText"), o.string("titleText"), o.string("answerText")!!)

    fun sourcesJson(s: ShareAboutSources) = obj(
        "lyricist" to str(s.lyricist),
        "composer" to str(s.composer),
        "arranger" to str(s.arranger),
        "producer" to str(s.producer),
        "serverComposer" to str(s.serverComposer),
        "year" to (s.year?.let(::JsonPrimitive) ?: JsonNull),
    )

    fun sourcesOf(o: JsonObject) = ShareAboutSources(
        lyricist = o.string("lyricist"),
        composer = o.string("composer"),
        arranger = o.string("arranger"),
        producer = o.string("producer"),
        serverComposer = o.string("serverComposer"),
        year = (o["year"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.int,
    )

    fun labelsJson(l: ShareAboutLabels) = obj(
        "lyricist" to str(l.lyricist),
        "composer" to str(l.composer),
        "arranger" to str(l.arranger),
        "producer" to str(l.producer),
        "creationTime" to str(l.creationTime),
        "creationLocation" to str(l.creationLocation),
        "year" to str(l.year),
        "review" to str(l.review),
    )

    fun labelsOf(o: JsonObject) = ShareAboutLabels(
        lyricist = o.string("lyricist")!!,
        composer = o.string("composer")!!,
        arranger = o.string("arranger")!!,
        producer = o.string("producer")!!,
        creationTime = o.string("creationTime")!!,
        creationLocation = o.string("creationLocation")!!,
        year = o.string("year")!!,
        review = o.string("review")!!,
    )

    private fun posterJson(p: SharePoster) = obj(
        "lead" to lineJson(p.lead),
        "rest" to JsonArray(p.rest.map(::lineJson)),
        "translation" to str(p.translation),
        "leadSizeSp" to num(p.leadSizeSp),
        "leadTime" to str(p.leadTime),
    )

    private fun excerptJson(e: ShareExcerpt) = obj(
        "start" to num(e.start),
        "end" to num(e.end),
        "centre" to num(e.centre),
        "startMs" to JsonPrimitive(e.startMs),
        "endMs" to JsonPrimitive(e.endMs),
        "startTime" to str(YoinShareCards.time(e.startMs)),
        "endTime" to str(YoinShareCards.time(e.endMs)),
    )

    private fun planJson(p: ShareVerticalPlan) = obj(
        "columns" to strings(p.columns),
        "fontSizeSp" to num(p.fontSizeSp),
        "charsPerColumn" to JsonPrimitive(p.charsPerColumn),
    )

    private fun inkJson(i: ShareInk) = obj("source" to str(i.source.name), "tone" to (i.tone?.let(::JsonPrimitive) ?: JsonNull))

    private fun noteLineJson(l: YoinNoteLine) = obj(
        "kind" to str(l.kind.name),
        "level" to JsonPrimitive(l.level),
        "checked" to JsonPrimitive(l.checked),
        "runs" to JsonArray(
            l.runs.map { r ->
                obj(
                    "text" to str(r.text),
                    "bold" to JsonPrimitive(r.bold),
                    "italic" to JsonPrimitive(r.italic),
                    "mention" to str(r.mention?.link),
                )
            },
        ),
    )

    // ── JSON helpers ─────────────────────────────────────────────────

    fun str(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    fun strings(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))

    fun ints(values: List<Int>): JsonArray = JsonArray(values.map(::JsonPrimitive))

    /** A float as the file writes it: rounded to 6 decimals (the same digits on the JVM and Kotlin/Native). */
    fun num(value: Float): JsonPrimitive = JsonPrimitive(round(value.toDouble() * 1e6) / 1e6)

    fun obj(vararg entries: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*entries))

    fun hex(argb: Int): String = "#" + argb.toUInt().toString(16).uppercase().padStart(8, '0')

    fun argbOf(hex: String): Int = hex.removePrefix("#").toLong(16).toInt()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

    private fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

    private fun JsonObject.longOrNull(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.long

    private fun JsonObject.float(key: String): Float = getValue(key).jsonPrimitive.double.toFloat()

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    private fun JsonObject.objOrNull(key: String): JsonObject? = (this[key] as? JsonObject)

    private fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray

    /** Compact JSON with the invisible characters some vectors rely on written as escapes, so a reader sees them. */
    fun encode(e: JsonElement): String = Json.encodeToString(JsonElement.serializer(), e)
        .replace("​", "\\u200b")
        .replace(" ", "\\u00a0")
        .replace("　", "\\u3000")
        .replace("", "\\ue000")
}
