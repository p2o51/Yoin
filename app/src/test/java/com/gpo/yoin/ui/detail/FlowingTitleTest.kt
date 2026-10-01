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
    fun should_bindBulletToPreviousTitle_when_separatorIsAppended() {
        // Non-breaking before the bullet (a line can't start with "•"),
        // plain spaces after it (the only break opportunity).
        val bullet = FlowingTitleSeparator.indexOf('•')
        assertTrue(FlowingTitleSeparator.substring(0, bullet).all { it == nbsp })
        assertTrue(FlowingTitleSeparator.substring(bullet + 1).all { it == ' ' })
        assertFalse(FlowingTitleSeparator.substring(bullet + 1).isEmpty())
    }
}
