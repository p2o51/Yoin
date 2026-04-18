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
    fun should_map_user_not_authorized_message_to_auth_failure_when_message_is_authorization_prompt() {
        val failure = userNotAuthorizedFailure(
            """{"message":"Explicit user authorization is required to use Spotify. The user has to complete the auth-flow to allow the app to use Spotify on their behalf"}""",
        )

        assertTrue(failure is SpotifyConnectFailure.AuthFailure)
        assertEquals(
            "Spotify needs permission in the Spotify app. Open Spotify, approve access if prompted, and try again.",
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
