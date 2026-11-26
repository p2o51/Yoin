package com.gpo.yoin.ui.home.edit

import androidx.compose.ui.geometry.Offset
import com.gpo.yoin.shared.home.HomeEditGestureAction
import com.gpo.yoin.shared.home.HomeEditGestureEvent
import com.gpo.yoin.shared.home.HomeEditGestureMachine
import com.gpo.yoin.shared.home.HomeEditHit
import com.gpo.yoin.shared.home.HomeEditTimer
import com.gpo.yoin.shared.home.HomeSize
import com.gpo.yoin.ui.home.HomeGoldenPin
import com.gpo.yoin.ui.home.HomeGoldenPin.float
import com.gpo.yoin.ui.home.HomeGoldenPin.name
import com.gpo.yoin.ui.home.HomeGoldenPin.section
import com.gpo.yoin.ui.home.HomeGoldenPin.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Android's detector answers every home-edit-gestures.json `machine` vector: the events built the way Home's
 * pointer modifier builds them (Compose [Offset]s turned into the machine's points, the hit's block-local point
 * and size as the hit tester resolves them), each answered with the actions — names, sections and points as the
 * executor reads them back as Compose values — and the state the file says.
 */
class HomeEditGesturesPinTest {

    private val golden = HomeGoldenPin.load("home-edit-gestures.json")

    @Test
    fun should_answerEveryMachineVectorAsPinned_throughTheDetectorsTypes() {
        with(HomeGoldenPin) { golden.section("machine") }.forEach { v ->
            val machine = HomeEditGestureMachine(v["longPressMs"]!!.jsonPrimitive.long, v.float("slopPx"))
            val steps = v["steps"]!!.jsonArray.map { it.jsonObject }
            v["events"]!!.jsonArray.forEachIndexed { i, e ->
                val actions = machine.handle(eventOf(e.jsonObject, machine))
                val expected = steps[i]
                val label = "${v.name} event $i"
                val names = expected["actions"]!!.jsonArray.map { it.jsonObject.string("a") }
                assertEquals(label, names, actions.map { it::class.simpleName })
                expected["actions"]!!.jsonArray.zip(actions).forEach { (want, got) -> checkFields(label, want.jsonObject, got) }
                assertEquals(label, expected.string("state"), machine.state.name)
            }
        }
    }

    // The fields the executor turns back into Compose values.
    private fun checkFields(label: String, want: JsonObject, got: HomeEditGestureAction) {
        fun offset(key: String): Offset? = (want[key] as? JsonArray)?.let { Offset(it.float(0), it.float(1)) }
        when (got) {
            is HomeEditGestureAction.Lift -> {
                assertEquals(label, want.string("section"), got.section.id)
                assertEquals(label, offset("local"), got.local.toOffset())
            }
            is HomeEditGestureAction.StartCharge -> assertEquals(label, offset("local"), got.local.toOffset())
            is HomeEditGestureAction.LatchPlate -> assertEquals(label, offset("local"), got.local.toOffset())
            is HomeEditGestureAction.BlockTap -> assertEquals(label, offset("local"), got.local?.toOffset())
            is HomeEditGestureAction.Enter -> assertEquals(label, want.string("origin"), got.origin?.id)
            is HomeEditGestureAction.StartCarry -> assertEquals(label, want.float("y"), got.y, 1e-4f)
            is HomeEditGestureAction.CarryMove -> assertEquals(label, want.float("y"), got.y, 1e-4f)
            is HomeEditGestureAction.DropLift -> assertEquals(label, want.float("kick"), got.kick, 1e-4f)
            is HomeEditGestureAction.PinchBy -> assertEquals(label, want.float("scale"), got.scale, 1e-4f)
            else -> Unit
        }
    }

    private fun JsonArray.float(i: Int): Float = (this[i] as JsonPrimitive).content.toFloat()

    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

    private fun eventOf(o: JsonObject, machine: HomeEditGestureMachine): HomeEditGestureEvent {
        fun t() = o["t"]!!.jsonPrimitive.long
        fun at() = o["at"]!!.jsonArray.let { Offset(it.float(0), it.float(1)) }
        return when (o.string("e")) {
            "down" -> HomeEditGestureEvent.Down(
                hit = hitOf(o["hit"]!!.jsonObject),
                position = at().toHomePoint(),
                editing = o.flag("editing"),
                flingInProgress = o.flag("fling"),
                t = t(),
                secondaryClick = o.flag("secondary"),
            )
            "move" -> HomeEditGestureEvent.Move(at().toHomePoint(), t())
            "up" -> HomeEditGestureEvent.Up(t())
            "cancel" -> HomeEditGestureEvent.Cancel(t())
            "timer" -> HomeEditGestureEvent.Timer(
                kind = o.string("kind").let { if (it == "next") machine.pendingTimer!!.kind else HomeEditTimer.valueOf(it!!) },
                flinging = o.flag("flinging"),
            )
            "editEnded" -> HomeEditGestureEvent.EditEnded
            "carryEnded" -> HomeEditGestureEvent.CarryEnded
            "other" -> HomeEditGestureEvent.OtherPointer
            "pinchDown" -> HomeEditGestureEvent.PinchDown(o.float("span"))
            "pinchMove" -> HomeEditGestureEvent.PinchMove(o.float("span"))
            "pinchUp" -> HomeEditGestureEvent.PinchUp
            else -> error("unknown event ${o.string("e")}")
        }
    }

    private fun hitOf(o: JsonObject): HomeEditHit {
        fun local() = o["local"]!!.jsonArray.let { Offset(it.float(0), it.float(1)) }.toHomePoint()
        fun size() = (o["size"] as? JsonArray)?.let { HomeSize(it.float(0), it.float(1)) } ?: HomeSize.Zero
        return when (o.string("kind")) {
            "Excluded" -> HomeEditHit.Excluded
            "Handle" -> HomeEditHit.Handle(section(o.string("section")!!), local(), size())
            "Block" -> HomeEditHit.Block(section(o.string("section")!!), local(), size())
            "Blank" -> HomeEditHit.Blank(o.string("nearest")?.let(::section))
            else -> error("unknown hit")
        }
    }
}
