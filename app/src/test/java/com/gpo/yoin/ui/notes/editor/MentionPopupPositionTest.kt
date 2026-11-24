package com.gpo.yoin.ui.notes.editor

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The @ popup never covers the line being written (2026-11-24 tablet QA, phone width). */
class MentionPopupPositionTest {

    private val window = IntSize(1080, 2400)
    private val popup = IntSize(820, 900)
    private val margin = 21

    @Test
    fun should_sitAboveTheWholeField_whenThereIsRoom() {
        // Now Playing's write bar over the keyboard: two lines, the @ on the second
        val field = IntRect(120, 1300, 1000, 1420)
        val caretOnSecondLine = Rect(300f, 60f, 302f, 120f)

        val at = mentionPopupOffset(field, caretOnSecondLine, window, popup, margin)

        assertEquals(1300 - 900 - margin, at.y)
        // the field's first line (and the caret's) stay uncovered
        assertTrue(at.y + popup.height <= field.top)
    }

    @Test
    fun should_sitAboveTheCaretsLine_whenTheFieldIsTooTallForAboveIt() {
        // a full-screen editor: the field starts near the top, the caret further down
        val field = IntRect(48, 200, 1032, 2000)
        val caret = Rect(400f, 1100f, 402f, 1160f)

        val at = mentionPopupOffset(field, caret, window, popup, margin)

        assertEquals(200 + 1100 - 900 - margin, at.y)
        assertTrue(at.y + popup.height <= field.top + 1100)
    }

    @Test
    fun should_dropBelowTheCaretsLine_whenNothingFitsAbove() {
        val field = IntRect(48, 100, 1032, 1500)
        val caret = Rect(400f, 40f, 402f, 100f)

        val at = mentionPopupOffset(field, caret, window, popup, margin)

        assertEquals(100 + 100 + margin, at.y)
    }

    @Test
    fun should_stayInsideTheWindowSideways() {
        val field = IntRect(0, 1300, 1080, 1420)
        val at = mentionPopupOffset(field, Rect(1070f, 0f, 1072f, 60f), window, popup, margin)

        assertEquals(1080 - 820 - margin, at.x)
    }
}
