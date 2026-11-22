package com.gpo.yoin.lyra.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyraShareReceiverRoutingTest {
    @Test
    fun extractsUrlFromSharedTextAndDetectsPassLinks() {
        val passText = "来听听这张专辑 https://lyra.example.com/s/AbCd1234。"
        val extracted = LyraShareReceiverActivity.extractFirstUrl(passText)
        assertEquals("https://lyra.example.com/s/AbCd1234", extracted)
        assertTrue(LyraShareReceiverActivity.isLyraPassUrl(extracted!!))

        val biliText = "【Live】某场演出 https://www.bilibili.com/video/BV1xx411c7mD/"
        val biliUrl = LyraShareReceiverActivity.extractFirstUrl(biliText)
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD/", biliUrl)
        assertFalse(LyraShareReceiverActivity.isLyraPassUrl(biliUrl!!))
    }
}
