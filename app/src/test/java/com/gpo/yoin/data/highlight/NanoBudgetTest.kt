package com.gpo.yoin.data.highlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NanoBudgetTest {
    private val now = 1_800_000_000_000L
    private val today = NanoBudget.dayOf(now, 0)

    @Test
    fun should_stopAtDailyLimit_andResetNextDay() {
        var budget = NanoBudget()
        repeat(NanoBudget.DAILY_LIMIT) {
            assertTrue(budget.allows(now, today))
            budget = budget.afterSuccess(today)
        }
        assertFalse(budget.allows(now, today))
        assertTrue(budget.allows(now + 86_400_000L, today + 1))
        assertEquals(1, budget.afterSuccess(today + 1).usedToday)
    }

    @Test
    fun should_backOffLonger_onEachBusy() {
        var budget = NanoBudget().afterBusy(now, today)
        assertFalse(budget.allows(now + 29_000L, today))
        assertTrue(budget.allows(now + 30_000L, today))
        budget = budget.afterBusy(now, today)
        assertEquals(now + 120_000L, budget.pausedUntil)
        budget = budget.afterBusy(now, today).afterBusy(now, today)
        assertEquals(now + 600_000L, budget.pausedUntil)
        // A success clears the streak.
        assertEquals(0, budget.afterSuccess(today).busyStreak)
    }

    @Test
    fun should_waitForTomorrow_when_quotaExhausted() {
        val budget = NanoBudget().afterQuotaExhausted(today)
        assertFalse(budget.allows(now, today))
        assertTrue(budget.allows(now + 86_400_000L, today + 1))
    }

    @Test
    fun should_countLocalDays() {
        // 23:30 UTC is already the next day at UTC+9.
        val lateUtc = 1_800_000_000_000L - (1_800_000_000_000L % 86_400_000L) + 84_600_000L
        assertEquals(NanoBudget.dayOf(lateUtc, 0) + 1, NanoBudget.dayOf(lateUtc, 9 * 3_600_000))
    }
}
