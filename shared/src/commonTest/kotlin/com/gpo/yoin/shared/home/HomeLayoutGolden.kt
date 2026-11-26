package com.gpo.yoin.shared.home

import com.gpo.yoin.shared.home.HomeGoldenJson.array
import com.gpo.yoin.shared.home.HomeGoldenJson.bool
import com.gpo.yoin.shared.home.HomeGoldenJson.int
import com.gpo.yoin.shared.home.HomeGoldenJson.intOrNull
import com.gpo.yoin.shared.home.HomeGoldenJson.obj
import com.gpo.yoin.shared.home.HomeGoldenJson.objOrNull
import com.gpo.yoin.shared.home.HomeGoldenJson.pairs
import com.gpo.yoin.shared.home.HomeGoldenJson.str
import com.gpo.yoin.shared.home.HomeGoldenJson.string
import com.gpo.yoin.shared.home.HomeGoldenJson.strings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The golden vectors of Home's layout core (shared/src/commonTest/resources/golden/home-layout.json). Each
 * vector is its inputs followed by what [YoinHomeLayout] (and, for `codec`, [HomeLayoutCodec]) answers for
 * them ([outputs]); the file is written by `HomeLayoutGoldenTest` (androidHostTest) from
 * [HomeLayoutGoldenFixtures], and [replay] reads every vector back and answers it again, on the JVM and on
 * the iOS simulator (through [HOME_LAYOUT_GOLDEN], the file as a Kotlin string). A port (HarmonyOS's Dart)
 * does what [outputs] does, section by section. Android's app pins the same file (app HomeLayoutPinTest).
 */
internal object HomeLayoutGolden {

    const val FILE = "shared/src/commonTest/resources/golden/home-layout.json"

    /** Input fields first, then the output fields [outputs] adds. */
    val sections: List<String> = listOf(
        "codec", "reconcile", "ops", "predicates", "sameSections", "widePairsAt", "samePairs", "spanPairs",
        "feedRows", "rowUnits", "canPair", "unitsPreset", "draftAtWidth", "echoes", "editStep",
    )

    /** What the core answers for one vector's inputs, [states] resolving a state named by a string. */
    fun outputs(section: String, v: JsonObject, states: Map<String, HomeLayoutState>): JsonObject {
        fun state(key: String): HomeLayoutState = stateIn(v.getValue(key), states)
        return when (section) {
            "codec" -> {
                val raw = v.string("raw")!!
                val document = HomeLayoutCodec.decode(raw)
                obj(
                    "document" to documentJson(document),
                    "encoded" to str(document?.let(HomeLayoutCodec::encode)),
                    "layoutEncoded" to str(YoinHomeLayout.encode(YoinHomeLayout.decode(raw))),
                )
            }
            "reconcile" -> {
                val raw = (v["document"] as? JsonObject)?.let(HomeGoldenJson::encode)
                val read = YoinHomeLayout.decode(raw)
                obj("state" to stateJson(read), "isDefault" to bool(YoinHomeLayout.isDefault(read)))
            }
            "ops" -> resultJson(applyOp(state("state"), v))
            "predicates" -> {
                val s = state("state")
                val persist = YoinHomeLayout.persist(s)
                val ids = YoinHomeLayout.sectionIds()
                obj(
                    "enabled" to strings(YoinHomeLayout.enabledIds(s)),
                    "hidden" to strings(YoinHomeLayout.hiddenIds(s)),
                    "isDefault" to bool(YoinHomeLayout.isDefault(s)),
                    "isWideDefault" to bool(YoinHomeLayout.isWideDefault(s)),
                    "isAnyDefault" to bool(YoinHomeLayout.isAnyDefault(s)),
                    "isDefaultForTwoColumn" to bool(YoinHomeLayout.isDefaultFor(s, twoColumn = true)),
                    "pairs" to pairs(YoinHomeLayout.pairs(s)),
                    "partners" to JsonObject(ids.associateWith { str(YoinHomeLayout.partnerOf(s, it)) }),
                    "units" to JsonObject(ids.associateWith { JsonPrimitive(YoinHomeLayout.unitsOf(s, it)) }),
                    "rows" to JsonObject(ids.associateWith { JsonPrimitive(YoinHomeLayout.rowsOf(s, it)) }),
                    "persist" to obj("clear" to bool(persist.clear), "document" to str(persist.documentJson)),
                )
            }
            "sameSections" -> obj("same" to bool(YoinHomeLayout.sameSections(state("a"), state("b"))))
            "widePairsAt" -> obj("pairs" to pairs(YoinHomeLayout.widePairsAt(v.strings("order"), v.pairs("memory"))))
            "samePairs" -> obj("same" to bool(YoinHomeLayout.samePairs(v.pairs("a"), v.pairs("b"))))
            "spanPairs" -> obj(
                "pairs" to pairs(YoinHomeLayout.spanPairs(v.strings("order"), v.array("halves").map { it.jsonPrimitive.content == "true" })),
            )
            "feedRows" -> obj(
                "rows" to pairs(YoinHomeLayout.feedRows(v.strings("displayed"), v.pairs("pairs"), v.bool("twoColumn"))),
            )
            "rowUnits" -> obj("units" to JsonPrimitive(YoinHomeLayout.rowUnits(state("state"), v.strings("row"))))
            "canPair" -> {
                val left = v.string("left")!!
                val right = v.string("right")
                obj(
                    "canPair" to (right?.let { bool(YoinHomeLayout.canPair(left, it)) } ?: JsonNull),
                    "rowUnitRange" to HomeGoldenJson.ints(YoinHomeLayout.rowUnitRange(left, right)),
                )
            }
            "unitsPreset" -> obj("preset" to JsonPrimitive(YoinHomeLayout.unitsPreset(v.int("units"))))
            "draftAtWidth" -> obj(
                "state" to stateJson(YoinHomeLayout.draftAtWidth(state("draft"), v.bool("edited"), state("base"), v.bool("twoColumn"))),
            )
            "echoes" -> obj("echoes" to bool(YoinHomeLayout.echoes(state("stored"), state("shown"))))
            "editStep" -> resultJson(YoinHomeLayout.editStep(applyOp(state("state"), v), v.bool("twoColumn")))
            else -> error("unknown section $section")
        }
    }

    /** One edit of `ops` / `editStep`: [v]'s `op` with its arguments. */
    fun applyOp(s: HomeLayoutState, v: JsonObject): HomeLayoutResult {
        fun id() = v.string("id")!!
        return when (val op = v.string("op")!!) {
            "withEnabled" -> YoinHomeLayout.withEnabled(s, id(), v.bool("enabled"))
            "withEnabledOrder" -> YoinHomeLayout.withEnabledOrder(s, v.strings("order"), v.strings("unseen"))
            "moved" -> YoinHomeLayout.moved(s, id(), v.int("index"))
            "withHalf" -> YoinHomeLayout.withHalf(s, id())
            "withFull" -> YoinHomeLayout.withFull(s, id())
            "withBesidePrevious" -> YoinHomeLayout.withBesidePrevious(s, id())
            "withArrangement" -> YoinHomeLayout.withArrangement(s, v.strings("order"), v.pairs("pairs"), v.strings("unseen"))
            "withUnits" -> YoinHomeLayout.withUnits(s, id(), v.int("units"))
            "withRows" -> YoinHomeLayout.withRows(s, id(), v.string("rows")!!)
            "reset" -> YoinHomeLayout.reset(s, v.bool("twoColumn"))
            "forTwoColumn" -> YoinHomeLayout.forTwoColumn(s, v.bool("twoColumn"))
            "shownAt" -> YoinHomeLayout.shownAt(s, v.bool("twoColumn"))
            "rememberingShownPairs" -> YoinHomeLayout.rememberingShownPairs(s)
            "withRememberedPairs" -> YoinHomeLayout.withRememberedPairs(s)
            "normalizedSpans" -> YoinHomeLayout.normalizedSpans(s)
            else -> error("unknown op $op")
        }
    }

    /** The whole file: the catalog and the named states, then one vector per line. */
    fun render(): String {
        val states = HomeLayoutGoldenFixtures.states
        val header = listOf(
            "about" to str(ABOUT),
            "constants" to constants(),
            "states" to JsonObject(states.mapValues { stateJson(it.value) }),
        )
        val body = sections.map { section ->
            section to HomeLayoutGoldenFixtures.inputs.getValue(section).map { input -> HomeGoldenJson.vector(input, outputs(section, input, states)) }
        }
        return HomeGoldenJson.render(header, body)
    }

    /** Every vector of [text] answered again; one line per field whose answer differs. */
    fun replay(text: String): List<String> {
        val root = Json.parseToJsonElement(text).jsonObject
        val states = root.getValue("states").jsonObject.mapValues { stateOf(it.value.jsonObject) }
        val mismatches = mutableListOf<String>()
        if (root["constants"] != constants()) mismatches += "constants: expected ${root["constants"]} but was ${constants()}"
        // The named states are what the fixtures build (a port reads them as its inputs).
        HomeLayoutGoldenFixtures.states.forEach { (name, state) ->
            if (root.getValue("states").jsonObject[name] != stateJson(state)) mismatches += "states.$name differs"
        }
        return mismatches + HomeGoldenJson.replay(root, sections, exact = true) { section, v -> outputs(section, v, states) }
    }

    /** The catalog and the codec's fixed keys, for a port to copy and check. */
    fun constants(): JsonObject = obj(
        "schemaVersion" to JsonPrimitive(HomeLayoutCodec.SCHEMA_VERSION),
        "documentKeys" to strings(HomeLayoutCodec.DocumentKeys.toList()),
        "entryKeys" to strings(HomeLayoutCodec.EntryKeys.toList()),
        "configKeys" to strings(listOf(HomeSectionState.RowsKey, HomeSectionState.SpanKey, HomeSectionState.UnitsKey)),
        "spanHalf" to str(HomeSectionState.SpanHalf),
        "widePairsKey" to str(HomeLayout.WidePairsKey),
        "retiredIds" to strings(YoinHomeLayout.retiredIds()),
        "undoMax" to JsonPrimitive(YoinHomeLayout.undoMax),
        "presets" to JsonArray(HomeRowPreset.entries.map { obj("key" to str(it.key), "units" to JsonPrimitive(it.units)) }),
        "defaultPreset" to str(HomeRowPreset.Default.key),
        "catalog" to JsonArray(
            YoinHomeLayout.sectionIds().map { id ->
                obj(
                    "id" to str(id),
                    "defaultEnabled" to bool(YoinHomeLayout.defaultEnabled(id)),
                    "appendEnabled" to bool(YoinHomeLayout.appendEnabled(id)),
                    "supportsRows" to bool(YoinHomeLayout.supportsRows(id)),
                    "takesPresets" to bool(YoinHomeLayout.takesPresets(id)),
                    "unitsOwnKey" to bool(YoinHomeLayout.unitsOwnKey(id)),
                    "unitRange" to HomeGoldenJson.ints(YoinHomeLayout.unitRange(id)),
                    "defaultUnits" to JsonPrimitive(YoinHomeLayout.defaultUnits(id)),
                )
            },
        ),
    )

    const val ABOUT = "Golden vectors for Home's layout core (com.gpo.yoin.shared.home, parity p4; Android PR #250 / " +
        "#292): the stored document (codec: unknown document and entry keys kept, a malformed entry drops alone, a " +
        "non-object wide reads as none), reconcile (retired ids dropped, unknown ids retained, first occurrence wins, " +
        "appended sections and WideDefault pairs for free partners), every edit (changed false = the same layout " +
        "back: no Undo step), the defaults and the persist rule, the pair helpers, the feed rows, the unit rules " +
        "and the edit session's draft / echo / step rules. Generated from :shared Kotlin through YoinHomeLayout by " +
        "HomeLayoutGoldenTest (androidHostTest); do not edit by hand. Every vector is its inputs followed by the " +
        "answer. A state is either a name from \"states\" or a state object: sections in render order (id, " +
        "enabled, then only what differs from the defaults: rows key (default l), rowsSet, half, units, " +
        "configExtras, entryExtras), then retained entries, newSections, widePairs ([left, right]), retainedWide " +
        "and retainedDocument when present. ops / editStep answer {changed, after}; after is left out when " +
        "nothing changed."

    // ── state ⇄ JSON ─────────────────────────────────────────────────

    fun stateIn(e: JsonElement, states: Map<String, HomeLayoutState>): HomeLayoutState =
        if (e is JsonPrimitive) states[e.content] ?: error("unknown state ${e.content}") else stateOf(e.jsonObject)

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
        if (s.newSections.isNotEmpty()) out["newSections"] = strings(s.newSections)
        out["widePairs"] = pairs(s.widePairs)
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
                units = s.intOrNull("units"),
                configExtrasJson = s.objOrNull("configExtras")?.let(HomeGoldenJson::encode),
                entryExtrasJson = s.objOrNull("entryExtras")?.let(HomeGoldenJson::encode),
            )
        },
        retained = (o["retained"] as? JsonArray).orEmpty().map { e ->
            val r = e.jsonObject
            HomeLayoutRetainedState(
                id = r.string("id")!!,
                enabled = r.bool("enabled"),
                configJson = r.objOrNull("config")?.let(HomeGoldenJson::encode),
                extrasJson = r.objOrNull("extras")?.let(HomeGoldenJson::encode),
            )
        },
        newSections = o.strings("newSections"),
        widePairs = o.pairs("widePairs"),
        retainedWideJson = o.objOrNull("retainedWide")?.let(HomeGoldenJson::encode),
        retainedDocumentJson = o.objOrNull("retainedDocument")?.let(HomeGoldenJson::encode),
    )

    private fun resultJson(result: HomeLayoutResult): JsonObject =
        if (result.changed) obj("changed" to bool(true), "after" to stateJson(result.state)) else obj("changed" to bool(false))

    fun documentJson(d: HomeLayoutDocument?): JsonElement = if (d == null) {
        JsonNull
    } else {
        obj(
            "sections" to JsonArray(d.sections.map(HomeLayoutCodec::encodeEntry)),
            "wide" to (d.wide ?: JsonNull),
            "extras" to (d.extras ?: JsonNull),
        )
    }
}
