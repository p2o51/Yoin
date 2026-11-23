package com.gpo.yoin.lyra.ui

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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

    @Test
    fun sharedAudioStreamsForwardUrisViaClipDataWithReadGrant() {
        val activity = Robolectric.buildActivity(LyraShareReceiverActivity::class.java).get()
        val u1 = Uri.parse("content://com.example.files/track1.flac")
        val u2 = Uri.parse("content://com.example.files/track2.flac")

        val single = activity.buildHubIntent(
            Intent(Intent.ACTION_SEND).apply {
                type = "audio/flac"
                putExtra(Intent.EXTRA_STREAM, u1)
            },
        )
        assertNotNull(single)
        assertTrue((single!!.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertEquals(1, single.clipData?.itemCount)
        assertEquals(u1, single.clipData?.getItemAt(0)?.uri)

        val multi = activity.buildHubIntent(
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "audio/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(u1, u2))
            },
        )
        assertNotNull(multi)
        assertTrue((multi!!.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertEquals(2, multi.clipData?.itemCount)
        assertEquals(u1, multi.clipData?.getItemAt(0)?.uri)
        assertEquals(u2, multi.clipData?.getItemAt(1)?.uri)
    }
}
