package com.gpo.yoin.ui.settings.applemusic

import com.gpo.yoin.R
import com.gpo.yoin.ui.common.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Apple Music button after a try that didn't take: the floating guide then says to tap Connect again
 * (guide_apple_retry), so the button has to keep that name (P10-6).
 */
class AppleMusicConnectLabelTest {

    private val failed = AppleMusicValidationUiState(
        status = UiText.Res(R.string.settings_apple_status_cancelled),
        retryable = true
    )

    @Test
    fun should_keepConnect_when_aSignInDidNotTake() {
        assertEquals(R.string.settings_apple_connect, appleMusicConnectLabel(failed))
    }

    @Test
    fun should_keepReconnect_when_aReconnectDidNotTake() {
        assertEquals(R.string.settings_apple_reconnect, appleMusicConnectLabel(failed.copy(connected = true)))
    }

    @Test
    fun should_readConnect_when_nothingWasTriedYet() {
        assertEquals(R.string.settings_apple_connect, appleMusicConnectLabel(AppleMusicValidationUiState()))
    }
}
