package com.gpo.yoin.data.sync.backup

import com.gpo.yoin.data.local.AlbumMemoryTitle
import com.gpo.yoin.data.local.AlbumNote
import com.gpo.yoin.data.local.AlbumRating
import com.gpo.yoin.data.local.LocalRating
import com.gpo.yoin.data.local.MemoryCopyCache
import com.gpo.yoin.data.local.PlayHistory
import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.local.TrackAlbumLink
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.sync.SyncAdapter
import com.gpo.yoin.data.sync.adapters.YoinSyncAdapters
import com.gpo.yoin.data.sync.identity.AccountFingerprints
import com.gpo.yoin.data.sync.testing.FakeSeamStyleGateway
import com.gpo.yoin.data.sync.testing.SyncDomainFixtures
import com.gpo.yoin.shared.portability.BackupFormat
import com.gpo.yoin.shared.portability.BackupSource
import com.gpo.yoin.shared.portability.ExportSummary
import com.gpo.yoin.shared.portability.ParsedBackup
import com.gpo.yoin.shared.portability.ReadableLabels
import com.gpo.yoin.shared.portability.ReadableNames
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** One simulated install for backup and transfer tests: its own domain + sync DBs and profiles. */
class BackupTestDevice(val deviceId: String = "dev-a", val name: String = "Pixel 9") {
    val fixtures = SyncDomainFixtures()
    val db get() = fixtures.db
    val syncDb get() = fixtures.syncDb
    val credentials = HashMap<String, ProfileCredentials>()

    val adapters: List<SyncAdapter> by lazy {
        YoinSyncAdapters.create(
            db = db,
            syncDb = syncDb,
            seam = FakeSeamStyleGateway(),
            decodeCredentials = { credentials[it.id] },
            profiles = { db.profileDao().getAll() },
        )
    }

    suspend fun profiles(): List<Profile> = db.profileDao().getAll()

    suspend fun addSubsonic(id: String, url: String, user: String, password: String = "PW-SENTINEL", createdAt: Long = 1_000): String {
        db.profileDao().upsert(Profile(id, "subsonic", "$user @ music", credentialsJson = "{}", createdAt = createdAt))
        credentials[id] = ProfileCredentials.Subsonic(url, user, password)
        return id
    }

    suspend fun addProfile(id: String, provider: String, name: String, createdAt: Long = 1_000): String {
        db.profileDao().upsert(Profile(id, provider, name, credentialsJson = "{}", createdAt = createdAt))
        return id
    }

    suspend fun note(profile: String, id: String, track: String, content: String, at: Long, provider: String = "subsonic") {
        db.syncDomainDao().writeNote(
            SongNote(id, profile, track, provider, content, at, at, "Title $track", "Artist", positionMs = 61_000),
        )
    }

    suspend fun rate(profile: String, song: String, rating: Float?, at: Long, provider: String = "subsonic") {
        db.syncDomainDao().upsertTrackRating(LocalRating(profile, song, provider, rating, 0, false, at))
    }

    suspend fun albumRating(profile: String, album: String, rating: Float, review: String?, at: Long, provider: String = "subsonic") {
        db.syncDomainDao().insertAlbumRatingIfAbsent(
            AlbumRating(profile, album, provider, rating, review, null, false, false, at, review?.let { at }),
        )
    }

    suspend fun albumNote(profile: String, id: String, album: String, content: String, at: Long, provider: String = "subsonic") {
        db.syncDomainDao().writeAlbumNote(AlbumNote(id, profile, album, provider, content, at, at, "Album $album", "Artist"))
    }

    suspend fun albumTitle(profile: String, album: String, title: String, at: Long, provider: String = "subsonic") {
        db.albumMemoryTitleDao().upsert(AlbumMemoryTitle(profile, provider, album, title, at))
    }

    suspend fun memory(profile: String, album: String, copy: String, title: String?, at: Long, provider: String = "subsonic") {
        db.syncDomainDao().upsertMemoryCopy(MemoryCopyCache(profile, provider, "album", album, copy, "ph", at, title, title?.let { "th" }))
    }

    suspend fun link(profile: String, track: String, album: String, at: Long, provider: String = "subsonic") {
        db.syncDomainDao().upsertTrackAlbumLink(TrackAlbumLink(profile, provider, track, album, TrackAlbumLink.SOURCE_DETAIL, at))
    }

    suspend fun played(profile: String, song: String, title: String, album: String, albumName: String, at: Long, provider: String = "subsonic") {
        db.playHistoryDao().insert(
            PlayHistory(songId = song, profileId = profile, provider = provider, title = title, artist = "Artist", album = albumName, albumId = album, coverArtId = null, playedAt = at, durationMs = 200_000, completedPercent = 1f),
        )
    }

    fun builder(now: Long = 10_000, replica: Boolean = false) = SnapshotBuilder(
        db = db,
        adapters = adapters,
        replica = if (replica) syncDb else null,
        profiles = ::profiles,
        decodeCredentials = { credentials[it.id] },
        deviceId = deviceId,
        deviceName = name,
        appVersionCode = 1,
        clock = { now },
    )

    suspend fun exportZip(now: Long = 10_000, replica: Boolean = false, names: ReadableNames = DomainReadableNames(db)): Pair<ByteArray, ExportSummary> {
        val out = ByteArrayOutputStream()
        val summary = BackupWriter(names, ReadableLabels.English, java.time.ZoneOffset.UTC).write(
            builder(now, replica).build(),
            BackupSource(BackupFormat.LOCATION_THIS_DEVICE, deviceId, name, "test", 1),
            out,
        )
        return out.toByteArray() to summary
    }

    fun importer() = BackupImporter(db, adapters, ::profiles) { credentials[it.id] }

    fun close() = fixtures.close()

    companion object {
        fun scopeOf(url: String, user: String): String =
            AccountFingerprints.deterministicId(requireNotNull(AccountFingerprints.subsonic(url, user)))

        /** Every zip entry, decompressed: name -> text. */
        fun entries(zip: ByteArray): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            ZipInputStream(ByteArrayInputStream(zip)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    out[entry.name] = input.readBytes().toString(Charsets.UTF_8)
                }
            }
            return out
        }

        fun parse(zip: ByteArray): ParsedBackup = BackupReader.read(ByteArrayInputStream(zip))
    }
}
