package com.gpo.yoin.ui.component

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarGeometryTest {

    private fun geometry(
        slotInner: Dp = 580.dp,
        morph: Float = 0f,
        pane: Float = 0f,
        idle: Float = 0f,
        navOnly: Float = 0f,
    ) = resolveBarGeometry(
        slotInner = slotInner,
        morph = morph,
        pane = pane,
        idle = idle,
        navOnly = navOnly,
        homeAspect = 1.5f,
        libraryAspect = 1f,
        buttonHeight = 44.dp,
        centered = true,
        promotedCount = 0,
        mergedCount = 1,
    )

    private fun BarGeometry.slots(): Dp = leftWidth + FloatingBarItemGap + extrasSlotWidth + rightWidth

    @Test
    fun should_fillTheSlot_when_idleInTheNavPose() {
        val g = geometry(idle = 1f)
        assertFalse(g.pillComposed)
        assertEquals(580f, g.surfaceInner.value, 0.01f)
        assertEquals(g.surfaceInner.value, g.slots().value, 0.01f)
    }

    @Test
    fun should_neverLeaveAHoleWhereThePillWas_when_idleAndTheColumnSprings() {
        // Nothing playing, the detail column opening: every frame of the
        // spring the surface hugs the slots exactly.
        for (step in 0..20) {
            val g = geometry(idle = 1f, pane = step / 20f)
            assertFalse(g.pillComposed)
            assertEquals("pane=${step / 20f}", g.surfaceInner.value, g.slots().value, 0.01f)
        }
    }

    @Test
    fun should_wrapTheMergedControls_when_idleAndTheColumnIsOpen() {
        val g = geometry(idle = 1f, pane = 1f)
        // [Home 66][8][Library 44][8] | right slot: [8 lead][Play split 156][8][Shuffle 44].
        val expected = 66f + 8f + 44f + 8f + 8f + 156f + 8f + 44f
        assertEquals(expected, g.surfaceInner.value, 0.01f)
    }

    @Test
    fun should_letThePillAbsorbTheRest_when_playing() {
        val g = geometry(pane = 0.5f)
        assertTrue(g.pillComposed)
        assertEquals(580f, g.surfaceInner.value, 0.01f)
        assertTrue(g.slots() < g.surfaceInner)
    }
}
