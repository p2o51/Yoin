package com.gpo.yoin.ui.component

import com.gpo.yoin.R
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiException
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiFailure
import com.gpo.yoin.data.repository.SubsonicException
import com.gpo.yoin.ui.common.UiText
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserMessageTest {
    @Test fun should_useScreenFallback_when_appleMusicReturnsNotFound() {
        assertEquals(
            "Couldn't load this album.",
            AppleMusicApiException(AppleMusicApiFailure.Http(404)).toUserMessage("Couldn't load this album.")
        )
    }

    // Apple has refused single playlists, and every library read for minutes, on accounts that played on.
    @Test fun should_notBlameTheSubscription_when_appleMusicRefusesARead() {
        assertEquals(
            "Apple Music refused this request. Try again later.",
            AppleMusicApiException(AppleMusicApiFailure.AccessDenied).toUserMessage("fallback")
        )
    }

    @Test fun should_keepTheAppsOwnLineAResource_when_aViewModelHasNoResources() {
        assertEquals(
            UiText.Res(R.string.cmp_error_apple_denied),
            AppleMusicApiException(AppleMusicApiFailure.AccessDenied).toUserMessageText(),
        )
    }

    @Test fun should_leaveTheScreenItsFallback_when_appleAnswersWithNoLineOfItsOwn() {
        assertNull(AppleMusicApiException(AppleMusicApiFailure.Http(404)).toUserMessageText())
    }

    @Test fun should_sayTheServersOwnWords_when_subsonicRefuses() {
        assertEquals(
            UiText.Raw("Wrong username or password"),
            SubsonicException(40, "Wrong username or password").toUserMessageText(),
        )
    }

    @Test fun should_reportConnectivity_when_plainIOException() {
        assertEquals(
            "Can't reach the server. Check your connection.",
            IOException("reset").toUserMessage("fallback")
        )
    }
}
