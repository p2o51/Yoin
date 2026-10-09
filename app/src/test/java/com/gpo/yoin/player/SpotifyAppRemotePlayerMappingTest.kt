package com.gpo.yoin.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyAppRemotePlayerMappingTest {

    @Test
    fun should_extract_embedded_message_when_spotify_wraps_error_as_json() {
        val raw = """{"message":"Explicit user authorization is required to use Spotify. The user has to complete the auth-flow to allow the app to use Spotify on their behalf"}"""

        assertEquals(
            "Explicit user authorization is required to use Spotify. The user has to complete the auth-flow to allow the app to use Spotify on their behalf",
            raw.normalizedSpotifyErrorMessage(),
        )
    }

    @Test
    fun should_map_user_not_authorized_message_to_needs_consent_when_message_is_authorization_prompt() {
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
    fun should_map_user_not_authorized_message_to_premium_when_message_explicitly_mentions_premium() {
        val failure = userNotAuthorizedFailure(
            """{"message":"Spotify Premium is required for this operation"}""",
        )

        assertEquals(SpotifyConnectFailure.PremiumRequired, failure)
    }
}
