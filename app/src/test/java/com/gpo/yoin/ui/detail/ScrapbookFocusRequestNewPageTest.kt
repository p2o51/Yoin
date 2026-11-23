package com.gpo.yoin.ui.detail

import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class ScrapbookFocusRequestNewPageTest {

    @Test
    fun should_leaveANoteLinksRequest_toTheWindowItOpens() {
        val pageBehind = SystemClock.elapsedRealtime()
        ShadowSystemClock.advanceBy(Duration.ofMillis(500))
        ScrapbookFocusRequest.request("subsonic:t2", newPageOnly = true)

        // the album page already open behind Now Playing doesn't take it
        assertNull(ScrapbookFocusRequest.takeFor(listOf("subsonic:t2"), pageOpenedAt = pageBehind))
        ShadowSystemClock.advanceBy(Duration.ofMillis(100))
        val newPage = SystemClock.elapsedRealtime()
        assertEquals("subsonic:t2", ScrapbookFocusRequest.takeFor(listOf("subsonic:t2"), pageOpenedAt = newPage)?.songId)

        ScrapbookFocusRequest.requestAlbum("subsonic:al-1", newPageOnly = true)
        assertFalse(ScrapbookFocusRequest.takeForAlbum("subsonic:al-1", pageOpenedAt = pageBehind))
        ShadowSystemClock.advanceBy(Duration.ofMillis(100))
        assertTrue(ScrapbookFocusRequest.takeForAlbum("subsonic:al-1", pageOpenedAt = SystemClock.elapsedRealtime()))
    }
}
