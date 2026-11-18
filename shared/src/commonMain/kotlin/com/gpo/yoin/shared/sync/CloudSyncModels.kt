package com.gpo.yoin.shared.sync

/*
 * What the Cloud sync UI shows. Same shapes as Android's
 * `data/sync/CloudSyncController.kt` (minus the Android-only TurnOnStep /
 * IntentSender), so both platforms describe one sync the same way.
 */

/** Provider tokens, as Android's MediaId. */
object SyncProviders {
    const val SUBSONIC = "subsonic"
    const val SPOTIFY = "spotify"
    const val APPLE_MUSIC = "applemusic"
}

data class CloudSyncState(
    val phase: CloudSyncPhase = CloudSyncPhase.Off,
    val account: GoogleAccountUi? = null,
    val lastSyncAt: Long? = null,
    /** Local changes not yet uploaded. */
    val pendingChanges: Boolean = false,
    val accounts: List<SyncAccountRow> = emptyList(),
    val cloudOnlyAccounts: List<CloudAccountRow> = emptyList(),
    val devices: List<SyncDeviceRow> = emptyList(),
    /** One-line result of the last notable sync, e.g. "Restored 128 notes for alice". */
    val lastSummary: String? = null,
    /**
     * iOS safety gate: this device has only pulled from Drive so far (nothing written). It turns
     * two-way after one successful pull + local mapping + backup (CloudSyncCore.readOnlyFirstPull).
     */
    val readOnly: Boolean = false,
    /** Folder name of the newest local backup of the appDataFolder, if any. */
    val lastBackup: String? = null,
) {
    val enabled: Boolean get() = phase.enabled
}

data class GoogleAccountUi(val email: String?, val displayName: String?)

sealed interface CloudSyncPhase {
    val enabled: Boolean

    // ---- Off states
    data object Off : CloudSyncPhase {
        override val enabled = false
    }

    /** No (or too old) Google Play services. */
    data class Unavailable(val updateRequired: Boolean) : CloudSyncPhase {
        override val enabled = false
    }

    /** OAuth client / Drive API not set up for this build; [packageName] + [certSha1] for the details row. */
    data class Misconfigured(val packageName: String, val certSha1: String?) : CloudSyncPhase {
        override val enabled = false
    }

    /** Another device deleted the Drive data; this device turned itself off. */
    data class ResetElsewhere(val deviceName: String?) : CloudSyncPhase {
        override val enabled = false
    }

    // ---- On states
    data object Syncing : CloudSyncPhase {
        override val enabled = true
    }

    data object UpToDate : CloudSyncPhase {
        override val enabled = true
    }

    /** Couldn't reach Google; changes wait on this device. */
    data object Offline : CloudSyncPhase {
        override val enabled = true
    }

    data object NeedsReauth : CloudSyncPhase {
        override val enabled = true
    }

    data object StorageFull : CloudSyncPhase {
        override val enabled = true
    }

    /** Mass delete held for confirmation. */
    data class NeedsReview(val accountName: String, val count: Int) : CloudSyncPhase {
        override val enabled = true
    }

    /** Our own Drive copy disappeared without a reset marker. */
    data object CloudCopyRemoved : CloudSyncPhase {
        override val enabled = true
    }

    /** A device with a newer Yoin wrote data this version can't read; other data keeps syncing. */
    data class UpdateRequired(val deviceName: String?) : CloudSyncPhase {
        override val enabled = true
    }

    data class Error(val message: String) : CloudSyncPhase {
        override val enabled = true
    }
}

/** A local music account and how it syncs. */
data class SyncAccountRow(
    val localProfileId: String,
    val provider: String,
    val displayName: String,
    val status: SyncAccountStatus,
)

sealed interface SyncAccountStatus {
    data object Syncing : SyncAccountStatus

    /** Spotify account id not known yet (token expired): opening the account once fixes it. */
    data object NeedsIdentity : SyncAccountStatus

    /** Credentials missing on this device (e.g. restored backup). */
    data object NeedsReconnect : SyncAccountStatus

    data class DuplicateOf(val otherDisplayName: String) : SyncAccountStatus

    /** Apple Music: only syncs after an explicit choice. [linkCandidates] = unbound Apple accounts in Drive. */
    data class NotSynced(val linkCandidates: List<CloudAccountRow>) : SyncAccountStatus

    /** Signed in as a different account than the one this profile synced as. */
    data object AccountChanged : SyncAccountStatus
}

/** An account whose data is in Drive but not connected on this device. */
data class CloudAccountRow(
    val syncProfileId: String,
    val provider: String,
    val displayName: String,
    /** "alice @ music.example.com" for Subsonic; null otherwise. */
    val hint: String?,
    val fromDeviceName: String?,
)

data class SyncDeviceRow(
    val deviceId: String,
    val name: String,
    val isThisDevice: Boolean,
    val lastSyncAt: Long?,
)

/** Swift-friendly names for [CloudSyncPhase] (sealed classes arrive as subclasses there). */
val CloudSyncPhase.wireName: String
    get() = when (this) {
        CloudSyncPhase.Off -> "off"
        is CloudSyncPhase.Unavailable -> "unavailable"
        is CloudSyncPhase.Misconfigured -> "misconfigured"
        is CloudSyncPhase.ResetElsewhere -> "reset_elsewhere"
        CloudSyncPhase.Syncing -> "syncing"
        CloudSyncPhase.UpToDate -> "up_to_date"
        CloudSyncPhase.Offline -> "offline"
        CloudSyncPhase.NeedsReauth -> "needs_reauth"
        CloudSyncPhase.StorageFull -> "storage_full"
        is CloudSyncPhase.NeedsReview -> "needs_review"
        CloudSyncPhase.CloudCopyRemoved -> "cloud_copy_removed"
        is CloudSyncPhase.UpdateRequired -> "update_required"
        is CloudSyncPhase.Error -> "error"
    }

/** One appDataFolder file as backed up before this device first writes to Drive. */
class SyncBackupFile(
    val id: String,
    val name: String,
    val version: Long,
    val modifiedTime: Long?,
    val size: Long?,
    val appProperties: Map<String, String>,
    /** Raw bytes as downloaded (gzip or JSON); null when the file vanished mid-backup. */
    val bytes: ByteArray?,
)

/** Writes a backup set somewhere safe; returns the folder name, or null when it failed. */
interface SyncBackupSink {
    fun save(files: List<SyncBackupFile>, manifestJson: String): String?
}
