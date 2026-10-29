package com.gpo.yoin

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.os.ParcelFileDescriptor
import com.gpo.yoin.data.integration.neodb.NeoDbRestoreGuard

/**
 * Auto Backup as before (`fullBackupOnly`: the platform's file backup under res/xml/data_extraction_rules.xml),
 * plus one hook: [onRestoreFinished] tells the next launch that this database came from another phone
 * ([NeoDbRestoreGuard]). No key/value data, so [onBackup] / [onRestore] do nothing.
 */
class YoinBackupAgent : BackupAgent() {
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) =
        Unit

    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit

    override fun onRestoreFinished() {
        runCatching { NeoDbRestoreGuard.markRestored(noBackupFilesDir) }
    }
}
