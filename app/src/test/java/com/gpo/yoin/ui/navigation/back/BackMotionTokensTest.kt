package com.gpo.yoin.ui.navigation.back

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class BackMotionTokensTest {

    @Test
    fun should_keep_pop_page_motion_tokens_stable() {
        // AOSP CrossActivityBackAnimation MAX_SCALE.
        assertEquals(0.9f, BackMotionTokens.PopPageScaleTarget, 0.0001f)
        // Nothing stored, no display report: the screen-corner fallback.
        assertEquals(28.dp, BackMotionTokens.PopPageCornerRadius)
    }

    @Test
    fun should_keep_memories_back_tokens_stable() {
        // Owner-approved showcase v4 (twostate4 Q_BODY / Q_BAR, releaseQ, releaseV, d.D, BAND).
        assertEquals(112.dp, BackMotionTokens.MemoriesDismissTrigger)
        assertEquals(56.dp, BackMotionTokens.MemoriesBarDismissTrigger)
        assertEquals(600.dp, BackMotionTokens.MemoriesDismissFling)
        assertEquals(450.dp, BackMotionTokens.MemoriesBarDismissFling)
        assertEquals(350.dp, BackMotionTokens.MemoriesFlickBack)
        assertEquals(320.dp, BackMotionTokens.MemoriesDiaryMorphDistance)
        assertEquals(24.dp, BackMotionTokens.MemoriesDiaryPullBand)
    }

    @Test
    fun should_keep_column_close_preview_travel_stable() {
        // The detail column's close preview: a quarter of its width toward the edge at full progress.
        assertEquals(0.25f, BackMotionTokens.ColumnClosePreviewTravel, 0.0001f)
    }
}
