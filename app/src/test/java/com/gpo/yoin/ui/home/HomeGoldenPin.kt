package com.gpo.yoin.ui.home

import com.gpo.yoin.shared.home.HomeLayoutRetainedState
import com.gpo.yoin.shared.home.HomeLayoutSectionState
import com.gpo.yoin.shared.home.HomeLayoutState
import java.io.File
import kotlin.math.abs
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

/**
 * Reading the Home golden files (shared/src/commonTest/resources/golden/home-*.json) in the app's pin tests:
 * the files iOS and HarmonyOS are tested against, answered here through the app's own paths.
 */
internal object HomeGoldenPin {

    fun load(name: String): JsonObject = Json.parseToJsonElement(
        File(
            generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile },
            "shared/src/commonTest/resources/golden/$name",
        ).readText(),
    ).jsonObject

    fun JsonObject.section(name: String): List<JsonObject> =
        getValue(name).jsonArray.map { it.jsonObject }.also { check(it.isNotEmpty()) { "empty section $name" } }

    val JsonObject.name: String get() = string("name") ?: ""

    fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

    fun JsonObject.ints(key: String): List<Int> = (this[key] as? JsonArray)?.map { it.jsonPrimitive.int }.orEmpty()

    fun JsonObject.pairs(key: String): List<List<String>> =
        (this[key] as? JsonArray)?.map { pair -> pair.jsonArray.map { it.jsonPrimitive.content } }.orEmpty()

    fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.booleanOrNull ?: error("$key not a boolean")

    fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    fun JsonObject.float(key: String): Float {
        val p = getValue(key).jsonPrimitive
        return if (p.isString && p.content == "NaN") Float.NaN else p.float
    }

    fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    fun section(id: String): HomeSection = HomeSection.fromId(id) ?: error("unknown section $id")

    fun sections(ids: List<String>): List<HomeSection> = ids.map(::section)

    fun pairMap(pairs: List<List<String>>): Map<HomeSection, HomeSection> = pairs.associate { section(it[0]) to section(it[1]) }

    /** A float as the files write it (1e-4). */
    fun num(value: Float): JsonElement =
        if (value.isNaN()) JsonPrimitive("NaN") else JsonPrimitive(kotlin.math.round(value.toDouble() * 1e4) / 1e4)

    /** The same JSON, numbers within 1e-4. */
    fun close(expected: JsonElement?, actual: JsonElement?): Boolean = when {
        expected is JsonObject && actual is JsonObject -> expected.keys == actual.keys && expected.all { (k, v) -> close(v, actual[k]) }
        expected is JsonArray && actual is JsonArray -> expected.size == actual.size && expected.indices.all { close(expected[it], actual[it]) }
        expected is JsonPrimitive && actual is JsonPrimitive && !expected.isString && !actual.isString &&
            expected !is JsonNull && actual !is JsonNull && expected.booleanOrNull == null -> {
            val e = expected.doubleOrNull
            val a = actual.doubleOrNull
            if (e != null && a != null) abs(e - a) <= 1e-4 else expected == actual
        }
        else -> expected == actual
    }

    // ── layout states, as home-layout.json writes them ──────────────

    fun stateIn(e: JsonElement, states: Map<String, HomeLayoutState>): HomeLayoutState =
        if (e is JsonPrimitive) states.getValue(e.content) else stateOf(e.jsonObject)

    fun states(root: JsonObject): Map<String, HomeLayoutState> = root.obj("states").mapValues { stateOf(it.value.jsonObject) }

    fun stateJson(s: HomeLayoutState): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        out["sections"] = JsonArray(
            s.sections.map { section ->
                val o = LinkedHashMap<String, JsonElement>()
                o["id"] = JsonPrimitive(section.id)
                o["enabled"] = JsonPrimitive(section.enabled)
                if (section.rows != HomeRowPreset.Default.key) o["rows"] = JsonPrimitive(section.rows)
                if (section.rowsSet) o["rowsSet"] = JsonPrimitive(true)
                if (section.half) o["half"] = JsonPrimitive(true)
                section.units?.let { o["units"] = JsonPrimitive(it) }
                section.configExtrasJson?.let { o["configExtras"] = Json.parseToJsonElement(it) }
                section.entryExtrasJson?.let { o["entryExtras"] = Json.parseToJsonElement(it) }
                JsonObject(o)
            },
        )
        if (s.retained.isNotEmpty()) {
            out["retained"] = JsonArray(
                s.retained.map { r ->
                    val o = LinkedHashMap<String, JsonElement>()
                    o["id"] = JsonPrimitive(r.id)
                    o["enabled"] = JsonPrimitive(r.enabled)
                    r.configJson?.let { o["config"] = Json.parseToJsonElement(it) }
                    r.extrasJson?.let { o["extras"] = Json.parseToJsonElement(it) }
                    JsonObject(o)
                },
            )
        }
        if (s.newSections.isNotEmpty()) out["newSections"] = JsonArray(s.newSections.map(::JsonPrimitive))
        out["widePairs"] = JsonArray(s.widePairs.map { pair -> JsonArray(pair.map(::JsonPrimitive)) })
        s.retainedWideJson?.let { out["retainedWide"] = Json.parseToJsonElement(it) }
        s.retainedDocumentJson?.let { out["retainedDocument"] = Json.parseToJsonElement(it) }
        return JsonObject(out)
    }

    fun stateOf(o: JsonObject): HomeLayoutState = HomeLayoutState(
        sections = o.getValue("sections").jsonArray.map { e ->
            val s = e.jsonObject
            HomeLayoutSectionState(
                id = s.string("id")!!,
                enabled = s.bool("enabled"),
                rows = s.string("rows") ?: HomeRowPreset.Default.key,
                rowsSet = s["rowsSet"] != null && s.bool("rowsSet"),
                half = s["half"] != null && s.bool("half"),
                units = (s["units"] as? JsonPrimitive)?.int,
                configExtrasJson = (s["configExtras"] as? JsonObject)?.toString(),
                entryExtrasJson = (s["entryExtras"] as? JsonObject)?.toString(),
            )
        },
        retained = (o["retained"] as? JsonArray).orEmpty().map { e ->
            val r = e.jsonObject
            HomeLayoutRetainedState(
                id = r.string("id")!!,
                enabled = r.bool("enabled"),
                configJson = (r["config"] as? JsonObject)?.toString(),
                extrasJson = (r["extras"] as? JsonObject)?.toString(),
            )
        },
        newSections = o.strings("newSections"),
        widePairs = o.pairs("widePairs"),
        retainedWideJson = (o["retainedWide"] as? JsonObject)?.toString(),
        retainedDocumentJson = (o["retainedDocument"] as? JsonObject)?.toString(),
    )
}
