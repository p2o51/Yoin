package com.gpo.yoin.shared.home

import com.gpo.yoin.shared.home.HomeGoldenJson.array
import com.gpo.yoin.shared.home.HomeGoldenJson.bool
import com.gpo.yoin.shared.home.HomeGoldenJson.float
import com.gpo.yoin.shared.home.HomeGoldenJson.int
import com.gpo.yoin.shared.home.HomeGoldenJson.num
import com.gpo.yoin.shared.home.HomeGoldenJson.obj
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
 * The golden vectors of Home edit's drag, span handle and pinch rules
 * (shared/src/commonTest/resources/golden/home-drag.json): every drop rule of HomeDragRules.kt (sides,
 * bands, beside / own row / swap arrangements, the half-travel rule, the two-column side step, the anchor,
 * edge scroll and its start guard, the exit rule, the layout catch-up check), the span handle's axis /
 * width / target / commit / key, and the pinch's progress and settle. Each vector is its inputs followed by
 * what the shared Kotlin answers ([outputs]); written by `HomeDragGoldenTest` (androidHostTest), replayed by
 * [replay] on the JVM and the iOS simulator (floats within [HomeGoldenJson.TOLERANCE]). Android's app pins
 * the same file (app HomeDragPinTest, through its own HomeDragRules.kt names).
 */
internal object HomeDragGolden {

    const val FILE = "shared/src/commonTest/resources/golden/home-drag.json"

    val sections: List<String> = listOf(
        "rel", "pairSide", "inOwnRow", "rowPart", "pairZone", "sideAllowed", "laneOf", "placeBeside", "insertRow",
        "swapRow", "swapDir", "sideStep", "rescaleGrabX", "laneWidth", "edge", "edgeGuard", "exitKeepsDrop",
        "layoutFollows", "spanAxis", "spanWidth", "spanTarget", "spanCommit", "spanKey", "pinch", "timelines",
    )

    fun outputs(section: String, v: JsonObject, layouts: Map<String, JsonObject>): JsonObject = when (section) {
        "rel" -> obj("rel" to num(dragRel(v.float("fingerX"), v.float("contentLeft"), v.float("contentWidth"))))
        "pairSide" -> obj("next" to str(dragPairSide(lane(v.string("side"))!!, v.float("rel")).name))
        "inOwnRow" -> obj("in" to bool(dragInOwnRow(v.float("fingerY"), bandOf(v.obj("band")), v.float("bufferPx"))))
        "rowPart" -> obj("part" to str(dragRowPart(v.float("fingerY"), bandOf(v.obj("band"))).name))
        "pairZone" -> obj("zone" to str(dragPairZone(v.float("rel"))?.name))
        "sideAllowed" -> obj("allowed" to bool(dragSideAllowed(rowOf(v.strings("row")), section(v.string("dragged")))))
        "laneOf" -> {
            val rows = v.pairs("rows").map(::rowOf)
            val s = section(v.string("section"))
            obj("lane" to str(dragLaneOf(s, rows).name), "rowIndex" to JsonPrimitive(dragRowIndexOf(s, rows)))
        }
        "placeBeside" -> obj(
            "arrangement" to arrangementJson(
                dragPlaceBeside(sections(v, "ids"), pairsOf(v, "pairs"), section(v.string("dragged")), section(v.string("target")), lane(v.string("side"))!!),
            ),
        )
        "insertRow" -> obj("arrangement" to arrangementJson(dragInsertRow(sections(v, "ids"), pairsOf(v, "pairs"), section(v.string("dragged")), v.int("gap"))))
        "swapRow" -> obj(
            "after" to strings(
                dragSwapRow(sections(v, "ids"), pairsOf(v, "pairs"), section(v.string("dragged")), v.int("dir"), v.bool("twoColumn")).map { it.id },
            ),
        )
        "swapDir" -> obj(
            "dir" to JsonPrimitive(
                dragSwapDir(v.float("visualTop"), bandOf(v.obj("slot")), (v["prev"] as? JsonObject)?.let(::bandOf), (v["next"] as? JsonObject)?.let(::bandOf)),
            ),
        )
        "sideStep" -> {
            val layout = layouts[v.string("layout")] ?: error("unknown layout ${v.string("layout")}")
            stepJson(
                dragSideStep(
                    ids = sections(layout, "ids"),
                    pairs = pairsOf(layout, "pairs"),
                    dragged = section(layout.string("dragged")),
                    side = lane(layout.string("side")),
                    rel = v.float("rel"),
                    fingerY = v.float("fingerY"),
                    bands = layout.array("bands").map { bandOf(it.jsonObject) },
                    bufferPx = layout.float("bufferPx"),
                    pairing = v.bool("pairing"),
                ),
            )
        }
        "rescaleGrabX" -> obj("rescaled" to num(dragRescaleGrabX(v.float("grabX"), v.float("oldWidth"), v.float("newWidth"))))
        "laneWidth" -> obj("width" to num(dragLaneWidth(lane(v.string("lane"))!!, v.float("contentWidth"), v.float("gap"))))
        "edge" -> obj(
            "edge" to JsonPrimitive(dragEdgeOf(v.float("y"), v.float("top"), v.float("bottom"), v.float("edgePx"))),
            "speed" to num(dragAutoScrollSpeed(v.float("y"), v.float("top"), v.float("bottom"), v.float("edgePx"), v.float("minPx"), v.float("maxPx"))),
        )
        "edgeGuard" -> obj(
            "holds" to bool(dragEdgeGuardHolds(v.int("startEdge"), v.float("startY"), v.float("y"), v.float("heldMs"), v.float("pushPx"), v.float("dwellMs"))),
        )
        "exitKeepsDrop" -> obj("keeps" to bool(dragExitKeepsDrop(HomeEditExitReason.valueOf(v.string("reason")!!))))
        "layoutFollows" -> obj(
            "follows" to bool(dragLayoutFollows(sections(v, "visible"), v.pairs("rows").map(::rowOf), section(v.string("dragged")))),
        )
        "spanAxis" -> obj("axis" to str(homeHandleAxis(v.float("dx"), v.float("dy"), v.float("slopPx"), v.bool("horizontal"))?.name))
        "spanWidth" -> obj(
            "widthPx" to num(homeSpanDragWidth(HomeSpan.valueOf(v.string("from")!!), v.float("startPx"), v.float("dx"), v.float("halfPx"), v.float("fullPx"))),
        )
        "spanTarget" -> obj("target" to str(homeSpanTarget(v.float("widthPx"), v.float("halfPx"), v.float("fullPx")).name))
        "spanCommit" -> obj(
            "commit" to str(homeSpanCommit(HomeSpan.valueOf(v.string("from")!!), HomeSpan.valueOf(v.string("target")!!), v.bool("hasNext")).name),
        )
        "spanKey" -> obj("commit" to str(homeSpanKey(HomeSpan.valueOf(v.string("from")!!), v.bool("left"), v.bool("hasNext"))?.name))
        "pinch" -> {
            val progress = homeListPinchProgress(v.bool("fromOn"), v.float("scale"))
            obj("progress" to num(progress), "settlesOn" to bool(homeListSettlesOn(v.bool("fromOn"), progress)))
        }
        "timelines" -> HomeDragTimelines.run(v)
        else -> error("unknown section $section")
    }

    fun render(): String {
        val layouts = HomeDragGoldenFixtures.layouts
        val header = listOf(
            "about" to str(ABOUT),
            "constants" to constants(),
            "layouts" to JsonObject(layouts),
        )
        val body = sections.map { section ->
            section to HomeDragGoldenFixtures.inputs.getValue(section).map { input -> HomeGoldenJson.vector(input, outputs(section, input, layouts)) }
        }
        return HomeGoldenJson.render(header, body)
    }

    fun replay(text: String): List<String> {
        val root = Json.parseToJsonElement(text).jsonObject
        val layouts = root.getValue("layouts").jsonObject.mapValues { it.value.jsonObject }
        val mismatches = mutableListOf<String>()
        if (!HomeGoldenJson.close(root["constants"], constants())) mismatches += "constants: expected ${root["constants"]} but was ${constants()}"
        return mismatches + HomeGoldenJson.replay(root, sections, exact = false) { section, v -> outputs(section, v, layouts) }
    }

    fun constants(): JsonObject = obj(
        "rowBuffer" to num(HomeDragRuleTokens.RowBuffer),
        "swapToLeft" to num(HomeDragRuleTokens.SwapToLeft),
        "swapToRight" to num(HomeDragRuleTokens.SwapToRight),
        "rowGapZone" to num(HomeDragRuleTokens.RowGapZone),
        "pairLeft" to num(HomeDragRuleTokens.PairLeft),
        "pairRight" to num(HomeDragRuleTokens.PairRight),
        "autoScrollEdge" to num(HomeDragTokens.AutoScrollEdge),
        "autoScrollMin" to num(HomeDragTokens.AutoScrollMin),
        "autoScrollMax" to num(HomeDragTokens.AutoScrollMax),
        "edgeStartPush" to num(HomeDragTokens.EdgeStartPush),
        "edgeStartDwellMs" to num(HomeDragTokens.EdgeStartDwellMs),
        "changeTravel" to num(HomeDragTokens.ChangeTravel),
        "maxFrameSeconds" to num(HomeDragTokens.MaxFrameSeconds),
        "spanColumnGap" to num(HomeSpanRules.ColumnGap),
        "spanUnderHalf" to num(HomeSpanRules.UnderHalf),
        "pinchInFull" to num(HomeListPinchRules.PinchInFull),
        "pinchOutFull" to num(HomeListPinchRules.PinchOutFull),
        "pinchCommit" to num(HomeListPinchRules.PinchCommit),
    )

    const val ABOUT = "Golden vectors for Home edit's drag (com.gpo.yoin.shared.home, parity p4; Android PR #292, " +
        "owner 2026-11-22): the drop rules (a half's own row ± 16dp only changes sides across 0.45 / 0.55; over " +
        "another full row its top and bottom quarters make a row of its own, its middle's outer 30% pairs; over a " +
        "pair of two others a row of its own by the row's middle; never three across; no pairing while the edge " +
        "scroll runs), the half-travel swap, the anchor, the edge scroll (72dp bands, 120 → 900dp/s eased by " +
        "depth²) and its start guard (8dp push or 180ms rest), the exit rule, the layout catch-up check, the span " +
        "handle and the pinch into the list. Generated from :shared Kotlin by HomeDragGoldenTest (androidHostTest); " +
        "do not edit by hand. Lengths are px unless a name says dp; bands are whole px. Floats are rounded to " +
        "1e-4; compare within 1e-4. NaN is written \"NaN\". A row is one id or [left, right]; an arrangement is " +
        "{ids, pairs}; a side step is {kind: Vertical | Stay | Arrange, arrangement?, side?}. sideStep vectors " +
        "name a layout from \"layouts\" (the live arrangement, the dragged block, its side and the laid-out bands). " +
        "timelines run HomeDragCore frame by frame on a fixed row model (see HomeDragTimelines.kt): started " +
        "{side, grab}, one decision per frame (None | Step {arrangement, side} | Scroll {px, consumed}), then the " +
        "result {write, arrangement, side, scrolled}."

    // ── codecs ───────────────────────────────────────────────────────

    fun section(id: String?): HomeSection = HomeSection.fromId(id ?: error("no id")) ?: error("unknown section $id")

    fun sections(o: JsonObject, key: String): List<HomeSection> = o.strings(key).map(::section)

    fun pairsOf(o: JsonObject, key: String): Map<HomeSection, HomeSection> =
        o.pairs(key).associate { section(it[0]) to section(it[1]) }

    fun lane(name: String?): HomeDragLane? = name?.let(HomeDragLane::valueOf)

    fun rowOf(ids: List<String>): HomeFeedRow =
        if (ids.size == 1) HomeFeedRow.Full(section(ids[0])) else HomeFeedRow.Halves(section(ids[0]), section(ids[1]))

    fun rowJson(row: HomeFeedRow): JsonArray = strings(row.sections().map { it.id })

    fun bandJson(b: DragRowBand): JsonObject = obj("row" to rowJson(b.row), "top" to num(b.top), "bottom" to num(b.bottom))

    fun bandOf(o: JsonObject): DragRowBand =
        DragRowBand(rowOf(o.getValue("row").jsonArray.map { it.jsonPrimitive.content }), o.float("top"), o.float("bottom"))

    fun arrangementJson(a: DragArrangement): JsonObject =
        obj("ids" to strings(a.ids.map { it.id }), "pairs" to pairs(a.pairs.map { (l, r) -> listOf(l.id, r.id) }))

    fun arrangementOf(o: JsonObject): DragArrangement = DragArrangement(sections(o, "ids"), pairsOf(o, "pairs"))

    fun stepJson(step: DragSideStep): JsonObject = when (step) {
        DragSideStep.Vertical -> obj("kind" to str("Vertical"))
        DragSideStep.Stay -> obj("kind" to str("Stay"))
        is DragSideStep.Arrange -> obj("kind" to str("Arrange"), "arrangement" to arrangementJson(step.arrangement), "side" to str(step.side?.name))
    }
}

/** The inputs of every home-drag golden vector ([HomeDragGolden]), from HomeDragRulesTest, HomeSpanResizeTest and HomeEditListTest. */
internal object HomeDragGoldenFixtures {

    private fun v(name: String, vararg entries: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf("name" to str(name), *entries))

    private fun n(value: Float) = num(value)

    private fun i(value: Int) = JsonPrimitive(value)

    private fun s(value: String?) = str(value)

    private fun row(vararg ids: String) = strings(ids.toList())

    private fun band(top: Float, bottom: Float, vararg ids: String) = obj("row" to row(*ids), "top" to n(top), "bottom" to n(bottom))

    // Rows: Activities 0..300, MadeForYou (dragged, own row) 318..518, the RA | RP pair 536..836, MemoryGrid 854..1100.
    private val fullIds = listOf("activities", "made_for_you", "recently_added", "recently_played", "memory_grid")

    /** The two layouts the side-step sweeps run over (HomeDragRulesTest's). */
    val layouts: Map<String, JsonObject> = linkedMapOf(
        "ownRow" to obj(
            "ids" to strings(fullIds),
            "pairs" to pairs(listOf(listOf("recently_added", "recently_played"))),
            "dragged" to s("made_for_you"),
            "side" to JsonNull,
            "bands" to JsonArray(
                listOf(
                    band(0f, 300f, "activities"),
                    band(318f, 518f, "made_for_you"),
                    band(536f, 836f, "recently_added", "recently_played"),
                    band(854f, 1100f, "memory_grid"),
                ),
            ),
            "bufferPx" to n(16f),
        ),
        // Beside Activities (on its right): rows Activities | MadeForYou 0..300, the pair 318..618, MemoryGrid 636..880.
        "besideRight" to obj(
            "ids" to strings(fullIds),
            "pairs" to pairs(listOf(listOf("activities", "made_for_you"), listOf("recently_added", "recently_played"))),
            "dragged" to s("made_for_you"),
            "side" to s("Right"),
            "bands" to JsonArray(
                listOf(
                    band(0f, 300f, "activities", "made_for_you"),
                    band(318f, 618f, "recently_added", "recently_played"),
                    band(636f, 880f, "memory_grid"),
                ),
            ),
            "bufferPx" to n(16f),
        ),
        // The same, dragged on the left of its pair.
        "besideLeft" to obj(
            "ids" to strings(listOf("made_for_you", "activities", "recently_added", "recently_played", "memory_grid")),
            "pairs" to pairs(listOf(listOf("made_for_you", "activities"), listOf("recently_added", "recently_played"))),
            "dragged" to s("made_for_you"),
            "side" to s("Left"),
            "bands" to JsonArray(
                listOf(
                    band(0f, 300f, "made_for_you", "activities"),
                    band(318f, 618f, "recently_added", "recently_played"),
                    band(636f, 880f, "memory_grid"),
                ),
            ),
            "bufferPx" to n(16f),
        ),
        // Its own row scrolled off: nothing to judge against.
        "besideOffscreen" to obj(
            "ids" to strings(fullIds),
            "pairs" to pairs(listOf(listOf("activities", "made_for_you"), listOf("recently_added", "recently_played"))),
            "dragged" to s("made_for_you"),
            "side" to s("Right"),
            "bands" to JsonArray(listOf(band(318f, 618f, "recently_added", "recently_played"), band(636f, 880f, "memory_grid"))),
            "bufferPx" to n(16f),
        ),
    )

    private val rel = listOf(
        Triple(500f, 100f, 800f), Triple(100f, 100f, 800f), Triple(900f, 100f, 800f), Triple(1000f, 100f, 800f),
        Triple(Float.NaN, 0f, 800f), Triple(10f, 0f, 0f), Triple(10f, 0f, -5f),
    ).map { (x, left, width) -> v("$x across $left + $width", "fingerX" to n(x), "contentLeft" to n(left), "contentWidth" to n(width)) }

    private val pairSide = listOf("Right", "Left").flatMap { side ->
        listOf(.44f, .45f, .46f, .5f, .54f, .55f, .56f, Float.NaN, 0f, 1f).map { r -> v("$side at $r", "side" to s(side), "rel" to n(r)) }
    } + v("Full at .1", "side" to s("Full"), "rel" to n(.1f))

    private val inOwnRow = listOf(83f, 84f, 100f, 250f, 400f, 416f, 417f).map { y ->
        v("$y in 100..400 ± 16", "fingerY" to n(y), "band" to band(100f, 400f, "activities", "made_for_you"), "bufferPx" to n(16f))
    }

    private val rowPart = listOf(99f, 199f, 200f, 201f, 300f, 399f, 400f, 401f, 600f).map { y ->
        v("$y over 100..500", "fingerY" to n(y), "band" to band(100f, 500f, "memory_grid"))
    } + listOf(49f, 50f, 51f).map { y -> v("$y over an empty band at 50", "fingerY" to n(y), "band" to band(50f, 50f, "memory_grid")) }

    private val pairZone = listOf(0f, .29f, .3f, .31f, .5f, .69f, .7f, .71f, 1f, Float.NaN).map { r -> v("$r", "rel" to n(r)) }

    private val sideAllowed = listOf(
        listOf("activities") to "jump_back_in", listOf("jump_back_in") to "jump_back_in",
        listOf("activities", "recently_added") to "jump_back_in", listOf("activities", "jump_back_in") to "jump_back_in",
        listOf("jump_back_in", "activities") to "jump_back_in",
    ).map { (r, d) -> v("${r.joinToString(" | ")}, dragging $d", "row" to strings(r), "dragged" to s(d)) }

    private val laneRows = pairs(listOf(listOf("activities", "jump_back_in"), listOf("memory_grid"), listOf("recently_added", "recently_played")))
    private val laneOf = listOf("activities", "jump_back_in", "memory_grid", "recently_played", "rediscover").map { id ->
        v(id, "rows" to laneRows, "section" to s(id))
    }

    private val four = listOf("activities", "memory_grid", "made_for_you", "recently_played")
    private val placeBeside = listOf(
        v("beside a full row, right", "ids" to strings(four), "pairs" to pairs(emptyList()), "dragged" to s("made_for_you"), "target" to s("memory_grid"), "side" to s("Right")),
        v("beside a full row, left", "ids" to strings(four), "pairs" to pairs(emptyList()), "dragged" to s("made_for_you"), "target" to s("memory_grid"), "side" to s("Left")),
        v(
            "own partner's other edge swaps",
            "ids" to strings(listOf("activities", "jump_back_in", "memory_grid")),
            "pairs" to pairs(listOf(listOf("activities", "jump_back_in"))),
            "dragged" to s("jump_back_in"),
            "target" to s("activities"),
            "side" to s("Left"),
        ),
        v(
            "moved to another block leaves the partner full",
            "ids" to strings(listOf("activities", "jump_back_in", "memory_grid")),
            "pairs" to pairs(listOf(listOf("activities", "jump_back_in"))),
            "dragged" to s("jump_back_in"),
            "target" to s("memory_grid"),
            "side" to s("Right"),
        ),
        v(
            "beside a section that was paired breaks its pair",
            "ids" to strings(listOf("activities", "jump_back_in", "memory_grid", "rediscover")),
            "pairs" to pairs(listOf(listOf("activities", "jump_back_in"))),
            "dragged" to s("rediscover"),
            "target" to s("activities"),
            "side" to s("Left"),
        ),
        v("first beside last", "ids" to strings(four), "pairs" to pairs(emptyList()), "dragged" to s("activities"), "target" to s("recently_played"), "side" to s("Right")),
    )

    private val five = listOf("activities", "jump_back_in", "memory_grid", "recently_added", "recently_played")
    private val fivePairs = pairs(listOf(listOf("activities", "jump_back_in"), listOf("recently_added", "recently_played")))
    private val insertRow = listOf(0, 1, 2, 3, 4, 99).map { gap ->
        v("jump back in out of its pair to gap $gap", "ids" to strings(five), "pairs" to fivePairs, "dragged" to s("jump_back_in"), "gap" to i(gap))
    } + listOf(0, 2, 3).map { gap ->
        v("memory grid to gap $gap", "ids" to strings(five), "pairs" to fivePairs, "dragged" to s("memory_grid"), "gap" to i(gap))
    }

    private val swapIds = listOf("memory_grid", "recently_added", "recently_played", "made_for_you")
    private val swapRow = listOf(
        Triple("memory_grid", 1, true), Triple("made_for_you", -1, true), Triple("memory_grid", -1, true), Triple("made_for_you", 1, true),
        Triple("memory_grid", 1, false), Triple("memory_grid", -1, false), Triple("recently_played", -1, false), Triple("recently_added", 1, true),
    ).map { (d, dir, two) ->
        v("$d by $dir${if (two) " in two columns" else ""}", "ids" to strings(swapIds), "pairs" to pairs(listOf(listOf("recently_added", "recently_played"))), "dragged" to s(d), "dir" to i(dir), "twoColumn" to bool(two))
    }

    private val swapDir: List<JsonObject> = buildList {
        val prev = band(0f, 100f, "activities")
        val slot = band(118f, 418f, "jump_back_in")
        val next = band(436f, 700f, "memory_grid")
        listOf(60f, 59f, 58f, 258f, 259f, 260f, 150f, -5000f, 5000f).forEach { top ->
            add(v("top at $top, both neighbours", "visualTop" to n(top), "slot" to slot, "prev" to prev, "next" to next))
        }
        add(v("no neighbours, far up", "visualTop" to n(-5000f), "slot" to slot, "prev" to JsonNull, "next" to JsonNull))
        add(v("no neighbours, far down", "visualTop" to n(5000f), "slot" to slot, "prev" to JsonNull, "next" to JsonNull))
        val back = band(118f, 382f, "memory_grid")
        val swapped = band(400f, 700f, "jump_back_in")
        listOf(260f, 259f, 258f).forEach { top -> add(v("just swapped, top at $top", "visualTop" to n(top), "slot" to swapped, "prev" to back, "next" to JsonNull)) }
    }

    private val sideStep: List<JsonObject> = buildList {
        val ys = listOf(-20f, -10f, 50f, 150f, 250f, 309f, 312f, 400f, 550f, 627f, 650f, 700f, 800f, 870f, 1080f, 1120f)
        val rels = listOf(.05f, .2f, .44f, .5f, .56f, .8f, .95f)
        for (layout in listOf("ownRow", "besideRight", "besideLeft")) {
            for (y in ys) {
                for (r in rels) add(v("$layout at $r, $y", "layout" to s(layout), "rel" to n(r), "fingerY" to n(y), "pairing" to bool(true)))
                add(v("$layout at .8, $y, edge scrolling", "layout" to s(layout), "rel" to n(.8f), "fingerY" to n(y), "pairing" to bool(false)))
            }
        }
        add(v("besideRight at NaN, 150", "layout" to s("besideRight"), "rel" to n(Float.NaN), "fingerY" to n(150f), "pairing" to bool(true)))
        add(v("ownRow at NaN, 150", "layout" to s("ownRow"), "rel" to n(Float.NaN), "fingerY" to n(150f), "pairing" to bool(true)))
        listOf(150f, 400f, 2000f).forEach { y -> add(v("besideOffscreen at .2, $y", "layout" to s("besideOffscreen"), "rel" to n(.2f), "fingerY" to n(y), "pairing" to bool(true))) }
    }

    private val rescaleGrabX = listOf(
        listOf(190f, 490f, 1000f), listOf(387.755f, 1000f, 490f), listOf(190f, 490f, 490f), listOf(190f, 0f, 1000f), listOf(190f, 490f, 0f), listOf(-20f, 490f, 980f),
    ).map { (g, o, w) -> v("$g of $o → $w", "grabX" to n(g), "oldWidth" to n(o), "newWidth" to n(w)) }

    private val laneWidth = listOf("Full", "Left", "Right").flatMap { lane ->
        listOf(1000f to 20f, 2176f to 56f, 10f to 20f).map { (w, g) -> v("$lane of $w, gap $g", "lane" to s(lane), "contentWidth" to n(w), "gap" to n(g)) }
    }

    private val edge: List<JsonObject> = buildList {
        listOf(600f, 200f, 1000f, 199.999f, 1000.001f, 150f, 1050f, 100f, 20f, 1300f, Float.NaN).forEach { y ->
            add(v("$y in 100..1100, 100 bands", "y" to n(y), "top" to n(100f), "bottom" to n(1100f), "edgePx" to n(100f), "minPx" to n(120f), "maxPx" to n(900f)))
        }
        listOf(110f, 175f, 176f, 240f).forEach { y ->
            add(v("$y in a narrow 100..250", "y" to n(y), "top" to n(100f), "bottom" to n(250f), "edgePx" to n(100f), "minPx" to n(1f), "maxPx" to n(10f)))
        }
        // The tablet at 2x: 72dp bands = 144px, 240 → 1800 px/s.
        listOf(150f, 200f, 293f, 294f, 1900f, 2000f, 2100f).forEach { y ->
            add(v("$y on a 2x tablet 150..2150", "y" to n(y), "top" to n(150f), "bottom" to n(2150f), "edgePx" to n(144f), "minPx" to n(240f), "maxPx" to n(1800f)))
        }
        add(v("no band", "y" to n(100f), "top" to n(0f), "bottom" to n(1000f), "edgePx" to n(0f), "minPx" to n(1f), "maxPx" to n(10f)))
        add(v("empty area", "y" to n(100f), "top" to n(500f), "bottom" to n(500f), "edgePx" to n(50f), "minPx" to n(1f), "maxPx" to n(10f)))
    }

    private val edgeGuard = listOf(
        Triple(0, 0f, 0f), Triple(1, 15f, 0f), Triple(1, 16f, 0f), Triple(1, -40f, 0f), Triple(-1, -15f, 0f), Triple(-1, -16f, 0f),
        Triple(1, 0f, 179f), Triple(1, 0f, 180f), Triple(-1, 30f, 100f),
    ).map { (e, dy, held) ->
        v("edge $e, moved $dy, rested $held", "startEdge" to i(e), "startY" to n(1000f), "y" to n(1000f + dy), "heldMs" to n(held), "pushPx" to n(16f), "dwellMs" to n(180f))
    }

    private val exitKeepsDrop = HomeEditExitReason.entries.map { v(it.name, "reason" to s(it.name)) }

    private val followRows = pairs(listOf(listOf("activities"), listOf("made_for_you"), listOf("memory_grid")))
    private val layoutFollows = listOf(
        listOf("activities", "made_for_you", "memory_grid"), listOf("activities", "jump_back_in", "made_for_you"),
        listOf("made_for_you", "activities", "memory_grid"), listOf("activities", "memory_grid"), listOf("made_for_you"), emptyList(),
    ).map { visible -> v("visible ${visible.joinToString()}", "visible" to strings(visible), "rows" to followRows, "dragged" to s("made_for_you")) }

    private val spanAxis = listOf(
        listOf(3f, 4f, 1f), listOf(9f, 4f, 1f), listOf(9f, 4f, 0f), listOf(4f, 9f, 1f), listOf(9f, 9f, 1f), listOf(-12f, 2f, 1f), listOf(2f, -12f, 1f), listOf(8f, 0f, 1f),
    ).map { (dx, dy, h) -> v("($dx, $dy)${if (h > 0f) "" else " vertical only"}", "dx" to n(dx), "dy" to n(dy), "slopPx" to n(8f), "horizontal" to bool(h > 0f)) }

    private val spanWidth = listOf(
        Triple("Full", 1000f, -300f), Triple("Full", 1000f, -800f), Triple("Full", 1000f, 100f), Triple("LeftHalf", 490f, 300f),
        Triple("LeftHalf", 490f, -300f), Triple("RightHalf", 490f, -300f), Triple("RightHalf", 490f, 300f), Triple("RightHalf", 490f, -900f),
    ).map { (from, start, dx) -> v("$from from $start by $dx", "from" to s(from), "startPx" to n(start), "dx" to n(dx), "halfPx" to n(490f), "fullPx" to n(1000f)) }

    private val spanTarget = listOf(401.8f, 490f, 744f, 744.9f, 745f, 900f, 1000f).map { w ->
        v("$w between 490 and 1000", "widthPx" to n(w), "halfPx" to n(490f), "fullPx" to n(1000f))
    }

    private val spanCommit = HomeSpan.entries.flatMap { from ->
        HomeSpan.entries.flatMap { target -> listOf(true, false).map { next -> v("$from → $target${if (next) "" else ", nothing after"}", "from" to s(from.name), "target" to s(target.name), "hasNext" to bool(next)) } }
    }

    private val spanKey = HomeSpan.entries.flatMap { from ->
        listOf(true, false).flatMap { left -> listOf(true, false).map { next -> v("$from ${if (left) "←" else "→"}${if (next) "" else ", nothing after"}", "from" to s(from.name), "left" to bool(left), "hasNext" to bool(next)) } }
    }

    private val pinch = listOf(false, true).flatMap { fromOn ->
        listOf(1.2f, 1f, .9f, .85f, .8425f, .842f, .7f, .55f, .4f, 1.27f, 1.28f, 1.52f, 1.53f, 1.8f, 2.5f).map { scale ->
            v("${if (fromOn) "from the list" else "from the feed"} at $scale", "fromOn" to bool(fromOn), "scale" to n(scale))
        }
    }

    val inputs: Map<String, List<JsonObject>> = mapOf(
        "rel" to rel, "pairSide" to pairSide, "inOwnRow" to inOwnRow, "rowPart" to rowPart, "pairZone" to pairZone,
        "sideAllowed" to sideAllowed, "laneOf" to laneOf, "placeBeside" to placeBeside, "insertRow" to insertRow,
        "swapRow" to swapRow, "swapDir" to swapDir, "sideStep" to sideStep, "rescaleGrabX" to rescaleGrabX,
        "laneWidth" to laneWidth, "edge" to edge, "edgeGuard" to edgeGuard, "exitKeepsDrop" to exitKeepsDrop,
        "layoutFollows" to layoutFollows, "spanAxis" to spanAxis, "spanWidth" to spanWidth, "spanTarget" to spanTarget,
        "spanCommit" to spanCommit, "spanKey" to spanKey, "pinch" to pinch, "timelines" to HomeDragTimelines.inputs,
    )
}
