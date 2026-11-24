package com.gpo.yoin.data.sync.backup

import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.sync.SyncDao
import com.gpo.yoin.shared.portability.BackupScopes

/**
 * Which scope a local profile's data has in backups and transfers ([BackupScopes], :shared): its sync
 * binding's scope, else the one a binding would get (Subsonic fingerprint, a Spotify user id this device
 * already resolved), else the device-local `local-` + profile id. No network.
 */
object AccountScopes {
    suspend fun of(profile: Profile, credentials: ProfileCredentials?, dao: SyncDao?): String =
        BackupScopes.of(syncProfileOf(profile, credentials, dao), dao?.let { RoomBackupReplica(it, engine = null) })

    suspend fun derived(profile: Profile, credentials: ProfileCredentials?, dao: SyncDao?): String =
        BackupScopes.derived(syncProfileOf(profile, credentials, dao))
}
