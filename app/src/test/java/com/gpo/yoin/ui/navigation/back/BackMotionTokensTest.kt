package com.gpo.yoin.ui.navigation.back

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class BackMotionTokensTest {

    @Test
    fun should_keep_simple_push_page_motion_tokens_stable() {
        assertEquals(0.70f, BackMotionTokens.PushPageScaleTarget, 0.0001f)
        assertEquals(32.dp, BackMotionTokens.PushPageCornerRadius)
        assertEquals(0.dp, BackMotionTokens.PushPageEdgeInset)
    }
}
