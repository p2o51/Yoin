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
    const val APP_REMOTE_CONTROL_SCOPE: String = "app-remote-control"
    const val AUTH_HOST: String = "accounts.spotify.com"
    const val API_HOST: String = "api.spotify.com"

    /** Registered in the Spotify Developer Dashboard for this app. */
    const val REDIRECT_URI: String = "yoin://auth/spotify/callback"
    const val REDIRECT_SCHEME: String = "yoin"
    const val REDIRECT_HOST: String = "auth"
    const val REDIRECT_PATH_PREFIX: String = "/spotify/callback"
    const val APP_REMOTE_REDIRECT_URI: String = "yoin://auth/spotify/app-remote"
    const val APP_REMOTE_REDIRECT_PATH_PREFIX: String = "/spotify/app-remote"

    /**
     * Current scope set. Includes App Remote grants (`app-remote-control`
     * and `streaming`) so the Android App Remote SDK can actually control
     * the Spotify app — without these, `SpotifyAppRemote.connect(...)`
     * throws `UserNotAuthorizedException`, which our code surfaces (since
     * the "premium-required" mapping fix) as the generic auth-failure
     * banner.
     *
     * Existing Spotify profiles that were authorised before this scope
     * bump still carry a token minted against the old scope list. They
     * must be re-authorized so Yoin can request App Remote control.
     */
    val SCOPES: List<String> = listOf(
        "user-read-private",
        "user-library-read",
        "user-library-modify",
        "playlist-read-private",
        "playlist-read-collaborative",
        "user-follow-read",
        // Web API PUT/DELETE /me/following?type=artist — the artist page Follow
        // toggle. Without it the read state is correct but the toggle 403s and
        // reverts. NOT in REQUIRED_SCOPES (a single feature, not worth forcing a
        // reconnect on every existing profile); a profile minted before this bump
        // keeps working and just can't follow/unfollow until it next reconnects.
        "user-follow-modify",
        // App Remote control + audio streaming — required for in-app Spotify playback.
        APP_REMOTE_CONTROL_SCOPE,
        "streaming",
        // Playlist write — AddToPlaylistSheet / PlaylistDetail mutations.
        "playlist-modify-private",
        "playlist-modify-public",
        // Web API PUT /me/player/play — required for context-aware playback
        // (playing *from* an album / playlist so Spotify's own UI shows the
        // right "playing from X" context and recommendations work). Without
        // this scope we fall back to App Remote's bare-track-uri path.
        "user-modify-playback-state",
        // Web API GET /me/player/devices — the Now Playing Devices sheet.
        // Playback runs through App Remote, but LISTING Connect devices is a
        // Web API read that 403s without this scope — the sheet sat on
        // "No Spotify devices found" forever. NOT in REQUIRED_SCOPES (one
        // sheet, not worth forcing a reconnect); pre-bump profiles surface a
        // reconnect hint in the sheet instead.
        "user-read-playback-state",
        // Web API GET /me/player/recently-played — sources the home Activities
        // feed from the user's real Spotify listening (album/artist/song
        // context). Deliberately NOT in REQUIRED_SCOPES: a profile minted
        // before this bump keeps working and just falls back to the locally
        // recorded activity feed until it next reconnects.
        "user-read-recently-played",
    )

    /**
     * Minimum scope set the current code depends on. A Spotify profile whose
     * stored token is missing *any* of these should be forced through the
     * reconnect flow — the absence of even one scope silently breaks a whole
     * feature (e.g. no `app-remote-control` = playback fails;
     * no `playlist-modify-*` = add-to-playlist 403s).
     *
     * Keep this a strict subset of [SCOPES]. When [SCOPES] grows, decide
     * explicitly whether the new entry is "nice to have" or "required" —
     * only required scopes belong here.
     */
    val REQUIRED_SCOPES: Set<String> = setOf(
        "user-read-private",
        APP_REMOTE_CONTROL_SCOPE,
        "streaming",
        "playlist-modify-private",
        "playlist-modify-public",
        "user-modify-playback-state",
    )

    /**
     * Dev-time fallback injected via Gradle `buildConfigField`. Empty when
     * the developer hasn't set `spotifyClientId` in `local.properties`; the
     * runtime `spotify_config` Room row takes precedence when non-blank.
     */
    val FALLBACK_CLIENT_ID: String get() = BuildConfig.SPOTIFY_CLIENT_ID
}
