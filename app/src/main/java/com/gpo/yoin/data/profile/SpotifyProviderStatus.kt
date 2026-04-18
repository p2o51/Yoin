package com.gpo.yoin.data.profile

/**
 * Runtime status of the active Spotify backend.
 *
 * This is **not** a [com.gpo.yoin.data.source.Capability]: capabilities are
 * static declarations about what a provider can do at all; status captures
 * dynamic "can it do it *right now*" signals — missing client id, Spotify
 * app not installed, user isn't Premium, auth revoked.
 *
 * UI components read this through
 * [com.gpo.yoin.AppContainer.spotifyProviderStatus] and should not
 * independently derive equivalent flags from `spotifyClientIdFlow` +
 * `PlaybackEvent.SpotifyConnectError` — the container is the single source
 * of truth.
 */
sealed interface SpotifyProviderStatus {
    /** Everything looks good from the signals we've observed. */
    data object Ready : SpotifyProviderStatus

    /**
     * The Spotify Developer Client ID is blank. Global, not per-profile —
     * one ID is shared by all Spotify profiles. UI should deep-link to
     * Settings → Spotify to fix.
     */
    data object NoClientId : SpotifyProviderStatus

    /**
     * Spotify for Android is not installed on the device. App Remote
     * cannot negotiate without it. UI should offer a Play Store link.
     */
    data object SpotifyAppMissing : SpotifyProviderStatus

    /**
     * App Remote rejected the handshake with a "Premium required" hint.
     * In-app Spotify playback is a Premium feature; free accounts can
     * browse but not control playback.
     */
    data object NoPremium : SpotifyProviderStatus

    /**
     * Authentication failed for a reason we can't cleanly categorise
     * (revoked token, missing scope observed at runtime, etc). Carries
     * the underlying message for snackbar / dialog surfaces.
     */
    data class AuthFailure(val message: String) : SpotifyProviderStatus

    /** Short label for badges / buttons. */
    val userLabel: String
        get() = when (this) {
            Ready -> "Ready"
            NoClientId -> "No Client ID"
            SpotifyAppMissing -> "Install Spotify"
            NoPremium -> "Premium required"
            is AuthFailure -> "Reconnect"
        }

    /** True when UI should gate Spotify-requiring affordances. */
    val blocksPlayback: Boolean get() = this !is Ready
}
