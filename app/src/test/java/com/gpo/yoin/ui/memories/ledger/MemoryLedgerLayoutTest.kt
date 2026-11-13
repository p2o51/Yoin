package com.gpo.yoin.ui.memories.ledger

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryLedgerLayoutTest {
    @Test
    fun should_keepPhoneLayout_when_windowIsShorterThan480dp() {
        for (width in listOf(844.dp, 1280.dp)) {
            val layout = ledgerLayoutFor(width, compactHeight = true)
            assertEquals(1, layout.columns)
            assertFalse(layout.wide)
            assertEquals(720.dp, layout.maxWidth)
        }
    }

    @Test
    fun should_stepAt600_840_1100_when_heightAllows() {
        assertEquals(1, ledgerLayoutFor(599.dp, compactHeight = false).columns)
        assertEquals(2, ledgerLayoutFor(600.dp, compactHeight = false).columns)
        assertFalse(ledgerLayoutFor(839.dp, compactHeight = false).wide)
        assertTrue(ledgerLayoutFor(840.dp, compactHeight = false).wide)
        assertEquals(2, ledgerLayoutFor(1099.dp, compactHeight = false).columns)
        assertEquals(3, ledgerLayoutFor(1100.dp, compactHeight = false).columns)
    }
}
