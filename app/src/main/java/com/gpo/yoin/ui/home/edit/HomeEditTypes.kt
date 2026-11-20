package com.gpo.yoin.ui.home.edit

import androidx.compose.runtime.Immutable
import com.gpo.yoin.ui.experience.YoinHaptics
import com.gpo.yoin.ui.home.HomeLayout
import com.gpo.yoin.ui.home.HomeSection

/** Why Home edit mode ended. Only [Done] confirms with a haptic. */
enum class HomeEditExitReason {
    /** The bar's Done button. */
    Done,

    /** System back (discrete in P0). */
    Back,

    /** A tap on blank page space. */
    Blank,

    /** The app left edit mode on its own (profile switch, section change, stop). */
    Programmatic,
}

enum class HomeEditChangeKind {
    Hide,
    Show,
    Order,
    Move,
    Reset,
    Undo,

    /** A section's row preset (the resize handle, its keyboard keys, TalkBack). */
    Rows,
}

/** One applied layout edit, handed to the Home layer so it can animate it. [serial] increases per change. */
@Immutable
data class HomeEditChange(
    val serial: Int,
    val kind: HomeEditChangeKind,
    val previous: HomeLayout,
    val next: HomeLayout,
    val subject: HomeSection? = null,
)

/**
 * Every haptic Home edit mode plays. Kept as an interface so the controller,
 * motion and gesture code stay testable without a `View`.
 */
interface HomeEditFeedback {
    /** Normal-mode long press reaching the threshold (entering edit). */
    fun longPress()

    /** Picking a block up in edit mode. */
    fun dragStart()

    /** The carried strip moving into the next slot. */
    fun segmentTick()

    /** The carried strip dragged past the first or last slot. */
    fun threshold()

    /** A changed order dropped, or Done. */
    fun confirm()

    /** Reset. */
    fun reject()

    /** Hide ([on] = false) or Show ([on] = true). */
    fun toggle(on: Boolean)

    /** Undo and Add. */
    fun click()

    companion object {
        /** Silent: previews, the debug harness, tests. */
        val None: HomeEditFeedback = object : HomeEditFeedback {
            override fun longPress() = Unit
            override fun dragStart() = Unit
            override fun segmentTick() = Unit
            override fun threshold() = Unit
            override fun confirm() = Unit
            override fun reject() = Unit
            override fun toggle(on: Boolean) = Unit
            override fun click() = Unit
        }
    }
}

/** The device haptics behind [HomeEditFeedback] (spec §2.8). */
fun YoinHaptics.asHomeEditFeedback(): HomeEditFeedback = YoinHapticsHomeEditFeedback(this)

private class YoinHapticsHomeEditFeedback(private val haptics: YoinHaptics) : HomeEditFeedback {
    override fun longPress() = haptics.performLongPress()
    override fun dragStart() = haptics.performDragStart()
    override fun segmentTick() = haptics.performSegmentTick()
    override fun threshold() = haptics.performThreshold()
    override fun confirm() = haptics.performConfirm()
    override fun reject() = haptics.performReject()
    override fun toggle(on: Boolean) = haptics.performToggle(on)
    override fun click() = haptics.performClick()
}
