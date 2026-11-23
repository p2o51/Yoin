package com.gpo.yoin.ui.settings.sync

import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.sync.CloudAccountContent
import com.gpo.yoin.data.sync.CloudAccountRow
import com.gpo.yoin.data.sync.CloudAboutEntry
import com.gpo.yoin.data.sync.CloudAlbumEntry
import com.gpo.yoin.data.sync.CloudDataSummary
import com.gpo.yoin.data.sync.CloudDeviceFiles
import com.gpo.yoin.data.sync.CloudFile
import com.gpo.yoin.data.sync.CloudGlobalContent
import com.gpo.yoin.data.sync.CloudGlobalCounts
import com.gpo.yoin.data.sync.CloudMemoryTitle
import com.gpo.yoin.data.sync.CloudNote
import com.gpo.yoin.data.sync.CloudPlaylistBackupEntry
import com.gpo.yoin.data.sync.CloudScopeCounts
import com.gpo.yoin.data.sync.CloudSongRating
import com.gpo.yoin.data.sync.CloudTranslationEntry
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

    var removeAccountResult: Result<Unit> = Result.success(Unit)

    /** Where [startFreshAccount] leaves the profile (start fresh may be put off, or need a sign-in). */
    var startFreshStatus: SyncAccountStatus = SyncAccountStatus.Syncing

    /** What the read-only 云端数据 views answer. */
    var filesResult: Result<List<CloudDeviceFiles>> = Result.success(CloudSyncSamples.files(clock()))
    var summaryResult: CloudDataSummary = CloudSyncSamples.summary
    var accountContentResult: CloudAccountContent = CloudSyncSamples.accountContent(clock())
    var globalContentResult: CloudGlobalContent = CloudSyncSamples.globalContent(clock())

    fun setState(state: CloudSyncState) {
        _state.value = state
    }

    override suspend fun beginTurnOn(accountEmail: String?): TurnOnStep {
        _calls += if (accountEmail == null) "beginTurnOn" else "beginTurnOn($accountEmail)"
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
        setAccountStatus(localProfileId, startFreshStatus)
    }

    override fun removeDevice(deviceId: String) {
        _calls += "removeDevice($deviceId)"
        _state.update { state -> state.copy(devices = state.devices.filterNot { it.deviceId == deviceId }) }
    }

    override suspend fun removeAccountFromCloud(syncProfileId: String): Result<Unit> {
        _calls += "removeAccountFromCloud($syncProfileId)"
        if (removeAccountResult.isFailure) return removeAccountResult
        _state.update { state ->
            state.copy(
                cloudOnlyAccounts = state.cloudOnlyAccounts.filterNot { it.syncProfileId == syncProfileId },
                accounts = state.accounts.map { row ->
                    if (row.syncProfileId == syncProfileId) row.copy(status = SyncAccountStatus.RemovedFromCloud) else row
                },
            )
        }
        return removeAccountResult
    }

    override suspend fun cloudFiles(): Result<List<CloudDeviceFiles>> {
        return filesResult
    }

    override suspend fun cloudDataSummary(): CloudDataSummary = summaryResult

    override suspend fun cloudAccountContent(syncProfileId: String): CloudAccountContent = accountContentResult

    override suspend fun cloudGlobalContent(): CloudGlobalContent = globalContentResult

    override fun resumeAccountSync(localProfileId: String) {
        _calls += "resumeAccountSync($localProfileId)"
        setAccountStatus(localProfileId, SyncAccountStatus.Syncing)
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
        SyncAccountRow("p1", MediaId.PROVIDER_SUBSONIC, "demo", SyncAccountStatus.Syncing, syncProfileId = "s-9a8b7c"),
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

    /** The prototype's sample (artifacts/sync-proto/data.js): three local accounts, two only in the cloud. */
    val protoCloudOnly = listOf(
        CloudAccountRow("sp-old", MediaId.PROVIDER_SPOTIFY, "mika.old", hint = null, fromDeviceName = "Pixel 7"),
        CloudAccountRow("am-us", MediaId.PROVIDER_APPLE_MUSIC, "Apple Music", hint = null, fromDeviceName = "iPad Air"),
    )

    val protoAccounts = listOf(
        SyncAccountRow("nav", MediaId.PROVIDER_SUBSONIC, "mika", SyncAccountStatus.Syncing, syncProfileId = "s-nav"),
        SyncAccountRow("sp", MediaId.PROVIDER_SPOTIFY, "Mika", SyncAccountStatus.Syncing, syncProfileId = "sp-mika"),
        SyncAccountRow("am", MediaId.PROVIDER_APPLE_MUSIC, "Apple Music", SyncAccountStatus.Syncing, syncProfileId = "am-cn"),
        SyncAccountRow("old", MediaId.PROVIDER_SPOTIFY, "Chen", SyncAccountStatus.RemovedFromCloud, syncProfileId = "sp-chen"),
    )

    val summary = CloudDataSummary(
        scopes = mapOf(
            "s-nav" to CloudScopeCounts(notes = 128, albums = 43, songRatings = 89, memoryTitles = 6, songsWithData = 168),
            "sp-mika" to CloudScopeCounts(notes = 64, albums = 21, songRatings = 140, memoryTitles = 4, songsWithData = 171),
            "am-cn" to CloudScopeCounts(notes = 37, albums = 12, songRatings = 55, memoryTitles = 3, songsWithData = 74),
            "sp-old" to CloudScopeCounts(notes = 212, albums = 58, songRatings = 176, memoryTitles = 9, songsWithData = 230),
            "am-us" to CloudScopeCounts(notes = 18, albums = 9, songRatings = 26, memoryTitles = 2, songsWithData = 40),
            "s-9a8b7c" to CloudScopeCounts(notes = 12, albums = 3, songRatings = 8, memoryTitles = 1, songsWithData = 20),
            "s-1d2e3f" to CloudScopeCounts(notes = 40, albums = 9, songRatings = 31, songsWithData = 52),
        ),
        global = CloudGlobalCounts(playlistBackups = 4, translations = 212, songAbout = 37),
    )

    fun files(now: Long): List<CloudDeviceFiles> = listOf(
        device("7f3a", "Pixel Tablet", true, now - 3 * Minute, listOf(46, 52, 38, 21, 9)),
        device("c21e", "iPad Air", false, now - 125 * Minute, listOf(44, 50, 37, 19, 8)),
        device("0b9d", "Pixel 7", false, now - 99 * Day, listOf(31, 27, 18, 8, 4)),
    )

    private fun device(id: String, name: String, me: Boolean, last: Long, kb: List<Int>) = CloudDeviceFiles(
        deviceId = id,
        deviceName = name,
        isThisDevice = me,
        lastSyncAt = last,
        files = kb.mapIndexed { index, size ->
            if (index == 0) {
                CloudFile("yoin-state-$id.json.gz", size * 1024L, "state", last)
            } else {
                CloudFile("yoin-artifacts-$id-${index - 1}.json.gz", size * 1024L, "artifacts", last)
            }
        },
    )

    fun accountContent(now: Long) = CloudAccountContent(
        notes = listOf(
            CloudNote("n1", "分裂", "伊能静", "副歌前那半拍停顿，像是把一句话咽回去。", 72_000, now - 20 * Day), // i18n-allow: sample data or a symbol around data
            CloudNote("n2", "关不住", "伊能静", "鼓一进来整首歌就亮了，适合冬天早上骑车。", 48_000, now - 26 * Day), // i18n-allow: sample data or a symbol around data
            CloudNote("n3", "Heaven or Las Vegas", "Cocteau Twins", "这里的吉他像水面反光，第一次听是在夜班巴士上。", 201_000, now - 54 * Day), // i18n-allow: sample data or a symbol around data
        ),
        albums = listOf(
            CloudAlbumEntry("al1", "关不住", "伊能静", 9.0f, "写给离开的人，也写给自己。整张专辑的编曲很克制。", now - 41 * Day), // i18n-allow: sample data or a symbol around data
            CloudAlbumEntry("al2", "夏 / 狂熱", "苏打绿", 8.5f, null, now - 90 * Day), // i18n-allow: sample data or a symbol around data
        ),
        songRatings = listOf(
            CloudSongRating("t1", "Andromeda", "Weyes Blood", 9.0f, now - 70 * Day),
            CloudSongRating("t2", "他夏了夏天", "苏打绿", 8.0f, now - 90 * Day), // i18n-allow: sample data or a symbol around data
        ),
        titles = listOf(
            CloudMemoryTitle("冬天骑车的那几首", custom = false, subject = "关不住", updatedAt = now - 10 * Day), // i18n-allow: sample data or a symbol around data
            CloudMemoryTitle("关不住的十月", custom = true, subject = "关不住", updatedAt = now - 40 * Day), // i18n-allow: sample data or a symbol around data
        ),
    )

    fun globalContent(now: Long) = CloudGlobalContent(
        playlistBackups = listOf(
            CloudPlaylistBackupEntry("深夜开车", 42, MediaId.PROVIDER_SPOTIFY, "Mika", now - 21 * Day), // i18n-allow: sample data or a symbol around data
            CloudPlaylistBackupEntry("Navidrome 收藏", 118, MediaId.PROVIDER_SUBSONIC, "mika @ music.home.arpa", now - 34 * Day), // i18n-allow: sample data or a symbol around data
        ),
        translations = listOf(CloudTranslationEntry("Andromeda", "Weyes Blood", "subsonic:tr-1", "zh-Hans")),
        about = listOf(CloudAboutEntry("Andromeda", "Weyes Blood", "这首歌写的是什么？", "把爱情比作仙女座：遥远、需要主动去够…", now - 5 * Day)), // i18n-allow: sample data or a symbol around data
    )

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

    /** The prototype's s1 page: synced accounts, two only in the cloud, one removed from the cloud. */
    fun proto(now: Long) = on(now, CloudSyncPhase.UpToDate).copy(
        account = GoogleAccountUi(email = "mika.demo@gmail.com", displayName = "Mika"),
        accounts = protoAccounts,
        cloudOnlyAccounts = protoCloudOnly,
        lastSummary = null,
    )

    fun accountChanged(now: Long) = on(now, CloudSyncPhase.UpToDate).copy(
        accounts = listOf(
            SyncAccountRow("p1", MediaId.PROVIDER_SUBSONIC, "demo", SyncAccountStatus.AccountChanged, syncProfileId = "s-9a8b7c"),
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
