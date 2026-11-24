package com.gpo.yoin.data.sync.backup

import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.sync.SyncAdapter
import com.gpo.yoin.data.sync.SyncDao
import com.gpo.yoin.data.sync.engine.SyncEngine
import com.gpo.yoin.shared.portability.BackupMerger
import com.gpo.yoin.shared.portability.ImportPlan
import com.gpo.yoin.shared.portability.ImportPlanner
import com.gpo.yoin.shared.portability.ImportResult
import com.gpo.yoin.shared.portability.ImportTarget
import com.gpo.yoin.shared.portability.ParsedBackup

/**
 * Imports a backup into this device's Room data. Planning ([ImportPlanner]) and the merge rules
 * ([BackupMerger]: last writer wins, both review texts kept, `mig-` copy ids, never delete, cloud-only
 * through the sync engine) are :shared's; this class hands them the app's adapters, replica and profiles.
 * Importing the same file twice changes nothing the second time.
 */
class BackupImporter(
    private val db: YoinDatabase,
    private val adapters: List<SyncAdapter>,
    private val profiles: suspend () -> List<Profile>,
    private val decodeCredentials: (Profile) -> ProfileCredentials?,
) {
    suspend fun plan(backup: ParsedBackup, replica: SyncDao?): ImportPlan =
        ImportPlanner.plan(backup, syncProfilesOf(profiles(), decodeCredentials, replica), replica?.let { RoomBackupReplica(it, null) })

    /**
     * Applies [plan], with [choices] (scope -> target) overriding its defaults. [replica]/[engine] are the
     * sync replica (null when sync was never on: cloud-only choices are then skipped). Run under the sync
     * lock (CloudSyncManager.withReplica).
     */
    suspend fun apply(
        plan: ImportPlan,
        choices: Map<String, ImportTarget> = emptyMap(),
        replica: SyncDao? = null,
        engine: SyncEngine? = null,
        importGlobal: Boolean = true,
    ): ImportResult = BackupMerger(adapters.toShared(), RoomBackupDomain(db)).apply(
        plan = plan,
        choices = choices,
        profiles = syncProfilesOf(profiles(), decodeCredentials, replica),
        replica = replica?.let { RoomBackupReplica(it, engine) },
        importGlobal = importGlobal,
    )
}
