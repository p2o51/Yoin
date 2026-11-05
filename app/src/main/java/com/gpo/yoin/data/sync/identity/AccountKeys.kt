package com.gpo.yoin.data.sync.identity

import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.sync.SyncBindingEntity
import com.gpo.yoin.data.sync.SyncDatabase

/**
 * A music account's id as every device knows it: what a global record (a
 * playlist backup) keeps to say which account it came from, since local
 * profile ids differ per device.
 *
 * The sync id of the profile's active binding when it has one (any
 * provider, Apple Music linked by hand included); otherwise, for Subsonic,
 * the id a binding would be minted with ([AccountFingerprints.deterministicId]),
 * worked out from the stored server and username with no network. Null when
 * neither is known (Spotify or Apple Music not syncing): their playlist ids
 * are the service's own and don't repeat across accounts the way Subsonic's
 * "1", "2"… do.
 */
class AccountKeys(
    private val syncDb: () -> SyncDatabase,
    private val decodeCredentials: (Profile) -> ProfileCredentials?,
) {
    suspend fun of(profile: Profile): String? {
        val subsonic = subsonicFingerprint(runCatching { decodeCredentials(profile) }.getOrNull())
        val binding = runCatching { syncDb().syncDao().bindingFor(profile.id) }.getOrNull()
        if (binding != null && bindingStillFits(binding, subsonic)) return binding.syncProfileId
        return subsonic?.let(AccountFingerprints::deterministicId)
    }

    companion object {
        internal fun subsonicFingerprint(credentials: ProfileCredentials?): String? =
            (credentials as? ProfileCredentials.Subsonic)
                ?.let { AccountFingerprints.subsonic(it.serverUrl, it.username) }

        /**
         * An active binding still names the profile's account: a Subsonic profile edited to another server
         * or user while sync was off keeps its binding active until sync next looks, so its fingerprint is
         * checked here rather than trusted.
         */
        internal fun bindingStillFits(binding: SyncBindingEntity, subsonicFingerprint: String?): Boolean {
            if (binding.state != SyncBindingEntity.STATE_ACTIVE) return false
            val bound = binding.fingerprintHash ?: return true
            return subsonicFingerprint == null || AccountFingerprints.fingerprintHash(subsonicFingerprint) == bound
        }
    }
}
