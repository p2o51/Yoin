package com.gpo.yoin.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyTypedFailureMappingTest {

    @Test
    fun authorization_prompt_maps_to_auth_failure_with_user_actionable_copy() {
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
