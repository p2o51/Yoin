package com.gpo.yoin.ui.settings.sync

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.transfer.memories.MemoryCatalog
import com.gpo.yoin.data.transfer.memories.MemorySource
import com.gpo.yoin.data.transfer.memories.TransferPlan
import com.gpo.yoin.shared.portability.BackupAccount
import com.gpo.yoin.shared.portability.BackupManifest
import com.gpo.yoin.shared.portability.BackupSnapshotInfo
import com.gpo.yoin.shared.portability.BackupSource
import com.gpo.yoin.shared.portability.ExportSummary
import com.gpo.yoin.shared.portability.ImportAccountPlan
import com.gpo.yoin.shared.portability.ImportPlan
import com.gpo.yoin.shared.portability.ImportProfileOption
import com.gpo.yoin.shared.portability.ImportResult
import com.gpo.yoin.shared.portability.ImportTarget
import com.gpo.yoin.shared.portability.ParsedBackup
import com.gpo.yoin.shared.portability.memories.MemoryItems
import com.gpo.yoin.shared.portability.memories.SourceNote
import com.gpo.yoin.shared.portability.memories.SourceRef
import com.gpo.yoin.shared.portability.memories.TransferAccount
import com.gpo.yoin.shared.portability.memories.TransferCounts
import com.gpo.yoin.shared.portability.memories.TransferResult
import com.gpo.yoin.shared.sync.SyncEnvelope
import java.io.InputStream
import java.io.OutputStream

/** In-memory [MemoriesOps]: answers what each test sets, records what was asked. */
class FakeMemoriesOps : MemoriesOps {
    val calls = mutableListOf<String>()

    var exportResult: Result<ExportSummary> = Result.success(TransferSamples.exportSummary())
    var readResult: Result<ImportPlan> = Result.success(TransferSamples.importPlan())
    var importResult: Result<ImportResult> = Result.success(ImportResult(emptyMap(), com.gpo.yoin.shared.portability.ImportCounts(), 0))
    var importedWith: Map<String, ImportTarget>? = null

    var sources: List<Pair<MemorySource, TransferAccount>> = TransferSamples.sources
    var draft: (MemorySource, String) -> TransferPlan = { source, target -> TransferSamples.directPlan(source, target) }
    var matched: (MemorySource, String) -> TransferPlan = { source, target -> TransferSamples.matchedPlan(source, target) }
    var catalogAvailable = true
    var runResult: Result<TransferResult> = Result.success(TransferSamples.result())
    var ranWith: TransferPlan? = null
    val removedProfiles = mutableListOf<String>()

    override fun backupFileName(): String = "yoin-backup-2026-11-23.zip"

    override suspend fun export(out: OutputStream): ExportSummary {
        calls += "export"
        return exportResult.getOrThrow()
    }

    override suspend fun readBackup(input: InputStream): ImportPlan {
        calls += "readBackup"
        return readResult.getOrThrow()
    }

    override suspend fun import(plan: ImportPlan, choices: Map<String, ImportTarget>): ImportResult {
        calls += "import"
        importedWith = choices
        return importResult.getOrThrow()
    }

    override suspend fun transferSources(): List<Pair<MemorySource, TransferAccount>> = sources

    override suspend fun planTransfer(
        source: MemorySource,
        targetProfileId: String,
        catalog: MemoryCatalog?,
        onProgress: (done: Int, total: Int) -> Unit,
    ): TransferPlan {
        calls += if (catalog == null) "draft" else "match"
        if (catalog == null) return draft(source, targetProfileId)
        onProgress(1, 2)
        onProgress(2, 2)
        return matched(source, targetProfileId)
    }

    override suspend fun runTransfer(plan: TransferPlan): TransferResult {
        calls += "run"
        ranWith = plan
        return runResult.getOrThrow()
    }

    override suspend fun <T> withCatalog(source: MemorySource, targetProfileId: String, block: suspend (MemoryCatalog) -> T): T? {
        if (!catalogAvailable) return null
        return block(
            object : MemoryCatalog {
                override suspend fun searchTracks(query: String): List<Track> = emptyList()

                override suspend fun searchAlbums(query: String) = emptyList<com.gpo.yoin.data.model.Album>()
            },
        )
    }

    override suspend fun removeProfile(profileId: String) {
        removedProfiles += profileId
    }
}

object TransferSamples {
    val navidrome = TransferAccount("s-nav", MediaId.PROVIDER_SUBSONIC, "mika", "music.home.arpa", "nav")
    val apple = TransferAccount("am-cn", MediaId.PROVIDER_APPLE_MUSIC, "Apple Music", null, "am")
    val spotifyOld = TransferAccount("sp-old", MediaId.PROVIDER_SPOTIFY, "mika.old", null, null)
    val spotify = TransferAccount("sp-mika", MediaId.PROVIDER_SPOTIFY, "Mika", null, "sp")

    val sources: List<Pair<MemorySource, TransferAccount>> = listOf(
        MemorySource.LocalProfile("nav") to navidrome,
        MemorySource.LocalProfile("am") to apple,
        MemorySource.LocalProfile("sp") to spotify,
        MemorySource.CloudAccount("sp-old") to spotifyOld,
    )

    fun track(id: String, title: String, provider: String = MediaId.PROVIDER_APPLE_MUSIC) = Track(
        id = MediaId(provider, id),
        title = title,
        artist = "Weyes Blood",
        artistId = null,
        album = "Titanic Rising",
        albumId = null,
        coverArt = null,
        durationSec = 283,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
    )

    private fun items(vararg tracks: String) = MemoryItems(
        songNotes = tracks.mapIndexed { i, t ->
            SourceNote("n$i", SourceRef(MediaId.PROVIDER_SUBSONIC, t), "note $i", 1L, 2L, "Song $t", "Weyes Blood", null)
        },
    )

    private fun accountOf(source: MemorySource): TransferAccount = sources.firstOrNull { it.first == source }?.second
        ?: TransferAccount((source as MemorySource.CloudAccount).syncProfileId, MediaId.PROVIDER_SPOTIFY, "previous", null, null)

    private fun targetOf(id: String): TransferAccount = sources.first { it.second.localProfileId == id }.second

    /** Nothing to match (the same id space): copied as it is. */
    fun directPlan(source: MemorySource, target: String, previouslyCopied: Int = 0) = TransferPlan(
        source = accountOf(source),
        target = targetOf(target),
        items = items("t1", "t2"),
        tracks = emptyList(),
        albums = emptyList(),
        previouslyCopied = previouslyCopied,
        toCopy = TransferCounts(songNotes = 2),
        ledger = emptyMap(),
    )

    /** Three songs need matching; with no catalog all three read as not found. */
    fun unmatchedDraft(source: MemorySource, target: String) = directPlan(source, target).copy(
        tracks = listOf("t1", "t2", "t3").map { ref ->
            com.gpo.yoin.data.transfer.memories.TrackMatch(
                SourceRef(MediaId.PROVIDER_SUBSONIC, ref),
                null,
                com.gpo.yoin.shared.portability.memories.MatchStatus.Unmatched,
                emptyList(),
                null,
            )
        },
        items = items("t1", "t2", "t3"),
    )

    /** t1 matched, t2 unsure between two, t3 not found. */
    fun matchedPlan(source: MemorySource, target: String): TransferPlan {
        val matched = track("a1", "Andromeda")
        val first = track("a2", "Everyday")
        val second = track("a3", "Everyday (Live)")
        return unmatchedDraft(source, target).copy(
            tracks = listOf(
                com.gpo.yoin.data.transfer.memories.TrackMatch(
                    SourceRef(MediaId.PROVIDER_SUBSONIC, "t1"),
                    null,
                    com.gpo.yoin.shared.portability.memories.MatchStatus.Matched,
                    listOf(matched),
                    matched,
                ),
                com.gpo.yoin.data.transfer.memories.TrackMatch(
                    SourceRef(MediaId.PROVIDER_SUBSONIC, "t2"),
                    null,
                    com.gpo.yoin.shared.portability.memories.MatchStatus.Uncertain,
                    listOf(first, second),
                    null,
                ),
                com.gpo.yoin.data.transfer.memories.TrackMatch(
                    SourceRef(MediaId.PROVIDER_SUBSONIC, "t3"),
                    null,
                    com.gpo.yoin.shared.portability.memories.MatchStatus.Unmatched,
                    emptyList(),
                    null,
                ),
            ),
        )
    }

    fun result(copied: Int = 2, skipped: Int = 0) = TransferResult(
        copied = TransferCounts(songNotes = copied),
        updated = TransferCounts(),
        kept = TransferCounts(),
        reviewsJoined = 0,
        unmatched = TransferCounts(),
        skippedPreviously = skipped,
    )

    private val manifest = BackupManifest(
        exportedAt = 1_763_800_000_000L,
        source = BackupSource("google-drive", "d1", "Pixel 9 Pro", "0.6.3", 28),
        accounts = listOf(
            BackupAccount("s-nav", MediaId.PROVIDER_SUBSONIC, "mika", onDevice = true, counts = mapOf("song_note" to 3)),
            BackupAccount("sp-old", MediaId.PROVIDER_SPOTIFY, "mika.old", onDevice = false, counts = mapOf("song_note" to 5)),
            BackupAccount("am-gone", MediaId.PROVIDER_APPLE_MUSIC, "Apple Music", onDevice = false),
        ),
        snapshot = BackupSnapshotInfo(sha256 = "00", bytes = 10, records = 8, tombstones = 0),
    )

    fun exportSummary() = ExportSummary(manifest, readableFiles = 3, readFailures = emptyList())

    fun importPlan(): ImportPlan {
        val backup = ParsedBackup(
            manifest,
            SyncEnvelope(deviceId = "d1", deviceName = "Pixel 9 Pro", fileClass = "backup", writtenAt = 1L, appVersionCode = 28, records = emptyList()),
            readableFiles = 3,
        )
        return ImportPlan(
            backup = backup,
            accounts = listOf(
                ImportAccountPlan(
                    account = manifest.accounts[0],
                    counts = mapOf("song_note" to 3),
                    target = ImportTarget.IntoProfile("nav"),
                    sameAccount = true,
                    options = listOf(ImportProfileOption("nav", MediaId.PROVIDER_SUBSONIC, "mika")),
                    cloudOnlyPossible = true,
                ),
                ImportAccountPlan(
                    account = manifest.accounts[1],
                    counts = mapOf("song_note" to 5),
                    target = ImportTarget.CloudOnly,
                    sameAccount = false,
                    options = listOf(ImportProfileOption("sp", MediaId.PROVIDER_SPOTIFY, "Mika")),
                    cloudOnlyPossible = true,
                ),
                ImportAccountPlan(
                    account = manifest.accounts[2],
                    counts = emptyMap(),
                    target = ImportTarget.Skip,
                    sameAccount = false,
                    options = listOf(ImportProfileOption("am", MediaId.PROVIDER_APPLE_MUSIC, "Apple Music")),
                    cloudOnlyPossible = false,
                    wasPurged = true,
                ),
            ),
            globalCounts = mapOf("lyrics_translation" to 2),
        )
    }
}
