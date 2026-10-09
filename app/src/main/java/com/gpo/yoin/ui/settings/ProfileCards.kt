package com.gpo.yoin.ui.settings

import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.data.profile.SpotifyProviderStatus
import com.gpo.yoin.data.source.spotify.SpotifyAuthConfig
import java.net.URI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

// The account cards Settings lists and Home's account switcher shows: one
// mapping from a profile to its card (name, service line, avatar shape and
// picture, and why it can't be used right now), so the two never disagree.

/** The accounts as cards, active one marked, avatar shapes distinct. */
internal fun profileCardsFlow(container: AppContainer): Flow<List<ProfileCard>> = combine(
    container.profileManager.profiles,
    container.profileAvatarStore.urls,
    container.profileManager.activeProfileId,
    container.spotifyProviderStatus,
) { profiles, avatarUrls, activeId, spotifyStatus ->
    val resolvedActiveId = activeId ?: profiles.firstOrNull()?.id
    val avatarShapes = assignAvatarShapes(profiles.map { it.id to it.createdAt })
    profiles.map {
        it.toProfileCard(
            profileManager = container.profileManager,
            activeProfileId = resolvedActiveId,
            spotifyStatus = spotifyStatus,
            avatarShape = avatarShapes[it.id] ?: 0,
        ).copy(photoUrl = avatarUrls[it.id])
    }
}

internal fun Profile.toProfileCard(
    profileManager: ProfileManager,
    activeProfileId: String?,
    spotifyStatus: SpotifyProviderStatus,
    avatarShape: Int,
): ProfileCard {
    val provider = ProviderKind.fromKeyOrSubsonic(provider)
    // Subsonic: lead with the username, the server's host goes on the
    // service line ("Subsonic · host"). Other services have no "where".
    val subsonic = if (provider == ProviderKind.SUBSONIC) {
        profileManager.decodeCredentials(this) as? ProfileCredentials.Subsonic
    } else {
        null
    }
    val subtitle = subsonic?.let {
        runCatching { URI(it.serverUrl).host }.getOrNull()?.takeIf { host -> host.isNotBlank() }
    }
    val title = subsonic?.username?.takeIf { it.isNotBlank() } ?: displayName
    // Per-Spotify-profile scope drift (legacy profile missing newly-
    // required scopes) is a static credential check that
    // [SpotifyProviderStatus] doesn't capture — it describes the
    // *runtime* backend. Combine the two: runtime status first (a
    // global blocker like missing client id is more urgent than a
    // per-profile reconnect), then per-profile scope check.
    val needsCredentialsReentry = profileHasMissingSubsonicCredentials(profileManager)
    val unavailableReason: String? = when (provider) {
        ProviderKind.SPOTIFY -> when {
            spotifyStatus is SpotifyProviderStatus.NoClientId ->
                spotifyStatus.userLabel
            spotifyStatus is SpotifyProviderStatus.SpotifyAppMissing ->
                spotifyStatus.userLabel
            spotifyStatus is SpotifyProviderStatus.NoPremium ->
                spotifyStatus.userLabel
            spotifyStatus is SpotifyProviderStatus.AuthFailure ->
                spotifyStatus.userLabel
            profileRequiresSpotifyReconnect(profileManager) -> "Reconnect"
            else -> null
        }
        ProviderKind.SUBSONIC -> when {
            needsCredentialsReentry -> "Credentials missing"
            else -> null
        }
        else -> null
    }
    return ProfileCard(
        id = id,
        displayName = displayName,
        subtitle = subtitle,
        provider = provider,
        isActive = id == activeProfileId,
        title = title,
        avatarShape = avatarShape,
        unavailableReason = unavailableReason,
        requiresReconnect = provider == ProviderKind.SPOTIFY &&
            unavailableReason == "Reconnect",
        requiresCredentialsReentry = needsCredentialsReentry,
    )
}

private fun Profile.profileRequiresSpotifyReconnect(profileManager: ProfileManager): Boolean {
    if (ProviderKind.fromKeyOrSubsonic(provider) != ProviderKind.SPOTIFY) return false
    val decoded = profileManager.decodeCredentials(this)
    // Three independent reasons a Spotify profile needs the user to redo
    // the OAuth flow:
    //   1. Credentials unavailable: either the Batch 3D file-backed
    //      secret is missing (post-restore / interrupted write) or a
    //      legacy inline blob is corrupt. Recover by running OAuth
    //      again — same profile id, new token.
    //   2. Runtime token revocation — the refresh endpoint last responded
    //      with `error: "invalid_grant"`, persisted as `revoked = true`.
    //   3. Static scope drift — the profile was authorised before the
    //      current `REQUIRED_SCOPES` set existed (e.g. before
    //      `playlist-modify-*` was added). Any missing required scope
    //      means the next API call against that scope will 403.
    if (decoded == null) return true
    val spotify = decoded as? ProfileCredentials.Spotify ?: return false
    if (spotify.revoked) return true
    return SpotifyAuthConfig.REQUIRED_SCOPES.any { required -> required !in spotify.scopes }
}

/**
 * Subsonic analogue to the Spotify recovery case. This covers both
 * post-restore missing files and pre-3D inline blobs that are now
 * corrupt / undecodable. UI surfaces "Credentials missing" and taps
 * into the edit form so the user can re-enter URL + username +
 * password against the same profile id.
 */
private fun Profile.profileHasMissingSubsonicCredentials(profileManager: ProfileManager): Boolean {
    if (ProviderKind.fromKeyOrSubsonic(provider) != ProviderKind.SUBSONIC) return false
    return profileManager.decodeCredentials(this) == null
}
