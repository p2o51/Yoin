package com.gpo.yoin.shared.home

import kotlin.math.abs
import kotlin.math.round
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** JSON helpers of the Home golden vectors (home-layout / home-grid / home-drag / home-edit-gestures.json). */
internal object HomeGoldenJson {

    fun str(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    fun strings(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))

    fun ints(values: List<Int>): JsonArray = JsonArray(values.map(::JsonPrimitive))

    fun bool(value: Boolean): JsonPrimitive = JsonPrimitive(value)

    fun int(value: Int?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    /** A float as written: rounded to 1e-4 (replays compare within [TOLERANCE], so JVM and Kotlin/Native agree). */
    fun num(value: Float): JsonElement = when {
        value.isNaN() -> JsonPrimitive("NaN")
        value.isInfinite() -> JsonPrimitive(if (value > 0) "Infinity" else "-Infinity")
        else -> JsonPrimitive(round(value.toDouble() * 1e4) / 1e4)
    }

    /** A written float read back (`"NaN"` included). */
    fun float(e: JsonElement?): Float {
        val p = e as? JsonPrimitive ?: error("not a number: $e")
        return when {
            p.isString && p.content == "NaN" -> Float.NaN
            p.isString && p.content == "Infinity" -> Float.POSITIVE_INFINITY
            p.isString && p.content == "-Infinity" -> Float.NEGATIVE_INFINITY
            else -> p.float
        }
    }

    fun pairs(pairs: List<List<String>>): JsonArray = JsonArray(pairs.map(::strings))

    fun obj(vararg entries: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*entries))

    fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

    fun JsonObject.ints(key: String): List<Int> = (this[key] as? JsonArray)?.map { it.jsonPrimitive.int }.orEmpty()

    fun JsonObject.pairs(key: String): List<List<String>> =
        (this[key] as? JsonArray)?.map { pair -> pair.jsonArray.map { it.jsonPrimitive.content } }.orEmpty()

    fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.booleanOrNull ?: error("$key not a boolean")

    fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.int

    fun JsonObject.float(key: String): Float = float(this[key])

    fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject

    fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray

    fun encode(e: JsonElement): String = Json.encodeToString(JsonElement.serializer(), e)

    /** Floats in a replay match within this. */
    const val TOLERANCE = 1e-4

    /** [expected] and [actual] are the same JSON, numbers within [TOLERANCE]. */
    fun close(expected: JsonElement?, actual: JsonElement?): Boolean = when {
        expected is JsonObject && actual is JsonObject ->
            expected.keys == actual.keys && expected.all { (k, v) -> close(v, actual[k]) }
        expected is JsonArray && actual is JsonArray ->
            expected.size == actual.size && expected.indices.all { close(expected[it], actual[it]) }
        expected is JsonPrimitive && actual is JsonPrimitive && !expected.isString && !actual.isString &&
            expected !is JsonNull && actual !is JsonNull -> {
            val e = expected.doubleOrNull
            val a = actual.doubleOrNull
            if (e != null && a != null && expected.booleanOrNull == null) abs(e - a) <= TOLERANCE else expected == actual
        }
        else -> expected == actual
    }

    /**
     * Every vector of [root]'s [sections] answered again by [outputs]; one line per field that differs
     * ([exact]: JSON equality, else [close]).
     */
    fun replay(
        root: JsonObject,
        sections: List<String>,
        exact: Boolean,
        outputs: (String, JsonObject) -> JsonObject,
    ): List<String> {
        val mismatches = mutableListOf<String>()
        sections.forEach { section ->
            val vectors = root[section]?.jsonArray ?: return@forEach run { mismatches += "missing section $section" }
            vectors.forEachIndexed { i, element ->
                val vector = element.jsonObject
                val answer = runCatching { outputs(section, vector) }.getOrElse {
                    mismatches += "$section[$i] ${vector["name"] ?: ""}: threw $it"
                    return@forEachIndexed
                }
                answer.forEach { (key, value) ->
                    val same = if (exact) vector[key] == value else close(vector[key], value)
                    if (!same) mismatches += "$section[$i] ${vector["name"] ?: ""} $key: expected ${vector[key]} but was $value"
                }
            }
        }
        return mismatches
    }

    /** The file: header fields, then one vector per line, section by section. */
    fun render(header: List<Pair<String, JsonElement>>, sections: List<Pair<String, List<JsonObject>>>): String = buildString {
        append("{\n")
        header.forEachIndexed { i, (key, value) ->
            append("  \"").append(key).append("\": ").append(encode(value))
            append(if (i == header.lastIndex && sections.isEmpty()) "\n" else ",\n")
        }
        sections.forEachIndexed { s, (section, vectors) ->
            append("  \"").append(section).append("\": [\n")
            vectors.forEachIndexed { i, vector ->
                append("    ").append(encode(vector))
                append(if (i == vectors.lastIndex) "\n" else ",\n")
            }
            append("  ]")
            append(if (s == sections.lastIndex) "\n" else ",\n")
        }
        append("}\n")
    }
}
