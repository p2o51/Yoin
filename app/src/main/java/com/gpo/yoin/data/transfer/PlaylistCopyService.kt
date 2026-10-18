package com.gpo.yoin.data.transfer

import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.ServiceFeatureCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** A profile a playlist can be copied to. */
data class PlaylistCopyTarget(
    val profileId: String,
    val displayName: String,
    val provider: String,
)

/**
 * The playlist page's "Copy to…": the active profile's playlist into another
 * profile of this install, through [PlaylistTransfer]
 * (docs/playlist-transfer.md). The target's source is built for the copy and
 * disposed after it; the active profile never changes.
 */
class PlaylistCopyService(
    private val profileManager: ProfileManager,
    private val transfer: PlaylistTransfer = PlaylistTransfer(),
) {
    /** Every profile but the active one whose service takes a copied playlist. */
    fun targets(): Flow<List<PlaylistCopyTarget>> =
        combine(profileManager.profiles, profileManager.activeProfileId) { profiles, activeId ->
            copyTargets(profiles, activeId)
        }

    /**
     * Reads [playlistId] afresh from the active profile (a cached copy may
     * predate its ISRCs) and copies it to profile [targetProfileId].
     */
    suspend fun copy(
        playlistId: MediaId,
        targetProfileId: String,
        onProgress: (done: Int, total: Int) -> Unit,
    ): TransferReport {
        val activeId = checkNotNull(profileManager.activeProfileId.value) { "No active profile" }
        require(activeId != targetProfileId) { "A playlist is copied to another profile" }
        val playlist = profileManager.withSourceFor(activeId) { it.library().getPlaylist(playlistId) }
            ?: throw IllegalStateException("Playlist not found")
        return profileManager.withSourceFor(targetProfileId) { target ->
            transfer.copy(playlist, target, onProgress)
        } ?: throw IllegalStateException("Profile unavailable")
    }

    companion object {
        internal fun copyTargets(profiles: List<Profile>, activeId: String?): List<PlaylistCopyTarget> =
            profiles
                .filter { it.id != activeId }
                .filter { Capability.PLAYLIST_IMPORT in ServiceFeatureCatalog.forProvider(it.provider).capabilities }
                .map { PlaylistCopyTarget(it.id, it.displayName, it.provider) }
    }
}
