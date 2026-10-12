package com.gpo.yoin.ui.settings

import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.data.profile.SpotifyProviderStatus
import com.gpo.yoin.data.profile.needsReconnect
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

// The account cards Settings lists and Home's account switcher shows: one
// mapping from a profile to its card (name, service line, avatar shape and
// picture, and why it can't be used right now), so the two never disagree.

/**
 * The accounts as cards, active one marked, avatar shapes distinct. Mapped
 * off the main thread: each card reads its account's stored credentials (a
 * file and a Keystore decrypt), and every Reconnect reads them all again.
 */
internal fun profileCardsFlow(container: AppContainer): Flow<List<ProfileCard>> = combine(
    container.profileManager.profiles,
    container.profileAvatarStore.urls,
    container.profileManager.activeProfileId,
    container.spotifyProviderStatus,
    // Credentials that changed state under an unchanged row (Spotify's grant
    // revoked, a retry reading them again): the cards read them again.
    container.profileManager.credentialsRevision,
) { profiles, avatarUrls, activeId, spotifyStatus, _ ->
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
}.flowOn(Dispatchers.IO)

internal fun Profile.toProfileCard(
    profileManager: ProfileManager,
    activeProfileId: String?,
    spotifyStatus: SpotifyProviderStatus,
    avatarShape: Int,
): ProfileCard {
    val provider = ProviderKind.fromKeyOrSubsonic(provider)
    // Read once, and only for the services whose card says something from
    // them (Subsonic's name, either's "needs its owner"): not Apple Music's.
    val credentials by lazy(LazyThreadSafetyMode.NONE) { profileManager.decodeCredentials(this) }
    // Subsonic: lead with the username, the server's host goes on the
    // service line ("Subsonic · host"). Other services have no "where".
    val subsonic = if (provider == ProviderKind.SUBSONIC) {
        credentials as? ProfileCredentials.Subsonic
    } else {
        null
    }
    val subtitle = subsonic?.let {
        runCatching { URI(it.serverUrl).host }.getOrNull()?.takeIf { host -> host.isNotBlank() }
    }
    val title = subsonic?.username?.takeIf { it.isNotBlank() } ?: displayName
    // Subsonic post-restore recovery: the row's there, its secret isn't
    // (missing file, or a pre-3D inline blob that won't decode). Tapping
    // opens the edit form, to re-enter URL + username + password against the
    // same profile id.
    val needsCredentialsReentry = provider == ProviderKind.SUBSONIC && credentials == null
    // Per-Spotify-profile scope drift (legacy profile missing newly-
    // required scopes) is a static credential check that
    // [SpotifyProviderStatus] doesn't capture — it describes the
    // *runtime* backend. Combine the two: runtime status first (a
    // global blocker like missing client id is more urgent than a
    // per-profile reconnect), then per-profile scope check.
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
            // Unreadable, revoked (`invalid_grant`) or short of a required
            // scope: the OAuth flow again, same profile id, new token.
            credentials.needsReconnect() -> "Reconnect"
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
