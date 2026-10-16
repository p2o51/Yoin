package com.gpo.yoin.data.remote.applemusic

import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun should_refetch_when_cachedTokenHasLessThanAskedFor() = runTest {
        MockWebServer().use { server ->
            server.start()
            val provider = AppleMusicDeveloperTokenProvider(server.url("/token"), nowEpochSeconds = { 1000 })
            val cached = jwt(1200)
            val fresh = jwt(4600)
            server.enqueue(MockResponse().setBody("""{"developerToken":"$cached"}"""))
            server.enqueue(MockResponse().setBody("""{"developerToken":"$fresh"}"""))
            assertEquals(cached, provider.token())
            // Playback asks for five minutes: 200 s left isn't enough.
            assertEquals(fresh, provider.token(minValiditySeconds = 300))
            assertEquals(fresh, provider.token(minValiditySeconds = 300))
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun should_readExpiry_when_tokenIsAnEs256Jwt() {
        assertEquals(1234L, developerTokenExpiry(jwt(1234)))
        assertNull(developerTokenExpiry("not-a-jwt"))
        assertNull(developerTokenExpiry("a.b.c"))
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

    @Test
    fun should_hideResponseBody_when_tokenServiceReturnsMalformedJson() = runTest {
        MockWebServer().use { server ->
            server.start()
            val provider = AppleMusicDeveloperTokenProvider(server.url("/token"), nowEpochSeconds = { 1000 })
            server.enqueue(MockResponse().setBody("""{"developerToken":"private-token",broken}"""))
            val error = runCatching { provider.token() }.exceptionOrNull()
            assertEquals("Developer token service returned an invalid response", error?.message)
            val causalMessages = generateSequence(error) { it.cause }.take(10).joinToString { it.message.orEmpty() }
            assertFalse(causalMessages.contains("private-token"))
            assertFalse(causalMessages.contains("broken"))
        }
    }

    private fun jwt(exp: Long): String {
        fun encode(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
        return "${encode("""{"alg":"ES256"}""")}.${encode("""{"exp":$exp}""")}.test-signature"
    }
}
