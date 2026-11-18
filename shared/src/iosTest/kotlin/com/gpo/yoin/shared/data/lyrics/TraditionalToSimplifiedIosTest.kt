package com.gpo.yoin.shared.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The default fold on iOS goes through Foundation's ICU transform. (On Android it is android.icu, which the
 * JVM host tests don't have; the Android app tests it under Robolectric.)
 */
class TraditionalToSimplifiedIosTest {

    @Test
    fun should_foldTraditionalHan_when_icuTransformIsAvailable() {
        assertEquals("雨爱", traditionalToSimplified("雨愛"))
        assertEquals("杨丞琳", traditionalToSimplified("楊丞琳"))
        assertEquals("痛快的哀艳", traditionalToSimplified("痛快的哀艷"))
    }

    @Test
    fun should_returnTextUnchanged_when_itHasNoHan() {
        assertEquals("テイラー・スウィフト", traditionalToSimplified("テイラー・スウィフト"))
        assertEquals("ROSÉ", traditionalToSimplified("ROSÉ"))
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
