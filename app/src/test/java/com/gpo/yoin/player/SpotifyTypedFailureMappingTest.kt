package com.gpo.yoin.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyTypedFailureMappingTest {

    @Test
    fun authorization_prompt_maps_to_needs_consent_not_a_token_failure() {
        val failure = userNotAuthorizedFailure(
            """{"message":"Explicit user authorization is required to use Spotify. The user has to complete the auth-flow to allow the app to use Spotify on their behalf"}""",
        )

        // Consent for App Remote, not a broken token: the recovery is Spotify's authorization
        // page, never "reconnect the account".
        assertTrue(failure is SpotifyConnectFailure.NeedsConsent)
        assertEquals(
            "Spotify hasn't allowed Yoin to control playback yet.",
            failure.userMessage(),
        )
    }

    @Test
    fun premium_message_maps_to_premium_required() {
        val failure = userNotAuthorizedFailure(
            """{"message":"Spotify Premium is required for this operation"}""",
        )

        assertEquals(SpotifyConnectFailure.PremiumRequired, failure)
        assertEquals(
            "Spotify Premium is required for in-app playback.",
            failure.userMessage(),
        )
    }
}
