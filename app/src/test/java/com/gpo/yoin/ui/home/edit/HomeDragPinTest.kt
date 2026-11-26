package com.gpo.yoin.ui.home.edit

import com.gpo.yoin.shared.home.DragSideStep
import com.gpo.yoin.shared.home.HomeFeedRow
import com.gpo.yoin.ui.home.HomeGoldenPin
import com.gpo.yoin.ui.home.HomeGoldenPin.bool
import com.gpo.yoin.ui.home.HomeGoldenPin.float
import com.gpo.yoin.ui.home.HomeGoldenPin.int
import com.gpo.yoin.ui.home.HomeGoldenPin.name
import com.gpo.yoin.ui.home.HomeGoldenPin.num
import com.gpo.yoin.ui.home.HomeGoldenPin.obj
import com.gpo.yoin.ui.home.HomeGoldenPin.pairs
import com.gpo.yoin.ui.home.HomeGoldenPin.section
import com.gpo.yoin.ui.home.HomeGoldenPin.string
import com.gpo.yoin.ui.home.HomeGoldenPin.strings
import com.gpo.yoin.ui.home.HomeSection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android answers every home-drag golden vector (shared/src/commonTest/resources/golden/home-drag.json) under the
 * names HomeDragEngine, HomeSpanDrag and HomeEditListMode call (HomeDragRules.kt, HomeSpanResize.kt,
 * HomeEditList.kt), with the drag tokens as the engine turns them into px (Dp × density).
 */
class HomeDragPinTest {

    private val golden = HomeGoldenPin.load("home-drag.json")
    private val layouts = golden.obj("layouts").mapValues { it.value.jsonObject }

    private fun vectors(name: String) = with(HomeGoldenPin) { golden.section(name) }

    @Test
    fun should_answerTheDropRulesAsPinned() {
        vectors("rel").forEach { v -> close(v.name, v["rel"], num(dragRel(v.float("fingerX"), v.float("contentLeft"), v.float("contentWidth")))) }
        vectors("pairSide").forEach { v -> assertEquals(v.name, v.string("next"), dragPairSide(lane(v.string("side"))!!, v.float("rel")).name) }
        vectors("inOwnRow").forEach { v -> assertEquals(v.name, v.bool("in"), dragInOwnRow(v.float("fingerY"), bandOf(v.obj("band")), v.float("bufferPx"))) }
        vectors("rowPart").forEach { v -> assertEquals(v.name, v.string("part"), dragRowPart(v.float("fingerY"), bandOf(v.obj("band"))).name) }
        vectors("pairZone").forEach { v -> assertEquals(v.name, v.string("zone"), dragPairZone(v.float("rel"))?.name) }
        vectors("sideAllowed").forEach { v -> assertEquals(v.name, v.bool("allowed"), dragSideAllowed(rowOf(v.strings("row")), section(v.string("dragged")!!))) }
        vectors("laneOf").forEach { v ->
            val rows = v.pairs("rows").map(::rowOf)
            val s = section(v.string("section")!!)
            assertEquals(v.name, v.string("lane"), dragLaneOf(s, rows).name)
            assertEquals(v.name, v.int("rowIndex"), dragRowIndexOf(s, rows))
        }
        vectors("placeBeside").forEach { v ->
            val a = dragPlaceBeside(sections(v, "ids"), pairsOf(v, "pairs"), section(v.string("dragged")!!), section(v.string("target")!!), lane(v.string("side"))!!)
            assertEquals(v.name, v["arrangement"], arrangementJson(a))
        }
        vectors("insertRow").forEach { v ->
            assertEquals(v.name, v["arrangement"], arrangementJson(dragInsertRow(sections(v, "ids"), pairsOf(v, "pairs"), section(v.string("dragged")!!), v.int("gap"))))
        }
        vectors("swapRow").forEach { v ->
            val ids = dragSwapRow(sections(v, "ids"), pairsOf(v, "pairs"), section(v.string("dragged")!!), v.int("dir"), v.bool("twoColumn"))
            assertEquals(v.name, v.strings("after"), ids.map { it.id })
        }
        vectors("swapDir").forEach { v ->
            val dir = dragSwapDir(v.float("visualTop"), bandOf(v.obj("slot")), (v["prev"] as? JsonObject)?.let(::bandOf), (v["next"] as? JsonObject)?.let(::bandOf))
            assertEquals(v.name, v.int("dir"), dir)
        }
        vectors("sideStep").forEach { v ->
            val layout = layouts.getValue(v.string("layout")!!)
            val step = dragSideStep(
                ids = sections(layout, "ids"),
                pairs = pairsOf(layout, "pairs"),
                dragged = section(layout.string("dragged")!!),
                side = lane(layout.string("side")),
                rel = v.float("rel"),
                fingerY = v.float("fingerY"),
                bands = layout["bands"]!!.jsonArray.map { bandOf(it.jsonObject) },
                bufferPx = layout.float("bufferPx"),
                pairing = v.bool("pairing"),
            )
            val expected = JsonObject(v.filterKeys { it in setOf("kind", "arrangement", "side") })
            assertEquals(v.name, expected, stepJson(step))
        }
        vectors("rescaleGrabX").forEach { v -> close(v.name, v["rescaled"], num(dragRescaleGrabX(v.float("grabX"), v.float("oldWidth"), v.float("newWidth")))) }
        vectors("laneWidth").forEach { v -> close(v.name, v["width"], num(dragLaneWidth(lane(v.string("lane"))!!, v.float("contentWidth"), v.float("gap")))) }
        vectors("edge").forEach { v ->
            assertEquals(v.name, v.int("edge"), dragEdgeOf(v.float("y"), v.float("top"), v.float("bottom"), v.float("edgePx")))
            close(v.name, v["speed"], num(dragAutoScrollSpeed(v.float("y"), v.float("top"), v.float("bottom"), v.float("edgePx"), v.float("minPx"), v.float("maxPx"))))
        }
        vectors("edgeGuard").forEach { v ->
            val holds = dragEdgeGuardHolds(v.int("startEdge"), v.float("startY"), v.float("y"), v.float("heldMs"), v.float("pushPx"), v.float("dwellMs"))
            assertEquals(v.name, v.bool("holds"), holds)
        }
        vectors("exitKeepsDrop").forEach { v -> assertEquals(v.name, v.bool("keeps"), dragExitKeepsDrop(HomeEditExitReason.valueOf(v.string("reason")!!))) }
        vectors("layoutFollows").forEach { v ->
            assertEquals(v.name, v.bool("follows"), dragLayoutFollows(sections(v, "visible"), v.pairs("rows").map(::rowOf), section(v.string("dragged")!!)))
        }
    }

    @Test
    fun should_answerTheSpanHandleAndPinchAsPinned() {
        vectors("spanAxis").forEach { v -> assertEquals(v.name, v.string("axis"), homeHandleAxis(v.float("dx"), v.float("dy"), v.float("slopPx"), v.bool("horizontal"))?.name) }
        vectors("spanWidth").forEach { v ->
            close(v.name, v["widthPx"], num(homeSpanDragWidth(HomeSpan.valueOf(v.string("from")!!), v.float("startPx"), v.float("dx"), v.float("halfPx"), v.float("fullPx"))))
        }
        vectors("spanTarget").forEach { v -> assertEquals(v.name, v.string("target"), homeSpanTarget(v.float("widthPx"), v.float("halfPx"), v.float("fullPx")).name) }
        vectors("spanCommit").forEach { v ->
            assertEquals(v.name, v.string("commit"), homeSpanCommit(HomeSpan.valueOf(v.string("from")!!), HomeSpan.valueOf(v.string("target")!!), v.bool("hasNext")).name)
        }
        vectors("spanKey").forEach { v -> assertEquals(v.name, v.string("commit"), homeSpanKey(HomeSpan.valueOf(v.string("from")!!), v.bool("left"), v.bool("hasNext"))?.name) }
        vectors("pinch").forEach { v ->
            val progress = homeListPinchProgress(v.bool("fromOn"), v.float("scale"))
            close(v.name, v["progress"], num(progress))
            assertEquals(v.name, v.bool("settlesOn"), homeListSettlesOn(v.bool("fromOn"), progress))
        }
    }

    @Test
    fun should_useThePinnedTokens() {
        val c = golden.obj("constants")
        close("rowBuffer", c["rowBuffer"], num(HomeDragRuleTokens.RowBuffer.value))
        close("autoScrollEdge", c["autoScrollEdge"], num(HomeDragTokens.AutoScrollEdge.value))
        close("autoScrollMin", c["autoScrollMin"], num(HomeDragTokens.AutoScrollMin.value))
        close("autoScrollMax", c["autoScrollMax"], num(HomeDragTokens.AutoScrollMax.value))
        close("edgeStartPush", c["edgeStartPush"], num(HomeDragTokens.EdgeStartPush.value))
        close("edgeStartDwellMs", c["edgeStartDwellMs"], num(HomeDragTokens.EdgeStartDwellMs))
        close("changeTravel", c["changeTravel"], num(HomeDragTokens.ChangeTravel.value))
        close("spanColumnGap", c["spanColumnGap"], num(HomeSpanTokens.ColumnGap.value))
        close("pinchInFull", c["pinchInFull"], num(HomeEditListTokens.PinchInFull))
        close("pinchOutFull", c["pinchOutFull"], num(HomeEditListTokens.PinchOutFull))
        close("pinchCommit", c["pinchCommit"], num(HomeEditListTokens.PinchCommit))
    }

    // ── codecs ───────────────────────────────────────────────────────

    private fun close(name: String, expected: JsonElement?, actual: JsonElement) {
        assertTrue("$name: expected $expected but was $actual", HomeGoldenPin.close(expected, actual))
    }

    private fun sections(o: JsonObject, key: String): List<HomeSection> = o.strings(key).map(::section)

    private fun pairsOf(o: JsonObject, key: String): Map<HomeSection, HomeSection> = HomeGoldenPin.pairMap(o.pairs(key))

    private fun lane(name: String?): HomeDragLane? = name?.let(HomeDragLane::valueOf)

    private fun rowOf(ids: List<String>): HomeFeedRow =
        if (ids.size == 1) HomeFeedRow.Full(section(ids[0])) else HomeFeedRow.Halves(section(ids[0]), section(ids[1]))

    private fun bandOf(o: JsonObject): DragRowBand =
        DragRowBand(rowOf(o["row"]!!.jsonArray.map { it.jsonPrimitive.content }), o.float("top"), o.float("bottom"))

    private fun arrangementJson(a: DragArrangement): JsonObject = JsonObject(
        linkedMapOf(
            "ids" to JsonArray(a.ids.map { JsonPrimitive(it.id) }),
            "pairs" to JsonArray(a.pairs.map { (l, r) -> JsonArray(listOf(JsonPrimitive(l.id), JsonPrimitive(r.id))) }),
        ),
    )

    private fun stepJson(step: DragSideStep): JsonObject = when (step) {
        DragSideStep.Vertical -> JsonObject(mapOf("kind" to JsonPrimitive("Vertical")))
        DragSideStep.Stay -> JsonObject(mapOf("kind" to JsonPrimitive("Stay")))
        is DragSideStep.Arrange -> JsonObject(
            linkedMapOf(
                "kind" to JsonPrimitive("Arrange"),
                "arrangement" to arrangementJson(step.arrangement),
                "side" to (step.side?.name?.let(::JsonPrimitive) ?: JsonNull),
            ),
        )
    }
}
