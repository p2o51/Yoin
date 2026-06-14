package com.gpo.yoin.ui.navigation.back

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class BackMotionTokensTest {

    @Test
    fun should_keep_pop_page_motion_tokens_stable() {
        // AOSP CrossActivityBackAnimation MAX_SCALE.
        assertEquals(0.9f, BackMotionTokens.PopPageScaleTarget, 0.0001f)
        assertEquals(28.dp, BackMotionTokens.PopPageCornerRadius)
    }

}
