package com.gpo.yoin.ui.notes.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowTapsTest {

    @Test
    fun should_endMentionMode_onlyForATapOutsideTheField() {
        val field = Rect(100f, 1300f, 1000f, 1420f)

        assertTrue(tapEndsMention(field, Offset(500f, 400f)))
        assertTrue(tapEndsMention(field, Offset(1040f, 1350f)))
        assertFalse(tapEndsMention(field, Offset(500f, 1350f)))
        // not laid out yet: nothing to compare with, nothing ends
        assertFalse(tapEndsMention(null, Offset(500f, 400f)))
    }

    @Test
    fun should_tellEveryListener_untilItStops() {
        val taps = WindowTaps()
        val heard = mutableListOf<Offset>()
        val stop = taps.listen { heard += it }

        taps.tap(Offset(1f, 2f))
        stop()
        taps.tap(Offset(3f, 4f))

        assertEquals(listOf(Offset(1f, 2f)), heard)
    }
}
