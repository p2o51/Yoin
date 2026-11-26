package com.gpo.yoin.shared.home

import com.gpo.yoin.shared.home.HomeGoldenJson.bool
import com.gpo.yoin.shared.home.HomeGoldenJson.float
import com.gpo.yoin.shared.home.HomeGoldenJson.num
import com.gpo.yoin.shared.home.HomeGoldenJson.obj
import com.gpo.yoin.shared.home.HomeGoldenJson.str
import com.gpo.yoin.shared.home.HomeGoldenJson.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The golden vectors of Home edit's gesture machine (shared/src/commonTest/resources/golden/home-edit-gestures.json):
 * `machine` feeds [HomeEditGestureMachine] one finger's events (and its timers, `"kind": "next"` firing the one
 * pending) and records each answer, the state after it and the next timer; `timeline` drives
 * [HomeEditGestureTimeline] (samples at their own stamps, the clock moved on, a wait timing out, the grace a late
 * wake gives) and records every action stepped. Written by `HomeEditGesturesGoldenTest` (androidHostTest),
 * replayed on the JVM and the iOS simulator; Android's app pins it (app HomeEditGesturesPinTest).
 */
internal object HomeEditGesturesGolden {

    const val FILE = "shared/src/commonTest/resources/golden/home-edit-gestures.json"

    val sections: List<String> = listOf("machine", "timeline")

    fun outputs(section: String, v: JsonObject): JsonObject = when (section) {
        "machine" -> {
            val machine = HomeEditGestureMachine(v.getValue("longPressMs").jsonPrimitive.long, v.float("slopPx"))
            obj(
                "steps" to JsonArray(
                    v.getValue("events").jsonArray.map { e ->
                        val actions = machine.handle(eventOf(e.jsonObject, machine))
                        obj("actions" to actionsJson(actions), "state" to str(machine.state.name), "pending" to timerJson(machine.pendingTimer))
                    },
                ),
            )
        }
        "timeline" -> {
            val machine = HomeEditGestureMachine(v.getValue("longPressMs").jsonPrimitive.long, v.float("slopPx"))
            val flinging = v["flinging"]?.jsonPrimitive?.booleanOrNull == true
            val stepped = ArrayList<HomeEditGestureAction>()
            val timeline = HomeEditGestureTimeline(machine, { flinging }) { event -> machine.handle(event).also { stepped += it } }
            timeline.start(v.getValue("start").jsonPrimitive.long)
            obj(
                "results" to JsonArray(
                    v.getValue("ops").jsonArray.map { e ->
                        val o = e.jsonObject
                        stepped.clear()
                        var grace: JsonElement? = null
                        when (o.string("op")) {
                            "step" -> machine.handle(eventOf(o.getValue("event").jsonObject, machine)).also { stepped += it }
                            "sample" -> timeline.sample(eventOf(o.getValue("event").jsonObject, machine))
                            "advance" -> timeline.advanceTo(o.getValue("t").jsonPrimitive.long)
                            "timeout" -> timeline.timeout()
                            "grace" -> grace = JsonPrimitive(timeline.graceAfter(o.getValue("wall").jsonPrimitive.long))
                            else -> error("unknown op ${o.string("op")}")
                        }
                        val out = linkedMapOf<String, JsonElement>(
                            "actions" to actionsJson(stepped.toList()),
                            "state" to str(machine.state.name),
                            "now" to JsonPrimitive(timeline.now),
                            "untilDue" to (timeline.untilDue()?.let(::JsonPrimitive) ?: JsonNull),
                        )
                        grace?.let { out["grace"] = it }
                        JsonObject(out)
                    },
                ),
            )
        }
        else -> error("unknown section $section")
    }

    fun render(): String {
        val header = listOf("about" to str(ABOUT), "constants" to constants())
        val body = sections.map { section ->
            section to HomeEditGesturesGoldenFixtures.inputs.getValue(section).map { HomeGoldenJson.vector(it, outputs(section, it)) }
        }
        return HomeGoldenJson.render(header, body)
    }

    fun replay(text: String): List<String> {
        val root = Json.parseToJsonElement(text).jsonObject
        val mismatches = mutableListOf<String>()
        if (!HomeGoldenJson.close(root["constants"], constants())) mismatches += "constants: expected ${root["constants"]} but was ${constants()}"
        return mismatches + HomeGoldenJson.replay(root, sections, exact = false, outputs = ::outputs)
    }

    fun constants(): JsonObject = obj(
        "bodyHoldMinMs" to JsonPrimitive(HomeEditGestureTokens.BodyHoldMinMs),
        "kickTap" to num(HomeEditGestureTokens.KickTap),
        "lateTimerMs" to JsonPrimitive(LateTimerMs),
        "stalledInputGraceMs" to JsonPrimitive(StalledInputGraceMs),
    )

    const val ABOUT = "Golden vectors for Home edit's gesture machine (com.gpo.yoin.shared.home, parity p4; Android PR " +
        "#292): normal mode watches until the long-press threshold (T) and charges at T/2 on a block; edit mode lifts " +
        "after the body hold (max(150ms, T/2)), a handle lifts and carries on its first slop, a tap kicks, a blank tap " +
        "exits; a press that stops a fling never lifts; moves stamped before a hold that arrive after it take the lift " +
        "back; a second finger before any lift pinches; a mouse's secondary click enters edit mode. Generated from " +
        ":shared Kotlin by HomeEditGesturesGoldenTest (androidHostTest); do not edit by hand. Times are pointer-event " +
        "uptimes (ms), positions px. machine: one answer per event ({actions, state, pending timer}); a timer event " +
        "with kind \"next\" fires the pending one. timeline: one result per op ({actions stepped, state, now, " +
        "untilDue, grace}). An action is {a: its name, then its fields}; points are [x, y]."

    // ── events and actions ⇄ JSON ────────────────────────────────────

    private fun point(e: JsonElement?): HomePoint = e!!.jsonArray.let { HomePoint(float(it[0]), float(it[1])) }

    private fun pointJson(p: HomePoint) = JsonArray(listOf(num(p.x), num(p.y)))

    private fun hitOf(o: JsonObject): HomeEditHit = when (o.string("kind")) {
        "Excluded" -> HomeEditHit.Excluded
        "Handle" -> HomeEditHit.Handle(HomeDragGolden.section(o.string("section")), point(o["local"]), sizeOf(o["size"]))
        "Block" -> HomeEditHit.Block(HomeDragGolden.section(o.string("section")), point(o["local"]), sizeOf(o["size"]))
        "Blank" -> HomeEditHit.Blank(o.string("nearest")?.let(HomeDragGolden::section))
        else -> error("unknown hit ${o.string("kind")}")
    }

    private fun sizeOf(e: JsonElement?): HomeSize = e?.jsonArray?.let { HomeSize(float(it[0]), float(it[1])) } ?: HomeSize.Zero

    fun eventOf(o: JsonObject, machine: HomeEditGestureMachine): HomeEditGestureEvent {
        fun t() = o.getValue("t").jsonPrimitive.long
        return when (o.string("e")) {
            "down" -> HomeEditGestureEvent.Down(
                hit = hitOf(o.getValue("hit").jsonObject),
                position = point(o["at"]),
                editing = o["editing"]?.jsonPrimitive?.booleanOrNull == true,
                flingInProgress = o["fling"]?.jsonPrimitive?.booleanOrNull == true,
                t = t(),
                secondaryClick = o["secondary"]?.jsonPrimitive?.booleanOrNull == true,
            )
            "move" -> HomeEditGestureEvent.Move(point(o["at"]), t())
            "up" -> HomeEditGestureEvent.Up(t())
            "cancel" -> HomeEditGestureEvent.Cancel(t())
            "timer" -> {
                val kind = o.string("kind")
                HomeEditGestureEvent.Timer(
                    kind = if (kind == "next") machine.pendingTimer!!.kind else HomeEditTimer.valueOf(kind!!),
                    flinging = o["flinging"]?.jsonPrimitive?.booleanOrNull == true,
                )
            }
            "editEnded" -> HomeEditGestureEvent.EditEnded
            "carryEnded" -> HomeEditGestureEvent.CarryEnded
            "other" -> HomeEditGestureEvent.OtherPointer
            "pinchDown" -> HomeEditGestureEvent.PinchDown(o.float("span"))
            "pinchMove" -> HomeEditGestureEvent.PinchMove(o.float("span"))
            "pinchUp" -> HomeEditGestureEvent.PinchUp
            else -> error("unknown event ${o.string("e")}")
        }
    }

    fun actionsJson(actions: List<HomeEditGestureAction>): JsonArray = JsonArray(actions.map(::actionJson))

    fun actionJson(a: HomeEditGestureAction): JsonObject = when (a) {
        HomeEditGestureAction.Abandon -> obj("a" to str("Abandon"))
        is HomeEditGestureAction.StartCharge -> obj("a" to str("StartCharge"), "section" to str(a.section.id), "local" to pointJson(a.local))
        HomeEditGestureAction.ReleaseCharge -> obj("a" to str("ReleaseCharge"))
        HomeEditGestureAction.LongPressHaptic -> obj("a" to str("LongPressHaptic"))
        HomeEditGestureAction.DragStartHaptic -> obj("a" to str("DragStartHaptic"))
        is HomeEditGestureAction.Enter -> obj("a" to str("Enter"), "origin" to str(a.origin?.id), "lifted" to bool(a.lifted))
        is HomeEditGestureAction.LatchPlate -> obj(
            "a" to str("LatchPlate"),
            "section" to str(a.section.id),
            "local" to pointJson(a.local),
            "size" to JsonArray(listOf(num(a.size.width), num(a.size.height))),
        )
        is HomeEditGestureAction.Lift -> obj("a" to str("Lift"), "section" to str(a.section.id), "local" to pointJson(a.local))
        is HomeEditGestureAction.DropLift -> obj("a" to str("DropLift"), "kick" to num(a.kick))
        HomeEditGestureAction.OpenPlaceholders -> obj("a" to str("OpenPlaceholders"))
        is HomeEditGestureAction.StartCarry -> obj("a" to str("StartCarry"), "y" to num(a.y), "t" to JsonPrimitive(a.t))
        is HomeEditGestureAction.CarryMove -> obj("a" to str("CarryMove"), "y" to num(a.y), "t" to JsonPrimitive(a.t))
        is HomeEditGestureAction.CarryRelease -> obj("a" to str("CarryRelease"), "cancelled" to bool(a.cancelled), "t" to JsonPrimitive(a.t))
        HomeEditGestureAction.PinchStart -> obj("a" to str("PinchStart"))
        is HomeEditGestureAction.PinchBy -> obj("a" to str("PinchBy"), "scale" to num(a.scale))
        HomeEditGestureAction.PinchEnd -> obj("a" to str("PinchEnd"))
        is HomeEditGestureAction.BlockTap -> obj("a" to str("BlockTap"), "section" to str(a.section.id), "local" to (a.local?.let(::pointJson) ?: JsonNull))
        HomeEditGestureAction.ExitBlank -> obj("a" to str("ExitBlank"))
        HomeEditGestureAction.Touch -> obj("a" to str("Touch"))
        is HomeEditGestureAction.PointerDown -> obj("a" to str("PointerDown"), "down" to bool(a.down))
        HomeEditGestureAction.ConsumeRest -> obj("a" to str("ConsumeRest"))
        HomeEditGestureAction.ConsumeOther -> obj("a" to str("ConsumeOther"))
    }

    private fun timerJson(t: HomeEditTimerAt?): JsonElement = t?.let { obj("kind" to str(it.kind.name), "atMs" to JsonPrimitive(it.atMs)) } ?: JsonNull
}

/** The inputs of every home-edit-gestures golden vector, from HomeEditGestureMachineTest and HomeEditGestureTimelineTest. */
internal object HomeEditGesturesGoldenFixtures {

    private fun n(v: Float) = num(v)

    private fun at(x: Float, y: Float) = JsonArray(listOf(n(x), n(y)))

    private val block = obj("kind" to str("Block"), "section" to str("jump_back_in"), "local" to at(40f, 60f), "size" to at(328f, 420f))
    private val handle = obj("kind" to str("Handle"), "section" to str("jump_back_in"), "local" to at(300f, 20f), "size" to at(328f, 420f))
    private val blank = obj("kind" to str("Blank"), "nearest" to str("activities"))
    private val blankNone = obj("kind" to str("Blank"), "nearest" to JsonNull)
    private val excluded = obj("kind" to str("Excluded"))

    private fun down(hit: JsonObject, editing: Boolean = false, fling: Boolean = false, t: Long = 0L, secondary: Boolean = false) = obj(
        "e" to str("down"),
        "hit" to hit,
        "at" to at(100f, 300f),
        "editing" to bool(editing),
        "fling" to bool(fling),
        "t" to JsonPrimitive(t),
        "secondary" to bool(secondary),
    )

    private fun move(dx: Float, dy: Float, t: Long = 0L) = obj("e" to str("move"), "at" to at(100f + dx, 300f + dy), "t" to JsonPrimitive(t))

    private fun up(t: Long = 0L) = obj("e" to str("up"), "t" to JsonPrimitive(t))

    private fun cancel(t: Long = 0L) = obj("e" to str("cancel"), "t" to JsonPrimitive(t))

    private fun timer(kind: String = "next", flinging: Boolean = false) = obj("e" to str("timer"), "kind" to str(kind), "flinging" to bool(flinging))

    private fun simple(e: String) = obj("e" to str(e))

    private fun pinch(e: String, span: Float) = obj("e" to str(e), "span" to n(span))

    private fun machine(name: String, vararg events: JsonObject, longPressMs: Long = 400L) = JsonObject(
        linkedMapOf(
            "name" to str(name),
            "longPressMs" to JsonPrimitive(longPressMs),
            "slopPx" to n(8f),
            "events" to JsonArray(events.toList()),
        ),
    )

    private val machineInputs = listOf(
        machine("normal: past the slop before the threshold abandons", down(block), move(9f, 0f)),
        machine("normal: within the slop keeps watching", down(block), move(5f, 5f), move(-5f, 4f)),
        machine("normal: up before the threshold lets the click through", down(block), up()),
        machine("normal: charge at T/2 on a block", down(block), timer(), up()),
        machine("normal: no charge on blank", down(blank), timer(), up()),
        machine("normal: the threshold sequence on a block", down(block), timer(), timer(), move(3f, 3f), up()),
        machine("normal: the threshold on blank enters without a lift", down(blank), timer(), up()),
        machine("normal: lifted then released drops with a half kick", down(block), timer(), timer(), up(500L)),
        machine("normal: lifted then moved past the slop from the down starts the carry", down(block), timer(), timer(), move(0f, 9f, 450L), move(0f, 40f, 460L), up(470L)),
        machine("normal: a press that stops a fling is the scroll's", down(block, fling = true), up()),
        machine("normal: the feed starts flinging as the threshold fires", down(block), timer(), timer(flinging = true), up()),
        machine("normal: mouse secondary press enters without a lift", down(block, secondary = true), up()),
        machine("normal: mouse secondary on blank enters at the nearest", down(blank, secondary = true), up()),
        machine("normal: an excluded press is ignored", down(excluded), up()),
        machine("normal: edit ended outside edit mode keeps the press", down(block), simple("editEnded"), timer()),
        machine("edit: body hold lifts", down(block, editing = true), timer(), up(300L)),
        machine("edit: body hold with T = 200 is 150ms", down(block, editing = true), timer(), longPressMs = 200L),
        machine("edit: body hold with T = 600 is 300ms", down(block, editing = true), timer(), longPressMs = 600L),
        machine("edit: a handle lifts and carries on its first slop", down(handle, editing = true), move(0f, 5f), move(0f, 12f, 30L), move(0f, 60f, 40L), up(50L)),
        machine("edit: a block tap kicks and pulses, never exits", down(block, editing = true), up(100L)),
        machine("edit: a handle tap kicks the block", down(handle, editing = true), up(100L)),
        machine("edit: a blank tap exits", down(blank, editing = true), up(100L)),
        machine("edit: a blank tap that stops a fling doesn't exit", down(blank, editing = true, fling = true), up(100L)),
        machine("edit: blank held past T doesn't exit", down(blank, editing = true), timer(), up(500L)),
        machine("edit: a press moved past the slop passes the scroll through", down(block, editing = true), move(0f, 20f), up(100L)),
        machine("edit: an excluded press only touches", down(excluded, editing = true), up()),
        machine("edit: a press that stops a fling never lifts nor kicks", down(block, editing = true, fling = true), up(100L)),
        machine("edit: the feed flings as the hold fires", down(block, editing = true), timer(flinging = true), up(300L)),
        machine("edit: secondary click does nothing", down(block, editing = true, secondary = true), up()),
        machine("edit: a second finger before the lift pinches", down(block, editing = true), pinch("pinchDown", 200f), pinch("pinchMove", 150f), pinch("pinchMove", 90f), pinch("pinchUp", 0f), up(300L)),
        machine("edit: pinch from a scroll", down(block, editing = true), move(0f, 30f), pinch("pinchDown", 300f), pinch("pinchMove", 600f), up(200L)),
        machine("edit: pinch from blank", down(blankNone, editing = true), pinch("pinchDown", 100f), cancel(50L)),
        machine("edit: a second finger once lifted is consumed, not a pinch", down(block, editing = true), timer(), pinch("pinchDown", 200f), simple("other"), up(300L)),
        machine("normal: a second finger is ignored", down(block), pinch("pinchDown", 200f), simple("other")),
        machine("edit: a zero span never pinches", down(block, editing = true), pinch("pinchDown", 0f)),
        machine("edit: a carry cancelled releases cancelled", down(handle, editing = true), move(0f, 20f, 10L), cancel(20L)),
        machine("edit: lifted then cancelled drops and opens placeholders", down(block, editing = true), timer(), cancel(250L)),
        machine("edit: edit ends mid hold", down(block, editing = true), simple("editEnded"), up(100L)),
        machine("edit: the carry ended from outside swallows the rest", down(handle, editing = true), move(0f, 20f, 10L), simple("carryEnded"), move(0f, 50f, 20L), up(30L)),
        machine("edit: moves stamped before the hold that arrive after it take the lift back", down(block, editing = true, t = 1000L), timer(), move(0f, -30f, 1100L), move(0f, -60f, 1150L), up(1300L)),
        machine("edit: still past the hold, then moved, carries", down(block, editing = true, t = 1000L), move(1f, 1f, 1100L), timer(), move(0f, -40f, 1260L), move(0f, -80f, 1268L), up(1300L)),
        machine("a second down while busy is ignored", down(block), down(blank)),
    )

    private fun sample(event: JsonObject) = obj("op" to str("sample"), "event" to event)

    private fun step(event: JsonObject) = obj("op" to str("step"), "event" to event)

    private fun advance(t: Long) = obj("op" to str("advance"), "t" to JsonPrimitive(t))

    private val timeout = obj("op" to str("timeout"))

    private fun grace(wall: Long) = obj("op" to str("grace"), "wall" to JsonPrimitive(wall))

    private fun flingMoves(fromMs: Long, untilMs: Long): List<JsonObject> =
        (fromMs..untilMs step 8L).map { t -> sample(move(0f, -(t - fromMs) * 1000f / 150f, t)) }

    private fun timeline(name: String, ops: List<JsonObject>, flinging: Boolean = false) = JsonObject(
        linkedMapOf(
            "name" to str(name),
            "longPressMs" to JsonPrimitive(400L),
            "slopPx" to n(8f),
            "start" to JsonPrimitive(0L),
            "flinging" to bool(flinging),
            "ops" to JsonArray(ops),
        ),
    )

    private val timelineInputs = listOf(
        timeline(
            "fling moves stamped before the hold, after a late wake: no lift",
            listOf(step(down(block, editing = true)), grace(260L)) + flingMoves(8L, 260L) + listOf(sample(up(270L))),
        ),
        timeline(
            "the hold fired, then moves stamped before it arrive: the lift is taken back",
            listOf(step(down(block, editing = true)), timeout) + flingMoves(8L, 120L) + listOf(sample(up(300L))),
        ),
        timeline(
            "held still past the hold in one batch, then moved: lifts then carries",
            listOf(
                step(down(block, editing = true)),
                sample(move(2f, 1f, 90L)),
                sample(move(3f, 2f, 180L)),
                sample(move(3f, -40f, 260L)),
                sample(move(3f, -80f, 268L)),
                sample(up(300L)),
            ),
        ),
        timeline(
            "a batch straddles the timer: it fires at its instant",
            listOf(step(down(block, editing = true)), sample(move(0f, 2f, 150L)), sample(move(0f, -30f, 200L)), sample(up(260L))),
        ),
        timeline("a wake on time gives no grace", listOf(step(down(block, editing = true)), grace(205L), grace(208L), grace(209L), timeout, sample(up(400L)))),
        timeline("flinging as the hold fires: no lift", listOf(step(down(block, editing = true)), timeout, sample(up(300L))), flinging = true),
        timeline("advance fires due timers in order", listOf(step(down(block)), advance(150L), advance(200L), advance(399L), advance(400L), sample(up(500L)))),
        timeline("a sample at the same stamp as the timer goes after it", listOf(step(down(block, editing = true)), sample(move(20f, 0f, 200L)), sample(up(250L)))),
    )

    val inputs: Map<String, List<JsonObject>> = mapOf("machine" to machineInputs, "timeline" to timelineInputs)
}
