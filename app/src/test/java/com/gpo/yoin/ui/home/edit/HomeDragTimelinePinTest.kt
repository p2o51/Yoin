package com.gpo.yoin.ui.home.edit

import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import com.gpo.yoin.ui.home.HomeGoldenPin
import com.gpo.yoin.ui.home.HomeGoldenPin.bool
import com.gpo.yoin.ui.home.HomeGoldenPin.float
import com.gpo.yoin.ui.home.HomeGoldenPin.name
import com.gpo.yoin.ui.home.HomeGoldenPin.num
import com.gpo.yoin.ui.home.HomeGoldenPin.obj
import com.gpo.yoin.ui.home.HomeGoldenPin.pairs
import com.gpo.yoin.ui.home.HomeGoldenPin.section
import com.gpo.yoin.ui.home.HomeGoldenPin.string
import com.gpo.yoin.ui.home.HomeGoldenPin.strings
import com.gpo.yoin.ui.home.HomeSection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android's [HomeDragEngine] drags every home-drag.json timeline as :shared's HomeDragCore does, frame by frame:
 * the engine runs on Compose's frame clock (16ms frames, the first with dt 0) over a host that lays the rows out
 * as the timelines' model does (rows 300px tall, 18px apart, a pair one row; the layout catching up after each
 * frame unless the frame says it doesn't), lifts and places the block as Home does, and writes through the
 * controller's commit callbacks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeDragTimelinePinTest {

    private val specs = HomeEditSpecs.create(MotionScheme.expressive(), reduced = false)
    private val golden = HomeGoldenPin.load("home-drag.json")

    /** The timelines' row model, scrolled as the engine asks. */
    private class ModelHost(private val v: JsonObject, var shown: DragArrangement) : HomeDragHost {
        private val twoColumn = v.bool("twoColumn")
        private val order = v.strings("order").map(::section)
        private val pairs = if (twoColumn) HomeGoldenPin.pairMap(v.pairs("pairs")).filter { (l, r) -> l in order && r in order } else emptyMap()
        val scrolls = mutableListOf<Pair<Float, Float>>()
        var scrolled = 0f

        override fun order(): List<HomeSection> = order

        override fun twoColumn(): Boolean = twoColumn

        override fun pairs(): Map<HomeSection, HomeSection> = pairs

        override fun visibleRows(): List<DragItemBand> {
            var y = v.float("top") - scrolled
            val height = v.float("rowHeight")
            return dragRows(shown.ids, shown.pairs, twoColumn).map { row ->
                DragItemBand(row.first, y, y + height).also { y += height + v.float("gap") }
            }
        }

        override fun contentLeft(): Float = 0f

        override fun contentWidth(): Float = v.float("contentWidth")

        override fun columnGap(): Float = v.float("columnGap")

        override fun scrollArea(): HomeEditSafeArea = HomeEditSafeArea(v.float("areaTop"), v.float("areaBottom"))

        override fun scrollBy(px: Float): Float {
            val consumed = px.coerceIn(-scrolled, v.float("scrollMax") - scrolled)
            scrolled += consumed
            scrolls += px to consumed
            return consumed
        }
    }

    @Test
    fun should_dragEveryTimelineAsPinned_throughTheEngine() = runEditClockTest { scope ->
        val timelines = with(HomeGoldenPin) { golden.section("timelines") }
        timelines.forEach { v ->
            val order = v.strings("order").map(::section)
            val twoColumn = v.bool("twoColumn")
            val before = DragArrangement(order, if (twoColumn) HomeGoldenPin.pairMap(v.pairs("pairs")).filter { (l, r) -> l in order && r in order } else emptyMap())
            val host = ModelHost(v, before)
            val writes = mutableListOf<DragArrangement>()
            val motion = HomeEditMotion(
                scope = scope,
                specs = specs,
                style = HomeWiggleStyle(),
                reduced = { false },
                progress = { 1f },
                editing = { true },
                feedback = HomeEditFeedback.None,
                uptimeMs = { testScheduler.currentTime },
            )
            val engine = HomeDragEngine(
                scope = scope,
                specs = specs,
                motion = motion,
                feedback = HomeEditFeedback.None,
                density = Density(v.float("density")),
                commitOrder = {
                    writes += DragArrangement(it, emptyMap())
                    true
                },
                finishDeferredExit = {},
                uptimeMs = { testScheduler.currentTime },
                commitArrangement = { ids, pairs ->
                    writes += DragArrangement(ids, pairs)
                    true
                },
            ).also { it.host = host }
            val dragged = section(v.string("dragged")!!)
            val finger = v["finger"]!!.jsonArray.map { (it as JsonPrimitive).content.toFloat() }

            engine.liftBlock(dragged, Offset.Zero)
            engine.fingerX = finger[0]
            engine.start(fingerY = finger[1], uptimeMs = 0L)
            assertEquals(v.name, dragged, engine.dragging)
            place(engine, dragged)

            val frames = v["frames"]!!.jsonArray.map { it.jsonObject }
            val decisions = v["decisions"]!!.jsonArray.map { it.jsonObject }
            frames.forEachIndexed { i, f ->
                (f["move"] as? JsonArray)?.let { m ->
                    engine.fingerX = m.float(0)
                    engine.move(m.float(1), 0L)
                }
                val arrangement = engine.arrangement
                val scrolls = host.scrolls.size
                frame()
                val d = decisions[i]
                val label = "${v.name} frame $i"
                when (d.string("kind")) {
                    "None" -> {
                        assertEquals(label, arrangement, engine.arrangement)
                        assertEquals(label, scrolls, host.scrolls.size)
                    }
                    "Step" -> {
                        assertEquals(label, d["arrangement"], arrangementJson(engine.arrangement!!))
                        assertEquals(label, scrolls, host.scrolls.size)
                    }
                    "Scroll" -> {
                        assertEquals(label, arrangement, engine.arrangement)
                        assertEquals(label, scrolls + 1, host.scrolls.size)
                        val (px, consumed) = host.scrolls.last()
                        close(label, d["px"], num(px))
                        close(label, d["consumed"], num(consumed))
                    }
                }
                if ((f["laidOut"] as? JsonPrimitive)?.booleanOrNull != false) {
                    host.shown = engine.arrangement ?: host.shown
                    place(engine, dragged)
                }
            }

            val end = v.obj("end")
            when (end.string("kind")) {
                "release" -> {
                    (end["move"] as? JsonArray)?.let { m ->
                        engine.fingerX = m.float(0)
                        engine.move(m.float(1), 0L)
                    }
                    engine.release(cancelled = end.bool("cancelled"), uptimeMs = 0L)
                }
                "exit" -> engine.deferExit(HomeEditExitReason.valueOf(end.string("reason")!!))
                "cancel" -> engine.cancel()
            }
            val result = v.obj("result")
            val expected = result["write"]
            assertEquals(v.name, if (expected is JsonNull) emptyList<JsonElement>() else listOf(expected), writes.map(::arrangementJson))
            close(v.name, result["scrolled"], num(host.scrolled))
            // Let the glide and the lift settle before the next timeline.
            repeat(60) { frame() }
            engine.abortNow()
        }
    }

    private fun TestScope.frame() {
        Snapshot.sendApplyNotifications()
        advanceTimeBy(16)
        runCurrent()
    }

    // Home places the dragged block in the layout's slot each frame (dragFollow): the engine reads where it was laid out.
    private fun place(engine: HomeDragEngine, dragged: HomeSection) {
        engine.dragOffset(dragged, inList = false, width = Float.NaN, placedAt = Offset.Zero)
    }

    private fun JsonArray.float(i: Int): Float = (this[i] as JsonPrimitive).let { if (it.content == "NaN") Float.NaN else it.content.toFloat() }

    private fun close(name: String, expected: JsonElement?, actual: JsonElement) {
        assertTrue("$name: expected $expected but was $actual", HomeGoldenPin.close(expected, actual))
    }

    private fun arrangementJson(a: DragArrangement): JsonObject = JsonObject(
        linkedMapOf(
            "ids" to JsonArray(a.ids.map { JsonPrimitive(it.id) }),
            "pairs" to JsonArray(a.pairs.map { (l, r) -> JsonArray(listOf(JsonPrimitive(l.id), JsonPrimitive(r.id))) }),
        ),
    )
}
