package com.gpo.yoin.ui.component

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTextTest {
    @Test
    fun should_renderBoldSpan_when_doubleAsteriskMarkersPresent() {
        val text = markdownBoldAnnotatedString("This is **important**.")

        assertEquals("This is important.", text.text)
        assertTrue(
            text.spanStyles.any { range ->
                range.start == 8 &&
                    range.end == 17 &&
                    range.item.fontWeight == FontWeight.Bold
            },
        )
    }

    @Test
    fun should_renderBoldSpan_when_doubleUnderscoreMarkersPresent() {
        val text = markdownBoldAnnotatedString("Made by __Brian Eno__ in 1977.")

        assertEquals("Made by Brian Eno in 1977.", text.text)
        assertTrue(
            text.spanStyles.any { range ->
                range.start == 8 &&
                    range.end == 18 &&
                    range.item.fontWeight == FontWeight.Bold
            },
        )
    }

    @Test
    fun should_preserveMarker_when_closingMarkerMissing() {
        val text = markdownBoldAnnotatedString("This **never closes")

        assertEquals("This **never closes", text.text)
        assertTrue(text.spanStyles.isEmpty())
    }
}
