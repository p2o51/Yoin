package com.gpo.yoin.data.sync.identity

import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.sync.SyncMetaKeys
import com.gpo.yoin.data.sync.testing.SyncDomainFixtures
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpotifyIdentityResolverTest {
    private val fixtures = SyncDomainFixtures()
    private val server = MockWebServer()
    private val now = 1_000_000L
    private lateinit var resolver: SpotifyIdentityResolver

    @Before
    fun setUp() {
        server.start()
        resolver = SpotifyIdentityResolver(
            syncDb = fixtures.syncDb,
            httpClient = OkHttpClient(),
            baseUrl = server.url("/v1/").toString(),
            clock = { now },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        fixtures.close()
    }

    @Test
    fun should_fetchAndCacheUserId_when_tokenIsFresh() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"alice_123","display_name":"Alice"}"""))

        val first = resolver.userId("p1", credentials(refreshToken = "rt-1"))
        val second = resolver.userId("p1", credentials(refreshToken = "rt-1"))

        assertEquals("alice_123", first)
        assertEquals("alice_123", second)
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("/v1/me", request.path)
        assertEquals("Bearer at-1", request.getHeader("Authorization"))
        val cached = fixtures.syncDb.syncDao().meta(SyncMetaKeys.SPOTIFY_UID_PREFIX + "p1")
        assertEquals("alice_123|" + SpotifyIdentityResolver.refreshTokenHash("rt-1"), cached)
    }

    @Test
    fun should_refetch_when_refreshTokenChanged() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"alice"}"""))
        server.enqueue(MockResponse().setBody("""{"id":"bob"}"""))

        resolver.userId("p1", credentials(refreshToken = "rt-1"))
        val afterReconnect = resolver.userId("p1", credentials(refreshToken = "rt-2"))

        assertEquals("bob", afterReconnect)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun should_returnNullWithoutCalling_when_tokenExpiresWithinAMinute() = runTest {
        val result = resolver.userId("p1", credentials(expiresAt = now + 30_000L))

        assertNull(result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun should_returnNullWithoutCalling_when_credentialsRevoked() = runTest {
        assertNull(resolver.userId("p1", credentials().copy(revoked = true)))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun should_returnNullAndNotCache_when_serverRejects() = runTest {
        for (code in listOf(401, 403, 429, 500)) {
            server.enqueue(MockResponse().setResponseCode(code))
            assertNull("HTTP $code", resolver.userId("p1", credentials()))
        }
        assertNull(fixtures.syncDb.syncDao().meta(SyncMetaKeys.SPOTIFY_UID_PREFIX + "p1"))
    }

    @Test
    fun should_returnNull_when_networkFails() = runTest {
        val unreachable = SpotifyIdentityResolver(
            syncDb = fixtures.syncDb,
            httpClient = OkHttpClient(),
            baseUrl = "http://127.0.0.1:1/v1/",
            clock = { now },
        )

        assertNull(unreachable.userId("p1", credentials()))
    }

    private fun credentials(refreshToken: String = "rt-1", expiresAt: Long = now + 3_600_000L) =
        ProfileCredentials.Spotify(
            accessToken = "at-1",
            refreshToken = refreshToken,
            expiresAtEpochMs = expiresAt,
            scopes = emptyList(),
        )
}
