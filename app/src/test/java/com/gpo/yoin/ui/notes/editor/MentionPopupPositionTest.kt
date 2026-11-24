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

    // ── the keyboard (2026-11-24 tablet QA: the sticky editor's popup sat on the keyboard's top rows) ──

    @Test
    fun should_neverReachUnderTheKeyboard() {
        // phone width, a scrapbook sticky in mid-screen, the keyboard's top at 1450
        val imeTop = 1450
        val statusBar = 60
        val field = IntRect(140, 700, 940, 900)
        val caret = Rect(200f, 0f, 202f, 60f)
        val listMax = mentionPopupListMax(
            caretTop = 700,
            caretBottom = 760,
            topLimit = statusBar,
            bottomLimit = imeTop,
            margin = margin,
            chrome = 168,
            min = 252,
            max = 788,
        )
        // the rows shrink to what the roomier side holds (below the line: 690px, above: 640px)
        assertEquals(imeTop - 760 - 2 * margin - 168, listMax)
        val shrunk = IntSize(820, listMax + 168)

        val at = mentionPopupOffset(field, caret, window, shrunk, margin, topLimit = statusBar, bottomLimit = imeTop)

        assertTrue(at.y + shrunk.height <= imeTop)
        assertTrue(at.y >= statusBar)
        // and it still clears the caret's line
        assertTrue(at.y + shrunk.height <= 700 || at.y >= 760)
    }

    @Test
    fun should_goBelowTheLine_onlyWhenItFitsAboveTheKeyboard() {
        val field = IntRect(48, 100, 1032, 400)
        val caret = Rect(0f, 0f, 2f, 60f)
        val small = IntSize(820, 500)

        val roomy = mentionPopupOffset(field, caret, window, small, margin, topLimit = 60, bottomLimit = 1400)
        assertEquals(160 + margin, roomy.y)

        // a keyboard so tall the popup can't go below: it takes the roomier side instead
        val cramped = mentionPopupOffset(field, caret, window, small, margin, topLimit = 60, bottomLimit = 600)
        assertTrue(cramped.y + small.height <= 600 || cramped.y >= 60)
    }

    @Test
    fun should_notCloseOnATouchOutside_soAKeyboardTapKeepsItOpen() {
        assertEquals(false, MentionPopupProperties.dismissOnClickOutside)
        assertEquals(false, MentionPopupProperties.focusable)
    }

    @Test
    fun should_showOnlyWhileTheFieldHoldsFocus() {
        val trigger = MentionTrigger(0, 1, "")
        assertTrue(mentionPopupOpen(enabled = true, trigger = trigger, focused = true, searchOpen = false))
        assertEquals(false, mentionPopupOpen(enabled = true, trigger = trigger, focused = false, searchOpen = false))
        assertEquals(false, mentionPopupOpen(enabled = true, trigger = null, focused = true, searchOpen = false))
        assertEquals(false, mentionPopupOpen(enabled = true, trigger = trigger, focused = true, searchOpen = true))
    }
}
