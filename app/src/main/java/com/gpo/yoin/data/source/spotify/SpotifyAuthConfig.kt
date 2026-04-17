package com.gpo.yoin.data.source.spotify

import com.gpo.yoin.BuildConfig

/**
 * Compile-time Spotify constants. Client ID is intentionally *not* a static
 * const — users configure it at runtime via Settings → Spotify (stored in
 * `spotify_config` Room table). Callers should resolve client id through
 * `AppContainer.spotifyClientIdFlow`; [FALLBACK_CLIENT_ID] is a dev-time
 * seed only, read from `spotifyClientId` in `local.properties`.
 */
object SpotifyAuthConfig {
    const val AUTH_HOST: String = "accounts.spotify.com"
    const val API_HOST: String = "api.spotify.com"

    /** Registered in the Spotify Developer Dashboard for this app. */
    const val REDIRECT_URI: String = "yoin://auth/spotify/callback"
    const val REDIRECT_SCHEME: String = "yoin"
    const val REDIRECT_HOST: String = "auth"
    const val REDIRECT_PATH_PREFIX: String = "/spotify/callback"

    /**
     * B2 scope set: only what `/me` needs. Phase 2 adds incremental scopes as
     * features land — Spotify's consent dialog handles the delta cleanly.
     */
    val SCOPES: List<String> = listOf("user-read-private")

    /**
     * Dev-time fallback injected via Gradle `buildConfigField`. Empty when
     * the developer hasn't set `spotifyClientId` in `local.properties`; the
     * runtime `spotify_config` Room row takes precedence when non-blank.
     */
    val FALLBACK_CLIENT_ID: String get() = BuildConfig.SPOTIFY_CLIENT_ID
}
