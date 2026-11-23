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

    @Test
    fun should_syncTwoAppleDevicesThroughICloudAndSupportAccountPurge() = runTest {
        val icloud = FakeDrive()
        val iphoneDomain = FakeSyncDomain().apply { profileList += profile }
        val ipadDomain = FakeSyncDomain().apply { profileList += profile }
        val icloudTransport = object : SyncTransport by icloud.transport() {
            override suspend fun about() = GoogleAccountInfo("icloud:_user123", null, "iCloud")
        }
        val iphone = CloudSyncCore(
            store = InMemorySyncStore(),
            adapters = { SharedSyncAdapters.create(iphoneDomain, it) },
            profiles = { iphoneDomain.profiles() },
            tokens = object : SyncTokenProvider {
                override suspend fun token() = error("iCloud must not ask for Google tokens")
                override suspend fun invalidate(token: String) = Unit
            },
            transportFactory = { error("iCloud must not construct Drive client") },
            deviceName = { "iPhone 17 Pro" },
            versionCode = 36,
            clock = { now },
            readOnlyFirstPull = false,
            icloudTransport = icloudTransport,
        )
        val ipad = CloudSyncCore(
            store = InMemorySyncStore(),
            adapters = { SharedSyncAdapters.create(ipadDomain, it) },
            profiles = { ipadDomain.profiles() },
            tokens = object : SyncTokenProvider {
                override suspend fun token() = error("iCloud must not ask for Google tokens")
                override suspend fun invalidate(token: String) = Unit
            },
            transportFactory = { error("iCloud must not construct Drive client") },
            deviceName = { "iPad Pro" },
            versionCode = 36,
            clock = { now },
            readOnlyFirstPull = false,
            icloudTransport = icloudTransport,
        )

        iphoneDomain.putNote(note("n1", "from iphone via icloud"))
        assertNull(iphone.enableICloud())
        assertEquals(SyncLocations.ICLOUD, iphone.currentState().location)
        assertEquals(CloudSyncPhase.UpToDate, iphone.currentState().phase)

        now += 1_000
        assertNull(ipad.enableICloud())
        assertEquals("from iphone via icloud", ipadDomain.note("n1")?.content)
        val scope = iphone.currentState().accounts.single().syncProfileId
        assertNotNull(scope)

        // Remove account from iCloud: writes yoin-purge-* marker, purges iPhone replica, stops iPad from re-uploading.
        now += 1_000
        assertNull(iphone.removeAccountFromCloud(scope))
        assertEquals(SyncAccountStatus.RemovedFromCloud, iphone.currentState().accounts.single().status)
        assertTrue(icloud.files.values.any { it.props[SyncFileProps.KIND] == SyncFileProps.KIND_PURGE })

        now += 1_000
        ipad.syncNow()
        assertEquals(SyncAccountStatus.RemovedFromCloud, ipad.currentState().accounts.single().status)
        // Local data on both devices stays intact.
        assertEquals("from iphone via icloud", iphoneDomain.note("n1")?.content)
        assertEquals("from iphone via icloud", ipadDomain.note("n1")?.content)
    }

    @Test
    fun should_switchFromDriveToICloudWith4StepVerificationAndNotifyPeers() = runTest {
        val icloud = FakeDrive()
        val icloudTransport = object : SyncTransport by icloud.transport() {
            override suspend fun about() = GoogleAccountInfo("icloud:_user123", null, "iCloud")
        }
        val iphoneDomain = FakeSyncDomain().apply { profileList += profile }
        val backups = Backups()
        val iphone = CloudSyncCore(
            store = InMemorySyncStore(),
            adapters = { SharedSyncAdapters.create(iphoneDomain, it) },
            profiles = { iphoneDomain.profiles() },
            tokens = object : SyncTokenProvider {
                override suspend fun token() = "t"
                override suspend fun invalidate(token: String) = Unit
            },
            transportFactory = { drive.transport() },
            deviceName = { "iPhone 17 Pro" },
            versionCode = 36,
            clock = { now },
            readOnlyFirstPull = false,
            backupSink = backups,
            icloudTransport = icloudTransport,
        )
        val (peerOnDrive, _) = device("Pixel Tablet", readOnly = false)

        iphoneDomain.putNote(note("n1", "carry over"))
        assertNull(iphone.enable("token"))
        now += 1_000
        assertNull(peerOnDrive.enable("token"))

        // If target fails mid-switch, old Drive location stays untouched.
        icloud.offline = true
        val failed = iphone.switchLocation(SyncLocations.ICLOUD)
        assertTrue(!failed.succeeded)
        assertEquals(SyncLocations.GOOGLE_DRIVE, iphone.currentState().location)
        assertTrue(drive.files.values.none { it.props[SyncFileProps.KIND] == SyncFileProps.KIND_MIGRATED })

        // Target online: switch succeeds, verifies counts, keeps old copy, writes migration marker to Drive.
        icloud.offline = false
        now += 1_000
        val result = iphone.switchLocation(SyncLocations.ICLOUD, clearOldCopy = false)
        assertTrue(result.succeeded)
        assertTrue(result.countsVerified)
        assertTrue(result.migrationMarkerWritten)
        assertTrue(!result.oldCopyCleared)
        assertNotNull(result.snapshotSha256)
        assertEquals(1, result.totalLiveRecords)
        assertEquals(SyncLocations.ICLOUD, iphone.currentState().location)
        assertTrue(icloud.files.values.any { it.props[SyncFileProps.KIND] == SyncFileProps.KIND_STATE })
        assertTrue(drive.files.values.any { it.props[SyncFileProps.KIND] == SyncFileProps.KIND_MIGRATED })

        // Peer still on Drive sees MigratedElsewhere banner and pauses publishing until user dismisses.
        now += 1_000
        peerOnDrive.syncNow()
        val phase = assertIs<CloudSyncPhase.MigratedElsewhere>(peerOnDrive.currentState().phase)
        assertEquals(SyncLocations.ICLOUD, phase.targetLocation)
        assertEquals("iPhone 17 Pro", phase.deviceName)

        // Dismissing migration notice resumes syncing on Drive.
        now += 1_000
        peerOnDrive.dismissMigrationNotice()
        assertEquals(CloudSyncPhase.UpToDate, peerOnDrive.currentState().phase)

        // Optional Step 4 cleanup deletes old Drive files except the migration marker.
        assertNull(iphone.clearOldLocationCopy(SyncLocations.GOOGLE_DRIVE))
        assertTrue(drive.files.values.all { it.props[SyncFileProps.KIND] == SyncFileProps.KIND_MIGRATED })
    }

    @Test
    fun should_pullPeerChangesBeforeSwitchingSoNothingIsLostOnCleanup() = runTest {
        val icloud = FakeDrive()
        val icloudTransport = object : SyncTransport by icloud.transport() {
            override suspend fun about() = GoogleAccountInfo("icloud:_user123", null, "iCloud")
        }
        val iphoneDomain = FakeSyncDomain().apply { profileList += profile }
        val backups = Backups()
        val iphone = CloudSyncCore(
            store = InMemorySyncStore(),
            adapters = { SharedSyncAdapters.create(iphoneDomain, it) },
            profiles = { iphoneDomain.profiles() },
            tokens = object : SyncTokenProvider {
                override suspend fun token() = "t"
                override suspend fun invalidate(token: String) = Unit
            },
            transportFactory = { drive.transport() },
            deviceName = { "iPhone 17 Pro" },
            versionCode = 36,
            clock = { now },
            readOnlyFirstPull = false,
            backupSink = backups,
            icloudTransport = icloudTransport,
        )
        val (pixel, pixelDomain) = device("Pixel Tablet", readOnly = false)

        iphoneDomain.putNote(note("n1", "from iphone"))
        assertNull(iphone.enable("token"))
        now += 1_000
        assertNull(pixel.enable("token"))

        // Pixel adds n2 to Drive AFTER iPhone's last sync; iPhone switches without calling syncNow() first.
        now += 1_000
        pixelDomain.putNote(note("n2", "from pixel before switch"))
        pixel.localChanged()

        now += 1_000
        val result = iphone.switchLocation(SyncLocations.ICLOUD, clearOldCopy = false)
        assertTrue(result.succeeded)
        assertEquals("from pixel before switch", iphoneDomain.note("n2")?.content)
        assertEquals(2, result.totalLiveRecords)

        // Even after clearing Drive, iCloud has both n1 and n2 (verified by second Apple device).
        assertNull(iphone.clearOldLocationCopy(SyncLocations.GOOGLE_DRIVE))
        val ipadDomain = FakeSyncDomain().apply { profileList += profile }
        val ipad = CloudSyncCore(
            store = InMemorySyncStore(),
            adapters = { SharedSyncAdapters.create(ipadDomain, it) },
            profiles = { ipadDomain.profiles() },
            tokens = object : SyncTokenProvider {
                override suspend fun token() = error("unused")
                override suspend fun invalidate(token: String) = Unit
            },
            transportFactory = { error("unused") },
            deviceName = { "iPad Pro" },
            versionCode = 36,
            clock = { now },
            readOnlyFirstPull = false,
            icloudTransport = icloudTransport,
        )
        now += 1_000
        assertNull(ipad.enableICloud())
        assertEquals("from iphone", ipadDomain.note("n1")?.content)
        assertEquals("from pixel before switch", ipadDomain.note("n2")?.content)
    }

    @Test
    fun should_requireBackupAndPreserveResetAndPurgeMarkersWhenClearingOldCopy() = runTest {
        val icloud = FakeDrive()
        val icloudTransport = object : SyncTransport by icloud.transport() {
            override suspend fun about() = GoogleAccountInfo("icloud:_user123", null, "iCloud")
        }
        val iphoneDomain = FakeSyncDomain().apply { profileList += profile }
        var failBackup = false
        val backups = object : SyncBackupSink {
            var count = 0
            override fun save(files: List<SyncBackupFile>, manifestJson: String): String? {
                if (failBackup) return null
                count++
                return "backup-$count"
            }
        }
        val iphone = CloudSyncCore(
            store = InMemorySyncStore(),
            adapters = { SharedSyncAdapters.create(iphoneDomain, it) },
            profiles = { iphoneDomain.profiles() },
            tokens = object : SyncTokenProvider {
                override suspend fun token() = "t"
                override suspend fun invalidate(token: String) = Unit
            },
            transportFactory = { drive.transport() },
            deviceName = { "iPhone 17 Pro" },
            versionCode = 36,
            clock = { now },
            readOnlyFirstPull = false,
            backupSink = backups,
            icloudTransport = icloudTransport,
        )
        // Plant an older reset marker and a scope purge marker on Drive before iPhone joins at that epoch.
        drive.files["reset-old"] = FakeDrive.File(
            "reset-old", "yoin-reset-100-old.json",
            mapOf(SyncFileProps.KIND to SyncFileProps.KIND_RESET, SyncFileProps.EPOCH to "100", SyncFileProps.DEVICE_ID to "old"),
            "{}".encodeToByteArray(), 1L,
        )
        drive.files["purge-1"] = FakeDrive.File(
            "purge-1", "yoin-purge-200-old.json",
            mapOf(
                SyncFileProps.KIND to SyncFileProps.KIND_PURGE,
                SyncFileProps.EPOCH to "200",
                SyncFileProps.SCOPE to "sp-removed",
                SyncFileProps.DEVICE_ID to "old",
            ),
            "{}".encodeToByteArray(), 1L,
        )

        iphoneDomain.putNote(note("n1", "keep"))
        assertNull(iphone.enable("token"))

        now += 1_000
        assertTrue(iphone.switchLocation(SyncLocations.ICLOUD, clearOldCopy = false).succeeded)

        // When backup fails, clearOldLocationCopy must refuse and delete nothing.
        val countBefore = drive.files.size
        failBackup = true
        val err = iphone.clearOldLocationCopy(SyncLocations.GOOGLE_DRIVE)
        assertNotNull(err)
        assertEquals(countBefore, drive.files.size)

        // When backup succeeds, clearOldLocationCopy backs up first, removes state files,
        // and preserves migrated + purge + newest reset markers.
        failBackup = false
        val backupsBefore = backups.count
        assertNull(iphone.clearOldLocationCopy(SyncLocations.GOOGLE_DRIVE))
        assertEquals(backupsBefore + 1, backups.count)
        assertTrue(drive.files.values.none { it.props[SyncFileProps.KIND] == SyncFileProps.KIND_STATE })
        assertTrue(drive.files.containsKey("reset-old"))
        assertTrue(drive.files.containsKey("purge-1"))
        assertTrue(drive.files.values.any { it.props[SyncFileProps.KIND] == SyncFileProps.KIND_MIGRATED })
    }

    @Test
    fun should_keepDriveIdentityIntactAfterSwitchingToICloudEvenAcrossTokenRefresh() = runTest {
        val icloud = FakeDrive()
        val icloudTransport = object : SyncTransport by icloud.transport() {
            override suspend fun about() = GoogleAccountInfo("icloud:_user123", null, "iCloud")
        }
        val iphoneDomain = FakeSyncDomain().apply { profileList += profile }
        var currentGoogleToken = "token-v1"
        val iphone = CloudSyncCore(
            store = InMemorySyncStore(),
            adapters = { SharedSyncAdapters.create(iphoneDomain, it) },
            profiles = { iphoneDomain.profiles() },
            tokens = object : SyncTokenProvider {
                override suspend fun token() = currentGoogleToken
                override suspend fun invalidate(token: String) = Unit
            },
            // Invoke provider.token() on every Drive call so IdentityCheckedTokens is exercised for real!
            transportFactory = { provider ->
                val raw = drive.transport()
                object : SyncTransport {
                    override suspend fun list(): List<RemoteFile> {
                        provider.token()
                        return raw.list()
                    }
                    override suspend fun download(fileId: String): ByteArray? {
                        provider.token()
                        return raw.download(fileId)
                    }
                    override suspend fun create(
                        fileId: String,
                        name: String,
                        appProperties: Map<String, String>,
                        mimeType: String,
                        bytes: ByteArray,
                    ): RemoteFile {
                        provider.token()
                        return raw.create(fileId, name, appProperties, mimeType, bytes)
                    }
                    override suspend fun update(fileId: String, bytes: ByteArray): RemoteFile? {
                        provider.token()
                        return raw.update(fileId, bytes)
                    }
                    override suspend fun delete(fileId: String): Boolean {
                        provider.token()
                        return raw.delete(fileId)
                    }
                    override suspend fun generateIds(count: Int): List<String> {
                        provider.token()
                        return raw.generateIds(count)
                    }
                    override suspend fun about(): GoogleAccountInfo {
                        provider.token()
                        return raw.about()
                    }
                }
            },
            deviceName = { "iPhone 17 Pro" },
            versionCode = 36,
            clock = { now },
            readOnlyFirstPull = false,
            backupSink = Backups(),
            icloudTransport = icloudTransport,
        )

        iphoneDomain.putNote(note("n1", "drive note"))
        assertNull(iphone.enable("token-v1"))
        now += 1_000
        assertTrue(iphone.switchLocation(SyncLocations.ICLOUD).succeeded)

        // Simulate 1-hour token refresh (new token string forces IdentityCheckedTokens to re-verify against Drive owner).
        currentGoogleToken = "token-v2-refreshed"
        // A token from a different Google account must be rejected before deleting anything.
        val otherDrive = FakeDrive()
        val wrongAccountCore = CloudSyncCore(
            store = InMemorySyncStore(),
            adapters = { SharedSyncAdapters.create(iphoneDomain, it) },
            profiles = { iphoneDomain.profiles() },
            tokens = object : SyncTokenProvider {
                override suspend fun token() = "token-v1"
                override suspend fun invalidate(token: String) = Unit
            },
            transportFactory = { provider ->
                val raw = otherDrive.transport()
                object : SyncTransport by raw {
                    override suspend fun about(): GoogleAccountInfo {
                        val tok = provider.token()
                        return if (tok == "wrong-user-token") {
                            GoogleAccountInfo("perm-OTHER", "other@example.com", "Other")
                        } else {
                            GoogleAccountInfo("perm-1", "me@example.com", "Me")
                        }
                    }
                }
            },
            deviceName = { "iPhone 17 Pro" },
            versionCode = 36,
            clock = { now },
            readOnlyFirstPull = false,
            backupSink = Backups(),
            icloudTransport = icloudTransport,
        )
        assertNull(wrongAccountCore.enable("token-v1"))
        now += 1_000
        assertTrue(wrongAccountCore.switchLocation(SyncLocations.ICLOUD).succeeded)
        val countBeforeWrongClean = otherDrive.files.size
        val wrongErr = wrongAccountCore.clearOldLocationCopy(SyncLocations.GOOGLE_DRIVE, accessToken = "wrong-user-token")
        assertNotNull(wrongErr)
        assertEquals(countBeforeWrongClean, otherDrive.files.size)

        // Same Google account after token refresh succeeds cleanly.
        assertNull(iphone.clearOldLocationCopy(SyncLocations.GOOGLE_DRIVE))
        assertTrue(drive.files.values.none { it.props[SyncFileProps.KIND] == SyncFileProps.KIND_STATE })
    }

    @Test
    fun should_notBounceBackOnStaleMigrationMarkerWhenSwitchingBackToOldLocation() = runTest {
        val icloud = FakeDrive()
        val icloudTransport = object : SyncTransport by icloud.transport() {
            override suspend fun about() = GoogleAccountInfo("icloud:_user123", null, "iCloud")
        }
        fun appleDevice(name: String): Pair<CloudSyncCore, FakeSyncDomain> {
            val dom = FakeSyncDomain().apply { profileList += profile }
            val core = CloudSyncCore(
                store = InMemorySyncStore(),
                adapters = { SharedSyncAdapters.create(dom, it) },
                profiles = { dom.profiles() },
                tokens = object : SyncTokenProvider {
                    override suspend fun token() = "t"
                    override suspend fun invalidate(token: String) = Unit
                },
                transportFactory = { drive.transport() },
                deviceName = { name },
                versionCode = 36,
                clock = { now },
                readOnlyFirstPull = false,
                icloudTransport = icloudTransport,
            )
            return core to dom
        }

        val (devA, domA) = appleDevice("iPhone")
        val (devB, _) = appleDevice("iPad")
        domA.putNote(note("n1", "hello"))
        assertNull(devA.enable("token"))
        now += 1_000
        assertNull(devB.enable("token"))

        // A switches Drive -> iCloud, then B sees the MigratedElsewhere banner and directly taps
        // "Switch to iCloud" WITHOUT dismissing first (follow-the-leader banner flow).
        now += 1_000
        assertTrue(devA.switchLocation(SyncLocations.ICLOUD).succeeded)
        now += 1_000
        devB.syncNow()
        assertIs<CloudSyncPhase.MigratedElsewhere>(devB.currentState().phase)
        now += 1_000
        assertTrue(devB.switchLocation(SyncLocations.ICLOUD).succeeded)

        // A switches back iCloud -> Drive; A must NOT bounce back to iCloud on B's older Drive marker!
        now += 1_000
        val back = devA.switchLocation(SyncLocations.GOOGLE_DRIVE, targetAccessToken = "token")
        assertTrue(back.succeeded)
        assertEquals(SyncLocations.GOOGLE_DRIVE, devA.currentState().location)
        now += 1_000
        devA.syncNow()
        assertEquals(CloudSyncPhase.UpToDate, devA.currentState().phase)
    }

    @Test
    fun should_persistPendingMigrationAndRetryAutomaticallyWhenMarkerWriteFails() = runTest {
        val icloud = FakeDrive()
        val icloudTransport = object : SyncTransport by icloud.transport() {
            override suspend fun about() = GoogleAccountInfo("icloud:_user123", null, "iCloud")
        }
        val iphoneDomain = FakeSyncDomain().apply { profileList += profile }
        val iphone = CloudSyncCore(
            store = InMemorySyncStore(),
            adapters = { SharedSyncAdapters.create(iphoneDomain, it) },
            profiles = { iphoneDomain.profiles() },
            tokens = object : SyncTokenProvider {
                override suspend fun token() = "t"
                override suspend fun invalidate(token: String) = Unit
            },
            transportFactory = { drive.transport() },
            deviceName = { "iPhone 17 Pro" },
            versionCode = 36,
            clock = { now },
            readOnlyFirstPull = false,
            icloudTransport = icloudTransport,
        )
        iphoneDomain.putNote(note("n1", "note"))
        assertNull(iphone.enable("token"))

        // Drive fails creates right when Step 4 writes the migration marker.
        drive.failCreates = true
        now += 1_000
        val res = iphone.switchLocation(SyncLocations.ICLOUD)
        assertTrue(res.succeeded)
        assertTrue(!res.migrationMarkerWritten)
        assertTrue(iphone.currentState().pendingMigrationNotice)
        assertTrue(drive.files.values.none { it.props[SyncFileProps.KIND] == SyncFileProps.KIND_MIGRATED })

        // Drive recovers: next sync cycle automatically writes the pending migration marker and clears the flag.
        drive.failCreates = false
        now += 1_000
        iphone.syncNow()
        assertTrue(!iphone.currentState().pendingMigrationNotice)
        assertTrue(drive.files.values.any { it.props[SyncFileProps.KIND] == SyncFileProps.KIND_MIGRATED })
    }

    @Test
    fun should_rollBackStoreCleanlyAndDeleteOrphanUploadsWhenVerificationFails() = runTest {
        val icloud = FakeDrive()
        var corruptReadBack = false
        val icloudTransport = object : SyncTransport by icloud.transport() {
            override suspend fun about() = GoogleAccountInfo("icloud:_user123", null, "iCloud")
            override suspend fun download(fileId: String): ByteArray? {
                val real = icloud.transport().download(fileId)
                return if (corruptReadBack && real != null) real.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() } else real
            }
        }
        val iphoneDomain = FakeSyncDomain().apply { profileList += profile }
        val iphone = CloudSyncCore(
            store = InMemorySyncStore(),
            adapters = { SharedSyncAdapters.create(iphoneDomain, it) },
            profiles = { iphoneDomain.profiles() },
            tokens = object : SyncTokenProvider {
                override suspend fun token() = "t"
                override suspend fun invalidate(token: String) = Unit
            },
            transportFactory = { drive.transport() },
            deviceName = { "iPhone 17 Pro" },
            versionCode = 36,
            clock = { now },
            readOnlyFirstPull = false,
            icloudTransport = icloudTransport,
        )
        iphoneDomain.putNote(note("n1", "local"))
        assertNull(iphone.enable("token"))
        val scope = iphone.currentState().accounts.single().syncProfileId!!

        // Plant a purge marker on iCloud for this scope; if switch fails during Step 3 verification,
        // the purge must be rolled back (account stays Syncing on Drive) and orphan iCloud upload deleted.
        icloud.files["purge-target"] = FakeDrive.File(
            "purge-target", "yoin-purge-${now + 500}-other.json",
            mapOf(
                SyncFileProps.KIND to SyncFileProps.KIND_PURGE,
                SyncFileProps.EPOCH to (now + 500).toString(),
                SyncFileProps.SCOPE to scope,
                SyncFileProps.DEVICE_ID to "other",
            ),
            "{}".encodeToByteArray(), 1L,
        )

        corruptReadBack = true
        now += 1_000
        val failed = iphone.switchLocation(SyncLocations.ICLOUD)
        assertTrue(!failed.succeeded)
        assertEquals(SyncLocations.GOOGLE_DRIVE, iphone.currentState().location)
        assertEquals(SyncAccountStatus.Syncing, iphone.currentState().accounts.single().status)
        // Orphan newly uploaded state file on iCloud was cleaned up.
        assertTrue(icloud.files.values.none { it.props[SyncFileProps.KIND] == SyncFileProps.KIND_STATE })
    }

    @Test
    fun should_notSuppressFutureMigrationMarkersWhenLocalClockIsAhead() = runTest {
        val (peer, _) = device("iPad", readOnly = false)
        assertNull(peer.enable("token"))

        // Peer's local clock is far ahead (+10 hours) of the remote migration marker's epoch.
        val marker1Epoch = now + 1_000
        drive.files["mig-1"] = FakeDrive.File(
            "mig-1", SyncFileProps.migratedFileName(marker1Epoch, "other"),
            mapOf(
                SyncFileProps.KIND to SyncFileProps.KIND_MIGRATED,
                SyncFileProps.EPOCH to marker1Epoch.toString(),
                SyncFileProps.DEVICE_ID to "other",
                SyncFileProps.TARGET_LOCATION to SyncLocations.ICLOUD,
            ),
            "{}".encodeToByteArray(), 1L,
        )
        now += 36_000_000 // +10h clock skew
        peer.syncNow()
        assertIs<CloudSyncPhase.MigratedElsewhere>(peer.currentState().phase)

        // Dismiss records marker1Epoch (NOT peer's skewed clock).
        peer.dismissMigrationNotice()
        assertEquals(CloudSyncPhase.UpToDate, peer.currentState().phase)

        // A newer marker with epoch = marker1Epoch + 5000 (still < skewed local clock) MUST still trigger!
        val marker2Epoch = marker1Epoch + 5_000
        drive.files["mig-2"] = FakeDrive.File(
            "mig-2", SyncFileProps.migratedFileName(marker2Epoch, "other"),
            mapOf(
                SyncFileProps.KIND to SyncFileProps.KIND_MIGRATED,
                SyncFileProps.EPOCH to marker2Epoch.toString(),
                SyncFileProps.DEVICE_ID to "other",
                SyncFileProps.TARGET_LOCATION to SyncLocations.ICLOUD,
            ),
            "{}".encodeToByteArray(), 1L,
        )
        peer.syncNow()
        assertIs<CloudSyncPhase.MigratedElsewhere>(peer.currentState().phase)
    }
}
