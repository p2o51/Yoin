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
        edit: Float = 0f,
    ) = resolveBarGeometry(
        slotInner = slotInner,
        morph = morph,
        pane = pane,
        idle = idle,
        edit = edit,
        navOnly = navOnly,
        homeAspect = 1.5f,
        libraryAspect = 1f,
        buttonHeight = 44.dp,
        centered = true,
        promotedCount = 0,
        mergedCount = 1,
    )

    private fun BarGeometry.slots(): Dp = leftWidth + FloatingBarItemGap + extrasSlotWidth + rightWidth

    /** The pill's remainder, as the bar lays it out. */
    private fun BarGeometry.pill(): Dp = surfaceInner - slots()

    private fun assertSameLayout(message: String, expected: BarGeometry, actual: BarGeometry) {
        listOf(
            "homeWidth" to (expected.homeWidth to actual.homeWidth),
            "libraryWidth" to (expected.libraryWidth to actual.libraryWidth),
            "leftWidth" to (expected.leftWidth to actual.leftWidth),
            "extrasSlotWidth" to (expected.extrasSlotWidth to actual.extrasSlotWidth),
            "splitDetailWidth" to (expected.splitDetailWidth to actual.splitDetailWidth),
            "rightWidth" to (expected.rightWidth to actual.rightWidth),
            "rightMergedWidth" to (expected.rightMergedWidth to actual.rightMergedWidth),
            "surfaceInner" to (expected.surfaceInner to actual.surfaceInner),
        ).forEach { (name, widths) ->
            assertEquals("$message $name", widths.first.value, widths.second.value, 0.001f)
        }
        assertEquals("$message idle", expected.idle, actual.idle, 0.0001f)
        assertEquals("$message idleWeight", expected.idleWeight, actual.idleWeight, 0.0001f)
        assertEquals("$message pillComposed", expected.pillComposed, actual.pillComposed)
    }

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

    @Test
    fun should_matchTheIdlePose_when_editingWithATrackPlaying() {
        for (e in listOf(0f, 0.5f, 1f)) {
            for (pane in listOf(0f, 0.5f, 1f)) {
                for (navOnly in listOf(0f, 1f)) {
                    assertSameLayout(
                        "e=$e pane=$pane navOnly=$navOnly",
                        expected = geometry(idle = e, pane = pane, navOnly = navOnly),
                        actual = geometry(edit = e, pane = pane, navOnly = navOnly),
                    )
                }
            }
        }
    }

    @Test
    fun should_beIdempotent_when_editingWhileIdle() {
        for (e in listOf(0f, 0.25f, 0.5f, 1f)) {
            for (pane in listOf(0f, 1f)) {
                for (navOnly in listOf(0f, 1f)) {
                    assertSameLayout(
                        "e=$e pane=$pane navOnly=$navOnly",
                        expected = geometry(idle = 1f, pane = pane, navOnly = navOnly),
                        actual = geometry(idle = 1f, edit = e, pane = pane, navOnly = navOnly),
                    )
                }
            }
        }
    }

    @Test
    fun should_splitTheBarIntoTwoHalves_when_editingInTheNavPose() {
        val g = geometry(edit = 1f)
        // (580 − 2 × 8) / 2 on each side: [Undo|Add 282][8][Done 282].
        assertEquals(282f, g.homeWidth.value, 0.01f)
        assertEquals(282f, g.libraryWidth.value, 0.01f)
        assertEquals(580f, g.surfaceInner.value, 0.01f)
        assertFalse(g.pillComposed)
        assertEquals(1f, g.idleWeight, 0.0001f)
    }

    @Test
    fun should_foldThePill_when_editingInTheMergedPose() {
        val g = geometry(edit = 1f, pane = 1f)
        // [Undo 66][8][Done 44][8] | [8 lead][Play split 156][8][Shuffle 44].
        assertFalse(g.pillComposed)
        assertEquals(342f, g.surfaceInner.value, 0.01f)
        assertEquals(g.surfaceInner.value, g.slots().value, 0.01f)
        assertEquals(0f, g.idleWeight, 0.0001f)
    }

    @Test
    fun should_showIconOnlyHalves_when_editingBesideTheNowPlayingPanel() {
        val g = geometry(edit = 1f, navOnly = 1f)
        // [Undo 66][8][8 lead][Done 44]; idleWeight 0 keeps the labels hidden.
        assertFalse(g.pillComposed)
        assertEquals(126f, g.surfaceInner.value, 0.01f)
        assertEquals(0f, g.idleWeight, 0.0001f)
    }

    @Test
    fun should_neverLeaveAHole_when_anyPoseMeetsEdit() {
        // Spec matrix (SPEC §2.2.7), with mid-spring panes and panel folds.
        val values = listOf(0f, 0.5f, 1f)
        val poses = values.flatMap { edit ->
            listOf(0f, 1f).flatMap { idle ->
                values.flatMap { pane -> values.map { navOnly -> listOf(edit, idle, pane, navOnly) } }
            }
        }
        for ((edit, idle, pane, navOnly) in poses) {
            val g = geometry(edit = edit, idle = idle, pane = pane, navOnly = navOnly)
            val message = "edit=$edit idle=$idle pane=$pane navOnly=$navOnly"
            if (g.pillComposed) {
                val pill = minOf(g.surfaceInner.value, 580f) - g.slots().value
                assertTrue("$message pill=$pill", pill >= -0.001f)
            } else {
                assertEquals(message, g.surfaceInner.value, g.slots().value, 0.01f)
            }
        }
    }

    @Test
    fun should_neverLeaveAHole_when_editSprings() {
        for (pane in listOf(0f, 1f)) {
            for (step in 0..20) {
                val g = geometry(edit = step / 20f, pane = pane)
                val message = "pane=$pane edit=${step / 20f}"
                if (g.pillComposed) {
                    assertTrue("$message pill=${g.pill()}", g.pill().value >= -0.001f)
                } else {
                    assertEquals(message, g.surfaceInner.value, g.slots().value, 0.01f)
                }
            }
        }
        // Mid-spring in the nav pose: halves of 174 and 163 leave the pill 227.
        assertEquals(227f, geometry(edit = 0.5f).pill().value, 0.01f)
        // Mid-spring merged: the surface is halfway to the wrap, the pill 119.
        val merged = geometry(edit = 0.5f, pane = 1f)
        assertEquals(461f, merged.surfaceInner.value, 0.01f)
        assertEquals(119f, merged.pill().value, 0.01f)
    }

    @Test
    fun should_neverLeaveAHole_when_editingAndTheColumnSprings() {
        for (step in 0..20) {
            val g = geometry(edit = 1f, pane = step / 20f)
            assertFalse(g.pillComposed)
            assertEquals("pane=${step / 20f}", g.surfaceInner.value, g.slots().value, 0.01f)
        }
    }

    @Test
    fun should_reportRawEditAndIdleLike() {
        val editing = geometry(idle = 0.2f, edit = 0.7f)
        assertEquals(0.7f, editing.edit, 0.0001f)
        assertEquals(0.7f, editing.idle, 0.0001f)
        val idle = geometry(idle = 0.8f, edit = 0.3f)
        assertEquals(0.3f, idle.edit, 0.0001f)
        assertEquals(0.8f, idle.idle, 0.0001f)
        assertEquals(0f, geometry().edit, 0.0001f)
    }

    @Test
    fun should_keepThePillComposed_when_detailChromeRunsDuringEdit() {
        // Why the shell exits edit before arming detail chrome: the morph
        // forces the pill back while edit would fold it.
        val g = geometry(edit = 1f, morph = 0.5f)
        assertTrue(g.pillComposed)
    }

    @Test
    fun should_swapContentOverTheMiddleOfP_when_editing() {
        assertEquals(0f, barEditSwap(0.35f), 0.0001f)
        assertEquals(0.5f, barEditSwap(0.5f), 0.0001f)
        assertEquals(1f, barEditSwap(0.65f), 0.0001f)
    }

    @Test
    fun should_describeTheLeftSlot_when_editing() {
        assertEquals("Undo", barEditLeftSlotDescription(BarEditLeftSlot.Undo))
        assertEquals("Show hidden sections", barEditLeftSlotDescription(BarEditLeftSlot.Add))
        assertEquals("Undo", barEditLeftSlotDescription(BarEditLeftSlot.UndoDisabled))
    }
}
