package com.gpo.yoin.ui.memories.emblem

import androidx.compose.ui.graphics.Color
import com.gpo.yoin.ui.memories.showcase.MemoryPalette
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Golden values dumped from the prototype (docs/handoff/memories-showcase/groove.js v4 + twostate4.html) by a
 * node harness into app/src/test/resources/memories/golden/.
 */
internal object GrooveGolden {
    fun load(name: String): JsonObject {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("memories/golden/$name")) {
            "missing golden fixture memories/golden/$name"
        }
        return Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    fun kindOf(token: String): GrooveKind = when (token) {
        "album" -> GrooveKind.Album
        "average" -> GrooveKind.Average
        else -> GrooveKind.Unrated
    }

    fun surfaceOf(token: String): GrooveSurface = if (token == "bar") GrooveSurface.Bar else GrooveSurface.Cover

    fun hex(token: String): Color = Color(0xFF000000 or token.removePrefix("#").toLong(16))

    fun palette(o: JsonObject): MemoryPalette = MemoryPalette(
        base = hex(o.str("base")),
        accent = hex(o.str("accent")),
        deep = hex(o.str("deep")),
        soft = hex(o.str("soft")),
    )

    /** The sample models by id (kind, score, rated flags, palette). */
    fun models(file: JsonObject): Map<String, GrooveModel> = file.arr("models").associate { e ->
        val o = e.jsonObject
        o.str("id") to GrooveModel(
            kind = kindOf(o.str("kind")),
            score = o["score"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.doubleOrNull,
            trackRated = o.arr("rated").map { it.jsonPrimitive.boolean },
            palette = palette(o.obj("palette")),
        )
    }

    fun primitiveOf(token: String): GroovePrimitive = GroovePrimitive.valueOf(token)

    fun fallbackOf(token: JsonElement?): GrooveFallback? = when (
        token?.takeIf {
            it !is JsonNull
        }?.jsonPrimitive?.content
    ) {
        "performTick" -> GrooveFallback.Tick
        "performLightTick" -> GrooveFallback.LightTick
        "performClick" -> GrooveFallback.Click
        "performConfirm" -> GrooveFallback.Confirm
        else -> null
    }
}

internal fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
internal fun JsonObject.num(key: String): Double = getValue(key).jsonPrimitive.double
internal fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
internal fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean
internal fun JsonObject.arr(key: String): JsonArray = getValue(key).jsonArray
internal fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
internal fun JsonObject.isNull(key: String): Boolean = this[key] == null || this[key] is JsonNull
