package com.gpo.yoin.data.source.spotify

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyAuthServiceTest {

    private val service = SpotifyAuthService(
        httpClient = OkHttpClient(),
        authBaseUrl = "https://accounts.spotify.com/".toHttpUrl(),
        apiBaseUrl = "https://api.spotify.com/".toHttpUrl(),
    )

    @Test
    fun should_compute_known_s256_challenge_when_verifier_is_rfc7636_example() {
        // RFC 7636 §4.2 — the canonical test vector every PKCE impl is
        // supposed to agree on.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expected = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        assertEquals(expected, SpotifyAuthService.s256Challenge(verifier))
    }

    @Test
    fun should_generate_pkce_verifier_of_allowed_length_and_alphabet() {
        val pair = SpotifyAuthService.generatePkce()
        assertEquals(64, pair.verifier.length)
        val allowed = Regex("^[A-Za-z0-9\\-._~]+$")
        assertTrue("verifier must match unreserved set", allowed.matches(pair.verifier))
        // Challenge must round-trip through s256
        assertEquals(SpotifyAuthService.s256Challenge(pair.verifier), pair.challenge)
    }

    @Test
    fun should_build_auth_url_with_required_pkce_params() {
        val url = service.buildAuthUrl(
            codeChallenge = "challenge-xyz",
            state = "state-abc",
            clientId = "client-123",
            scopes = listOf("user-read-private", "user-library-read"),
            redirectUri = "yoin://auth/spotify/callback",
        )

        assertEquals("accounts.spotify.com", url.host)
        assertEquals("/authorize", url.encodedPath)
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("client-123", url.queryParameter("client_id"))
        assertEquals("yoin://auth/spotify/callback", url.queryParameter("redirect_uri"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("challenge-xyz", url.queryParameter("code_challenge"))
        assertEquals("state-abc", url.queryParameter("state"))
        assertEquals("user-read-private user-library-read", url.queryParameter("scope"))
    }

    @Test
    fun should_generate_non_blank_state_nonce() {
        val s1 = SpotifyAuthService.generateState()
        val s2 = SpotifyAuthService.generateState()
        assertNotNull(s1)
        assertTrue(s1.length >= 16)
        assertTrue("state nonces should differ across calls", s1 != s2)
    }
}
