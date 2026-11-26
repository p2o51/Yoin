package com.gpo.yoin.shared.home

import com.gpo.yoin.shared.home.HomeGoldenJson.bool
import com.gpo.yoin.shared.home.HomeGoldenJson.pairs
import com.gpo.yoin.shared.home.HomeGoldenJson.str
import com.gpo.yoin.shared.home.HomeGoldenJson.strings
import com.gpo.yoin.shared.home.HomeSection.Activities
import com.gpo.yoin.shared.home.HomeSection.JumpBackIn
import com.gpo.yoin.shared.home.HomeSection.LyricHighlight
import com.gpo.yoin.shared.home.HomeSection.MadeForYou
import com.gpo.yoin.shared.home.HomeSection.MemoryGrid
import com.gpo.yoin.shared.home.HomeSection.OtherProfiles
import com.gpo.yoin.shared.home.HomeSection.RecentlyAdded
import com.gpo.yoin.shared.home.HomeSection.RecentlyPlayed
import com.gpo.yoin.shared.home.HomeSection.Rediscover
import com.gpo.yoin.shared.home.HomeSection.YourPlaylists
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The inputs of every home-layout golden vector ([HomeLayoutGolden]), section by section, drawn from the
 * Android tests that pinned the behaviour before the move (HomeLayoutTest, HomeLayoutRowsTest,
 * HomeLayoutSpanTest, HomeLayoutWideMemoryTest, HomeLayoutStore*Test, HomeEditWideMemoryTest) and swept
 * over every section where an edit takes one.
 */
internal object HomeLayoutGoldenFixtures {

    private fun doc(text: String): HomeLayout = HomeLayout.fromDocument(HomeLayoutCodec.decode(text))

    /** The named states vectors start from (written once, in the file's "states"). */
    val states: Map<String, HomeLayoutState> by lazy {
        val wide = HomeLayout.WideDefault
        linkedMapOf(
            "default" to HomeLayout.Default,
            "wideDefault" to wide,
            // A portrait reorder that split Activities | Jump Back In: remembered, not shown.
            "split" to wide.moved(MemoryGrid, 1),
            // The whole order turned around: every remembered pair still shows, swapped.
            "reversed" to wide.withEnabledOrder(wide.enabledSections.reversed()),
            // One side of a pair hidden in one column: the pair breaks, the memory keeps it.
            "hiddenHalf" to wide.withEnabled(JumpBackIn, false),
            // A phone edit: two hidden, rows chosen, one moved to the top.
            "custom" to HomeLayout.Default
                .withEnabled(RecentlyAdded, false)
                .withEnabled(Rediscover, false)
                .withRows(JumpBackIn, HomeRowPreset.XL)
                .withRows(Activities, HomeRowPreset.S)
                .moved(MadeForYou, 0),
            // A two-column edit: units chosen, a pair split up, a new pair made.
            "wideEdited" to wide
                .withUnits(Activities, 3)
                .withFull(LyricHighlight)
                .withHalf(MemoryGrid)
                .withUnits(LyricHighlight, 1),
            // Everything this build can't read: unknown ids, entry and config keys, memory pairs and keys, document keys.
            "kept" to doc(
                """{"version":1,"sections":[{"id":"activities","enabled":true,"pin":true,"config":{"rows":"m","tint":"red"}},""" +
                    """{"id":"your_tracks","enabled":false,"config":{"rows":"s"},"since":3},{"id":"jump_back_in","enabled":true},""" +
                    """{"id":"memory_grid","enabled":true},{"id":"recently_added","enabled":false},{"id":"made_for_you","enabled":true},""" +
                    """{"id":"lyric_highlight","enabled":true,"config":{"units":1,"span":"third"}},{"id":"recently_played","enabled":true},""" +
                    """{"id":"your_playlists","enabled":true},{"id":"rediscover","enabled":true},{"id":"other_profiles","enabled":true}],""" +
                    """"wide":{"pairs":[["activities","jump_back_in"],["your_playlists","rediscover"],["memory_grid","future_x"]],"gap":"wide"},""" +
                    """"theme":"dense"}""",
            ),
            // A customized layout from before most sections existed: the rest appended, Rediscover hidden and new.
            "appended" to doc(
                """{"version":1,"sections":[{"id":"jump_back_in","enabled":true},{"id":"activities","enabled":true},""" +
                    """{"id":"recently_added","enabled":false}]}""",
            ),
            // Lyric Cards keeps its own unit count beside its card count.
            "lyricUnits" to wide.withRows(LyricHighlight, HomeRowPreset.S).withUnits(LyricHighlight, 1),
        ).mapValues { it.value.toState() }
    }

    private val ids = HomeSection.entries.map { it.id }

    private fun v(name: String, vararg entries: Pair<String, JsonElement>): JsonObject =
        JsonObject(linkedMapOf("name" to str(name), *entries))

    private fun s(value: String) = JsonPrimitive(value)

    private fun i(value: Int) = JsonPrimitive(value)

    private fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

    // ── codec ────────────────────────────────────────────────────────

    private val codec: List<JsonObject> = listOf(
        "plain" to """{"version":1,"sections":[{"id":"activities","enabled":true},{"id":"jump_back_in","enabled":false}]}""",
        "pre-versioned row" to """{"sections":[{"id":"activities","enabled":false}]}""",
        "version quoted" to """{"version":"1","sections":[{"id":"activities","enabled":true}]}""",
        "version a newer build wrote" to """{"version":2,"sections":[{"id":"activities","enabled":true}]}""",
        "version not a number" to """{"version":"one","sections":[{"id":"activities","enabled":true}]}""",
        "version null" to """{"version":null,"sections":[]}""",
        "version fractional" to """{"version":1.5,"sections":[]}""",
        "sections missing" to """{"version":1}""",
        "sections not an array" to """{"version":1,"sections":"activities"}""",
        "sections null" to """{"version":1,"sections":null}""",
        "empty sections" to """{"version":1,"sections":[]}""",
        "not json" to "not json at all",
        "array root" to """[{"id":"activities","enabled":true}]""",
        "trailing garbage" to """{"version":1,"sections":[]} x""",
        "entry without enabled" to """{"sections":[{"id":"activities"},{"id":"jump_back_in","enabled":true}]}""",
        "entry id a number" to """{"sections":[{"id":5,"enabled":true},{"id":"jump_back_in","enabled":true}]}""",
        "entry enabled quoted" to """{"sections":[{"id":"activities","enabled":"true"},{"id":"jump_back_in","enabled":true}]}""",
        "entry enabled a number" to """{"sections":[{"id":"activities","enabled":1}]}""",
        "entry not an object" to """{"sections":["activities",{"id":"jump_back_in","enabled":true},null,3]}""",
        "config null" to """{"sections":[{"id":"activities","enabled":true,"config":null}]}""",
        "config not an object" to """{"sections":[{"id":"activities","enabled":true,"config":"xl"},{"id":"rediscover","enabled":false,"config":[1]}]}""",
        "config empty" to """{"sections":[{"id":"activities","enabled":true,"config":{}}]}""",
        "config kept verbatim" to """{"sections":[{"id":"activities","enabled":true,"config":{"rows":2,"tint":{"a":[1,2]}}}]}""",
        "unknown entry keys" to """{"sections":[{"pin":true,"id":"activities","enabled":true,"order":{"x":1}},{"id":"your_tracks","enabled":false,"since":3}]}""",
        "unknown document keys" to """{"theme":"dense","version":1,"sections":[{"id":"activities","enabled":true}],"flags":[1,2],"n":null}""",
        "wide object" to """{"sections":[{"id":"activities","enabled":true}],"wide":{"pairs":[["activities","jump_back_in"]]}}""",
        "wide a number" to """{"version":1,"sections":[{"id":"activities","enabled":false}],"wide":3}""",
        "wide an array" to """{"sections":[{"id":"activities","enabled":true}],"wide":[["activities","jump_back_in"]]}""",
        "wide null" to """{"sections":[{"id":"activities","enabled":true}],"wide":null}""",
        "wide with its own keys" to """{"sections":[{"id":"activities","enabled":true}],"wide":{"gap":"wide","pairs":[]}}""",
        "duplicate and retired ids" to """{"sections":[{"id":"activities","enabled":false},{"id":"memories","enabled":true},{"id":"activities","enabled":true},{"id":"","enabled":true}]}""",
        "non-ascii id and keys" to """{"sections":[{"id":"未来","enabled":true,"注":"x"}],"主题":"密"}""",
        "escapes" to """{"sections":[{"id":"a\"b","enabled":true,"note":"line\nbreak  "}]}""",
    ).map { (name, raw) -> v(name, "raw" to s(raw)) }

    // ── reconcile ────────────────────────────────────────────────────

    private fun entries(vararg e: Pair<String, Boolean>): String =
        e.joinToString(",", "[", "]") { (id, on) -> """{"id":"$id","enabled":$on}""" }

    private val legacy = entries("activities" to true, "jump_back_in" to true, "recently_added" to true)

    private val reconcile: List<JsonObject> = listOf(
        "never customized" to null,
        "empty sections" to """{"version":1,"sections":[]}""",
        "only retired ids" to """{"sections":${entries("memory_teaser" to true, "memories" to false)}}""",
        "legacy three sections" to """{"sections":$legacy}""",
        "legacy plus hidden rediscover" to """{"sections":${entries("activities" to true, "jump_back_in" to true, "recently_added" to true, "rediscover" to false)}}""",
        "order and flags kept" to """{"sections":${entries("recently_added" to false, "jump_back_in" to true, "activities" to false)}}""",
        "first occurrence wins" to """{"sections":${entries("activities" to false, "activities" to true, "your_tracks" to false, "your_tracks" to true)}}""",
        "unknown ids retained in order" to """{"sections":${entries("your_tracks" to true, "activities" to true, "top_three" to false)}}""",
        "blank and retired dropped" to """{"sections":${entries("memories" to true, "memory_teaser" to true, "" to true, "jump_back_in" to false)}}""",
        "every section listed" to """{"sections":${entries(*ids.map { it to true }.toTypedArray())}}""",
        "rows read" to """{"sections":[{"id":"jump_back_in","enabled":true,"config":{"rows":"xl"}},{"id":"activities","enabled":true,"config":{"rows":"s"}}]}""",
        "rows value unknown" to """{"sections":[{"id":"activities","enabled":true,"config":{"rows":"xxl"}}]}""",
        "rows l chosen" to """{"sections":[{"id":"jump_back_in","enabled":true,"config":{"rows":"l"}}]}""",
        "units on lyric cards" to """{"sections":[{"id":"lyric_highlight","enabled":true,"config":{"rows":"s","units":1}}]}""",
        "units not a number" to """{"sections":[{"id":"lyric_highlight","enabled":true,"config":{"units":"two"}}]}""",
        "span half pair" to """{"sections":[{"id":"activities","enabled":true,"config":{"span":"half"}},{"id":"jump_back_in","enabled":true,"config":{"span":"half"}}]}""",
        "span value unknown kept" to """{"sections":[{"id":"activities","enabled":true,"config":{"span":"third"}}]}""",
        "orphan half" to """{"sections":[{"id":"activities","enabled":true,"config":{"span":"half"}},{"id":"rediscover","enabled":true}]}""",
        "odd run of halves" to """{"sections":[{"id":"activities","enabled":true,"config":{"span":"half"}},{"id":"memory_grid","enabled":true},""" +
            """{"id":"jump_back_in","enabled":true,"config":{"span":"half"}},{"id":"recently_added","enabled":true,"config":{"span":"half"}},""" +
            """{"id":"recently_played","enabled":true,"config":{"span":"half"}}]}""",
        "hidden half" to """{"sections":[{"id":"activities","enabled":false,"config":{"span":"half"}},{"id":"jump_back_in","enabled":true,"config":{"span":"half"}}]}""",
        "old document, pairs from flags" to """{"sections":${HomeLayoutCodec.encode(HomeLayout.WideDefault.moved(MemoryGrid, 1).toDocument()).substringAfter("\"sections\":").substringBefore(",\"wide\"")}}""",
        "old document, no pairs" to """{"sections":${entries(*ids.map { it to true }.toTypedArray())}}""",
        "memory stored" to """{"sections":${entries(*ids.map { it to true }.toTypedArray())},"wide":{"pairs":[["made_for_you","rediscover"],["jump_back_in","activities"]]}}""",
        "memory empty" to """{"sections":${entries(*ids.map { it to true }.toTypedArray())},"wide":{"pairs":[]}}""",
        "memory names unknown and retired" to """{"sections":$legacy,"wide":{"pairs":[["activities","future_x"],["memories","jump_back_in"],["future_a","future_b"]],"v":2}}""",
        "memory malformed pairs" to """{"sections":$legacy,"wide":{"pairs":[["activities"],["activities","activities"],[1,2],"x",["", "jump_back_in"],["jump_back_in","recently_added","x"],["activities","jump_back_in"]]}}""",
        "memory first pair a section is in wins" to """{"sections":$legacy,"wide":{"pairs":[["activities","jump_back_in"],["recently_added","activities"],["jump_back_in","recently_added"]]}}""",
        "memory pairs not an array" to """{"sections":$legacy,"wide":{"pairs":"activities"}}""",
        "appended section joins its free WideDefault partner" to """{"sections":${entries(*ids.filter { it != "lyric_highlight" }.map { it to true }.toTypedArray())},"wide":{"pairs":[["activities","jump_back_in"]]}}""",
        "appended section whose partner is taken" to """{"sections":${entries(*ids.filter { it != "lyric_highlight" }.map { it to true }.toTypedArray())},"wide":{"pairs":[["recently_added","rediscover"]]}}""",
        "kept keys" to """{"sections":[{"id":"activities","enabled":true,"pin":1}],"wide":{"pairs":[],"gap":2},"theme":"dense"}""",
    ).map { (name, document) -> v(name, "document" to (document?.let(::parse) ?: JsonNull)) }

    // ── ops ──────────────────────────────────────────────────────────

    private fun op(name: String, state: String, op: String, vararg args: Pair<String, JsonElement>) =
        v(name, "state" to s(state), "op" to s(op), *args)

    private val ops: List<JsonObject> by lazy {
        val out = ArrayList<JsonObject>()
        val all = states.keys.toList()
        val twoColumnish = listOf("wideDefault", "split", "reversed", "wideEdited")
        // Show / hide every section.
        for (state in listOf("default", "wideDefault", "split", "custom", "kept")) {
            val layout = states.getValue(state)
            for (section in layout.sections) {
                out += op("$state: ${if (section.enabled) "hide" else "show"} ${section.id}", state, "withEnabled", "id" to s(section.id), "enabled" to bool(!section.enabled))
            }
            out += op("$state: hide again ${layout.sections.first().id} unchanged", state, "withEnabled", "id" to s(layout.sections.first().id), "enabled" to bool(layout.sections.first().enabled))
        }
        // The two-column span edits on every section.
        for (state in twoColumnish) {
            for (id in ids) {
                out += op("$state: half $id", state, "withHalf", "id" to s(id))
                out += op("$state: full $id", state, "withFull", "id" to s(id))
                out += op("$state: beside previous $id", state, "withBesidePrevious", "id" to s(id))
            }
        }
        // Moves: to the top, to the end, past the end, to where it is.
        for (state in listOf("wideDefault", "custom")) {
            for (id in ids) {
                out += op("$state: move $id to 0", state, "moved", "id" to s(id), "index" to i(0))
                out += op("$state: move $id past the end", state, "moved", "id" to s(id), "index" to i(99))
            }
            out += op("$state: move to where it is", state, "moved", "id" to s(states.getValue(state).sections.first { it.enabled }.id), "index" to i(0))
        }
        out += op("default: move to 3", "default", "moved", "id" to s("rediscover"), "index" to i(3))
        out += op("default: move to -1 clamps", "default", "moved", "id" to s("rediscover"), "index" to i(-1))
        out += op("custom: move a hidden section", "custom", "moved", "id" to s("recently_added"), "index" to i(0))
        // Units and rows.
        for (state in listOf("wideDefault", "lyricUnits")) {
            for (id in ids) {
                out += op("$state: $id at 1 unit", state, "withUnits", "id" to s(id), "units" to i(1))
                out += op("$state: $id at 3 units", state, "withUnits", "id" to s(id), "units" to i(3))
            }
            out += op("$state: activities at 9 units clamps", state, "withUnits", "id" to s("activities"), "units" to i(9))
            out += op("$state: activities at 0 units clamps", state, "withUnits", "id" to s("activities"), "units" to i(0))
        }
        for (state in listOf("custom", "lyricUnits")) {
            for (id in ids) {
                out += op("$state: $id rows s", state, "withRows", "id" to s(id), "rows" to s("s"))
                out += op("$state: $id rows l", state, "withRows", "id" to s(id), "rows" to s("l"))
            }
            out += op("$state: rows key unknown", state, "withRows", "id" to s("activities"), "rows" to s("xxl"))
        }
        // Width and reset.
        for (state in all) {
            for (twoColumn in listOf(false, true)) {
                out += op("$state: reset${if (twoColumn) " two columns" else ""}", state, "reset", "twoColumn" to bool(twoColumn))
                out += op("$state: for ${if (twoColumn) "two columns" else "one column"}", state, "forTwoColumn", "twoColumn" to bool(twoColumn))
                out += op("$state: shown at ${if (twoColumn) "two columns" else "one column"}", state, "shownAt", "twoColumn" to bool(twoColumn))
            }
            out += op("$state: remembering shown pairs", state, "rememberingShownPairs")
            out += op("$state: with remembered pairs", state, "withRememberedPairs")
            out += op("$state: normalized spans", state, "normalizedSpans")
        }
        // Orders.
        val wideOrder = states.getValue("wideDefault").sections.filter { it.enabled }.map { it.id }
        val defaultOrder = ids
        out += op("default: order reversed", "default", "withEnabledOrder", "order" to strings(defaultOrder.reversed()), "unseen" to strings(emptyList()))
        out += op("default: order unchanged", "default", "withEnabledOrder", "order" to strings(defaultOrder), "unseen" to strings(emptyList()))
        out += op("default: order not a permutation", "default", "withEnabledOrder", "order" to strings(defaultOrder.drop(1)), "unseen" to strings(emptyList()))
        out += op("default: order with a duplicate", "default", "withEnabledOrder", "order" to strings(defaultOrder.drop(1) + defaultOrder[1]), "unseen" to strings(emptyList()))
        out += op(
            "default: order leaves an unseen section out",
            "default",
            "withEnabledOrder",
            "order" to strings((defaultOrder - "lyric_highlight").reversed()),
            "unseen" to strings(listOf("lyric_highlight")),
        )
        out += op(
            "default: order leaves out one that isn't unseen",
            "default",
            "withEnabledOrder",
            "order" to strings((defaultOrder - "lyric_highlight").reversed()),
            "unseen" to strings(emptyList()),
        )
        out += op("wideDefault: order reversed", "wideDefault", "withEnabledOrder", "order" to strings(wideOrder.reversed()), "unseen" to strings(emptyList()))
        out += op(
            "wideDefault: memory grid between activities and jump back in",
            "wideDefault",
            "withEnabledOrder",
            "order" to strings(listOf("activities", "memory_grid", "jump_back_in") + wideOrder.drop(3)),
            "unseen" to strings(emptyList()),
        )
        out += op("custom: order of the enabled reversed", "custom", "withEnabledOrder", "order" to strings(states.getValue("custom").sections.filter { it.enabled }.map { it.id }.reversed()), "unseen" to strings(emptyList()))
        out += op("split: back together", "split", "withEnabledOrder", "order" to strings(wideOrder), "unseen" to strings(emptyList()))
        // Arrangements (a two-column drag's drop).
        out += op(
            "wideDefault: arrangement unchanged",
            "wideDefault",
            "withArrangement",
            "order" to strings(wideOrder),
            "pairs" to pairs(states.getValue("wideDefault").let { YoinHomeLayout.pairs(it) }),
            "unseen" to strings(emptyList()),
        )
        out += op(
            "wideDefault: memory grid beside activities",
            "wideDefault",
            "withArrangement",
            "order" to strings(listOf("activities", "memory_grid", "jump_back_in") + wideOrder.drop(3)),
            "pairs" to pairs(listOf(listOf("activities", "memory_grid"), listOf("lyric_highlight", "recently_added"), listOf("your_playlists", "rediscover"))),
            "unseen" to strings(emptyList()),
        )
        out += op(
            "wideDefault: no pairs",
            "wideDefault",
            "withArrangement",
            "order" to strings(wideOrder),
            "pairs" to pairs(emptyList()),
            "unseen" to strings(emptyList()),
        )
        out += op(
            "wideDefault: unseen lyric cards keeps its pair",
            "wideDefault",
            "withArrangement",
            "order" to strings(wideOrder - "lyric_highlight"),
            "pairs" to pairs(listOf(listOf("jump_back_in", "activities"))),
            "unseen" to strings(listOf("lyric_highlight")),
        )
        out += op(
            "wideDefault: arrangement with an unknown id",
            "wideDefault",
            "withArrangement",
            "order" to strings(wideOrder),
            "pairs" to pairs(listOf(listOf("activities", "future_x"))),
            "unseen" to strings(emptyList()),
        )
        out += op(
            "default: arrangement pairs the first two",
            "default",
            "withArrangement",
            "order" to strings(defaultOrder),
            "pairs" to pairs(listOf(listOf("activities", "jump_back_in"))),
            "unseen" to strings(emptyList()),
        )
        out += op("default: unknown id", "default", "withHalf", "id" to s("future_x"))
        out += op("hiddenHalf: half memory grid", "hiddenHalf", "withHalf", "id" to s("memory_grid"))
        out += op("hiddenHalf: full activities", "hiddenHalf", "withFull", "id" to s("activities"))
        out += op("hiddenHalf: show jump back in again", "hiddenHalf", "withEnabled", "id" to s("jump_back_in"), "enabled" to bool(true))
        out += op("kept: half memory grid", "kept", "withHalf", "id" to s("memory_grid"))
        out += op("kept: hide activities", "kept", "withEnabled", "id" to s("activities"), "enabled" to bool(false))
        out += op("wideEdited: memory grid at 2 units", "wideEdited", "withUnits", "id" to s("memory_grid"), "units" to i(2))
        out += op("custom: jump back in at 2 units", "custom", "withUnits", "id" to s("jump_back_in"), "units" to i(2))
        out += op("default: rows s on activities", "default", "withRows", "id" to s("activities"), "rows" to s("s"))
        out += op("kept: rows xl on activities", "kept", "withRows", "id" to s("activities"), "rows" to s("xl"))
        out
    }

    // ── the rest ─────────────────────────────────────────────────────

    private val predicates: List<JsonObject> by lazy {
        states.keys.map { v(it, "state" to s(it)) } + listOf(
            v("wide default with retained", "state" to HomeLayoutGolden.stateJson(HomeLayout.WideDefault.copy(retained = listOf(HomeSectionPref("your_tracks", true))).toState())),
            v("default with only document keys", "state" to HomeLayoutGolden.stateJson(HomeLayout.Default.copy(retainedDocument = JsonObject(mapOf("theme" to s("dense")))).toState())),
            v("default with its memory turned around", "state" to HomeLayoutGolden.stateJson(HomeLayout.Default.copy(widePairs = HomeLayout.Default.widePairs.entries.associate { (l, r) -> r to l }).toState())),
            v("default with a rows choice at l", "state" to HomeLayoutGolden.stateJson(HomeLayout.Default.withUnits(JumpBackIn, 3).toState())),
        )
    }

    private val sameSections: List<JsonObject> = listOf(
        Triple("default, itself", "default", "default"),
        Triple("default, wide default", "default", "wideDefault"),
        Triple("wide default, split", "wideDefault", "split"),
        Triple("wide default, reversed", "wideDefault", "reversed"),
        Triple("kept, kept", "kept", "kept"),
        Triple("custom, default", "custom", "default"),
    ).map { (name, a, b) -> v(name, "a" to s(a), "b" to s(b)) } + listOf(
        v(
            "default, its memory turned around",
            "a" to s("default"),
            "b" to HomeLayoutGolden.stateJson(HomeLayout.Default.copy(widePairs = HomeLayout.Default.widePairs.entries.associate { (l, r) -> r to l }).toState()),
        ),
        v("default, retained and document keys aside", "a" to s("default"), "b" to HomeLayoutGolden.stateJson(HomeLayout.Default.copy(retained = listOf(HomeSectionPref("x", true)), retainedDocument = JsonObject(mapOf("t" to i(1)))).toState())),
        v("default, only the memory differs", "a" to s("default"), "b" to HomeLayoutGolden.stateJson(HomeLayout.Default.copy(widePairs = emptyMap()).toState())),
    )

    private val wideOrder = HomeLayout.WideDefault.enabledSections.map { it.id }
    private val widePairs = YoinHomeLayout.pairs(HomeLayout.WideDefault.toState())

    private val widePairsAt: List<JsonObject> = listOf(
        v("wide default", "order" to strings(wideOrder), "memory" to pairs(widePairs)),
        v("reversed order pairs swapped", "order" to strings(wideOrder.reversed()), "memory" to pairs(widePairs)),
        v("memory turned around", "order" to strings(wideOrder), "memory" to pairs(widePairs.map { it.reversed() })),
        v("split up", "order" to strings(listOf("activities", "memory_grid", "jump_back_in")), "memory" to pairs(widePairs)),
        v("partner hidden", "order" to strings(wideOrder - "jump_back_in"), "memory" to pairs(widePairs)),
        v("empty memory", "order" to strings(wideOrder), "memory" to pairs(emptyList())),
        v("empty order", "order" to strings(emptyList()), "memory" to pairs(widePairs)),
        v("one section", "order" to strings(listOf("activities")), "memory" to pairs(widePairs)),
        v("chain a-b-c remembers a|b only", "order" to strings(listOf("activities", "jump_back_in", "recently_added")), "memory" to pairs(listOf(listOf("activities", "jump_back_in")))),
        v("unknown id in the order", "order" to strings(listOf("activities", "future_x", "jump_back_in")), "memory" to pairs(widePairs)),
    )

    private val samePairs: List<JsonObject> = listOf(
        v("equal", "a" to pairs(widePairs), "b" to pairs(widePairs)),
        v("sides turned", "a" to pairs(widePairs), "b" to pairs(widePairs.map { it.reversed() })),
        v("one fewer", "a" to pairs(widePairs), "b" to pairs(widePairs.drop(1))),
        v("other partner", "a" to pairs(listOf(listOf("activities", "jump_back_in"))), "b" to pairs(listOf(listOf("activities", "memory_grid")))),
        v("both empty", "a" to pairs(emptyList()), "b" to pairs(emptyList())),
        v("order of pairs aside", "a" to pairs(widePairs), "b" to pairs(widePairs.reversed())),
    )

    private fun halves(vararg h: Boolean) = kotlinx.serialization.json.JsonArray(h.map(::JsonPrimitive))

    private val spanPairs: List<JsonObject> = listOf(
        v("two halves", "order" to strings(listOf("activities", "jump_back_in")), "halves" to halves(true, true)),
        v("full breaks the run", "order" to strings(listOf("activities", "memory_grid", "jump_back_in")), "halves" to halves(true, false, true)),
        v("odd run", "order" to strings(listOf("activities", "jump_back_in", "recently_added")), "halves" to halves(true, true, true)),
        v("four halves", "order" to strings(listOf("activities", "jump_back_in", "recently_added", "rediscover")), "halves" to halves(true, true, true, true)),
        v("none", "order" to strings(listOf("activities", "jump_back_in")), "halves" to halves(false, false)),
        v("empty", "order" to strings(emptyList()), "halves" to halves()),
        v("half, full, half, half", "order" to strings(listOf("activities", "memory_grid", "jump_back_in", "recently_added")), "halves" to halves(true, false, true, true)),
    )

    private val feedRows: List<JsonObject> = listOf(
        v("wide default, two columns", "displayed" to strings(wideOrder), "pairs" to pairs(widePairs), "twoColumn" to bool(true)),
        v("wide default, one column", "displayed" to strings(wideOrder), "pairs" to pairs(widePairs), "twoColumn" to bool(false)),
        v("a partner with no content shows full width", "displayed" to strings(wideOrder - "jump_back_in"), "pairs" to pairs(widePairs), "twoColumn" to bool(true)),
        v("a right half not after its left", "displayed" to strings(listOf("jump_back_in", "activities")), "pairs" to pairs(widePairs), "twoColumn" to bool(true)),
        v("pairs turned around", "displayed" to strings(wideOrder), "pairs" to pairs(widePairs.map { it.reversed() }), "twoColumn" to bool(true)),
        v("nothing displayed", "displayed" to strings(emptyList()), "pairs" to pairs(widePairs), "twoColumn" to bool(true)),
    )

    private val rowUnits: List<JsonObject> by lazy {
        val out = ArrayList<JsonObject>()
        for (state in listOf("wideDefault", "wideEdited", "custom", "lyricUnits", "kept")) {
            YoinHomeLayout.pairs(states.getValue(state)).forEach { pair -> out += v("$state: ${pair.joinToString(" | ")}", "state" to s(state), "row" to strings(pair)) }
            ids.forEach { id -> out += v("$state: $id", "state" to s(state), "row" to strings(listOf(id))) }
        }
        out += v("a pair of sections whose left is not in the layout", "state" to s("default"), "row" to strings(listOf("activities", "jump_back_in")))
        out
    }

    private val canPair: List<JsonObject> = ids.map { v(it, "left" to s(it), "right" to JsonNull) } + listOf(
        Activities to JumpBackIn, JumpBackIn to Activities, Activities to MemoryGrid, JumpBackIn to MemoryGrid,
        LyricHighlight to RecentlyAdded, YourPlaylists to Rediscover, JumpBackIn to RecentlyPlayed,
        MadeForYou to OtherProfiles, Activities to LyricHighlight, MemoryGrid to JumpBackIn,
    ).map { (l, r) -> v("${l.id} | ${r.id}", "left" to s(l.id), "right" to s(r.id)) } +
        v("unknown right", "left" to s("activities"), "right" to s("future_x"))

    private val unitsPreset: List<JsonObject> = (-1..6).map { v("$it units", "units" to i(it)) }

    private val draftAtWidth: List<JsonObject> = buildList {
        for ((draft, base) in listOf("default" to "default", "wideDefault" to "default", "split" to "split", "custom" to "custom", "reversed" to "kept")) {
            for (edited in listOf(false, true)) {
                for (twoColumn in listOf(false, true)) {
                    add(
                        v(
                            "$draft from $base, ${if (edited) "edited" else "untouched"}, ${if (twoColumn) "two columns" else "one column"}",
                            "draft" to s(draft),
                            "edited" to bool(edited),
                            "base" to s(base),
                            "twoColumn" to bool(twoColumn),
                        ),
                    )
                }
            }
        }
    }

    private val echoes: List<JsonObject> = listOf(
        "default", "default", "default", "wideDefault", "wideDefault", "default", "custom", "custom",
        "custom", "default", "split", "wideDefault", "kept", "kept",
    ).chunked(2).map { (stored, shown) -> v("$stored stands for $shown", "stored" to s(stored), "shown" to s(shown)) }

    private val editStep: List<JsonObject> = listOf(
        op("wideDefault: hide jump back in, two columns", "wideDefault", "withEnabled", "id" to s("jump_back_in"), "enabled" to bool(false)),
        op("wideDefault: hide jump back in, one column", "wideDefault", "withEnabled", "id" to s("jump_back_in"), "enabled" to bool(false)),
        op("split: move memory grid back, two columns", "split", "moved", "id" to s("memory_grid"), "index" to i(2)),
        op("split: unchanged stays unchanged", "split", "withEnabled", "id" to s("activities"), "enabled" to bool(true)),
        op("reversed: half memory grid, two columns", "reversed", "withHalf", "id" to s("memory_grid")),
        op("default: move rediscover first, two columns", "default", "moved", "id" to s("rediscover"), "index" to i(0)),
    ).mapIndexed { index, vector -> JsonObject(vector + ("twoColumn" to bool(index != 1))) }

    val inputs: Map<String, List<JsonObject>> by lazy {
        mapOf(
            "codec" to codec,
            "reconcile" to reconcile,
            "ops" to ops,
            "predicates" to predicates,
            "sameSections" to sameSections,
            "widePairsAt" to widePairsAt,
            "samePairs" to samePairs,
            "spanPairs" to spanPairs,
            "feedRows" to feedRows,
            "rowUnits" to rowUnits,
            "canPair" to canPair,
            "unitsPreset" to unitsPreset,
            "draftAtWidth" to draftAtWidth,
            "echoes" to echoes,
            "editStep" to editStep,
        )
    }
}
