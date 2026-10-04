package com.gpo.yoin.ui.home.edit

/** Records every haptic Home edit mode asks for, in order, e.g. `["longPress", "toggleOff", "confirm"]`. */
class RecordingHomeEditFeedback : HomeEditFeedback {
    val events = mutableListOf<String>()

    override fun longPress() {
        events += "longPress"
    }

    override fun dragStart() {
        events += "dragStart"
    }

    override fun segmentTick() {
        events += "segmentTick"
    }

    override fun threshold() {
        events += "threshold"
    }

    override fun confirm() {
        events += "confirm"
    }

    override fun reject() {
        events += "reject"
    }

    override fun toggle(on: Boolean) {
        events += if (on) "toggleOn" else "toggleOff"
    }

    override fun click() {
        events += "click"
    }

    fun clear() = events.clear()
}
