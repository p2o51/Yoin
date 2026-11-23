package com.gpo.yoin.data.sync.backup

import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.sync.SyncBindingEntity
import com.gpo.yoin.data.sync.SyncDao
import com.gpo.yoin.data.sync.SyncMetaKeys
import com.gpo.yoin.data.sync.identity.AccountFingerprints
import com.gpo.yoin.data.sync.identity.AccountKeys

/**
 * Which scope a local profile's data has in backups and transfers: its sync binding's scope, else the one a
 * binding would get (Subsonic fingerprint, a Spotify user id this device already resolved), else the
 * device-local [BackupFormat.LOCAL_SCOPE_PREFIX] + profile id. No network.
 */
object AccountScopes {
    suspend fun of(profile: Profile, credentials: ProfileCredentials?, dao: SyncDao?): String {
        val binding = dao?.bindingFor(profile.id)
        if (binding != null && binding.state != SyncBindingEntity.STATE_PAUSED_MISMATCH) return binding.syncProfileId
        return derived(profile, credentials, dao)
    }

    suspend fun derived(profile: Profile, credentials: ProfileCredentials?, dao: SyncDao?): String {
        AccountKeys.subsonicFingerprint(credentials)?.let { return AccountFingerprints.deterministicId(it) }
        if (profile.provider == MediaId.PROVIDER_SPOTIFY) {
            val userId = dao?.meta(SyncMetaKeys.SPOTIFY_UID_PREFIX + profile.id)?.substringBefore('|')
            if (!userId.isNullOrEmpty()) return AccountFingerprints.deterministicId(AccountFingerprints.spotify(userId))
        }
        return BackupFormat.LOCAL_SCOPE_PREFIX + profile.id
    }
}
