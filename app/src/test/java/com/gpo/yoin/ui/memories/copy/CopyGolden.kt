package com.gpo.yoin.ui.memories.copy

import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Golden strings produced by running the approved prototype's own copy
 * functions (docs/handoff/memories-showcase/twostate4.html `voice`,
 * `excerptCands`, `day`/`dayZh`/`dayY`, `stats`, `sentences`/`joinS`/`wlen`
 * over data.js m1–m4 and twostate4's m5, plus a few derived shapes) under
 * node, "today" 2026-10-04. Fixtures: app/src/test/resources/memories/golden/copy-*.json.
 */
internal object CopyGolden {
    val TODAY: LocalDate = LocalDate.of(2026, 10, 4)

    fun load(name: String): JsonObject {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("memories/golden/$name")) {
            "missing golden fixture memories/golden/$name"
        }
        return Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    /** "m1/as-is" → the prototype memory rebuilt as a [MemoryCopyInput]. */
    val inputs: Map<String, MemoryCopyInput> by lazy {
        load("copy-inputs.json").getValue("inputs").jsonObject.mapValues { (_, value) -> input(value.jsonObject) }
    }

    /** The raw prototype memory, for checks that need fields the input drops (a note's anchor). */
    val rawInputs: Map<String, JsonObject> by lazy {
        load("copy-inputs.json").getValue("inputs").jsonObject.mapValues { (_, value) -> value.jsonObject }
    }

    fun input(o: JsonObject): MemoryCopyInput {
        val tracks = o.list("tracks").map { element ->
            val t = element.jsonObject
            MemoryCopyTrack(number = t.integer("n"), title = t.text("title"), rating = t.optDouble("rating")?.toFloat())
        }
        val byNumber = tracks.associateBy(MemoryCopyTrack::number)
        val notes = o.list("notes").map { element ->
            val n = element.jsonObject
            MemoryCopyNote(
                text = n.text("text"),
                writtenOn = LocalDate.parse(n.text("date")),
                track = n.optInt("track")?.let(byNumber::getValue),
                positionMs = n.optInt("at")?.let { seconds -> seconds * 1000L },
            )
        }
        return MemoryCopyInput(
            albumName = o.text("album"),
            aiTitle = o.optText("aiTitle"),
            review = o.optText("review"),
            reviewWrittenOn = o.optText("reviewWrittenAt")?.let(LocalDate::parse),
            notes = notes,
            tracks = tracks,
            ratedTracks = o.integer("ratedTracks"),
            totalTracks = o.integer("totalTracks"),
            albumScore = if (o.text("scoreKind") == "album") o.optDouble("score")?.toFloat() else null,
            listening = MemoryListening(
                plays = o.integer("plays"),
                firstHeard = LocalDate.parse(o.text("firstPlayed")),
                lastHeard = LocalDate.parse(o.text("lastPlayed")),
            ),
        )
    }

    /** The one deliberate English fix over the prototype: "an" before an eight ("an 8.5", not "a 8.5"). */
    fun withArticleFix(text: String?): String? = text?.replace(Regex("""\ba (?=8)"""), "an ")

    fun language(token: String): MemoryProseLanguage =
        if (token == "zh") MemoryProseLanguage.ZH else MemoryProseLanguage.EN

    fun titleKind(token: String): MemoryTitleKind = when (token) {
        "ai" -> MemoryTitleKind.AI
        "motif" -> MemoryTitleKind.MOTIF
        else -> MemoryTitleKind.ALBUM
    }

    fun fact(token: String): MemoryFact = MemoryFact.valueOf(token.uppercase())
}

internal fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
internal fun JsonObject.optText(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
internal fun JsonObject.integer(key: String): Int = getValue(key).jsonPrimitive.int
internal fun JsonObject.optInt(key: String): Int? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int
internal fun JsonObject.optDouble(key: String): Double? =
    this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.double
internal fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.boolean
internal fun JsonObject.list(key: String): JsonArray = getValue(key).jsonArray
internal fun JsonElement.textOrNull(): String? = takeUnless { it is JsonNull }?.jsonPrimitive?.content
