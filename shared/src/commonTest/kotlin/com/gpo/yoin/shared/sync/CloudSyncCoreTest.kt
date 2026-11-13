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
        /** Every create throws (a network drop / process death right before the marker is written). */
        var failCreates = false
        /** Files whose download returns bytes that are not a sync file. */
        val corrupt = mutableSetOf<String>()
        /** Google can't be reached: every request fails before it does anything. */
        var offline = false
        private var nextId = 0
        private var nextVersion = 1L

        private fun reach() {
            if (offline) throw SyncTransportException.Network("offline")
        }

        private fun listed() = files.values.map {
            RemoteFile(it.id, it.name, it.version, null, it.bytes.size.toLong(), it.props)
        }

        fun transport(): SyncTransport = object : SyncTransport {
            override suspend fun list(): List<RemoteFile> {
                reach()
                return listed()
            }

            override suspend fun download(fileId: String): ByteArray? {
                reach()
                return if (fileId in corrupt) "not a sync file".encodeToByteArray() else files[fileId]?.bytes
            }

            override suspend fun create(
                fileId: String,
                name: String,
                appProperties: Map<String, String>,
                mimeType: String,
                bytes: ByteArray,
            ): RemoteFile {
                reach()
                if (fileId in files) throw SyncTransportException.AlreadyExists(fileId)
                if (failCreates) throw SyncTransportException.Network("create failed")
                writes++
                files[fileId] = File(fileId, name, appProperties, bytes, nextVersion++)
                return listed().first { it.id == fileId }
            }

            override suspend fun update(fileId: String, bytes: ByteArray): RemoteFile? {
                reach()
                val file = files[fileId] ?: return null
                writes++
                file.bytes = bytes
                file.version = nextVersion++
                return listed().first { it.id == fileId }
            }

            override suspend fun delete(fileId: String): Boolean {
                reach()
                writes++
                files.remove(fileId)
                return true
            }

            override suspend fun generateIds(count: Int): List<String> {
                reach()
                return List(count) { "id-${nextId++}" }
            }

            override suspend fun about(): GoogleAccountInfo {
                reach()
                return GoogleAccountInfo("perm-1", "me@example.com", "Me")
            }
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

    private fun device(
        name: String,
        readOnly: Boolean,
        backups: SyncBackupSink? = null,
        store: InMemorySyncStore = InMemorySyncStore(),
        domain: FakeSyncDomain = FakeSyncDomain().apply { profileList += profile },
    ): Pair<CloudSyncCore, FakeSyncDomain> {
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

    @Test
    fun should_createMarkerBeforeDeletingAnything_when_interruptedResetResumes() = runTest {
        val (android, androidDomain) = device("Pixel Tablet", readOnly = false)
        androidDomain.putNote(note("n1", "hello"))
        android.enable("token")
        val store = InMemorySyncStore()
        val (ios, iosDomain) = device("iPhone", readOnly = false, backups = Backups(), store = store)
        now += 1_000
        ios.enable("token")
        val before = drive.files.keys.toSet()

        // The marker can't be created: PENDING_RESET is saved, nothing in Drive is touched.
        drive.failCreates = true
        now += 1_000
        assertNotNull(ios.deleteCloudData())
        assertEquals(before, drive.files.keys.toSet())
        assertNotNull(store.meta(SyncMetaKeys.PENDING_RESET))

        // Relaunch: the resume creates the marker first, turns this device off, then deletes the rest.
        drive.failCreates = false
        val (relaunched, _) = device("iPhone", readOnly = false, backups = Backups(), store = store, domain = iosDomain)
        relaunched.initialize()
        assertEquals(listOf(SyncFileProps.KIND_RESET), drive.files.values.map { it.props[SyncFileProps.KIND] })
        assertNull(store.meta(SyncMetaKeys.PENDING_RESET))
        assertEquals(CloudSyncPhase.Off, relaunched.currentState().phase)
        val epoch = store.meta(SyncMetaKeys.EPOCH)
        assertEquals(drive.files.values.single().props[SyncFileProps.EPOCH], epoch)

        // Peers see why their files went.
        now += 61_000
        android.syncNow()
        assertIs<CloudSyncPhase.ResetElsewhere>(android.currentState().phase)
    }

    @Test
    fun should_stayReadOnly_when_aPeerFileCannotBeRead() = runTest {
        val (android, androidDomain) = device("Pixel Tablet", readOnly = false)
        androidDomain.putNote(note("n1", "from Android"))
        android.enable("token")
        drive.corrupt += drive.files.keys
        val writes = drive.writes

        val (ios, _) = device("iPhone", readOnly = true, backups = Backups())
        now += 1_000
        ios.enable("token")
        assertTrue(ios.currentState().readOnly, "an unread peer file keeps the first pull read-only")
        now += 61_000
        ios.syncNow()
        assertTrue(ios.currentState().readOnly)
        assertEquals(writes, drive.writes, "nothing written to Drive while read-only")

        // Once it reads, the gate opens.
        drive.corrupt.clear()
        now += 61_000
        ios.syncNow()
        assertTrue(!ios.currentState().readOnly)
    }

    @Test
    fun should_keepRecordUnapplied_when_aDomainWriteFails() = runTest {
        val (android, androidDomain) = device("Pixel Tablet", readOnly = false)
        androidDomain.putNote(note("n1", "from Android"))
        android.enable("token")

        val (ios, iosDomain) = device("iPhone", readOnly = true, backups = Backups())
        iosDomain.failWrites = true
        now += 1_000
        ios.enable("token")
        assertNull(iosDomain.note("n1"))
        assertTrue(ios.currentState().readOnly, "a failed apply keeps the first pull read-only")

        // The disk recovers: the next cycle applies it.
        iosDomain.failWrites = false
        now += 61_000
        ios.syncNow()
        assertEquals("from Android", iosDomain.note("n1")?.content)
        assertTrue(!ios.currentState().readOnly)
    }

    /** Android CloudSyncManager.startFreshAccount: no purge while the old account's rows are not in the Drive. */
    @Test
    fun should_putOffStartFresh_when_theOldAccountsChangesAreNotUploaded() = runTest {
        val (core, domain) = device("iPhone", readOnly = false)
        domain.putNote(note("n1", "uploaded"))
        assertNull(core.enable("token"))
        val writes = drive.writes

        // A note written while Google can't be reached: captured, waiting to upload.
        drive.offline = true
        domain.putNote(note("n2", "not uploaded yet"))
        now += CloudSyncCore.LOCAL_DEBOUNCE_MS
        core.localChanged()
        assertEquals(CloudSyncPhase.Offline, core.currentState().phase)
        assertTrue(core.currentState().pendingChanges)

        // The profile signs in as someone else: its account pauses.
        domain.profileList[0] = profile.copy(subsonicUsername = "someone-else")
        core.localChanged()
        assertEquals(SyncAccountStatus.AccountChanged, core.currentState().accounts.single().status)

        // Start fresh while the upload still fails: put off, and nothing of the old account is touched.
        assertEquals(1, core.startFreshAccount("p"))
        assertEquals("uploaded", domain.note("n1")?.content)
        assertEquals("not uploaded yet", domain.note("n2")?.content)
        assertEquals(SyncAccountStatus.AccountChanged, core.currentState().accounts.single().status)
        assertTrue(core.currentState().pendingChanges)
        assertEquals(writes, drive.writes)

        // Google answers again: asked once more, the note goes up first and the account starts fresh.
        drive.offline = false
        now += 1_000
        assertEquals(0, core.startFreshAccount("p"))
        assertTrue(drive.writes > writes)
        assertNull(domain.note("n1"))
        assertNull(domain.note("n2"))
        assertEquals(SyncAccountStatus.Syncing, core.currentState().accounts.single().status)
        assertTrue(!core.currentState().pendingChanges)

        // The Drive kept the old account's rows: a device still signed in as it gets both notes.
        val (peer, peerDomain) = device("Pixel Tablet", readOnly = false)
        now += 1_000
        assertNull(peer.enable("token"))
        assertEquals("uploaded", peerDomain.note("n1")?.content)
        assertEquals("not uploaded yet", peerDomain.note("n2")?.content)
    }

    @Test
    fun should_startFreshAtOnce_when_everythingOfTheOldAccountIsUploaded() = runTest {
        val (core, domain) = device("iPhone", readOnly = false)
        domain.putNote(note("n1", "uploaded"))
        assertNull(core.enable("token"))
        domain.profileList[0] = profile.copy(subsonicUsername = "someone-else")
        now += 1_000
        core.syncNow()
        assertEquals(SyncAccountStatus.AccountChanged, core.currentState().accounts.single().status)

        assertEquals(0, core.startFreshAccount("p"))

        assertNull(domain.note("n1"))
        assertEquals(SyncAccountStatus.Syncing, core.currentState().accounts.single().status)
    }

    @Test
    fun should_doNothing_when_startFreshIsAskedForAnAccountThatIsNotPaused() = runTest {
        val (core, domain) = device("iPhone", readOnly = false)
        domain.putNote(note("n1", "uploaded"))
        assertNull(core.enable("token"))

        assertEquals(0, core.startFreshAccount("p"))

        assertEquals("uploaded", domain.note("n1")?.content)
        assertEquals(SyncAccountStatus.Syncing, core.currentState().accounts.single().status)
    }
}
