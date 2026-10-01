package com.gpo.yoin.data.remote.applemusic

import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AppleMusicDeveloperTokenProviderTest {
    @Test
    fun should_refreshCachedToken_when_nearExpiry() = runTest {
        MockWebServer().use { server ->
            server.start()
            var now = 1000L
            val provider = AppleMusicDeveloperTokenProvider(server.url("/token"), nowEpochSeconds = { now })
            val first = jwt(1200)
            val second = jwt(2000)
            server.enqueue(MockResponse().setBody("""{"developerToken":"$first"}"""))
            assertEquals(first, provider.token())
            assertEquals(first, provider.token())
            assertEquals(1, server.requestCount)
            now = 1150
            server.enqueue(MockResponse().setBody("""{"developerToken":"$second"}"""))
            assertEquals(second, provider.token())
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun should_rejectExpiredToken_when_serviceReturnsStaleJwt() = runTest {
        MockWebServer().use { server ->
            server.start()
            val provider = AppleMusicDeveloperTokenProvider(server.url("/token"), nowEpochSeconds = { 1000 })
            server.enqueue(MockResponse().setBody("""{"developerToken":"${jwt(999)}"}"""))
            try {
                provider.token()
                fail("Expired token must not be used")
            } catch (_: IOException) {
                assertEquals(1, server.requestCount)
            }
        }
    }

    private fun jwt(exp: Long): String {
        fun encode(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
        return "${encode("""{"alg":"ES256"}""")}.${encode("""{"exp":$exp}""")}.test-signature"
    }
}
