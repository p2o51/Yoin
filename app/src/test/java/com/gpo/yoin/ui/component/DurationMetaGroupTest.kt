package com.gpo.yoin.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The album header's length (owner 2026-11-27: "1 h 16 min" → "1h 16min"; 「1 小时 16 分钟」 keeps its gaps). */
class DurationMetaGroupTest {

    @Test
    fun should_setLatinUnitsFlush_and_keepHoursAndMinutesInOneGroup() {
        val group = durationMetaGroup(76 * 60 + 40, "h", "min")

        assertEquals(listOf(MetaGroup.Stat("1", "h"), MetaGroup.Stat("16", "min")), group.parts)
        assertTrue(group.flush)
    }

    @Test
    fun should_keepTheGap_when_theUnitsAreWords() {
        val group = durationMetaGroup(76 * 60, "小时", "分钟")

        assertEquals(listOf(MetaGroup.Stat("1", "小时"), MetaGroup.Stat("16", "分钟")), group.parts)
        assertFalse(group.flush)
    }

    @Test
    fun should_leaveOutZeroMinutesOnTheHour_and_showMinutesAloneUnderAnHour() {
        assertEquals(listOf(MetaGroup.Stat("2", "h")), durationMetaGroup(2 * 3600 + 30, "h", "min").parts)
        assertEquals(listOf(MetaGroup.Stat("44", "min")), durationMetaGroup(44 * 60 + 59, "h", "min").parts)
        assertEquals(listOf(MetaGroup.Stat("0", "min")), durationMetaGroup(40, "h", "min").parts)
    }

    @Test
    fun should_setOnlyLatinAbbreviationsFlush() {
        assertTrue(unitSetsFlush("h"))
        assertTrue(unitSetsFlush("min"))
        assertFalse(unitSetsFlush("分钟"))
        assertFalse(unitSetsFlush("hr."))
        assertFalse(unitSetsFlush(""))
    }
}
