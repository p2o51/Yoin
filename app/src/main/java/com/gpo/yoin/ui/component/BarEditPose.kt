package com.gpo.yoin.ui.component

import androidx.compose.runtime.Stable

/** What the bar's left (Home) slot offers while Home is being edited. */
enum class BarEditLeftSlot {
    /** Something to undo this session. */
    Undo,

    /** Nothing to undo, but hidden sections to bring back: scrolls to the tray. */
    Add,

    /** Neither: a dimmed, inert Undo. */
    UndoDisabled,
}

/** Undo wins while the session has history; otherwise Add while the tray has rows. */
fun resolveEditLeftSlot(undoDepth: Int, trayCount: Int): BarEditLeftSlot = when {
    undoDepth > 0 -> BarEditLeftSlot.Undo
    trayCount > 0 -> BarEditLeftSlot.Add
    else -> BarEditLeftSlot.UndoDisabled
}

/**
 * The bar's Home edit pose: `[Undo|Add] [Done]` where the nav buttons were.
 * The shell's edit controller owns every value; the bar only reads.
 */
@Stable
class BarEditPose(
    /** Edit progress P. Read only in the bar's measure and draw. */
    val progress: () -> Float,
    /** Read in the bar's (sub)composition. */
    val leftSlot: () -> BarEditLeftSlot,
    /** The controller resolves Undo, Add or nothing (disabled). */
    val onLeftSlotClick: () -> Unit,
    val onDone: () -> Unit,
)
