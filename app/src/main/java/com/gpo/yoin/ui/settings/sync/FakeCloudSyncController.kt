package com.gpo.yoin.ui.settings.sync

import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.sync.CloudAccountRow
import com.gpo.yoin.data.sync.CloudSyncController
import com.gpo.yoin.data.sync.CloudSyncPhase
import com.gpo.yoin.data.sync.CloudSyncState
import com.gpo.yoin.data.sync.GoogleAccountUi
import com.gpo.yoin.data.sync.SyncAccountRow
import com.gpo.yoin.data.sync.SyncAccountStatus
import com.gpo.yoin.data.sync.SyncDeviceRow
import com.gpo.yoin.data.sync.TurnOnStep
import com.gpo.yoin.ui.settings.SettingsSegments
import com.gpo.yoin.ui.theme.YoinTheme
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory [CloudSyncController] for previews, UI tests and the temporary
 * container wiring: every action records itself in [calls] and moves [state]
 * the way the real manager would, without Google or Drive.
 */
class FakeCloudSyncController(
    initial: CloudSyncState = CloudSyncState(),
    private val clock: () -> Long = System::currentTimeMillis,
) : CloudSyncController {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<CloudSyncState> = _state

    /** Names of the actions called, in order, e.g. `resolveReview(true)`. */
    val calls: List<String> get() = _calls
    private val _calls = CopyOnWriteArrayList<String>()

    /** What [beginTurnOn] answers. */
    var beginTurnOnResult: TurnOnStep = TurnOnStep.Started

    /** What [completeTurnOn] answers. */
    var completeTurnOnResult: TurnOnStep = TurnOnStep.Started

    var deleteCloudDataResult: Result<Unit> = Result.success(Unit)

    fun setState(state: CloudSyncState) {
        _state.value = state
    }

    override suspend fun beginTurnOn(): TurnOnStep {
        _calls += "beginTurnOn"
        return beginTurnOnResult.also(::applyTurnOn)
    }

    override suspend fun completeTurnOn(resultCode: Int, data: Intent?): TurnOnStep {
        _calls += "completeTurnOn($resultCode)"
        return completeTurnOnResult.also(::applyTurnOn)
    }

    private fun applyTurnOn(step: TurnOnStep) {
        if (step == TurnOnStep.Started) _state.value = CloudSyncSamples.upToDate(clock())
    }

    override fun syncNow() {
        _calls += "syncNow"
        _state.update {
            it.copy(phase = CloudSyncPhase.UpToDate, lastSyncAt = clock(), pendingChanges = false)
        }
    }

    override fun turnOff() {
        _calls += "turnOff"
        _state.value = CloudSyncState()
    }

    override suspend fun deleteCloudData(): Result<Unit> {
        _calls += "deleteCloudData"
        return deleteCloudDataResult.also { result ->
            if (result.isSuccess) _state.value = CloudSyncState()
        }
    }

    override fun resolveReview(restore: Boolean) {
        _calls += "resolveReview($restore)"
        _state.update { it.copy(phase = CloudSyncPhase.UpToDate, lastSyncAt = clock()) }
    }

    override fun uploadAgain() {
        _calls += "uploadAgain"
        _state.update { it.copy(phase = CloudSyncPhase.UpToDate, lastSyncAt = clock()) }
    }

    override fun startSyncingAccount(localProfileId: String) {
        _calls += "startSyncingAccount($localProfileId)"
        setAccountStatus(localProfileId, SyncAccountStatus.Syncing)
    }

    override fun linkAccount(localProfileId: String, syncProfileId: String) {
        _calls += "linkAccount($localProfileId,$syncProfileId)"
        setAccountStatus(localProfileId, SyncAccountStatus.Syncing)
        _state.update { state ->
            state.copy(cloudOnlyAccounts = state.cloudOnlyAccounts.filterNot { it.syncProfileId == syncProfileId })
        }
    }

    override fun keepAccountAsBefore(localProfileId: String) {
        _calls += "keepAccountAsBefore($localProfileId)"
        setAccountStatus(localProfileId, SyncAccountStatus.Syncing)
    }

    override fun startFreshAccount(localProfileId: String) {
        _calls += "startFreshAccount($localProfileId)"
        setAccountStatus(localProfileId, SyncAccountStatus.Syncing)
    }

    override fun removeDevice(deviceId: String) {
        _calls += "removeDevice($deviceId)"
        _state.update { state -> state.copy(devices = state.devices.filterNot { it.deviceId == deviceId }) }
    }

    private fun setAccountStatus(localProfileId: String, status: SyncAccountStatus) {
        _state.update { state ->
            state.copy(
                accounts = state.accounts.map { row ->
                    if (row.localProfileId == localProfileId) row.copy(status = status) else row
                },
            )
        }
    }
}

/** Sample states for previews, tests and the fake. Times are relative to [now]. */
object CloudSyncSamples {
    private const val Minute = 60_000L
    private const val Hour = 60 * Minute
    private const val Day = 24 * Hour

    val google = GoogleAccountUi(email = "chen@example.com", displayName = "Chen")

    private val appleInDrive = CloudAccountRow(
        syncProfileId = "a-7f3c",
        provider = MediaId.PROVIDER_APPLE_MUSIC,
        displayName = "Apple Music",
        hint = null,
        fromDeviceName = "Pixel 9",
    )

    val cloudOnly = listOf(
        CloudAccountRow(
            syncProfileId = "s-1d2e3f",
            provider = MediaId.PROVIDER_SUBSONIC,
            displayName = "alice",
            hint = "alice @ music.example.com",
            fromDeviceName = "Pixel 9",
        ),
        appleInDrive,
    )

    val accounts = listOf(
        SyncAccountRow("p1", MediaId.PROVIDER_SUBSONIC, "demo", SyncAccountStatus.Syncing),
        SyncAccountRow("p2", MediaId.PROVIDER_SPOTIFY, "Chen", SyncAccountStatus.NeedsIdentity),
        SyncAccountRow(
            "p3",
            MediaId.PROVIDER_APPLE_MUSIC,
            "Apple Music",
            SyncAccountStatus.NotSynced(listOf(appleInDrive)),
        ),
        SyncAccountRow("p4", MediaId.PROVIDER_SUBSONIC, "demo (home)", SyncAccountStatus.DuplicateOf("demo")),
    )

    fun devices(now: Long) = listOf(
        SyncDeviceRow("d-this", "Pixel Tablet", isThisDevice = true, lastSyncAt = now - 2 * Minute),
        SyncDeviceRow("d-phone", "Pixel 9", isThisDevice = false, lastSyncAt = now - 3 * Hour),
        SyncDeviceRow("d-old", "Galaxy S21", isThisDevice = false, lastSyncAt = now - 40 * Day),
    )

    val off = CloudSyncState()

    val unavailable = CloudSyncState(phase = CloudSyncPhase.Unavailable(updateRequired = false))

    val misconfigured = CloudSyncState(
        phase = CloudSyncPhase.Misconfigured(
            packageName = "com.gpo.yoin",
            certSha1 = "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01",
        ),
    )

    val resetElsewhere = CloudSyncState(phase = CloudSyncPhase.ResetElsewhere(deviceName = "Pixel 9"))

    private fun on(now: Long, phase: CloudSyncPhase) = CloudSyncState(
        phase = phase,
        account = google,
        lastSyncAt = now - 2 * Minute,
        accounts = accounts,
        cloudOnlyAccounts = cloudOnly,
        devices = devices(now),
    )

    fun syncing(now: Long) = on(now, CloudSyncPhase.Syncing)

    fun upToDate(now: Long) = on(now, CloudSyncPhase.UpToDate).copy(
        lastSummary = "Restored 128 notes for alice",
    )

    fun pending(now: Long) = on(now, CloudSyncPhase.UpToDate).copy(pendingChanges = true)

    fun offline(now: Long) = on(now, CloudSyncPhase.Offline).copy(pendingChanges = true)

    fun needsReauth(now: Long) = on(now, CloudSyncPhase.NeedsReauth)

    fun needsReview(now: Long) = on(now, CloudSyncPhase.NeedsReview(accountName = "demo", count = 3))

    fun storageFull(now: Long) = on(now, CloudSyncPhase.StorageFull).copy(pendingChanges = true)

    fun cloudCopyRemoved(now: Long) = on(now, CloudSyncPhase.CloudCopyRemoved)

    fun accountChanged(now: Long) = on(now, CloudSyncPhase.UpToDate).copy(
        accounts = listOf(
            SyncAccountRow("p1", MediaId.PROVIDER_SUBSONIC, "demo", SyncAccountStatus.AccountChanged),
            SyncAccountRow("p2", MediaId.PROVIDER_SPOTIFY, "Chen", SyncAccountStatus.NeedsReconnect),
        ),
    )
}

// ── Previews ─────────────────────────────────────────────────────────

/** Fixed clock for previews: 2026-10-04 12:00 UTC. */
private const val PreviewNow = 1_791_115_200_000L

@Composable
private fun CloudSyncPreviewHost(state: CloudSyncState) {
    YoinTheme {
        CloudSyncContent(state = state, onBackClick = {}, now = PreviewNow)
    }
}

@Preview(name = "Off", showBackground = true, backgroundColor = 0xFF1C1B1F, heightDp = 1500)
@Composable
private fun CloudSyncOffPreview() = CloudSyncPreviewHost(CloudSyncSamples.off)

@Preview(name = "Unavailable", showBackground = true, backgroundColor = 0xFF1C1B1F, heightDp = 1400)
@Composable
private fun CloudSyncUnavailablePreview() = CloudSyncPreviewHost(CloudSyncSamples.unavailable)

@Preview(name = "Misconfigured", showBackground = true, backgroundColor = 0xFF1C1B1F, heightDp = 1500)
@Composable
private fun CloudSyncMisconfiguredPreview() = CloudSyncPreviewHost(CloudSyncSamples.misconfigured)

@Preview(name = "Reset elsewhere", showBackground = true, backgroundColor = 0xFF1C1B1F, heightDp = 1500)
@Composable
private fun CloudSyncResetElsewherePreview() = CloudSyncPreviewHost(CloudSyncSamples.resetElsewhere)

@Preview(name = "Syncing", showBackground = true, backgroundColor = 0xFF1C1B1F, heightDp = 1800)
@Composable
private fun CloudSyncSyncingPreview() = CloudSyncPreviewHost(CloudSyncSamples.syncing(PreviewNow))

@Preview(name = "Up to date", showBackground = true, backgroundColor = 0xFF1C1B1F, heightDp = 1800)
@Composable
private fun CloudSyncUpToDatePreview() = CloudSyncPreviewHost(CloudSyncSamples.upToDate(PreviewNow))

@Preview(name = "Needs reauth", showBackground = true, backgroundColor = 0xFF1C1B1F, heightDp = 1800)
@Composable
private fun CloudSyncNeedsReauthPreview() = CloudSyncPreviewHost(CloudSyncSamples.needsReauth(PreviewNow))

@Preview(name = "Needs review", showBackground = true, backgroundColor = 0xFF1C1B1F, heightDp = 1800)
@Composable
private fun CloudSyncNeedsReviewPreview() = CloudSyncPreviewHost(CloudSyncSamples.needsReview(PreviewNow))

@Preview(name = "Storage full", showBackground = true, backgroundColor = 0xFF1C1B1F, heightDp = 1800)
@Composable
private fun CloudSyncStorageFullPreview() = CloudSyncPreviewHost(CloudSyncSamples.storageFull(PreviewNow))

@Preview(name = "Drive copy removed", showBackground = true, backgroundColor = 0xFF1C1B1F, heightDp = 1800)
@Composable
private fun CloudSyncCloudCopyRemovedPreview() = CloudSyncPreviewHost(CloudSyncSamples.cloudCopyRemoved(PreviewNow))

@Preview(name = "Account changed", showBackground = true, backgroundColor = 0xFF1C1B1F, heightDp = 1800)
@Composable
private fun CloudSyncAccountChangedPreview() = CloudSyncPreviewHost(CloudSyncSamples.accountChanged(PreviewNow))

@Preview(name = "Settings row", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun CloudSyncEntryRowPreview() {
    YoinTheme {
        SettingsSegments(modifier = Modifier.padding(16.dp)) {
            listOf(
                CloudSyncSamples.off,
                CloudSyncSamples.upToDate(PreviewNow),
                CloudSyncSamples.syncing(PreviewNow),
                CloudSyncSamples.pending(PreviewNow),
                CloudSyncSamples.needsReauth(PreviewNow),
                CloudSyncSamples.storageFull(PreviewNow),
                CloudSyncSamples.misconfigured,
                CloudSyncSamples.resetElsewhere,
            ).forEach { sample ->
                item { CloudSyncEntryRow(state = sample, onClick = {}, now = PreviewNow) }
            }
        }
    }
}
