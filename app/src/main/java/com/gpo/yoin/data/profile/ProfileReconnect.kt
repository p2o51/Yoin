package com.gpo.yoin.data.profile

import com.gpo.yoin.data.source.spotify.SpotifyAuthConfig

/**
 * Whether an account whose stored credentials read as these (null: they
 * couldn't be read) needs its owner to sign in again before it works. The one
 * rule behind every "needs reconnect" Yoin shows — Settings' account cards,
 * the setup page's Spotify reconnect, Home's avatar dot and Reconnect
 * ([ProfileManager.activeNeedsReconnect]):
 *  - null — the secret file is gone (a restore from backup doesn't carry it)
 *    or won't decrypt (AndroidKeyStore's key didn't come along:
 *    [SpotifyProviderStatus.ReconnectReason.BackupRestored]);
 *  - Spotify refused the refresh token (`invalid_grant`, kept as
 *    [ProfileCredentials.Spotify.revoked]:
 *    [SpotifyProviderStatus.ReconnectReason.CredentialsRevoked]);
 *  - Spotify's grant predates a scope the app now requires
 *    ([SpotifyAuthConfig.REQUIRED_SCOPES]).
 *
 * Local state only: nothing is asked of any server.
 */
fun ProfileCredentials?.needsReconnect(): Boolean = when (this) {
    null -> true
    is ProfileCredentials.Spotify ->
        revoked || SpotifyAuthConfig.REQUIRED_SCOPES.any { required -> required !in scopes }
    else -> false
}
