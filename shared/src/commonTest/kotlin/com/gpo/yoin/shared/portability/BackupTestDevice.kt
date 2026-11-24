package com.gpo.yoin.shared.portability

import com.gpo.yoin.shared.sync.InMemorySyncStore
import com.gpo.yoin.shared.sync.SyncAdapter
import com.gpo.yoin.shared.sync.adapters.AlbumNoteRow
import com.gpo.yoin.shared.sync.adapters.AlbumRatingRow
import com.gpo.yoin.shared.sync.adapters.SharedSyncAdapters
import com.gpo.yoin.shared.sync.adapters.SongNoteRow
import com.gpo.yoin.shared.sync.adapters.TrackRatingRow
import com.gpo.yoin.shared.sync.engine.SyncEngine
import com.gpo.yoin.shared.sync.identity.AccountFingerprints
import com.gpo.yoin.shared.sync.identity.SyncProfile
import com.gpo.yoin.shared.sync.testing.FakeSyncDomain

/** One simulated install for the shared backup tests: the shared adapters over a [FakeSyncDomain]. */
class BackupTestDevice(val deviceId: String = "dev-a", val name: String = "Phone") {
    val domain = FakeSyncDomain()
    val store = InMemorySyncStore()
    val links = LinkedHashMap<Pair<String, String>, BackupTrackAlbumLink>()
    val adapters: List<SyncAdapter> by lazy { SharedSyncAdapters.create(domain, store) }

    val backupDomain = object : BackupDomain {
        override suspend fun trackAlbumLinks(localProfileId: String) =
            links.filterKeys { it.first == localProfileId }.values.toList()

        override suspend fun trackAlbumLink(localProfileId: String, provider: String, trackId: String) =
            links[localProfileId to "$provider:$trackId"]

        override suspend fun putTrackAlbumLink(localProfileId: String, link: BackupTrackAlbumLink) {
            links[localProfileId to "${link.provider}:${link.trackId}"] = link
        }

        override suspend fun songNoteOwner(id: String) = domain.note(id)?.profileId

        override suspend fun albumNoteOwner(id: String) = domain.albumNote(id)?.profileId
    }

    fun engine(now: Long = 5_000) = SyncEngine(store, adapters, deviceId, { name }, { now })

    fun replica(withEngine: Boolean = true) = StoreBackupReplica(store, if (withEngine) engine() else null)

    fun addSubsonic(id: String, url: String, user: String, createdAt: Long = 1_000): String {
        domain.profileList += SyncProfile(id, "subsonic", "$user @ music", createdAt, subsonicServerUrl = url, subsonicUsername = user)
        return id
    }

    fun addProfile(id: String, provider: String, name: String, createdAt: Long = 1_000): String {
        domain.profileList += SyncProfile(id, provider, name, createdAt)
        return id
    }

    fun note(profile: String, id: String, track: String, content: String, at: Long, provider: String = "subsonic") {
        domain.putNote(SongNoteRow(id, profile, track, provider, content, at, at, "Title $track", "Artist", 61_000))
    }

    fun rate(profile: String, song: String, rating: Double?, at: Long, provider: String = "subsonic") {
        domain.putTrackRating(profile, TrackRatingRow(provider, song, rating, at))
    }

    fun albumRating(profile: String, album: String, rating: Double, review: String?, at: Long, provider: String = "subsonic") {
        domain.putAlbumRating(profile, AlbumRatingRow(provider, album, rating, review, review?.let { at }, at))
    }

    fun albumNote(profile: String, id: String, album: String, content: String, at: Long, provider: String = "subsonic") {
        domain.putAlbumNote(AlbumNoteRow(id, profile, album, provider, content, at, at, "Album $album", "Artist"))
    }

    fun link(profile: String, track: String, album: String, at: Long, provider: String = "subsonic") {
        links[profile to "$provider:$track"] = BackupTrackAlbumLink(provider, track, album, BackupTrackAlbumLink.SOURCE_DETAIL, at)
    }

    suspend fun export(now: Long = 10_000, replica: BackupReplica? = null): BackupExportFiles {
        val content = SnapshotAssembler(adapters, replica, backupDomain, deviceId, name, 1).build(domain.profiles(), now)
        return BackupExport.files(
            content,
            BackupSource(BackupFormat.LOCATION_THIS_DEVICE, deviceId, name, "test", 1),
            ReadableNames.None,
            ReadableLabels.English,
            "UTC",
        )
    }

    fun merger() = BackupMerger(adapters, backupDomain)

    suspend fun import(
        backup: ParsedBackup,
        replica: BackupReplica? = null,
        choices: Map<String, ImportTarget> = emptyMap(),
    ): ImportResult {
        val plan = ImportPlanner.plan(backup, domain.profiles(), replica)
        return merger().apply(plan, choices, domain.profiles(), replica, importGlobal = true)
    }

    companion object {
        fun scopeOf(url: String, user: String): String =
            AccountFingerprints.deterministicId(requireNotNull(AccountFingerprints.subsonic(url, user)))

        fun parse(files: BackupExportFiles): ParsedBackup =
            BackupParser.parse(files.manifestBytes, files.snapshotBytes, files.readable.size)
    }
}
