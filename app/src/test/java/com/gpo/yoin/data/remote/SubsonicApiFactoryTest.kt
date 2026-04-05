package com.gpo.yoin.data.remote

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SubsonicApiFactoryTest {
    @Test
    fun should_buildEncodedStreamUrl_when_usernameContainsReservedCharacters() {
        val credentials =
            ServerCredentials(
                serverUrl = "https://music.example.com/navidrome",
                username = "user+demo@example.com",
                password = "secret",
            )

        val streamUrl =
            SubsonicApiFactory.buildStreamUrl(
                credentials,
                "song/123",
                maxBitRate = 320,
            )

        assertTrue(streamUrl.startsWith("https://music.example.com/navidrome/rest/stream.view?"))
        assertTrue(streamUrl.contains("id=song%2F123"))
        assertTrue(streamUrl.contains("u=user%2Bdemo%40example.com"))
        assertTrue(streamUrl.contains("maxBitRate=320"))
        assertTrue(streamUrl.contains("v=1.16.1"))
        assertTrue(streamUrl.contains("c=Yoin"))
        assertTrue(streamUrl.contains("f=json"))
    }

    @Test
    fun should_buildStableCoverArtUrl_when_calledRepeatedlyWithSameCredentials() {
        val credentials =
            ServerCredentials(
                serverUrl = "https://music.example.com/navidrome",
                username = "demo",
                password = "secret",
            )

        val first = SubsonicApiFactory.buildCoverArtUrl(credentials, "cover-123", size = 320)
        val second = SubsonicApiFactory.buildCoverArtUrl(credentials, "cover-123", size = 320)

        assertEquals(first, second)
    }
}
