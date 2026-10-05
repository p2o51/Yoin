package com.gpo.yoin.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 默认折叠走真实的 ICU `Traditional-Simplified` 变换，需要 Robolectric 提供 android.icu。 */
@RunWith(RobolectricTestRunner::class)
class TraditionalToSimplifiedTest {

    @Test
    fun should_foldTraditionalHan_when_icuTransformIsAvailable() {
        assertEquals("雨爱", TraditionalToSimplified("雨愛"))
        assertEquals("杨丞琳", TraditionalToSimplified("楊丞琳"))
        assertEquals("痛快的哀艳", TraditionalToSimplified("痛快的哀艷"))
    }

    @Test
    fun should_returnTextUnchanged_when_itHasNoHan() {
        assertEquals("テイラー・スウィフト", TraditionalToSimplified("テイラー・スウィフト"))
        assertEquals("ROSÉ", TraditionalToSimplified("ROSÉ"))
    }

    @Test
    fun should_matchTraditionalRequest_when_defaultFoldIsUsed() {
        val candidates = listOf(
            LyricCandidateMatcher.Candidate("雨爱 (DJ 阿若版)", listOf("杨丞琳")),
            LyricCandidateMatcher.Candidate("雨爱", listOf("杨丞琳")),
        )

        assertEquals(1, LyricCandidateMatcher().pick(candidates, title = "雨愛", artist = "楊丞琳"))
    }
}
