package com.gpo.yoin.data.sync.backup

import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.sync.SyncAdapter
import com.gpo.yoin.data.sync.SyncDatabase
import com.gpo.yoin.shared.portability.BackupContent
import com.gpo.yoin.shared.portability.SnapshotAssembler

/**
 * Builds a backup's record set from this device's Room data and sync replica. The rules (what the sync
 * engine would publish now, replica versions kept, local edits overlaid, tombstones, cloud-only accounts,
 * device-local scopes) are :shared's [SnapshotAssembler]; this class hands it the app's adapters, replica
 * and profiles.
 */
class SnapshotBuilder(
    private val db: YoinDatabase,
    private val adapters: List<SyncAdapter>,
    private val replica: SyncDatabase?,
    private val profiles: suspend () -> List<Profile>,
    private val decodeCredentials: (Profile) -> ProfileCredentials?,
    private val deviceId: String,
    private val deviceName: String,
    private val appVersionCode: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun build(): BackupContent {
        val now = clock()
        val dao = replica?.syncDao()
        val assembler = SnapshotAssembler(
            adapters = adapters.toShared(),
            replica = dao?.let { RoomBackupReplica(it, engine = null) },
            domain = RoomBackupDomain(db),
            deviceId = deviceId,
            deviceName = deviceName,
            appVersionCode = appVersionCode,
        )
        return assembler.build(syncProfilesOf(profiles(), decodeCredentials, dao), now)
    }
}
