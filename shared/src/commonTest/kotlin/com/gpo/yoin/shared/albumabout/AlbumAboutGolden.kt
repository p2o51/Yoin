package com.gpo.yoin.shared.albumabout

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The golden vectors of the album About core (shared/src/commonTest/resources/golden/album-about.json). Each
 * vector is its inputs followed by what [YoinAlbumAbout] answers for them ([outputs]); the file is written by
 * `AlbumAboutGoldenTest` (androidHostTest) from [AlbumAboutGoldenFixtures], and [replay] reads every vector
 * back and answers it again, on the JVM and on the iOS simulator (through [ALBUM_ABOUT_GOLDEN], the file as a
 * Kotlin string). A port (HarmonyOS's Dart) does what [outputs] does, section by section.
 */
internal object AlbumAboutGolden {

    const val FILE = "shared/src/commonTest/resources/golden/album-about.json"

    /** Input fields first, then the output fields [outputs] adds. */
    val sections: List<String> = listOf(
        "searchLeads", "blurbLength", "searchQueries", "prompts", "replies", "credits", "cleanValue", "cleanBlurb",
        "encodeCredits", "decodeCredits", "content", "questionKey", "creditsLine", "unbreakable", "editorialNotes",
        "plainTextFromHtml", "aiCopy",
    )

    /** What the facade answers for one vector's inputs (the vector's own output fields are ignored). */
    fun outputs(section: String, v: JsonObject, labels: Map<String, AlbumCreditLabels>): JsonObject = when (section) {
        "searchLeads" -> obj("lead" to str(YoinAlbumAbout.searchLead(v.bool("builtInSearch"), v.int("searchedPages"))))
        "blurbLength" -> obj("length" to str(YoinAlbumAbout.blurbLength(v.string("targetLanguage")!!)))
        "searchQueries" -> obj(
            "queries" to strings(YoinAlbumAbout.searchQueries(groundingOf(v.obj("grounding")), v.int("count"))),
        )
        "prompts" -> {
            val grounding = groundingOf(v.obj("grounding"))
            val language = v.string("targetLanguage")!!
            val lead = v.string("searchLead")!!
            val question = v.string("question")
            obj(
                "creditsOnly" to JsonPrimitive(YoinAlbumAbout.isCreditsOnly(grounding)),
                "groundingLines" to str(YoinAlbumAbout.groundingLines(grounding)),
                "about" to str(YoinAlbumAbout.aboutPrompt(grounding, language, lead)),
                "ask" to str(question?.let { YoinAlbumAbout.askPrompt(grounding, it, language, lead) }),
            )
        }
        "replies" -> {
            val raw = v.string("raw")!!
            obj(
                "parsed" to replyJson(YoinAlbumAbout.parseReply(raw)),
                "stored" to replyJson(YoinAlbumAbout.storedReply(raw, v.bool("hasServiceNotes"))),
            )
        }
        "credits" -> obj("credits" to creditsJson(YoinAlbumAbout.parseCredits(v.string("text"))))
        "cleanValue" -> obj("clean" to str(YoinAlbumAbout.cleanValue(v.string("value")!!)))
        "cleanBlurb" -> obj("clean" to str(YoinAlbumAbout.cleanBlurb(v.string("text"))))
        "encodeCredits" -> obj("encoded" to str(YoinAlbumAbout.encodeCredits(creditsOf(v.obj("credits")))))
        "decodeCredits" -> obj("credits" to creditsJson(YoinAlbumAbout.decodeCredits(v.string("text"))))
        "content" -> obj("content" to contentJson(YoinAlbumAbout.content(v.array("rows").map { rowOf(it.jsonObject) })))
        "questionKey" -> obj("key" to str(YoinAlbumAbout.questionKey(v.string("question")!!)))
        "creditsLine" -> {
            val items = YoinAlbumAbout.creditItems(
                sourceLabels = v.strings("sourceLabels"),
                releaseDate = v.string("releaseDate"),
                originalReleaseDate = v.string("originalReleaseDate"),
                ai = creditsOf(v.obj("ai")),
            )
            val line = YoinAlbumAbout.creditsLine(items, labels.getValue(v.string("labels")!!), v.string("dateText"))
            obj("items" to JsonArray(items.map(::itemJson)), "line" to (line?.let(::lineJson) ?: JsonNull))
        }
        "unbreakable" -> obj("text" to str(YoinAlbumAbout.unbreakable(v.string("label")!!)))
        "editorialNotes" -> obj("notes" to str(YoinAlbumAbout.editorialNotes(v.string("standard"), v.string("short"))))
        "plainTextFromHtml" -> obj("text" to str(YoinAlbumAbout.plainTextFromHtml(v.string("html")!!)))
        "aiCopy" -> obj("text" to str(YoinAlbumAbout.aiCopy(v.string("template")!!, v.string("name")!!)))
        else -> error("unknown section $section")
    }

    /** The whole file: one vector per line, so a drift shows as the lines that changed. */
    fun render(): String {
        val labels = AlbumAboutGoldenFixtures.labels
        val constants = obj(
            "maxPromptTracks" to JsonPrimitive(YoinAlbumAbout.maxPromptTracks),
            "maxNotesChars" to JsonPrimitive(YoinAlbumAbout.maxNotesChars),
            "maxNames" to JsonPrimitive(YoinAlbumAbout.maxNames),
            "kindCanonical" to str(YoinAlbumAbout.KIND_CANONICAL),
            "kindAsk" to str(YoinAlbumAbout.KIND_ASK),
            "canonCredits" to str(YoinAlbumAbout.CANON_CREDITS),
            "canonBlurb" to str(YoinAlbumAbout.CANON_BLURB),
            "creditItemSeparator" to str(YoinAlbumAbout.creditItemSeparator),
        )
        return buildString {
            append("{\n")
            append("  \"about\": ").append(encode(str(ABOUT))).append(",\n")
            append("  \"constants\": ").append(encode(constants)).append(",\n")
            append("  \"labels\": {\n")
            labels.entries.forEachIndexed { i, (key, value) ->
                append("    \"").append(key).append("\": ").append(encode(labelsJson(value)))
                append(if (i == labels.size - 1) "\n" else ",\n")
            }
            append("  }")
            sections.forEach { section ->
                val inputs = AlbumAboutGoldenFixtures.inputs.getValue(section)
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

    const val ABOUT = "Golden vectors for the album About core (com.gpo.yoin.shared.albumabout, Android PR #326): " +
        "the prompts, the reply parser, the stored credits format, the stored rows as the page reads them, the " +
        "credits line with its line-break segments, Apple Music's editorial notes and the AI service's name in " +
        "copy. Generated from :shared Kotlin through YoinAlbumAbout by AlbumAboutGoldenTest (androidHostTest); do " +
        "not edit by hand. Every vector is its inputs followed by the answer. Strings are UTF-16 (Kotlin / Swift " +
        "String.utf16 / Dart); in creditsLine.text, \\u00a0 is a no-break space and \\u2060 a word joiner. " +
        "Dates are ISO at the service's precision; creditsLine.dateText is the service-dated first release as " +
        "the platform's own date format writes it in that language (Android ICU yMMMMd / yMMMM)."

    // ── codecs ───────────────────────────────────────────────────────

    fun groundingJson(g: AlbumAboutGrounding): JsonObject = obj(
        "album" to str(g.album),
        "artist" to str(g.artist),
        "version" to str(g.version),
        "releaseDate" to str(g.releaseDate),
        "originalReleaseDate" to str(g.originalReleaseDate),
        "labels" to strings(g.labels),
        "releaseType" to str(g.releaseType),
        "genre" to str(g.genre),
        "tracks" to strings(g.tracks),
        "totalDurationSec" to (g.totalDurationSec?.let(::JsonPrimitive) ?: JsonNull),
        "serviceNotes" to str(g.serviceNotes),
    )

    fun groundingOf(o: JsonObject) = AlbumAboutGrounding(
        album = o.string("album")!!,
        artist = o.string("artist")!!,
        version = o.string("version"),
        releaseDate = o.string("releaseDate"),
        originalReleaseDate = o.string("originalReleaseDate"),
        labels = o.strings("labels"),
        releaseType = o.string("releaseType"),
        genre = o.string("genre"),
        tracks = o.strings("tracks"),
        totalDurationSec = (o["totalDurationSec"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.int,
        serviceNotes = o.string("serviceNotes"),
    )

    fun creditsJson(c: AlbumAiCredits): JsonObject = obj(
        "label" to strings(c.label),
        "producers" to strings(c.producers),
        "mastering" to strings(c.mastering),
        "mixing" to strings(c.mixing),
        "recordedTime" to str(c.recordedTime),
        "recordedStudios" to strings(c.recordedStudios),
        "source" to str(c.source),
        "firstReleased" to str(c.firstReleased),
    )

    fun creditsOf(o: JsonObject) = AlbumAiCredits(
        label = o.strings("label"),
        producers = o.strings("producers"),
        mastering = o.strings("mastering"),
        mixing = o.strings("mixing"),
        recordedTime = o.string("recordedTime"),
        recordedStudios = o.strings("recordedStudios"),
        source = o.string("source"),
        firstReleased = o.string("firstReleased"),
    )

    private fun replyJson(r: AlbumAboutReply) = obj(
        "credits" to creditsJson(r.credits),
        "blurb" to str(r.blurb),
        "answered" to JsonPrimitive(r.answered),
        "isEmpty" to JsonPrimitive(r.isEmpty),
    )

    fun rowJson(r: AlbumAboutRow) = obj(
        "kind" to str(r.kind),
        "entryKey" to str(r.entryKey),
        "promptText" to str(r.promptText),
        "titleText" to str(r.titleText),
        "answerText" to str(r.answerText),
        "createdAt" to JsonPrimitive(r.createdAt),
    )

    private fun rowOf(o: JsonObject) = AlbumAboutRow(
        kind = o.string("kind")!!,
        entryKey = o.string("entryKey")!!,
        promptText = o.string("promptText"),
        titleText = o.string("titleText"),
        answerText = o.string("answerText")!!,
        createdAt = o.getValue("createdAt").jsonPrimitive.long,
    )

    private fun contentJson(c: AlbumAboutContent) = obj(
        "credits" to creditsJson(c.credits),
        "blurb" to str(c.blurb),
        "asks" to JsonArray(c.asks.map { obj("question" to str(it.question), "title" to str(it.title), "answer" to str(it.answer)) }),
        "generated" to JsonPrimitive(c.generated),
    )

    private fun itemJson(i: AlbumCreditItem) = obj(
        "kind" to str(i.kind.name),
        "names" to strings(i.names),
        "text" to str(i.text),
        "date" to str(i.date),
    )

    private fun lineJson(l: AlbumCreditsLine) = obj(
        "text" to str(l.text),
        "plain" to str(l.plain),
        "segments" to strings(l.segments),
        "rows" to JsonArray(l.rows.map { obj("kind" to str(it.kind.name), "label" to str(it.label), "value" to str(it.value)) }),
    )

    fun labelsJson(l: AlbumCreditLabels) = obj(
        "label" to str(l.label),
        "producer" to str(l.producer),
        "mastering" to str(l.mastering),
        "mixing" to str(l.mixing),
        "recorded" to str(l.recorded),
        "source" to str(l.source),
        "firstReleased" to str(l.firstReleased),
        "nameSeparator" to str(l.nameSeparator),
        "timeAndPlace" to str(l.timeAndPlace),
    )

    private fun labelsOf(o: JsonObject) = AlbumCreditLabels(
        label = o.string("label")!!,
        producer = o.string("producer")!!,
        mastering = o.string("mastering")!!,
        mixing = o.string("mixing")!!,
        recorded = o.string("recorded")!!,
        source = o.string("source")!!,
        firstReleased = o.string("firstReleased")!!,
        nameSeparator = o.string("nameSeparator")!!,
        timeAndPlace = o.string("timeAndPlace")!!,
    )

    // ── JSON helpers ─────────────────────────────────────────────────

    fun str(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    fun strings(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))

    fun obj(vararg entries: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*entries))

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

    private fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    private fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray

    /**
     * Compact JSON with the invisible characters the credits line relies on written as escapes (so a reader of
     * the file sees them), and anything outside the Basic Multilingual Plane left as it is.
     */
    fun encode(e: JsonElement): String = Json.encodeToString(JsonElement.serializer(), e)
        .replace("\u2060", "\\u2060")
        .replace("\u00A0", "\\u00a0")
}
