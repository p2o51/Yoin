package com.gpo.yoin.data.integration.neodb

import java.io.File

/**
 * A database restored from Android backup / device transfer arrives with the old phone's NeoDB dirty flags
 * (`album_ratings.*NeedsSync`): the old phone may still push those rows itself, and this one would push the
 * same snapshot again later, over anything newer written on NeoDB since. The flags belong to the device that
 * made the edit, so a restore drops them.
 *
 * [YoinBackupAgent][com.gpo.yoin.YoinBackupAgent] leaves a marker in `noBackupFilesDir` (never backed up, so
 * it can only come from this device's restore); the next launch clears the flags and then the marker.
 */
internal object NeoDbRestoreGuard {
    private const val MARKER = "neodb/restored_pending_clear"

    fun marker(noBackupDir: File): File = File(noBackupDir, MARKER)

    /** A backup restore just finished on this device. */
    fun markRestored(noBackupDir: File) {
        val marker = marker(noBackupDir)
        marker.parentFile?.mkdirs()
        marker.createNewFile()
    }

    /**
     * After a restore, [clearPending] drops every NeoDB dirty flag; the marker goes only once that succeeded,
     * so a failed clear is tried again next launch. No marker: nothing happens.
     */
    suspend fun clearIfRestored(noBackupDir: File, clearPending: suspend () -> Unit) {
        val marker = marker(noBackupDir)
        if (!marker.exists()) return
        clearPending()
        marker.delete()
    }
}
