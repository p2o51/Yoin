package com.gpo.yoin.ui.component

import com.gpo.yoin.data.remote.applemusic.AppleMusicApiException
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiFailure
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class UserMessageTest {
    @Test fun should_useScreenFallback_when_appleMusicReturnsNotFound() {
        assertEquals(
            "Couldn't load this album.",
            AppleMusicApiException(AppleMusicApiFailure.Http(404)).toUserMessage("Couldn't load this album.")
        )
    }

    @Test fun should_pointToSubscription_when_appleMusicDeniesAccess() {
        assertEquals(
            "Apple Music denied access. Check your subscription.",
            AppleMusicApiException(AppleMusicApiFailure.AccessDenied).toUserMessage("fallback")
        )
    }

    @Test fun should_reportConnectivity_when_plainIOException() {
        assertEquals(
            "Can't reach the server. Check your connection.",
            IOException("reset").toUserMessage("fallback")
        )
    }
}
