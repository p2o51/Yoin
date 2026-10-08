package com.gpo.yoin.ui.settings.sync

import android.content.Intent
import android.content.IntentSender
import android.content.res.Resources
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.gpo.yoin.R
import com.gpo.yoin.data.sync.CloudSyncController
import com.gpo.yoin.data.sync.CloudSyncState
import com.gpo.yoin.data.sync.TurnOnStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** One-shot things the page must do for the ViewModel. */
sealed interface CloudSyncEvent {
    /** Show Google's consent / account sheet (turn on, reconnect). */
    data class LaunchConsent(val intentSender: IntentSender) : CloudSyncEvent

    data class ShowMessage(val message: String) : CloudSyncEvent
}

/**
 * The Cloud sync page's window onto the app-scoped [CloudSyncController].
 * State is the controller's own flow, passed straight through.
 *
 * Suspending controller calls (turn on, delete) run on [actionScope], which
 * outlives this ViewModel: backing out of the page mid-way must not cancel
 * a half-done turn-on or delete. Only the follow-up (a message, the consent
 * sheet) is lost if nobody is listening any more.
 */
class CloudSyncViewModel(
    private val controller: CloudSyncController,
    private val actionScope: CoroutineScope = ControllerCallScope,
    private val resources: Resources? = null,
) : ViewModel() {
    val state: StateFlow<CloudSyncState> = controller.state

    private val _turningOn = MutableStateFlow(false)

    /** Turn on / Reconnect is in flight (waiting for Google or for its sheet). */
    val turningOn: StateFlow<Boolean> = _turningOn.asStateFlow()

    private val _deleting = MutableStateFlow(false)
    val deleting: StateFlow<Boolean> = _deleting.asStateFlow()

    // A Channel, not a SharedFlow: an event that lands while nobody collects
    // (rotation, window resize, page in the background) must wait for the
    // page, not vanish. A lost LaunchConsent would leave turningOn stuck.
    private val _events = Channel<CloudSyncEvent>(Channel.BUFFERED)

    /** Each event is delivered once, to one collector; buffered while the page isn't collecting. */
    val events: Flow<CloudSyncEvent> = _events.receiveAsFlow()

    /** "Turn on with Google" and "Reconnect". */
    fun turnOn() {
        if (_turningOn.value) return
        _turningOn.value = true
        actionScope.launch {
            // A throw is a controller bug, not a user-facing reason: never show its raw text.
            val step = runCatching { controller.beginTurnOn() }
                .getOrElse { TurnOnStep.Failed(turnOnFailedMessage()) }
            handle(step)
        }
    }

    /** The consent sheet's result, from the page's IntentSender launcher. */
    fun onConsentResult(resultCode: Int, data: Intent?) {
        // The activity-result registry reports a sheet it couldn't send as a
        // plain cancel carrying the exception; that one deserves a message.
        val sheetNotShown = data?.hasExtra(SendIntentExceptionExtra) == true
        _turningOn.value = true
        actionScope.launch {
            val step = runCatching { controller.completeTurnOn(resultCode, data) }
                .getOrElse { TurnOnStep.Failed(turnOnFailedMessage()) }
            handle(
                if (sheetNotShown && step == TurnOnStep.Canceled) {
                    TurnOnStep.Failed(turnOnFailedMessage())
                } else {
                    step
                },
            )
        }
    }

    /** The consent sheet couldn't be shown at all. */
    fun onConsentLaunchFailed() {
        _turningOn.value = false
        _events.trySend(CloudSyncEvent.ShowMessage(turnOnFailedMessage()))
    }

    private fun handle(step: TurnOnStep) {
        when (step) {
            // Still turning on: the page shows Google's sheet, its result comes back here.
            is TurnOnStep.NeedsConsent -> _events.trySend(CloudSyncEvent.LaunchConsent(step.intentSender))
            TurnOnStep.Started, TurnOnStep.Canceled -> _turningOn.value = false
            is TurnOnStep.Failed -> {
                _turningOn.value = false
                _events.trySend(CloudSyncEvent.ShowMessage(step.message))
            }
        }
    }

    fun syncNow() = controller.syncNow()

    fun turnOff() = controller.turnOff()

    fun deleteCloudData() {
        if (_deleting.value) return
        _deleting.value = true
        actionScope.launch {
            val result = runCatching { controller.deleteCloudData() }.getOrElse { Result.failure(it) }
            _deleting.value = false
            _events.trySend(
                CloudSyncEvent.ShowMessage(
                    if (result.isSuccess) {
                        resources?.getString(R.string.settings_sync_deleted)
                            ?: "Deleted Yoin's data from your Google Drive"
                    } else {
                        resources?.getString(R.string.settings_sync_delete_failed)
                            ?: "Couldn't delete the cloud data. Check your connection and try again."
                    },
                ),
            )
        }
    }

    fun resolveReview(restore: Boolean) = controller.resolveReview(restore)

    fun uploadAgain() = controller.uploadAgain()

    fun startSyncingAccount(localProfileId: String) = controller.startSyncingAccount(localProfileId)

    fun linkAccount(localProfileId: String, syncProfileId: String) =
        controller.linkAccount(localProfileId, syncProfileId)

    fun keepAccountAsBefore(localProfileId: String) = controller.keepAccountAsBefore(localProfileId)

    fun startFreshAccount(localProfileId: String) = controller.startFreshAccount(localProfileId)

    fun removeDevice(deviceId: String) = controller.removeDevice(deviceId)

    /** A music account was just added from "In your Drive": sync now so its data comes down right away. */
    fun onAccountAdded() {
        if (state.value.enabled) controller.syncNow()
    }

    class Factory(
        private val controller: CloudSyncController,
        private val resources: Resources,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CloudSyncViewModel(controller, resources = resources) as T
    }

    private fun turnOnFailedMessage(): String = resources?.getString(R.string.settings_sync_turn_on_failed)
        ?: "Couldn't reach Google. Try again in a moment." // i18n-allow: CloudSyncViewModelTest asserts this English

    private companion object {

        const val SendIntentExceptionExtra =
            ActivityResultContracts.StartIntentSenderForResult.EXTRA_SEND_INTENT_EXCEPTION

        /** Process-lifetime scope for UI-started controller calls (see the class KDoc). */
        val ControllerCallScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    }
}
