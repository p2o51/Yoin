package com.gpo.yoin.ui.experience

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingRequestTest {

    @Test
    fun should_letOneScreenTakeTheRequest_when_itIsFresh() {
        val store = ExperienceSessionStore()
        store.requestNowPlaying()
        val token = store.nowPlayingRequest.value

        assertTrue(store.consumeNowPlayingRequest(token, now = token + 1))
        assertFalse(store.consumeNowPlayingRequest(token, now = token + 2))
    }

    @Test
    fun should_dropTheRequest_when_nobodyTookItInTime() {
        val store = ExperienceSessionStore()
        store.requestNowPlaying()
        val token = store.nowPlayingRequest.value

        assertFalse(store.consumeNowPlayingRequest(token, now = token + NOW_PLAYING_REQUEST_TTL_NS + 1))
    }
}
