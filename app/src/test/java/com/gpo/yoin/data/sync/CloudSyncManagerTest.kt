package com.gpo.yoin.data.sync

import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.local.GeminiConfig
import com.gpo.yoin.data.local.LocalRating
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.profile.InMemoryProfileCredentialsStore
import com.gpo.yoin.data.profile.PlaintextProfileCredentialsCodec
import com.gpo.yoin.data.profile.ProfileActiveIdStore
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.sync.engine.SnapshotCodec
import com.gpo.yoin.data.sync.testing.FakeSeamStyleGateway
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * End to end through the real manager, engine and adapters: devices with
 * their own app DB and sync DB, sharing one in-memory Drive.
 */
@RunWith(RobolectricTestRunner::class)
class CloudSyncManagerTest {
    private val drive = FakeDrive()
    private val clock = AtomicLong(1_700_000_000_000L)
    private val devices = mutableListOf<Device>()

    @After
    fun tearDown() {
        devices.forEach { it.close() }
    }

    @Test
    fun should_mergeNotesAndRatingsBothWays_when_twoDevicesShareAnAccount() = runBlocking {
        val phone = device("Pixel 9")
        val tablet = device("Pixel Tablet")
        val phoneProfile = phone.addSubsonic("https://Music.Example.com/", "Alice")
        val tabletProfile = tablet.addSubsonic("http://music.example.com", "alice")
        phone.addNote(phoneProfile, "n-phone", "from phone")
        phone.rate(phoneProfile, "song-1", 8f)
        tablet.addNote(tabletProfile, "n-tablet", "from tablet")

        phone.turnOn()
        tablet.turnOn()
        phone.sync()

        assertEquals(setOf("n-phone", "n-tablet"), tablet.noteIds(tabletProfile))
        assertEquals(setOf("n-phone", "n-tablet"), phone.noteIds(phoneProfile))
        assertEquals(8f, tablet.rating(tabletProfile, "song-1"))
        assertEquals(CloudSyncPhase.UpToDate, tablet.manager.state.value.phase)
    }

    @Test
    fun should_deleteNoteOnOtherDevice_when_deletedLocally() = runBlocking {
        val phone = device("Pixel 9")
        val tablet = device("Pixel Tablet")
        val p = phone.addSubsonic("https://music.example.com", "alice")
        val t = tablet.addSubsonic("https://music.example.com", "alice")
        phone.addNote(p, "n1", "one")
        phone.addNote(p, "n2", "two")
        phone.turnOn()
        tablet.turnOn()
        assertEquals(setOf("n1", "n2"), tablet.noteIds(t))

        phone.db.syncDomainDao().deleteNote("n1", p)
        phone.sync()
        tablet.sync()

        assertEquals(setOf("n2"), tablet.noteIds(t))
    }

    @Test
    fun should_neverUploadSecrets_when_publishing() = runBlocking {
        val phone = device("Pixel 9")
        val p = phone.addSubsonic("https://music.example.com", "alice", password = "PASSWORD-SENTINEL")
        phone.db.geminiConfigDao().upsert(GeminiConfig(apiKey = "APIKEY-SENTINEL", targetLanguage = "Japanese"))
        phone.addNote(p, "n1", "hello")
        phone.turnOn()

        val everything = drive.allContents()
        assertTrue("something was uploaded", everything.contains("hello"))
        assertTrue("language syncs", everything.contains("Japanese"))
        assertFalse(everything.contains("PASSWORD-SENTINEL"))
        assertFalse(everything.contains("APIKEY-SENTINEL"))
    }

    @Test
    fun should_turnOtherDeviceOffWithoutReuploading_when_cloudDataIsDeleted() = runBlocking {
        val phone = device("Pixel 9")
        val tablet = device("Pixel Tablet")
        val p = phone.addSubsonic("https://music.example.com", "alice")
        tablet.addSubsonic("https://music.example.com", "alice")
        phone.addNote(p, "n1", "one")
        phone.turnOn()
        tablet.turnOn()

        assertTrue(phone.manager.deleteCloudData().isSuccess)
        assertTrue("only the reset marker is left", drive.onlyResetMarkers())

        tablet.sync()

        val phase = tablet.manager.state.value.phase
        assertTrue("tablet turned itself off: $phase", phase is CloudSyncPhase.ResetElsewhere)
        assertEquals("Pixel 9", (phase as CloudSyncPhase.ResetElsewhere).deviceName)
        assertTrue(drive.onlyResetMarkers())
        assertEquals("local data stays", setOf("n1"), phone.noteIds(p))
    }

    @Test
    fun should_joinNormally_when_newDeviceArrivesAfterAnOldReset() = runBlocking {
        val phone = device("Pixel 9")
        val p = phone.addSubsonic("https://music.example.com", "alice")
        phone.addNote(p, "n1", "one")
        phone.turnOn()
        phone.manager.deleteCloudData()
        phone.turnOn()

        val fresh = device("New phone")
        val f = fresh.addSubsonic("https://music.example.com", "alice")
        fresh.turnOn()

        assertTrue(fresh.manager.state.value.phase.enabled)
        assertEquals(setOf("n1"), fresh.noteIds(f))
    }

    @Test
    fun should_leaveOtherDeviceSyncing_when_turnedOffOnOneDevice() = runBlocking {
        val phone = device("Pixel 9")
        val tablet = device("Pixel Tablet")
        val p = phone.addSubsonic("https://music.example.com", "alice")
        val t = tablet.addSubsonic("https://music.example.com", "alice")
        phone.turnOn()
        tablet.turnOn()

        phone.manager.turnOff()
        phone.awaitIdle()
        tablet.addNote(t, "n-late", "after phone left")
        tablet.sync()

        assertEquals(CloudSyncPhase.Off, phone.manager.state.value.phase)
        assertEquals(CloudSyncPhase.UpToDate, tablet.manager.state.value.phase)
        assertTrue(phone.noteIds(p).isEmpty())
        assertTrue(drive.allContents().contains("after phone left"))
    }

    @Test
    fun should_skipUpload_when_nothingChanged() = runBlocking {
        val phone = device("Pixel 9")
        val p = phone.addSubsonic("https://music.example.com", "alice")
        phone.addNote(p, "n1", "one")
        phone.turnOn()
        val writes = drive.writes

        phone.sync()
        phone.sync()

        assertEquals(writes, drive.writes)
    }

    @Test
    fun should_restoreDataForAccount_when_itIsAddedOnANewDevice() = runBlocking {
        val phone = device("Pixel 9")
        val p = phone.addSubsonic("https://music.example.com", "alice")
        phone.addNote(p, "n1", "one")
        phone.rate(p, "song-1", 6f)
        phone.turnOn()

        val fresh = device("New phone")
        fresh.turnOn()
        val cloudOnly = fresh.manager.state.value.cloudOnlyAccounts
        assertEquals(1, cloudOnly.size)
        assertEquals("alice @ music.example.com", cloudOnly.single().hint)

        val f = fresh.addSubsonic("https://music.example.com", "alice")
        fresh.sync()

        assertEquals(setOf("n1"), fresh.noteIds(f))
        assertEquals(6f, fresh.rating(f, "song-1"))
        assertTrue(fresh.manager.state.value.cloudOnlyAccounts.isEmpty())
    }

    @Test
    fun should_mergePeersInNewDrive_when_turnedOnWithAnotherAccountAfterDeleting() = runBlocking {
        val phone = device("Pixel 9")
        val p = phone.addSubsonic("https://music.example.com", "alice")
        phone.addNote(p, "n-phone", "phone")
        phone.turnOn()
        assertTrue(phone.manager.deleteCloudData().isSuccess)

        val driveB = FakeDrive("perm-2")
        val tablet = device("Pixel Tablet", on = driveB)
        val t = tablet.addSubsonic("https://music.example.com", "alice")
        tablet.addNote(t, "n-tablet", "tablet")
        tablet.turnOn()

        phone.useDrive(driveB)
        phone.turnOn()

        assertTrue("phone receives the new Drive's data", "n-tablet" in phone.noteIds(p))
        tablet.sync()
        assertTrue("n-phone" in tablet.noteIds(t))
    }

    @Test
    fun should_refuseToDelete_when_tokenReachesAnotherAccountsDrive() = runBlocking {
        val phone = device("Pixel 9")
        phone.addSubsonic("https://music.example.com", "alice")
        phone.turnOn()
        val driveB = FakeDrive("perm-2")
        val tablet = device("Pixel Tablet", on = driveB)
        tablet.addSubsonic("https://music.example.com", "bob")
        tablet.turnOn()

        phone.useDrive(driveB) // e.g. a token silently fell back to another account on the phone
        val result = phone.manager.deleteCloudData()

        assertTrue(result.isFailure)
        assertEquals("other account's data untouched", 1, driveB.stateFileCount())
        assertTrue(driveB.files().none { it.appProperties[SyncFileProps.KIND] == SyncFileProps.KIND_RESET })
    }

    @Test
    fun should_republishItsData_when_anotherDeviceRemovedItFromSync() = runBlocking {
        val phone = device("Pixel 9")
        val tablet = device("Pixel Tablet")
        phone.addSubsonic("https://music.example.com", "alice")
        val t = tablet.addSubsonic("https://music.example.com", "alice")
        tablet.addNote(t, "n-tablet", "only on tablet")
        phone.turnOn()
        tablet.turnOn()
        phone.sync()
        val tabletId = phone.manager.state.value.devices.single { !it.isThisDevice }.deviceId

        phone.manager.removeDevice(tabletId)
        phone.awaitIdle()
        assertFalse(drive.hasStateFileFrom(tabletId))
        tablet.sync()

        assertEquals(CloudSyncPhase.UpToDate, tablet.phase)
        assertTrue(drive.hasStateFileFrom(tabletId))
    }

    @Test
    fun should_turnOffInsteadOfReuploading_when_resetFollowsAFailedFirstPublish() = runBlocking {
        val phone = device("Pixel 9")
        val tablet = device("Pixel Tablet")
        val p = phone.addSubsonic("https://music.example.com", "alice")
        val t = tablet.addSubsonic("https://music.example.com", "alice")
        phone.addNote(p, "n1", "deleted later")
        phone.turnOn()
        drive.failCreatesOfKind = SyncFileProps.KIND_STATE
        tablet.turnOn()
        assertEquals(CloudSyncPhase.StorageFull, tablet.phase)
        assertTrue("tablet already merged the phone's data", "n1" in tablet.noteIds(t))

        drive.failCreatesOfKind = null
        assertTrue(phone.manager.deleteCloudData().isSuccess)
        tablet.sync()

        assertTrue(tablet.phase is CloudSyncPhase.ResetElsewhere)
        assertFalse("deleted data must not come back", drive.allContents().contains("deleted later"))
    }

    @Test
    fun should_honourDelete_when_deletingDevicesClockIsBehind() = runBlocking {
        val phone = device("Pixel 9")
        val tablet = device("Pixel Tablet")
        phone.addSubsonic("https://music.example.com", "alice")
        tablet.addSubsonic("https://music.example.com", "alice")
        phone.clockOffset = 10L * 24 * 60 * 60 * 1000
        phone.turnOn()
        tablet.turnOn()
        assertTrue(phone.manager.deleteCloudData().isSuccess) // epoch from a clock 10 days ahead
        tablet.sync()
        tablet.turnOn()
        phone.turnOn()

        assertTrue(tablet.manager.deleteCloudData().isSuccess) // tablet's clock is behind that epoch
        phone.sync()

        assertTrue(phone.phase is CloudSyncPhase.ResetElsewhere)
    }

    @Test
    fun should_finishDeleting_when_anInterruptedDeleteIsRetried() = runBlocking {
        val phone = device("Pixel 9")
        val tablet = device("Pixel Tablet")
        phone.addSubsonic("https://music.example.com", "alice")
        tablet.addSubsonic("https://music.example.com", "alice")
        phone.turnOn()
        tablet.turnOn()

        drive.failDeletes = 1
        assertTrue(phone.manager.deleteCloudData().isFailure)
        assertEquals("already off: never re-uploads", CloudSyncPhase.Off, phone.phase)
        assertTrue(phone.manager.deleteCloudData().isSuccess)

        assertTrue(drive.onlyResetMarkers())
        assertEquals(1, drive.files().size)
    }

    @Test
    fun should_staySyncOn_when_driveReportsMisconfiguredDuringACycle() = runBlocking {
        val phone = device("Pixel 9")
        phone.addSubsonic("https://music.example.com", "alice")
        phone.turnOn()

        drive.failListWith = SyncTransportException.Misconfigured("accessNotConfigured")
        phone.sync()
        assertTrue(phone.phase is CloudSyncPhase.Error)
        assertTrue(phone.phase.enabled)

        drive.failListWith = null
        phone.sync()
        assertEquals(CloudSyncPhase.UpToDate, phone.phase)
    }

    @Test
    fun should_restoreDefaultLayoutEverywhere_when_layoutIsClearedOnOneDevice() = runBlocking {
        val phone = device("Pixel 9")
        val tablet = device("Pixel Tablet")
        val p = phone.addSubsonic("https://music.example.com", "alice")
        val t = tablet.addSubsonic("https://music.example.com", "alice")
        phone.db.syncDomainDao().upsertHomeLayout(
            com.gpo.yoin.data.local.HomeLayoutPreference(p, """{"version":1,"sections":[]}""", clock.addAndGet(1_000)),
        )
        phone.turnOn()
        tablet.turnOn()
        assertNotNull(tablet.db.syncDomainDao().homeLayout(t))

        phone.db.syncDomainDao().deleteHomeLayout(p) // what HomeLayoutStore.clearLayout does on Restore default
        phone.sync()
        tablet.sync()
        phone.sync()

        assertNull("tablet back to default", tablet.db.syncDomainDao().homeLayout(t))
        assertNull("the old layout is not pulled back", phone.db.syncDomainDao().homeLayout(p))
    }

    // ---- harness

    private fun device(name: String, on: FakeDrive = drive): Device = Device(name, on, clock).also { devices += it }

    private class Device(name: String, drive: FakeDrive, private val clock: AtomicLong) {
        /** Which Drive this device's tokens currently reach (simulates account switches/fallbacks). */
        val transport = SwitchableTransport(drive)

        /** Simulated clock skew of this device. */
        var clockOffset = 0L
        private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db: YoinDatabase = Room.inMemoryDatabaseBuilder(context, YoinDatabase::class.java).build()
        private val syncDb = SyncDatabase.inMemory(context)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val credentials = InMemoryProfileCredentialsStore()
        val profileManager = ProfileManager(
            profileDao = db.profileDao(),
            activeIdStore = object : ProfileActiveIdStore {
                private var value: String? = null
                override fun read(): String? = value
                override fun write(id: String?) {
                    value = id
                }
            },
            credentialsStore = credentials,
            legacyCodec = PlaintextProfileCredentialsCodec(),
            scope = scope,
        )
        val manager = CloudSyncManager(
            context = context,
            database = db,
            profileManager = profileManager,
            versionName = "test",
            versionCode = 1,
            spotifyHttpClient = { OkHttpClient() },
            clock = { clock.addAndGet(1_000) + clockOffset },
            hooks = CloudSyncTestHooks(
                syncDb = syncDb,
                transport = transport,
                authorizer = FakeAuthorizer,
                deviceName = name,
                seam = FakeSeamStyleGateway(),
            ),
        )

        suspend fun addSubsonic(url: String, user: String, password: String = "pw"): String =
            profileManager.create("$user @ $url", ProfileCredentials.Subsonic(url, user, password)).id

        suspend fun addNote(profileId: String, id: String, content: String) {
            val now = clock.addAndGet(1_000)
            db.songNoteDao().insert(
                SongNote(
                    id = id,
                    profileId = profileId,
                    trackId = "track-$id",
                    provider = "subsonic",
                    content = content,
                    createdAt = now,
                    updatedAt = now,
                    title = "Title $id",
                    artist = "Artist",
                ),
            )
        }

        suspend fun rate(profileId: String, songId: String, rating: Float) {
            db.localRatingDao().upsert(
                LocalRating(
                    profileId = profileId,
                    songId = songId,
                    provider = "subsonic",
                    rating = rating,
                    serverRating = 0,
                    updatedAt = clock.addAndGet(1_000),
                ),
            )
        }

        suspend fun noteIds(profileId: String): Set<String> =
            db.syncDomainDao().notesForProfile(profileId).map { it.id }.toSet()

        suspend fun rating(profileId: String, songId: String): Float? =
            db.syncDomainDao().trackRating(profileId, songId, "subsonic")?.rating

        suspend fun turnOn() {
            val step = manager.beginTurnOn()
            assertEquals(TurnOnStep.Started, step)
            sync()
        }

        fun useDrive(drive: FakeDrive) {
            transport.drive = drive
        }

        val phase: CloudSyncPhase get() = manager.state.value.phase

        suspend fun sync() = manager.syncNowAndWait()

        /** Fire-and-forget actions run on the manager's scope; a cycle takes its lock after them. */
        suspend fun awaitIdle() {
            repeat(20) {
                kotlinx.coroutines.delay(25)
            }
        }

        fun close() {
            scope.cancel()
            db.close()
            syncDb.close()
        }
    }

    private object FakeAuthorizer : GoogleSyncAuthorizer {
        private val token = GoogleAuthResult.Token("token", listOf(GoogleSyncAuthorizer.SCOPE_DRIVE_APPDATA))

        override fun availability() = GoogleAuthAvailability.Available

        override suspend fun authorizeSilently(accountEmail: String?) = token

        override suspend fun authorizeInteractively() = token

        override fun resultFromIntent(data: Intent?) = token

        override suspend fun clearToken(accessToken: String) = Unit

        override suspend fun revoke(accountEmail: String?) = Unit

        override fun signingCertSha1(): String = "AA:BB"
    }

    private class SwitchableTransport(@Volatile var drive: FakeDrive) : SyncTransport {
        override suspend fun list() = drive.list()

        override suspend fun download(fileId: String) = drive.download(fileId)

        override suspend fun create(
            fileId: String,
            name: String,
            appProperties: Map<String, String>,
            mimeType: String,
            bytes: ByteArray,
        ) = drive.create(fileId, name, appProperties, mimeType, bytes)

        override suspend fun update(fileId: String, bytes: ByteArray) = drive.update(fileId, bytes)

        override suspend fun delete(fileId: String) = drive.delete(fileId)

        override suspend fun generateIds(count: Int) = drive.generateIds(count)

        override suspend fun about() = drive.about()
    }

    /** appDataFolder in memory, with Drive's versioning and 404/409 semantics. */
    private class FakeDrive(private val permissionId: String = "perm-1") : SyncTransport {
        /** Creates of files with this yoinKind fail with StorageFull. */
        @Volatile var failCreatesOfKind: String? = null

        /** The next N deletes fail with a network error. */
        @Volatile var failDeletes = 0

        @Volatile var failListWith: SyncTransportException? = null
        private data class Entry(val file: RemoteFile, val bytes: ByteArray)

        private val files = linkedMapOf<String, Entry>()
        private var nextId = 0
        var writes = 0
            private set

        @Synchronized
        fun files(): List<RemoteFile> = files.values.map { it.file }

        @Synchronized
        fun allContents(): String = files.values.joinToString("\n") { entry ->
            if (SnapshotCodec.isGzip(entry.bytes)) {
                java.util.zip.GZIPInputStream(entry.bytes.inputStream()).readBytes().decodeToString()
            } else {
                entry.bytes.decodeToString()
            }
        }

        override suspend fun list(): List<RemoteFile> {
            failListWith?.let { throw it }
            return files()
        }

        override suspend fun download(fileId: String): ByteArray? = synchronized(this) { files[fileId]?.bytes }

        override suspend fun create(
            fileId: String,
            name: String,
            appProperties: Map<String, String>,
            mimeType: String,
            bytes: ByteArray,
        ): RemoteFile {
            if (appProperties[SyncFileProps.KIND] == failCreatesOfKind) throw SyncTransportException.StorageFull("full")
            synchronized(this) {
                if (fileId in files) throw SyncTransportException.AlreadyExists(fileId)
                writes++
                val file = RemoteFile(
                    id = fileId,
                    name = name,
                    version = 1,
                    modifiedTime = null,
                    size = bytes.size.toLong(),
                    appProperties = appProperties,
                )
                files[fileId] = Entry(file, bytes)
                return file
            }
        }

        override suspend fun update(fileId: String, bytes: ByteArray): RemoteFile? {
            synchronized(this) {
                val entry = files[fileId] ?: return null
                writes++
                val file = entry.file.copy(version = entry.file.version + 1, size = bytes.size.toLong())
                files[fileId] = Entry(file, bytes)
                return file
            }
        }

        override suspend fun delete(fileId: String): Boolean {
            synchronized(this) {
                if (failDeletes > 0) {
                    failDeletes--
                    throw SyncTransportException.Network("offline")
                }
                files.remove(fileId)
            }
            return true
        }

        override suspend fun generateIds(count: Int): List<String> = synchronized(this) {
            List(count) { "id-${nextId++}" }
        }

        override suspend fun about() =
            GoogleAccountInfo(permissionId = permissionId, email = "$permissionId@example.com", displayName = "QA")

        fun onlyResetMarkers(): Boolean =
            files().all { it.appProperties[SyncFileProps.KIND] == SyncFileProps.KIND_RESET }

        fun hasStateFileFrom(deviceId: String): Boolean = files().any {
            it.appProperties[SyncFileProps.KIND] == SyncFileProps.KIND_STATE &&
                it.appProperties[SyncFileProps.DEVICE_ID] == deviceId
        }

        fun stateFileCount(): Int = files().count { it.appProperties[SyncFileProps.KIND] == SyncFileProps.KIND_STATE }
    }
}
