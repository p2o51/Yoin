package com.gpo.yoin.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On the tablet's three panes one colour-wash step recomposed the whole window
 * in ~40 ms, so the stepped wash was a run of long frames. A window that can't
 * afford a step cuts; a light one keeps stepping.
 */
class ColorWashBudgetTest {

    @Test
    fun should_keepStepping_when_stepFramesFitTheBudget() {
        val budget = ColorWashBudget()
        repeat(12) { i ->
            budget.mark(ms(16L * i))
            assertEquals(false, budget.measure(ms(16L * i + 16)))
        }
        assertFalse(budget.cutsOnly)
    }

    @Test
    fun should_switchToCuts_when_aStepFrameIsHeavy() {
        val budget = ColorWashBudget()
        budget.mark(ms(0))

        assertEquals(true, budget.measure(ms(41)))
        assertTrue(budget.cutsOnly)
    }

    @Test
    fun should_stepAgain_when_aCutFitsTheBudget() {
        val budget = ColorWashBudget()
        budget.mark(ms(0))
        budget.measure(ms(45))

        // The window got lighter (e.g. panes closed): the next cut is cheap.
        budget.mark(ms(1_000))
        budget.measure(ms(1_016))

        assertFalse(budget.cutsOnly)
    }

    @Test
    fun should_measureNothing_withoutAMark() {
        val budget = ColorWashBudget()
        assertNull(budget.measure(ms(500)))

        budget.mark(ms(0))
        budget.forgetMark()
        // A cancelled wash's mark must not be blamed on a later frame.
        assertNull(budget.measure(ms(900)))
        assertFalse(budget.cutsOnly)
    }

    private fun ms(value: Long) = value * 1_000_000L
}
