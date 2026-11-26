package com.gpo.yoin.shared.home

import com.gpo.yoin.shared.home.HomeGoldenJson.bool
import com.gpo.yoin.shared.home.HomeGoldenJson.float
import com.gpo.yoin.shared.home.HomeGoldenJson.num
import com.gpo.yoin.shared.home.HomeGoldenJson.obj
import com.gpo.yoin.shared.home.HomeGoldenJson.pairs
import com.gpo.yoin.shared.home.HomeGoldenJson.str
import com.gpo.yoin.shared.home.HomeGoldenJson.string
import com.gpo.yoin.shared.home.HomeGoldenJson.strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Drag timelines through [HomeDragCore] (home-drag.json `timelines`), on a fixed layout model a port rebuilds
 * exactly: rows [rowHeight] px tall, [gap] apart, the first at y = [top] minus the scroll so far; a pair is one
 * row; the content runs from x = 0 across [contentWidth], a right half starting at (W − columnGap) / 2 +
 * columnGap. The list shows the arrangement it last laid out: the one before the lift at the start (the block
 * placed in it), then after every frame the live one (the block placed again, [HomeDragCore.placed]) unless the
 * frame says `"laidOut": false`. A scroll consumes what it asks, held between 0 and `scrollMax`.
 *
 * Per frame: the finger moves first when the frame has `"move": [x, y]` (a NaN x keeps the last x), then
 * [HomeDragCore.frame] runs on the layout shown with the frame's `dt` (seconds). Then `end`: `release`
 * (`cancelled`, optional last `move`), `exit` (`reason`) or `cancel`.
 */
internal object HomeDragTimelines {

    fun run(v: JsonObject): JsonObject {
        val density = v.float("density")
        val twoColumn = v.getValue("twoColumn").jsonPrimitive.booleanOrNull == true
        val order = HomeDragGolden.sections(v, "order")
        val pairs = if (twoColumn) HomeDragGolden.pairsOf(v, "pairs").filter { (l, r) -> l in order && r in order } else emptyMap()
        val rowHeight = v.float("rowHeight")
        val gap = v.float("gap")
        val top = v.float("top")
        val width = v.float("contentWidth")
        val columnGap = v.float("columnGap")
        val areaTop = v.float("areaTop")
        val areaBottom = v.float("areaBottom")
        val scrollMax = v.float("scrollMax")
        val dragged = HomeDragGolden.section(v.string("dragged"))
        val finger = v.getValue("finger").jsonArray.map { float(it) }

        val core = HomeDragCore(density)
        var scrolled = 0f
        fun bands(a: DragArrangement): List<DragItemBand> {
            var y = top - scrolled
            return dragRows(a.ids, a.pairs, twoColumn).map { row -> DragItemBand(row.first, y, y + rowHeight).also { y += rowHeight + gap } }
        }
        fun view(a: DragArrangement) = HomeDragView(bands(a), 0f, width, areaTop, areaBottom, twoColumn)

        val before = DragArrangement(order, pairs)
        val rows = dragRows(before.ids, before.pairs, twoColumn)
        val band = bands(before).first { it.key == rows.first { row -> dragged in row.sections() }.first }
        val left = if (dragLaneOf(dragged, rows) == HomeDragLane.Right) (width - columnGap) / 2f + columnGap else 0f
        core.start(dragged, before, twoColumn, finger[0], finger[1], left, band.top, areaTop, areaBottom)
        var shown = before
        core.placed()
        val started = obj(
            "side" to str(core.side?.name),
            "grab" to JsonArray(listOf(num(core.grabX), num(core.grabY))),
        )

        val decisions = v.getValue("frames").jsonArray.map { e ->
            val f = e.jsonObject
            (f["move"] as? JsonArray)?.let { m -> core.move(float(m[0]), float(m[1])) }
            val out = when (val r = core.frame(f.float("dt"), view(shown))) {
                HomeDragFrame.None -> obj("kind" to str("None"))
                is HomeDragFrame.Step -> obj("kind" to str("Step"), "arrangement" to HomeDragGolden.arrangementJson(r.arrangement), "side" to str(r.side?.name))
                is HomeDragFrame.Scroll -> {
                    val consumed = r.px.coerceIn(-scrolled, scrollMax - scrolled)
                    scrolled += consumed
                    core.scrolled(consumed)
                    obj("kind" to str("Scroll"), "px" to num(r.px), "consumed" to num(consumed))
                }
            }
            if ((f["laidOut"] as? JsonPrimitive)?.booleanOrNull != false) {
                shown = core.arrangement ?: shown
                core.placed()
            }
            out
        }

        val end = v.getValue("end").jsonObject
        val write = when (end.string("kind")) {
            "release" -> {
                (end["move"] as? JsonArray)?.let { m -> core.move(float(m[0]), float(m[1])) }
                core.release(end.getValue("cancelled").jsonPrimitive.booleanOrNull == true, view(shown))
            }
            "exit" -> core.exit(HomeEditExitReason.valueOf(end.string("reason")!!))
            "cancel" -> null
            else -> error("unknown end ${end.string("kind")}")
        }
        val result = obj(
            "write" to (write?.let(HomeDragGolden::arrangementJson) ?: JsonNull),
            "arrangement" to (core.arrangement?.let(HomeDragGolden::arrangementJson) ?: JsonNull),
            "side" to str(core.side?.name),
            "scrolled" to num(scrolled),
        )
        core.end()
        return obj("started" to started, "decisions" to JsonArray(decisions), "result" to result)
    }

    // ── fixtures (HomeDragEngineTest's) ─────────────────────────────

    private fun n(value: Float) = num(value)

    private fun frame(dt: Float, x: Float? = null, y: Float? = null, laidOut: Boolean = true): JsonObject {
        val o = LinkedHashMap<String, JsonElement>()
        o["dt"] = n(dt)
        if (y != null) o["move"] = JsonArray(listOf(n(x ?: Float.NaN), n(y)))
        if (!laidOut) o["laidOut"] = bool(false)
        return JsonObject(o)
    }

    /** The engine's frames: the first has dt 0, then one every 16ms. */
    private fun frames(vararg moves: Pair<Float, Float>?, first: Boolean = true, count: Int = 0): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        moves.forEachIndexed { i, m -> out += frame(if (first && i == 0) 0f else .016f, m?.first, m?.second) }
        repeat(count) { out += frame(.016f) }
        return out
    }

    private fun timeline(
        name: String,
        twoColumn: Boolean,
        order: List<String>,
        dragged: String,
        finger: Pair<Float, Float>,
        frames: List<JsonObject>,
        end: JsonObject,
        pairs: List<List<String>> = emptyList(),
        density: Float = 2f,
        scrollMax: Float = 100_000f,
    ): JsonObject = JsonObject(
        linkedMapOf(
            "name" to str(name),
            "density" to n(density),
            "twoColumn" to bool(twoColumn),
            "order" to strings(order),
            "pairs" to pairs(pairs),
            "rowHeight" to n(300f),
            "gap" to n(18f),
            "top" to n(100f),
            "contentWidth" to n(1000f),
            "columnGap" to n(20f),
            "areaTop" to n(100f),
            "areaBottom" to n(2000f),
            "scrollMax" to n(scrollMax),
            "dragged" to str(dragged),
            "finger" to JsonArray(listOf(n(finger.first), n(finger.second))),
            "frames" to JsonArray(frames),
            "end" to end,
        ),
    )

    private fun release(cancelled: Boolean = false, move: Pair<Float, Float>? = null): JsonObject {
        val o = LinkedHashMap<String, JsonElement>()
        o["kind"] = str("release")
        o["cancelled"] = bool(cancelled)
        if (move != null) o["move"] = JsonArray(listOf(n(move.first), n(move.second)))
        return JsonObject(o)
    }

    private fun exit(reason: HomeEditExitReason) = obj("kind" to str("exit"), "reason" to str(reason.name))

    private val cancel = obj("kind" to str("cancel"))

    private val eight = listOf(
        "activities", "jump_back_in", "memory_grid", "made_for_you", "lyric_highlight", "recently_added", "recently_played", "your_playlists",
    )

    val inputs: List<JsonObject> = listOf(
        // Activities 100..400, Made For You 418..718, All Memories 736..1036, Recently Added below.
        timeline(
            "one column: makes way past its neighbour, commits once",
            false,
            listOf("activities", "made_for_you", "memory_grid", "recently_added"),
            "made_for_you",
            500f to 568f,
            frames(500f to 768f, null, null),
            release(),
        ),
        timeline("one column: dropped where it started", false, listOf("activities", "made_for_you", "memory_grid"), "made_for_you", 500f to 568f, frames(500f to 600f, null, null), release()),
        timeline(
            "one column: the 12dp travel gate",
            false,
            listOf("activities", "made_for_you", "memory_grid"),
            "made_for_you",
            500f to 568f,
            frames(500f to 548f, null, null, 500f to 300f, null, null),
            release(),
        ),
        timeline(
            "one column: never pairs",
            false,
            listOf("activities", "made_for_you", "memory_grid"),
            "memory_grid",
            500f to 886f,
            frames(990f to 568f, null, null),
            release(),
            pairs = listOf(listOf("activities", "made_for_you")),
        ),
        timeline(
            "two columns: pairs with a full row at its edge",
            true,
            listOf("activities", "made_for_you", "memory_grid"),
            "memory_grid",
            500f to 886f,
            frames(950f to 568f, null, null),
            release(),
        ),
        timeline(
            "two columns: swaps at the middle, stays beside its partner until out of the row",
            true,
            listOf("activities", "jump_back_in", "memory_grid"),
            "jump_back_in",
            700f to 250f,
            frames(460f to 300f, null, null, 400f to 300f, null, null, 500f to 300f, null, null, 500f to 428f, null, null, 500f to 440f, null, null),
            release(),
            pairs = listOf(listOf("activities", "jump_back_in")),
        ),
        timeline(
            "two columns: no pair while the edge scroll carries rows past",
            true,
            eight,
            "activities",
            950f to 250f,
            frames(950f to 1950f, count = 40) + frames(950f to 1100f, null, null, first = false) + frames(990f to 1150f, null, null, first = false),
            release(),
        ),
        timeline(
            "two columns: pairs where the finger let go, before a frame decided",
            true,
            listOf("jump_back_in", "activities", "memory_grid"),
            "activities",
            900f to 568f,
            frames(900f to 450f, null, null),
            release(move = 900f to 250f),
        ),
        timeline(
            "two columns: decides from the laid-out layout when the last change isn't laid out",
            true,
            listOf("jump_back_in", "activities", "memory_grid"),
            "activities",
            900f to 700f,
            listOf(frame(0f, 900f, 530f, laidOut = false), frame(.016f, laidOut = false), frame(.016f, laidOut = false)),
            release(move = 900f to 250f),
        ),
        timeline(
            "lifted in the bottom band: the start guard holds until 180ms rest",
            false,
            eight,
            "recently_added",
            500f to 1950f,
            frames(null, count = 14),
            release(),
        ),
        timeline(
            "lifted in the bottom band: the start guard lets go after an 8dp push",
            false,
            eight,
            "recently_added",
            500f to 1950f,
            frames(null, 500f to 1960f, null, 500f to 1967f, null, null),
            release(),
        ),
        timeline(
            "lifted in the bottom band, moved out and back: scrolls at once",
            false,
            eight,
            "recently_added",
            500f to 1950f,
            frames(null, 500f to 1500f, 500f to 1990f, null, null),
            release(),
        ),
        timeline(
            "top band at the list's start: nothing to scroll",
            false,
            eight,
            "memory_grid",
            500f to 886f,
            frames(500f to 120f, null, null, null),
            release(),
        ),
        timeline(
            "two columns: the edge scroll stops at the list's end; out of the band it pairs",
            true,
            eight,
            "activities",
            950f to 250f,
            frames(950f to 1950f, count = 16) + frames(990f to 1640f, null, null, first = false),
            release(),
            scrollMax = 200f,
        ),
        timeline("cancelled by the system: nothing written", false, listOf("activities", "made_for_you", "memory_grid"), "made_for_you", 500f to 568f, frames(500f to 900f, null, null), release(cancelled = true)),
        timeline("Done mid-drag keeps the drop", false, listOf("activities", "made_for_you", "memory_grid"), "made_for_you", 500f to 568f, frames(500f to 900f, null, null), exit(HomeEditExitReason.Done)),
        timeline("Back mid-drag rolls back", false, listOf("activities", "made_for_you", "memory_grid"), "made_for_you", 500f to 568f, frames(500f to 900f, null, null), exit(HomeEditExitReason.Back)),
        timeline("rotation mid-drag rolls back", true, listOf("activities", "made_for_you", "memory_grid"), "made_for_you", 500f to 568f, frames(500f to 900f, null, null), cancel),
        timeline(
            "density 2.625: a long drag down a phone list",
            false,
            eight,
            "activities",
            500f to 250f,
            frames(500f to 500f, null, 500f to 800f, null, 500f to 1100f, null, 500f to 1400f, null, 500f to 1700f, null),
            release(),
            density = 2.625f,
        ),
    )
}
