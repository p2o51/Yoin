package com.gpo.yoin.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class BarEditPoseTest {

    @Test
    fun should_resolveLeftSlot() {
        // Undo wins over Add while there is history.
        assertEquals(BarEditLeftSlot.Undo, resolveEditLeftSlot(undoDepth = 1, trayCount = 2))
        assertEquals(BarEditLeftSlot.Add, resolveEditLeftSlot(undoDepth = 0, trayCount = 1))
        assertEquals(BarEditLeftSlot.UndoDisabled, resolveEditLeftSlot(undoDepth = 0, trayCount = 0))
    }
}
