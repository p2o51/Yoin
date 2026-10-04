package com.gpo.yoin.data.sync

import android.content.Intent
import android.content.IntentSender
import kotlinx.coroutines.flow.StateFlow

/**
 * What the Cloud sync UI can see and do. Implemented by the app-scoped
 * CloudSyncManager; previews/tests use fakes. All actions are safe to call
 * from the main thread: they return immediately or suspend off-main.
 */
interface CloudSyncController {
    val state: StateFlow<CloudSyncState>

    /** Turn on (or reconnect): may need the user's consent through [TurnOnStep.NeedsConsent]. */
    suspend fun beginTurnOn(): TurnOnStep

    /** Feed the result of the consent IntentSender back. */
    suspend fun completeTurnOn(resultCode: Int, data: Intent?): TurnOnStep

    fun syncNow()

    /** This device only: stop syncing, keep local data, the replica and the Drive copy. */
    fun turnOff()

    /** All devices: delete Yoin's Drive data, disconnect, turn off. Local data stays. */
    suspend fun deleteCloudData(): Result<Unit>

    /** Held mass-delete review: true = restore the items here, false = delete them on all devices. */
    fun resolveReview(restore: Boolean)

    /** Our Drive copy vanished (e.g. "Delete hidden app data" in Drive settings): re-upload from this device. */
    fun uploadAgain()

    fun startSyncingAccount(localProfileId: String)

    fun linkAccount(localProfileId: String, syncProfileId: String)

    /** Fingerprint changed (new server address / re-sign-in): keep syncing into the same Drive data. */
    fun keepAccountAsBefore(localProfileId: String)

    /** Fingerprint changed to a different account: drop the previous account's synced items here and sync as the new one. */
    fun startFreshAccount(localProfileId: String)

    /** Delete another device's files from Drive (it re-uploads if it is still alive). */
    fun removeDevice(deviceId: String)
}

sealed interface TurnOnStep {
    data class NeedsConsent(val intentSender: IntentSender) : TurnOnStep

    /** Enabled; the first sync is running. */
    data object Started : TurnOnStep

    /** User backed out of the Google sheet: nothing changes, no message. */
    data object Canceled : TurnOnStep

    data class Failed(val message: String) : TurnOnStep
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
