package com.gpo.yoin.data.source.spotify

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Stateless helper for the Spotify Authorization Code + PKCE flow and the
 * handful of endpoints we hit during B2. All HTTP work runs on the caller's
 * dispatcher — caller is responsible for using [kotlinx.coroutines.withContext]
 * or similar to keep off the main thread.
 */
class SpotifyAuthService(
    private val httpClient: OkHttpClient,
    private val authBaseUrl: HttpUrl = "https://${SpotifyAuthConfig.AUTH_HOST}/".toHttpUrl(),
    private val apiBaseUrl: HttpUrl = "https://${SpotifyAuthConfig.API_HOST}/".toHttpUrl(),
) {

    fun buildAuthUrl(
        codeChallenge: String,
        state: String,
        clientId: String,
        scopes: List<String> = SpotifyAuthConfig.SCOPES,
        redirectUri: String = SpotifyAuthConfig.REDIRECT_URI,
    ): HttpUrl = authBaseUrl.newBuilder()
        .addPathSegment("authorize")
        .addQueryParameter("response_type", "code")
        .addQueryParameter("client_id", clientId)
        .addQueryParameter("redirect_uri", redirectUri)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("code_challenge", codeChallenge)
        .addQueryParameter("state", state)
        .addQueryParameter("scope", scopes.joinToString(" "))
        .build()

    fun exchangeCode(
        code: String,
        verifier: String,
        clientId: String,
        redirectUri: String = SpotifyAuthConfig.REDIRECT_URI,
    ): TokenResponse {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", clientId)
            .add("code_verifier", verifier)
            .build()
        return postToken(form)
    }

    fun refreshToken(
        refreshToken: String,
        clientId: String,
    ): TokenResponse {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .build()
        return postToken(form)
    }

    fun fetchMe(accessToken: String): SpotifyMe {
        val url = apiBaseUrl.newBuilder()
            .addPathSegment("v1")
            .addPathSegment("me")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw SpotifyAuthException(
                    code = response.code,
                    message = "Spotify /me failed: ${response.code} ${response.message}",
                )
            }
            return JSON.decodeFromString(SpotifyMe.serializer(), body)
        }
    }

    private fun postToken(form: FormBody): TokenResponse {
        val url = authBaseUrl.newBuilder()
            .addPathSegment("api")
            .addPathSegment("token")
            .build()
        val request = Request.Builder()
            .url(url)
            .post(form)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                val error = runCatching {
                    JSON.decodeFromString(TokenErrorResponse.serializer(), body)
                }.getOrNull()
                throw SpotifyAuthException(
                    code = response.code,
                    // Preserve the OAuth `error` field so callers can branch
                    // on it without re-parsing the body — `invalid_grant`
                    // indicates a revoked refresh token, distinct from
                    // garden-variety 5xx / network failures.
                    oauthError = error?.error,
                    message = error?.errorDescription
                        ?: error?.error
                        ?: "Spotify token endpoint failed: ${response.code}",
                )
            }
            return JSON.decodeFromString(TokenResponse.serializer(), body)
        }
    }

    companion object {
        // Mirror SpotifyApiClient's JSON config so null-on-default fields
        // (rare in OAuth responses but occurs in refresh edge cases like
        // missing `scope`) don't throw a parse exception mid-auth.
        private val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        private const val VERIFIER_LENGTH = 64
        private const val STATE_LENGTH = 32
        private val VERIFIER_ALPHABET: CharArray =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~".toCharArray()

        /** Generate a fresh PKCE code_verifier + code_challenge pair. */
        fun generatePkce(random: SecureRandom = SecureRandom()): PkcePair {
            val verifier = randomString(VERIFIER_LENGTH, VERIFIER_ALPHABET, random)
            val challenge = s256Challenge(verifier)
            return PkcePair(verifier = verifier, challenge = challenge)
        }

        fun generateState(random: SecureRandom = SecureRandom()): String =
            randomString(STATE_LENGTH, VERIFIER_ALPHABET, random)

        /** S256 transform per RFC 7636 §4.2: BASE64URL-NO-PAD(SHA-256(verifier)). */
        fun s256Challenge(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }

        private fun randomString(length: Int, alphabet: CharArray, random: SecureRandom): String {
            val chars = CharArray(length)
            for (i in 0 until length) {
                chars[i] = alphabet[random.nextInt(alphabet.size)]
            }
            return String(chars)
        }
    }
}

data class PkcePair(val verifier: String, val challenge: String)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresInSec: Long,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val scope: String? = null,
)

@Serializable
internal data class TokenErrorResponse(
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

@Serializable
data class SpotifyMe(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
    val email: String? = null,
)

/**
 * Auth-layer failure surfaced to API / playback callers.
 *
 * @property code HTTP status code, or `0` for client-side preconditions
 *   (e.g. blank client id).
 * @property oauthError The OAuth 2.0 `error` field from the token endpoint
 *   response when present. Use this to distinguish a revoked refresh token
 *   (`"invalid_grant"`) from generic network / server failures without
 *   re-parsing the response body.
 */
class SpotifyAuthException(
    val code: Int,
    message: String,
    val oauthError: String? = null,
) : Exception(message) {
    /** True when the OAuth response identified a dead refresh token. */
    val isRefreshTokenRevoked: Boolean
        get() = oauthError == "invalid_grant"
}
