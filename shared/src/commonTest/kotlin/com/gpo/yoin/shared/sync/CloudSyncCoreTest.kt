package com.gpo.yoin.shared.sync

import com.gpo.yoin.shared.sync.adapters.SharedSyncAdapters
import com.gpo.yoin.shared.sync.adapters.SongNoteRow
import com.gpo.yoin.shared.sync.adapters.TrackRatingRow
import com.gpo.yoin.shared.sync.identity.SyncProfile
import com.gpo.yoin.shared.sync.testing.FakeSyncDomain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The cycle coordinator (port of CloudSyncManager's cycle) over an in-memory appDataFolder. */
class CloudSyncCoreTest {
    /** One Google account's appDataFolder, every write counted. */
    private class FakeDrive {
        class File(val id: String, val name: String, val props: Map<String, String>, var bytes: ByteArray, var version: Long)

        val files = LinkedHashMap<String, File>()
        var writes = 0
        private var nextId = 0
        private var nextVersion = 1L

        fun transport(): SyncTransport = object : SyncTransport {
            override suspend fun list() = files.values.map {
                RemoteFile(it.id, it.name, it.version, null, it.bytes.size.toLong(), it.props)
            }

            override suspend fun download(fileId: String) = files[fileId]?.bytes

            override suspend fun create(
                fileId: String,
                name: String,
                appProperties: Map<String, String>,
                mimeType: String,
                bytes: ByteArray,
            ): RemoteFile {
                if (fileId in files) throw SyncTransportException.AlreadyExists(fileId)
                writes++
                files[fileId] = File(fileId, name, appProperties, bytes, nextVersion++)
                return list().first { it.id == fileId }
            }

            override suspend fun update(fileId: String, bytes: ByteArray): RemoteFile? {
                val file = files[fileId] ?: return null
                writes++
                file.bytes = bytes
                file.version = nextVersion++
                return list().first { it.id == fileId }
            }

            override suspend fun delete(fileId: String): Boolean {
                writes++
                files.remove(fileId)
                return true
            }

            override suspend fun generateIds(count: Int) = List(count) { "id-${nextId++}" }

            override suspend fun about() = GoogleAccountInfo("perm-1", "me@example.com", "Me")
        }
    }

    private class Backups : SyncBackupSink {
        val sets = mutableListOf<List<SyncBackupFile>>()

        override fun save(files: List<SyncBackupFile>, manifestJson: String): String {
            sets += files
            return "backup-${sets.size}"
        }
    }

    private val drive = FakeDrive()
    private var now = 1_800_000_000_000L
    private val profile = SyncProfile("p", "subsonic", "Demo", 1L, subsonicServerUrl = "http://127.0.0.1:4977", subsonicUsername = "demo")

    private fun device(name: String, readOnly: Boolean, backups: SyncBackupSink? = null): Pair<CloudSyncCore, FakeSyncDomain> {
        val domain = FakeSyncDomain().apply { profileList += profile }
        val store = InMemorySyncStore()
        val core = CloudSyncCore(
            store = store,
            adapters = { SharedSyncAdapters.create(domain, it) },
            profiles = { domain.profiles() },
            tokens = object : SyncTokenProvider {
                override suspend fun token() = "t"
                override suspend fun invalidate(token: String) = Unit
            },
            transportFactory = { drive.transport() },
            deviceName = { name },
            versionCode = 1,
            clock = { now },
            readOnlyFirstPull = readOnly,
            backupSink = backups,
        )
        return core to domain
    }

    private fun note(id: String, text: String) =
        SongNoteRow(id, "p", "s-harbor-02", "subsonic", text, now, now, "Paper Lanterns", "Kiri Ono", 1_000L)

    @Test
    fun should_writeNothingUntilOnePullMappedAndBackedUp_when_iosJoinsAndroidData() = runTest {
        val (android, androidDomain) = device("Pixel Tablet", readOnly = false)
        androidDomain.putNote(note("n1", "from Android"))
        androidDomain.putTrackRating("p", TrackRatingRow("subsonic", "s-harbor-02", 9.3, now))
        assertNull(android.enable("token"))
        val androidFiles = drive.files.keys.toSet()
        assertTrue(androidFiles.isNotEmpty())

        val backups = Backups()
        val (ios, iosDomain) = device("iPhone", readOnly = true, backups = backups)
        iosDomain.putNote(note("n2", "from iPhone"))
        val writesBefore = drive.writes
        now += 1_000

        assertNull(ios.enable("token"))

        // First cycle: Android's data is here, nothing went to Drive, the folder is backed up.
        assertEquals("from Android", iosDomain.note("n1")?.content)
        assertEquals(9.3, iosDomain.trackRating("p", "subsonic", "s-harbor-02")?.rating)
        assertEquals(writesBefore, drive.writes)
        assertEquals(androidFiles, drive.files.keys)
        assertEquals(androidFiles, backups.sets.single().map { it.id }.toSet())
        assertTrue(backups.sets.single().all { it.bytes != null })
        // The pull mapped cleanly and the backup is on disk: the gate opens for the next cycle.
        assertTrue(!ios.currentState().readOnly)
        assertEquals("backup-1", ios.currentState().lastBackup)

        // Next cycle: two-way. The iPhone's own note reaches Android; Android's files are untouched.
        now += 61_000
        ios.syncNow()
        assertTrue(drive.writes > writesBefore)
        assertEquals(CloudSyncPhase.UpToDate, ios.currentState().phase)
        val androidBytes = androidFiles.associateWith { drive.files.getValue(it).bytes.toList() }
        now += 1_000
        android.syncNow()
        assertEquals("from iPhone", androidDomain.note("n2")?.content)
        assertEquals(androidBytes.keys, androidFiles)
    }

    @Test
    fun should_turnPeerOff_when_cloudDataIsDeletedElsewhere() = runTest {
        val (android, androidDomain) = device("Pixel Tablet", readOnly = false)
        androidDomain.putNote(note("n1", "hello"))
        android.enable("token")
        val backups = Backups()
        val (ios, _) = device("iPhone", readOnly = true, backups = backups)
        now += 1_000
        ios.enable("token")
        now += 61_000
        ios.syncNow()

        now += 1_000
        assertNull(ios.deleteCloudData())

        // Backed up before deleting; only the reset marker is left; this device is off.
        assertEquals(2, backups.sets.size)
        assertEquals(listOf(SyncFileProps.KIND_RESET), drive.files.values.map { it.props[SyncFileProps.KIND] })
        assertEquals(CloudSyncPhase.Off, ios.currentState().phase)

        now += 61_000
        android.syncNow()
        val phase = android.currentState().phase
        assertIs<CloudSyncPhase.ResetElsewhere>(phase)
        assertEquals("iPhone", phase.deviceName)
        assertNotNull(androidDomain.note("n1"), "local data stays")
    }
}
