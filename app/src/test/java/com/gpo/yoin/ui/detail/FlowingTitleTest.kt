package com.gpo.yoin.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowingTitleTest {
    private val nbsp = ' '
    private val wordJoiner = '⁠'

    @Test
    fun should_glueWordsWithNbsp_when_latinTitleIsShort() {
        assertEquals("Old${nbsp}Photographs", flowingTitle("Old Photographs"))
    }

    @Test
    fun should_joinEveryGlyph_when_cjkTitleIsShort() {
        assertEquals("晴${wordJoiner}天${wordJoiner}以${wordJoiner}后", flowingTitle("晴天以后"))
    }

    @Test
    fun should_stayBreakable_when_titleIsLong() {
        val long = "Everything I Meant to Say at the Station"
        assertEquals(long, flowingTitle(long))
        // 9 wide glyphs = 18 units, over the glue budget.
        assertEquals("おやすみ、また明日", flowingTitle("おやすみ、また明日"))
    }

    @Test
    fun should_separateTitlesWithASpace_when_theyFlow() {
        val text = flowingTitleSequence(listOf("Describe", "Gimme Time", "Tell Me"))
        assertFalse(text.contains('•'))
        assertFalse(text.contains('·'))
        assertEquals("Describe Gimme${nbsp}Time Tell${nbsp}Me", text)
    }

    @Test
    fun should_usePrimaryTone_when_titleIndexIsEven() {
        assertTrue(flowingTitleUsesPrimaryTone(0))
        assertFalse(flowingTitleUsesPrimaryTone(1))
        assertTrue(flowingTitleUsesPrimaryTone(2))
        assertFalse(flowingTitleUsesPrimaryTone(3))
    }
}
