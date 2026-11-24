package com.gpo.yoin.shared.portability

import com.gpo.yoin.shared.portability.memories.AlbumPick
import com.gpo.yoin.shared.portability.memories.AlbumTarget
import com.gpo.yoin.shared.portability.memories.InMemoryTransferLedger
import com.gpo.yoin.shared.portability.memories.MatchStatus
import com.gpo.yoin.shared.portability.memories.MemoryItems
import com.gpo.yoin.shared.portability.memories.MemoryTransferApplier
import com.gpo.yoin.shared.portability.memories.SourceRef
import com.gpo.yoin.shared.portability.memories.SyncDomainTransferStore
import com.gpo.yoin.shared.portability.memories.SyncStoreTransferLedger
import com.gpo.yoin.shared.portability.memories.TargetRef
import com.gpo.yoin.shared.portability.memories.TrackPick
import com.gpo.yoin.shared.portability.memories.TrackTarget
import com.gpo.yoin.shared.portability.memories.TransferAccount
import com.gpo.yoin.shared.portability.memories.TransferCounts
import com.gpo.yoin.shared.portability.memories.TransferLedger
import com.gpo.yoin.shared.portability.memories.TransferResult
import com.gpo.yoin.shared.portability.memories.TransferRules
import com.gpo.yoin.shared.portability.memories.fromDomain
import com.gpo.yoin.shared.sync.InMemorySyncStore
import com.gpo.yoin.shared.sync.SyncFormat
import com.gpo.yoin.shared.sync.SyncKinds
import com.gpo.yoin.shared.sync.SyncStore
import com.gpo.yoin.shared.sync.adapters.SharedSyncAdapters
import com.gpo.yoin.shared.sync.adapters.SyncDomain
import com.gpo.yoin.shared.sync.engine.SyncEngine
import com.gpo.yoin.shared.sync.identity.CloudAccountDescriptor
import com.gpo.yoin.shared.sync.identity.SyncProfile

/*
 * The Swift facade over the backup / import / transfer core (docs/cloud-sync.md「导出与导入」「转移 Memories」).
 * Flat data classes and enums only, no default arguments, no sealed types; suspend calls are `async throws`
 * in Swift; the synchronous [YoinBackup.readBackup] never throws (it returns a flat [YoinReadBackupResult]).
 * iOS keeps the zip, the file pickers and the UI; it hands the facade its stores (the same [SyncDomain] CloudSyncKit uses), its replica (CloudSyncKit.store, or
 * null before sync ever ran) and a [YoinTrackAlbumLinkStore].
 *
 * Call it while no sync runs (the replica isn't locked here), and flush CloudSyncKit's store after an
 * import or transfer that touched the replica.
 */

/** `track_album_links` on the platform (which album a song is on). Synchronous, like [SyncDomain]. */
interface YoinTrackAlbumLinkStore {
    fun links(profileId: String): List<BackupTrackAlbumLink>

    fun link(profileId: String, provider: String, trackId: String): BackupTrackAlbumLink?

    /** true once durable. */
    fun putLink(profileId: String, link: BackupTrackAlbumLink): Boolean
}

/** An album name this device knows (its plays), for the readable Markdown. */
data class YoinKnownAlbum(
    val profileId: String,
    val provider: String,
    val albumId: String,
    val name: String,
    val artist: String?,
)

/** A song this device knows (its plays), for the readable Markdown. */
data class YoinKnownTrack(
    val profileId: String,
    val provider: String,
    val trackId: String,
    val title: String,
    val artist: String?,
    val albumId: String?,
)

/** The files of one export: write them into `fileName` as a zip, in this order, UTF-8. */
data class YoinBackupExport(
    val fileName: String,
    val manifest: BackupManifest,
    val manifestJson: String,
    val snapshotJson: String,
    val readable: List<BackupTextFile>,
    /** Kinds whose local table couldn't be read (their replica copy, if any, went in instead). */
    val readFailures: List<String>,
)

enum class YoinBackupErrorKind {
    /** Not a zip, or no Yoin manifest in it. */
    NOT_A_BACKUP,

    /** Written by a newer Yoin: update to import it ([YoinBackupError.formatVersion]). */
    TOO_NEW,

    /** The snapshot doesn't match the manifest's checksum. */
    CHECKSUM_MISMATCH,

    CORRUPT,

    /** Anything else (not a [BackupReadException]). */
    OTHER,
}

data class YoinBackupError(val kind: YoinBackupErrorKind, val formatVersion: Int, val message: String)

/** What [YoinBackup.readBackup] found: [backup] when the file is a readable Yoin backup, else [error]. */
data class YoinReadBackupResult(val backup: ParsedBackup?, val error: YoinBackupError?)

enum class YoinImportTargetKind { INTO_PROFILE, CLOUD_ONLY, SKIP }

/** Where one account of a backup goes: [profileId] for [YoinImportTargetKind.INTO_PROFILE], else null. */
data class YoinImportChoice(val scope: String, val kind: YoinImportTargetKind, val profileId: String?)

data class YoinImportAccount(
    val account: BackupAccount,
    /** Live records per kind. */
    val counts: Map<String, Int>,
    /** The default target ([YoinImportChoice] with this account's scope). */
    val target: YoinImportChoice,
    /** The default is a local profile that is the same account (sync binding or fingerprint). */
    val sameAccount: Boolean,
    /** Profiles of the same service the user may pick instead. */
    val options: List<ImportProfileOption>,
    val cloudOnlyPossible: Boolean,
    /** Removed from the cloud on this device: offer "into a local account" or "resume sync first". */
    val wasPurged: Boolean,
)

/** What an import would do. Pass it back to [YoinBackup.applyImport] with the user's choices. */
class YoinImportPlan internal constructor(
    val backup: ParsedBackup,
    val accounts: List<YoinImportAccount>,
    /** Live global records per kind (settings, translations, About, playlist backups). */
    val globalCounts: Map<String, Int>,
    internal val plan: ImportPlan,
)

data class YoinImportScopeCounts(val scope: String, val counts: ImportCounts)

data class YoinImportResult(
    val byScope: List<YoinImportScopeCounts>,
    val global: ImportCounts,
    val cloudOnlyMerged: Int,
    val cloudOnlyRefused: List<String>,
    /** Something was written: run a sync (or let the next one) publish it. */
    val changed: Boolean,
)

enum class YoinTransferSourceKind { LOCAL_PROFILE, CLOUD_ACCOUNT, BACKUP_ACCOUNT }

/** An account Memories can be copied from. */
data class YoinTransferSource(val kind: YoinTransferSourceKind, val account: TransferAccount)

/** What is known about a source song that needs matching on the target (search with the shared TrackMatcher). */
data class YoinTrackToMatch(
    val source: SourceRef,
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumId: String?,
    val durationSec: Int?,
)

/** A source album that needs matching on the target. */
data class YoinAlbumToMatch(val source: SourceRef, val name: String?, val artist: String?)

/**
 * A transfer before matching: what will be copied ([items], minus what an earlier run already copied), which
 * songs and albums need a counterpart on the target, and the matches / picks earlier runs left in the ledger
 * ([reusedTracks] / [reusedAlbums], Matched or Picked, to pass back as they are). Ids in one id space need
 * no matching ([TransferRules.compatible]).
 */
class YoinTransferPlan internal constructor(
    val source: TransferAccount,
    val target: TransferAccount,
    val items: MemoryItems,
    val tracksToMatch: List<YoinTrackToMatch>,
    val albumsToMatch: List<YoinAlbumToMatch>,
    val reusedTracks: List<TrackPick>,
    val reusedAlbums: List<AlbumPick>,
    /** Items a previous run already copied: skipped this time. */
    val previouslyCopied: Int,
    /** Items whose ids carry over or were matched before: copied for sure (more once matching picks). */
    val toCopy: TransferCounts,
    /**
     * Songs whose ids carry over as they are and that still have something to copy (an earlier run's copies
     * left out): the summary's "direct" count and the review's (Android TransferUiState.directSongs).
     */
    val directSongs: Int,
    internal val ledger: Map<String, String>,
)

class YoinBackup(
    private val domain: SyncDomain,
    /** CloudSyncKit.store, or null when Cloud sync never ran on this device. */
    private val replica: SyncStore?,
    private val links: YoinTrackAlbumLinkStore,
    private val deviceId: String,
    private val deviceName: String,
    private val appVersionName: String,
    private val appVersionCode: Int,
) {
    private val store: SyncStore = replica ?: InMemorySyncStore()
    private val adapters by lazy { SharedSyncAdapters.create(domain, store) }

    private fun backupReplica(nowMs: Long): BackupReplica? = replica?.let {
        StoreBackupReplica(it, SyncEngine(it, adapters, deviceId, { deviceName }, { nowMs }))
    }

    private val backupDomain = object : BackupDomain {
        override suspend fun trackAlbumLinks(localProfileId: String) = links.links(localProfileId)

        override suspend fun trackAlbumLink(localProfileId: String, provider: String, trackId: String) =
            links.link(localProfileId, provider, trackId)

        override suspend fun putTrackAlbumLink(localProfileId: String, link: BackupTrackAlbumLink) {
            check(links.putLink(localProfileId, link)) { "Couldn't save a track album link on this device" }
        }

        override suspend fun songNoteOwner(id: String): String? = domain.note(id)?.profileId

        override suspend fun albumNoteOwner(id: String): String? = domain.albumNote(id)?.profileId
    }

    // ---- export

    /**
     * Everything this device would sync now, plus accounts it can't, as the backup's files. [syncLocation] is
     * where Cloud sync keeps the data (null = sync off: "this-device"; "google_drive" / "icloud", or the
     * [BackupFormat] location constants) and lands in the manifest's source; anything else throws.
     * [language] picks the readable labels ("zh…" = Chinese); dates and the file name use the time zone
     * [zoneId] (TimeZone.current.identifier).
     */
    @Throws(Throwable::class)
    suspend fun exportBackup(
        nowMs: Long,
        syncLocation: String?,
        language: String,
        zoneId: String,
        knownAlbums: List<YoinKnownAlbum>,
        knownTracks: List<YoinKnownTrack>,
    ): YoinBackupExport {
        val location = BackupFormat.locationOf(syncLocation)
        val content = SnapshotAssembler(adapters, backupReplica(nowMs), backupDomain, deviceId, deviceName, appVersionCode)
            .build(domain.profiles(), nowMs)
        val source = BackupSource(
            location = location,
            deviceId = deviceId,
            deviceName = deviceName,
            appVersionName = appVersionName,
            appVersionCode = appVersionCode,
        )
        val files = BackupExport.files(content, source, NameTable(knownAlbums, knownTracks), ReadableLabels.forLanguage(language), zoneId)
        return YoinBackupExport(
            fileName = BackupFormat.fileName(nowMs, zoneId),
            manifest = files.manifest,
            manifestJson = files.manifestBytes.decodeToString(),
            snapshotJson = files.snapshotBytes.decodeToString(),
            readable = files.readable,
            readFailures = files.summary.readFailures,
        )
    }

    // ---- import

    /**
     * Checks what the platform read out of the zip: [manifestJson] / [snapshotJson] are the entries
     * `manifest.json` / `data/snapshot.json` (null when missing), [readableFiles] the entries under readable/.
     * Never throws: a file that isn't a readable Yoin backup comes back as [YoinReadBackupResult.error].
     */
    fun readBackup(manifestJson: ByteArray?, snapshotJson: ByteArray?, readableFiles: Int): YoinReadBackupResult = try {
        YoinReadBackupResult(BackupParser.parse(manifestJson, snapshotJson, readableFiles), null)
    } catch (e: Exception) {
        YoinReadBackupResult(null, errorOf(e))
    }

    /** Where each account of [backup] would go by default, and what the user may choose instead. */
    @Throws(Throwable::class)
    suspend fun planImport(backup: ParsedBackup, nowMs: Long): YoinImportPlan {
        val plan = ImportPlanner.plan(backup, domain.profiles(), backupReplica(nowMs))
        return YoinImportPlan(
            backup = backup,
            accounts = plan.accounts.map { account ->
                YoinImportAccount(
                    account = account.account,
                    counts = account.counts,
                    target = choiceOf(account.account.scope, account.target),
                    sameAccount = account.sameAccount,
                    options = account.options,
                    cloudOnlyPossible = account.cloudOnlyPossible,
                    wasPurged = account.wasPurged,
                )
            },
            globalCounts = plan.globalCounts,
            plan = plan,
        )
    }

    /** Applies [plan] with [choices] overriding its defaults; importing the same file again changes nothing. */
    @Throws(Throwable::class)
    suspend fun applyImport(plan: YoinImportPlan, choices: List<YoinImportChoice>, importGlobal: Boolean, nowMs: Long): YoinImportResult {
        val targets = choices.associate { it.scope to targetOf(it) }
        val result = BackupMerger(adapters, backupDomain)
            .apply(plan.plan, targets, domain.profiles(), backupReplica(nowMs), importGlobal)
        return YoinImportResult(
            byScope = result.byScope.map { (scope, counts) -> YoinImportScopeCounts(scope, counts) },
            global = result.global,
            cloudOnlyMerged = result.cloudOnlyMerged,
            cloudOnlyRefused = result.cloudOnlyRefused,
            changed = result.changed,
        )
    }

    // ---- transfer Memories

    /** Accounts Memories can be copied from: this device's profiles, then accounts only in the cloud. */
    @Throws(Throwable::class)
    suspend fun transferSources(): List<YoinTransferSource> {
        val backupReplica = replica?.let { StoreBackupReplica(it, null) }
        val local = domain.profiles().sortedWith(compareBy(SyncProfile::createdAt, SyncProfile::id))
            .map { YoinTransferSource(YoinTransferSourceKind.LOCAL_PROFILE, accountOf(it, backupReplica)) }
        val localScopes = local.map { it.account.scope }.toSet()
        val cloud = backupReplica?.recordsOfKind(SyncKinds.ACCOUNT).orEmpty()
            .filter { !it.deleted && it.scope == SyncFormat.GLOBAL_SCOPE && it.key !in localScopes && it.payload != null }
            .mapNotNull { CloudAccountDescriptor.fromPayload(it.key, it.payload!!) }
            .map { YoinTransferSource(YoinTransferSourceKind.CLOUD_ACCOUNT, accountOf(it)) }
        return local + cloud
    }

    /** The accounts of a backup file, as transfer sources. */
    fun transferSourcesOf(backup: ParsedBackup): List<YoinTransferSource> = ImportPlanner.accountsOf(backup).map { account ->
        YoinTransferSource(
            YoinTransferSourceKind.BACKUP_ACCOUNT,
            TransferAccount(account.scope, account.provider, account.displayName, BackupScopes.serverKeyOf(account.hint), null),
        )
    }

    /** [profileId] as a transfer target (or source). */
    @Throws(Throwable::class)
    suspend fun transferAccountOf(profileId: String): TransferAccount {
        val profile = requireNotNull(domain.profiles().firstOrNull { it.id == profileId }) { "No such profile" }
        return accountOf(profile, replica?.let { StoreBackupReplica(it, null) })
    }

    /** What [source] holds: a local profile's stores, the replica (cloud-only), or a backup's snapshot. */
    @Throws(Throwable::class)
    suspend fun memoryItems(source: YoinTransferSource, backup: ParsedBackup?): MemoryItems = when (source.kind) {
        YoinTransferSourceKind.LOCAL_PROFILE ->
            MemoryItems.fromDomain(domain, links, requireNotNull(source.account.localProfileId) { "No local profile" })
        YoinTransferSourceKind.CLOUD_ACCOUNT -> {
            val store = requireNotNull(replica) { "Cloud sync has never run on this device" }
            MemoryItems.fromRecords(StoreBackupReplica(store, null).recordsInScope(source.account.scope), source.account.scope)
        }
        YoinTransferSourceKind.BACKUP_ACCOUNT ->
            MemoryItems.fromRecords(requireNotNull(backup) { "No backup" }.snapshot.records, source.account.scope)
    }

    /** What a copy from [source] into [target] (a local profile) needs: the songs and albums to match. */
    @Throws(Throwable::class)
    suspend fun planTransfer(source: TransferAccount, items: MemoryItems, target: TransferAccount): YoinTransferPlan {
        val targetProfile = requireNotNull(target.localProfileId) { "Memories are copied into a local profile" }
        require(source.localProfileId != targetProfile) { "Source and target are the same profile" }
        val ledger = ledger().read(source.scope, targetProfile)
        val pending = TransferRules.pendingItems(items, ledger)
        val (tracks, albums) = TransferRules.needsMatching(source, target, pending)
        val reusedTracks = tracks.mapNotNull { ref ->
            reused(ledger[TransferRules.trackKey(ref)])?.let { (status, to) ->
                TrackPick(ref, status, TrackTarget(to.provider, to.id, null, null, null, null))
            }
        }
        val reusedAlbums = albums.mapNotNull { ref ->
            reused(ledger[TransferRules.albumKey(ref)])?.let { (status, to) ->
                val facts = items.albumFacts[ref]
                AlbumPick(ref, status, AlbumTarget(to.provider, to.id, facts?.name.orEmpty(), facts?.artist))
            }
        }
        val trackRefs = reusedTracks.map { it.source }.toSet()
        val albumRefs = reusedAlbums.map { it.source }.toSet()
        return YoinTransferPlan(
            source = source,
            target = target,
            items = items,
            tracksToMatch = tracks.filter { it !in trackRefs }.map { ref -> trackToMatch(ref, items) },
            albumsToMatch = albums.filter { it !in albumRefs }.map { ref ->
                YoinAlbumToMatch(ref, items.albumFacts[ref]?.name, items.albumFacts[ref]?.artist)
            },
            reusedTracks = reusedTracks,
            reusedAlbums = reusedAlbums,
            previouslyCopied = TransferRules.countItems(items) - TransferRules.countItems(pending),
            toCopy = TransferRules.mappableCounts(source, target, pending, { it in trackRefs }, { it in albumRefs }),
            directSongs = pending.tracks.count { it !in tracks },
            ledger = ledger,
        )
    }

    /**
     * Copies [plan] into its target with the matches [tracks] / [albums] (reused ones included; a song or
     * album left out, Uncertain or Unmatched isn't copied and is retried next time), and records the run.
     */
    @Throws(Throwable::class)
    suspend fun applyTransfer(plan: YoinTransferPlan, tracks: List<TrackPick>, albums: List<AlbumPick>, nowMs: Long): TransferResult {
        val outcome = MemoryTransferApplier(SyncDomainTransferStore(domain, links))
            .apply(plan.source, plan.target, plan.items, tracks, albums, plan.ledger) { nowMs }
        ledger().write(plan.source.scope, requireNotNull(plan.target.localProfileId), outcome.ledgerEntries)
        return outcome.result
    }

    // ---- helpers

    private val scratchLedger = InMemoryTransferLedger()

    private fun ledger(): TransferLedger = replica?.let(::SyncStoreTransferLedger) ?: scratchLedger

    private suspend fun accountOf(profile: SyncProfile, replica: BackupReplica?) = TransferAccount(
        scope = BackupScopes.of(profile, replica),
        provider = profile.provider,
        displayName = profile.displayName,
        serverKey = BackupScopes.serverKeyOf(BackupScopes.subsonicHint(profile)),
        localProfileId = profile.id,
    )

    private fun accountOf(descriptor: CloudAccountDescriptor) = TransferAccount(
        scope = descriptor.syncProfileId,
        provider = descriptor.provider,
        displayName = descriptor.displayName.ifBlank { descriptor.syncProfileId },
        serverKey = BackupScopes.serverKeyOf(descriptor.hint),
        localProfileId = null,
    )

    /** A song / album match an earlier run stored in the ledger (`matched|` or `chosen|`), else null. */
    private fun reused(earlier: String?): Pair<MatchStatus, TargetRef>? {
        earlier ?: return null
        val status = when {
            earlier.startsWith(TransferLedger.CHOSEN) -> MatchStatus.Picked
            earlier.startsWith(TransferLedger.MATCHED) -> MatchStatus.Matched
            else -> return null
        }
        val target = TransferRules.parseTarget(earlier.substringAfter('|')) ?: return null
        return status to target
    }

    private fun trackToMatch(ref: SourceRef, items: MemoryItems): YoinTrackToMatch {
        val facts = items.trackFacts[ref]
        val note = items.songNotes.firstOrNull { it.track == ref && it.title.isNotBlank() }
        return YoinTrackToMatch(
            source = ref,
            title = facts?.title ?: note?.title,
            artist = facts?.artist?.takeIf(String::isNotBlank) ?: note?.artist?.takeIf(String::isNotBlank),
            album = facts?.album,
            albumId = facts?.albumId ?: TransferRules.linkedAlbum(ref, items),
            durationSec = facts?.durationSec,
        )
    }

    private fun choiceOf(scope: String, target: ImportTarget) = when (target) {
        is ImportTarget.IntoProfile -> YoinImportChoice(scope, YoinImportTargetKind.INTO_PROFILE, target.localProfileId)
        ImportTarget.CloudOnly -> YoinImportChoice(scope, YoinImportTargetKind.CLOUD_ONLY, null)
        ImportTarget.Skip -> YoinImportChoice(scope, YoinImportTargetKind.SKIP, null)
    }

    private fun targetOf(choice: YoinImportChoice): ImportTarget = when (choice.kind) {
        YoinImportTargetKind.INTO_PROFILE -> ImportTarget.IntoProfile(requireNotNull(choice.profileId) { "No profile for ${choice.scope}" })
        YoinImportTargetKind.CLOUD_ONLY -> ImportTarget.CloudOnly
        YoinImportTargetKind.SKIP -> ImportTarget.Skip
    }

    private class NameTable(albums: List<YoinKnownAlbum>, tracks: List<YoinKnownTrack>) : ReadableNames {
        private val albums = albums.associate { listOf(it.profileId, it.provider, it.albumId) to ReadableAlbum(it.name, it.artist) }
        private val tracks = tracks.associate { listOf(it.profileId, it.provider, it.trackId) to ReadableTrack(it.title, it.artist, it.albumId) }

        override suspend fun album(localProfileId: String?, provider: String, albumId: String): ReadableAlbum? =
            localProfileId?.let { albums[listOf(it, provider, albumId)] }

        override suspend fun track(localProfileId: String?, provider: String, trackId: String): ReadableTrack? =
            localProfileId?.let { tracks[listOf(it, provider, trackId)] }
    }

    internal companion object {
        /** A failed read as a flat error. */
        fun errorOf(error: Throwable): YoinBackupError = when (error) {
            is BackupReadException.NotABackup -> YoinBackupError(YoinBackupErrorKind.NOT_A_BACKUP, 0, error.message.orEmpty())
            is BackupReadException.TooNew -> YoinBackupError(YoinBackupErrorKind.TOO_NEW, error.formatVersion, error.message.orEmpty())
            is BackupReadException.ChecksumMismatch -> YoinBackupError(YoinBackupErrorKind.CHECKSUM_MISMATCH, 0, error.message.orEmpty())
            is BackupReadException.Corrupt -> YoinBackupError(YoinBackupErrorKind.CORRUPT, 0, error.message.orEmpty())
            else -> YoinBackupError(YoinBackupErrorKind.OTHER, 0, error.message.orEmpty())
        }
    }
}
