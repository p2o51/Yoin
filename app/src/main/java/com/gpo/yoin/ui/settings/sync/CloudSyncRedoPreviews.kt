package com.gpo.yoin.ui.settings.sync

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.transfer.memories.MemoryCatalog
import com.gpo.yoin.data.transfer.memories.MemorySource
import com.gpo.yoin.data.transfer.memories.TrackMatch
import com.gpo.yoin.data.transfer.memories.TransferPlan
import com.gpo.yoin.shared.portability.BackupAccount
import com.gpo.yoin.shared.portability.BackupManifest
import com.gpo.yoin.shared.portability.BackupSnapshotInfo
import com.gpo.yoin.shared.portability.BackupSource
import com.gpo.yoin.shared.portability.ExportSummary
import com.gpo.yoin.shared.portability.ImportAccountPlan
import com.gpo.yoin.shared.portability.ImportCounts
import com.gpo.yoin.shared.portability.ImportPlan
import com.gpo.yoin.shared.portability.ImportProfileOption
import com.gpo.yoin.shared.portability.ImportResult
import com.gpo.yoin.shared.portability.ImportTarget
import com.gpo.yoin.shared.portability.ParsedBackup
import com.gpo.yoin.shared.portability.memories.MatchStatus
import com.gpo.yoin.shared.portability.memories.MemoryItems
import com.gpo.yoin.shared.portability.memories.SourceAlbumRating
import com.gpo.yoin.shared.portability.memories.SourceAlbumTitle
import com.gpo.yoin.shared.portability.memories.SourceMemoryTitle
import com.gpo.yoin.shared.portability.memories.SourceNote
import com.gpo.yoin.shared.portability.memories.SourceRef
import com.gpo.yoin.shared.portability.memories.SourceTrackRating
import com.gpo.yoin.shared.portability.memories.TransferAccount
import com.gpo.yoin.shared.portability.memories.TransferCounts
import com.gpo.yoin.shared.portability.memories.TransferResult
import com.gpo.yoin.shared.sync.SyncEnvelope
import com.gpo.yoin.ui.theme.YoinTheme
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.delay

/*
 * Sample states for the redone Cloud sync pages (the prototype's fictional library: artifacts/sync-proto/data.js),
 * shared by the @Preview functions below, the debug screenshot harness and [PreviewMemoriesOps].
 */
object SyncRedoSamples {
    private const val Day = 24 * 60 * 60 * 1000L

    val navidrome = TransferAccount("s-nav", MediaId.PROVIDER_SUBSONIC, "mika", "music.home.arpa", "nav")
    val spotify = TransferAccount("sp-mika", MediaId.PROVIDER_SPOTIFY, "Mika", null, "sp")
    val apple = TransferAccount("am-cn", MediaId.PROVIDER_APPLE_MUSIC, "Apple Music", null, "am")
    val spotifyOld = TransferAccount("sp-old", MediaId.PROVIDER_SPOTIFY, "mika.old", null, null)
    val appleUs = TransferAccount("am-us", MediaId.PROVIDER_APPLE_MUSIC, "Apple Music", null, null)

    val sources: List<Pair<MemorySource, TransferAccount>> = listOf(
        MemorySource.LocalProfile("nav") to navidrome,
        MemorySource.LocalProfile("sp") to spotify,
        MemorySource.LocalProfile("am") to apple,
        MemorySource.CloudAccount("sp-old") to spotifyOld,
        MemorySource.CloudAccount("am-us") to appleUs,
    )

    fun accounts(): List<TransferAccountUi> = sources.map { (source, account) ->
        TransferAccountUi(
            key = TransferViewModel.keyOf(source),
            source = source,
            account = account,
            counts = CloudSyncSamples.summary.scopes[account.scope],
            cloudOnly = source is MemorySource.CloudAccount,
            fromDevice = CloudSyncSamples.protoCloudOnly.firstOrNull { it.syncProfileId == account.scope }?.fromDeviceName,
        )
    }

    private fun ref(id: String) = SourceRef(MediaId.PROVIDER_SUBSONIC, id)

    private val songs = listOf(
        "t1" to ("分裂" to "伊能静"), // i18n-allow: sample data or a symbol around data
        "t2" to ("Cherry-coloured Funk" to "Cocteau Twins"),
        "t3" to ("Movies" to "Weyes Blood"),
        "t4" to ("关不住" to "伊能静"), // i18n-allow: sample data or a symbol around data
        "t5" to ("Heaven or Las Vegas" to "Cocteau Twins"),
        "t6" to ("当我醒来" to "Tuno桐音"), // i18n-allow: sample data or a symbol around data
        "t7" to ("蜉蝣玉響嗚呼哀哉 (demo)" to "PostmodernHippie"), // i18n-allow: sample data or a symbol around data
        "t8" to ("南北寻光 (Live at 家里)" to "Tuno桐音"), // i18n-allow: sample data or a symbol around data
    )

    fun items(): MemoryItems = MemoryItems(
        songNotes = songs.mapIndexed { i, (id, song) ->
            SourceNote("n$i", ref(id), "副歌前那半拍停顿。", 1L, 2L, song.first, song.second, 72_000L) // i18n-allow: sample data or a symbol around data
        },
        trackRatings = songs.take(5).map { (id, _) -> SourceTrackRating(ref(id), 8.5f, 3L) },
        albumRatings = listOf(SourceAlbumRating(SourceRef(MediaId.PROVIDER_SUBSONIC, "al1"), 9f, 3L)),
        albumTitles = listOf(SourceAlbumTitle(SourceRef(MediaId.PROVIDER_SUBSONIC, "al1"), "关不住的十月", 3L)), // i18n-allow: sample data or a symbol around data
        memoryTitles = listOf(SourceMemoryTitle(MediaId.PROVIDER_SUBSONIC, "album", "al1", "冬天骑车的那几首", null, 3L)), // i18n-allow: sample data or a symbol around data
    )

    fun track(id: String, title: String, artist: String, album: String, seconds: Int) = Track(
        id = MediaId(MediaId.PROVIDER_APPLE_MUSIC, id),
        title = title,
        artist = artist,
        artistId = null,
        album = album,
        albumId = MediaId(MediaId.PROVIDER_APPLE_MUSIC, "alb-$album"),
        coverArt = null,
        durationSec = seconds,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
    )

    private fun sourceTrack(id: String): Track? = songs.firstOrNull { it.first == id }?.second?.let { (title, artist) ->
        Track(MediaId(MediaId.PROVIDER_SUBSONIC, id), title, artist, null, null, null, null, 240, null, null, null, null)
    }

    /** Subsonic → Apple Music before matching: every song needs a counterpart. */
    fun draft(source: TransferAccount = navidrome, target: TransferAccount = apple, previouslyCopied: Int = 0): TransferPlan {
        val crossService = source.provider != target.provider
        return TransferPlan(
            source = source,
            target = target,
            items = items(),
            tracks = if (crossService) {
                songs.map { (id, _) -> TrackMatch(ref(id), sourceTrack(id), MatchStatus.Unmatched, emptyList(), null) }
            } else {
                emptyList()
            },
            albums = emptyList(),
            previouslyCopied = previouslyCopied,
            toCopy = TransferCounts(songNotes = 8),
            ledger = emptyMap(),
        )
    }

    /** After matching: four matched, three unsure, one not found. */
    fun matched(source: TransferAccount = navidrome, target: TransferAccount = apple): TransferPlan {
        val base = draft(source, target)
        fun found(id: String, title: String, artist: String, album: String, s: Int): TrackMatch {
            val t = track("a$id", title, artist, album, s)
            return TrackMatch(ref(id), sourceTrack(id), MatchStatus.Matched, listOf(t), t)
        }
        fun unsure(id: String, vararg candidates: Track) =
            TrackMatch(ref(id), sourceTrack(id), MatchStatus.Uncertain, candidates.toList(), null)
        return base.copy(
            tracks = listOf(
                found("t1", "分裂", "伊能静", "关不住", 228), // i18n-allow: sample data or a symbol around data
                found("t2", "Cherry-coloured Funk", "Cocteau Twins", "Heaven or Las Vegas", 193),
                found("t3", "Movies", "Weyes Blood", "Titanic Rising", 352),
                unsure("t4", track("c41", "关不住", "伊能静", "关不住", 246), track("c42", "关不住 (Live)", "伊能静", "2003 演唱会", 271)), // i18n-allow: sample data or a symbol around data
                unsure(
                    "t5",
                    track("c51", "Heaven or Las Vegas (Remastered 2014)", "Cocteau Twins", "Heaven or Las Vegas", 298),
                    track("c52", "Heaven or Las Vegas", "Cocteau Twins", "Stars and Topsoil", 297),
                ),
                unsure("t6", track("c61", "当我醒来", "Tuno桐音", "南北寻光", 241)), // i18n-allow: sample data or a symbol around data
                TrackMatch(ref("t7"), sourceTrack("t7"), MatchStatus.Unmatched, emptyList(), null),
                found("t8", "南北寻光", "Tuno桐音", "南北寻光", 312), // i18n-allow: sample data or a symbol around data
            ),
        )
    }

    fun result() = TransferResult(
        copied = TransferCounts(songNotes = 7, trackRatings = 5, albumRatings = 1, albumTitles = 1, memoryTitles = 1),
        updated = TransferCounts(),
        kept = TransferCounts(),
        reviewsJoined = 0,
        unmatched = TransferCounts(songNotes = 1),
        skippedPreviously = 12,
    )

    fun transfer(step: TransferStep, sourceKey: String? = "local:nav", targetKey: String? = "local:am"): TransferUiState {
        val accounts = accounts()
        val source = accounts.firstOrNull { it.key == sourceKey }?.account ?: navidrome
        val target = accounts.firstOrNull { it.key == targetKey }?.account ?: apple
        val plan = matched(source, target)
        return TransferUiState(
            step = step,
            firstStep = TransferStep.Source,
            accounts = accounts,
            sourceKey = sourceKey,
            targetKey = targetKey.takeIf { step != TransferStep.Source },
            draft = draft(source, target, previouslyCopied = if (step == TransferStep.Summary) 12 else 0),
            plan = plan,
            rows = reviewRowsOf(plan),
            progressDone = 5,
            progressTotal = 8,
            result = result(),
            cloudSyncOn = true,
        )
    }

    private val manifest = BackupManifest(
        exportedAt = 1_763_818_800_000L,
        source = BackupSource("google-drive", "d1", "Pixel 9 Pro", "0.6.3", 28),
        accounts = listOf(
            BackupAccount("s-nav", MediaId.PROVIDER_SUBSONIC, "mika", hint = "mika @ music.home.arpa", onDevice = true,
                counts = mapOf("song_note" to 128, "album_rating" to 43, "track_rating" to 89), readableDir = "readable/subsonic-mika@music.home.arpa/"),
            BackupAccount("sp-old", MediaId.PROVIDER_SPOTIFY, "mika.old", onDevice = false,
                counts = mapOf("song_note" to 212, "album_rating" to 58, "track_rating" to 176), readableDir = "readable/spotify-mika.old/"),
            BackupAccount("am-us", MediaId.PROVIDER_APPLE_MUSIC, "Apple Music", onDevice = false,
                counts = mapOf("song_note" to 18, "album_review" to 9), readableDir = "readable/applemusic-us/"),
        ),
        globalCounts = mapOf("playlist_backup" to 4, "lyrics_translation" to 212, "song_about" to 37),
        snapshot = BackupSnapshotInfo(sha256 = "3b7c0d5e", bytes = 1_148_213, records = 900, tombstones = 3),
    )

    fun exportSummary() = ExportSummary(manifest, readableFiles = 136, readFailures = emptyList())

    fun importPlan(): ImportPlan = ImportPlan(
        backup = ParsedBackup(
            manifest,
            SyncEnvelope(deviceId = "d1", deviceName = "Pixel 9 Pro", fileClass = "backup", writtenAt = 1L, appVersionCode = 28, records = emptyList()),
            readableFiles = 136,
        ),
        accounts = listOf(
            ImportAccountPlan(manifest.accounts[0], manifest.accounts[0].counts, ImportTarget.IntoProfile("nav"), true,
                listOf(ImportProfileOption("nav", MediaId.PROVIDER_SUBSONIC, "mika")), cloudOnlyPossible = true),
            ImportAccountPlan(manifest.accounts[1], manifest.accounts[1].counts, ImportTarget.CloudOnly, false,
                listOf(ImportProfileOption("sp", MediaId.PROVIDER_SPOTIFY, "Mika")), cloudOnlyPossible = true),
            ImportAccountPlan(manifest.accounts[2], manifest.accounts[2].counts, ImportTarget.Skip, false,
                listOf(ImportProfileOption("am", MediaId.PROVIDER_APPLE_MUSIC, "Apple Music")), cloudOnlyPossible = false, wasPurged = true),
        ),
        globalCounts = manifest.globalCounts,
    )

    fun importReview() = BackupUiState.Review(
        fileName = "yoin-backup-2026-11-22.zip",
        plan = importPlan(),
        choices = importPlan().accounts.associate { it.account.scope to it.target },
        intoCloud = true,
    )

    fun imported() = BackupUiState.Imported(
        importReview(),
        ImportResult(
            byScope = mapOf("s-nav" to ImportCounts(written = 14, unchanged = 240)),
            global = ImportCounts(written = 3, unchanged = 250),
            cloudOnlyMerged = 0,
        ),
    )
}

/** [MemoriesOps] over [SyncRedoSamples] with short pauses, for the debug harness's live flow. Writes nothing. */
class PreviewMemoriesOps : MemoriesOps {
    override fun backupFileName(): String = "yoin-backup-2026-11-22.zip"

    override suspend fun export(out: OutputStream) = SyncRedoSamples.exportSummary().also { delay(1200) }

    override suspend fun readBackup(input: InputStream) = SyncRedoSamples.importPlan().also { delay(900) }

    override suspend fun import(plan: ImportPlan, choices: Map<String, ImportTarget>) =
        SyncRedoSamples.imported().result.also { delay(1200) }

    override suspend fun transferSources() = SyncRedoSamples.sources

    override suspend fun planTransfer(
        source: MemorySource,
        targetProfileId: String,
        catalog: MemoryCatalog?,
        onProgress: (done: Int, total: Int) -> Unit,
    ): TransferPlan {
        val from = SyncRedoSamples.sources.first { it.first == source }.second
        val to = SyncRedoSamples.sources.first { it.second.localProfileId == targetProfileId }.second
        if (catalog == null) {
            delay(400)
            return SyncRedoSamples.draft(from, to, previouslyCopied = if (from.scope == "sp-old") 12 else 0)
        }
        for (i in 1..8) {
            delay(250)
            onProgress(i, 8)
        }
        return SyncRedoSamples.matched(from, to)
    }

    override suspend fun runTransfer(plan: TransferPlan) = SyncRedoSamples.result().also { delay(1500) }

    override suspend fun <T> withCatalog(source: MemorySource, targetProfileId: String, block: suspend (MemoryCatalog) -> T): T =
        block(
            object : MemoryCatalog {
                override suspend fun searchTracks(query: String): List<Track> = emptyList()

                override suspend fun searchAlbums(query: String): List<Album> = emptyList()
            },
        )

    override suspend fun removeProfile(profileId: String) = Unit
}

// ── Previews ─────────────────────────────────────────────────────────

private const val PreviewNow = 1_791_115_200_000L

@Preview(name = "Cloud sync (redo)", showBackground = true, heightDp = 2000)
@Composable
private fun CloudSyncRedoPreview() {
    YoinTheme {
        CloudSyncContent(
            state = CloudSyncSamples.proto(PreviewNow),
            onBackClick = {},
            now = PreviewNow,
            summary = CloudSyncSamples.summary,
            files = CloudSyncSamples.files(PreviewNow),
        )
    }
}

@Preview(name = "All files", showBackground = true, heightDp = 1100)
@Composable
private fun CloudFilesPreview() {
    YoinTheme { CloudFilesPage(CloudDataLoad.Ready(CloudSyncSamples.files(PreviewNow)), PreviewNow, {}, {}, {}) }
}

@Preview(name = "Account cloud data", showBackground = true, heightDp = 1300)
@Composable
private fun CloudAccountPreview() {
    YoinTheme {
        CloudAccountPage(
            account = CloudSyncSamples.protoCloudOnly.first().toRef(),
            content = CloudDataLoad.Ready(CloudSyncSamples.accountContent(PreviewNow)),
            counts = CloudSyncSamples.summary.scopes["sp-old"],
            removing = false,
            now = PreviewNow,
            onBack = {},
            onTransfer = {},
            onRemove = {},
        )
    }
}

@Preview(name = "Global data", showBackground = true, heightDp = 1000)
@Composable
private fun CloudGlobalPreview() {
    YoinTheme { CloudGlobalPage(CloudDataLoad.Ready(CloudSyncSamples.globalContent(PreviewNow)), PreviewNow, {}) }
}

@Preview(name = "Sync location", showBackground = true, heightDp = 1300)
@Composable
private fun SyncLocationPreview() {
    YoinTheme { SyncLocationPage(account = "mika.demo@gmail.com", onBack = {}) }
}

@Preview(name = "Export done", showBackground = true, heightDp = 1500)
@Composable
private fun ExportDonePreview() {
    YoinTheme {
        BackupPage(
            BackupUiState.Exported("yoin-backup-2026-11-22.zip", SyncRedoSamples.exportSummary(), 486 * 1024L),
            {},
            { _, _ -> },
            {},
            {},
        )
    }
}

@Preview(name = "Import review", showBackground = true, heightDp = 1800)
@Composable
private fun ImportReviewPreview() {
    YoinTheme { BackupPage(SyncRedoSamples.importReview(), {}, { _, _ -> }, {}, {}) }
}

@Preview(name = "Import done", showBackground = true, heightDp = 900)
@Composable
private fun ImportDonePreview() {
    YoinTheme { BackupPage(SyncRedoSamples.imported(), {}, { _, _ -> }, {}, {}) }
}

@Preview(name = "Transfer: target", showBackground = true, heightDp = 1300)
@Composable
private fun TransferTargetPreview() {
    YoinTheme { TransferFlow(SyncRedoSamples.transfer(TransferStep.Target, targetKey = null), {}, TransferActions()) }
}

@Preview(name = "Transfer: summary", showBackground = true, heightDp = 2000)
@Composable
private fun TransferSummaryPreview() {
    YoinTheme { TransferFlow(SyncRedoSamples.transfer(TransferStep.Summary), {}, TransferActions()) }
}

@Preview(name = "Transfer: matching", showBackground = true, heightDp = 900)
@Composable
private fun TransferMatchingPreview() {
    YoinTheme { TransferFlow(SyncRedoSamples.transfer(TransferStep.Matching), {}, TransferActions()) }
}

@Preview(name = "Transfer: review", showBackground = true, heightDp = 1600)
@Composable
private fun TransferReviewPreview() {
    YoinTheme { TransferFlow(SyncRedoSamples.transfer(TransferStep.Review), {}, TransferActions()) }
}

@Preview(name = "Transfer: done", showBackground = true, heightDp = 1200)
@Composable
private fun TransferDonePreview() {
    YoinTheme { TransferFlow(SyncRedoSamples.transfer(TransferStep.Done), {}, TransferActions()) }
}
