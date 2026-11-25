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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
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

    @Test
    fun should_syncAiAlbumTitleAndCopy_when_peerWroteThem() = runBlocking {
        val phone = device("Pixel 9")
        val fold = device("Fold")
        val p = phone.addSubsonic("https://music.example.com", "alice")
        val f = fold.addSubsonic("https://music.example.com", "alice")
        phone.db.syncDomainDao().upsertMemoryCopy(
            com.gpo.yoin.data.local.MemoryCopyCache(
                profileId = p, provider = "subsonic", entityType = "album", entityId = "al-1",
                copy = "Rainy-night record", promptHash = "h1", generatedAt = clock.addAndGet(1_000),
                title = "Neon Rain", titlePromptHash = "t1",
            ),
        )
        phone.turnOn()
        fold.turnOn()

        val row = fold.db.syncDomainDao().memoryCopy(f, "subsonic", "album", "al-1")
        assertEquals("Neon Rain", row?.title)
        assertEquals("t1", row?.titlePromptHash)
        assertEquals("Rainy-night record", row?.copy)

        // A newer copy elsewhere must not wipe the title.
        fold.db.syncDomainDao().upsertMemoryCopy(row!!.copy(copy = "New mood", promptHash = "h2", generatedAt = clock.addAndGet(1_000)))
        fold.sync()
        phone.sync()
        val back = phone.db.syncDomainDao().memoryCopy(p, "subsonic", "album", "al-1")
        assertEquals("New mood", back?.copy)
        assertEquals("Neon Rain", back?.title)
    }

    @Test
    fun should_syncAskGeminiQuestions_onlyIntoTheSameLanguage() = runBlocking {
        val phone = device("Pixel 9")
        val fold = device("Fold")
        phone.db.geminiConfigDao().upsert(GeminiConfig(apiKey = "k", targetLanguage = "Japanese"))
        phone.db.syncDomainDao().upsertSongAbout(
            com.gpo.yoin.data.local.SongAboutEntry(
                titleKey = "song", artistKey = "artist", albumKey = "album",
                titleDisplay = "Song", artistDisplay = "Artist", albumDisplay = "Album",
                kind = "ask", entryKey = "who wrote it", promptText = "Who wrote it?", titleText = "作者",
                answerText = "答え", createdAt = 1L, updatedAt = 2L,
            ),
        )
        fold.db.geminiConfigDao().upsert(GeminiConfig(apiKey = "", targetLanguage = "German"))
        fold.db.syncDomainDao().upsertSongAbout(
            com.gpo.yoin.data.local.SongAboutEntry(
                titleKey = "other", artistKey = "artist", albumKey = "album",
                titleDisplay = "Other", artistDisplay = "Artist", albumDisplay = "Album",
                kind = "canonical", entryKey = "review", promptText = null, titleText = null,
                answerText = "Gut", createdAt = 1L, updatedAt = 2L,
            ),
        )
        phone.turnOn()
        fold.turnOn()
        assertTrue("German device doesn't show Japanese answers", fold.db.syncDomainDao().allSongAbout().none { it.kind == "ask" })

        // The user switches the fold to Japanese (the app wipes About/Ask on a switch).
        fold.db.geminiConfigDao().upsert(GeminiConfig(apiKey = "", targetLanguage = "Japanese"))
        fold.db.songAboutEntryDao().deleteAll()
        fold.sync()

        val ask = fold.db.syncDomainDao().songAbout("song", "artist", "album", "ask", "who wrote it")
        assertEquals("Who wrote it?", ask?.promptText)
        phone.sync()
        assertTrue("the wipe didn't delete anything on the phone", phone.db.syncDomainDao().allSongAbout().any { it.kind == "ask" })
    }

    @Test
    fun should_syncTheListenersOwnEntries_whateverEachDevicesLanguage() = runBlocking {
        val phone = device("Pixel 9")
        val fold = device("Fold")
        phone.db.geminiConfigDao().upsert(GeminiConfig(apiKey = "k", targetLanguage = "Japanese"))
        fold.db.geminiConfigDao().upsert(GeminiConfig(apiKey = "", targetLanguage = "German"))
        phone.db.syncDomainDao().upsertSongAbout(
            com.gpo.yoin.data.local.SongAboutEntry(
                titleKey = "song", artistKey = "artist", albumKey = "album",
                titleDisplay = "Song", artistDisplay = "Artist", albumDisplay = "Album",
                kind = "own", entryKey = "first listen", promptText = null, titleText = "First listen",
                answerText = "On the night bus.", createdAt = 1L, updatedAt = 2L,
            ),
        )
        phone.turnOn()
        fold.turnOn()
        assertEquals(
            "On the night bus.",
            fold.db.syncDomainDao().songAbout("song", "artist", "album", "own", "first listen")?.answerText,
        )

        // A language switch keeps the listener's entries, and they stay one record in Drive.
        phone.db.geminiConfigDao().upsert(GeminiConfig(apiKey = "k", targetLanguage = "English"))
        phone.db.songAboutEntryDao().deleteGenerated()
        phone.sync()
        fold.sync()
        assertEquals(listOf("first listen"), fold.db.syncDomainDao().allSongAbout().filter { it.kind == "own" }.map { it.entryKey })
    }

    @Test
    fun should_syncUserAlbumTitle_andRestoreEverywhere_when_cleared() = runBlocking {
        val phone = device("Pixel 9")
        val fold = device("Fold")
        val p = phone.addSubsonic("https://music.example.com", "alice")
        val f = fold.addSubsonic("https://music.example.com", "alice")
        phone.db.syncDomainDao().upsertMemoryCopy(
            com.gpo.yoin.data.local.MemoryCopyCache(p, "subsonic", "album", "al-1", "copy", "h", clock.addAndGet(1_000), "AI Title", "t"),
        )
        phone.db.albumMemoryTitleDao().upsert(
            com.gpo.yoin.data.local.AlbumMemoryTitle(p, "subsonic", "al-1", "My Title", clock.addAndGet(1_000)),
        )
        phone.turnOn()
        fold.turnOn()

        assertEquals("My Title", fold.db.albumMemoryTitleDao().get(f, "subsonic", "al-1")?.title)
        assertEquals("AI title kept apart", "AI Title", fold.db.syncDomainDao().memoryCopy(f, "subsonic", "album", "al-1")?.title)

        phone.db.albumMemoryTitleDao().delete(p, "subsonic", "al-1") // Restore the AI title
        phone.sync()
        fold.sync()
        phone.sync()

        assertNull(fold.db.albumMemoryTitleDao().get(f, "subsonic", "al-1"))
        assertNull("not pulled back", phone.db.albumMemoryTitleDao().get(p, "subsonic", "al-1"))
    }

    // ---- removing one account from the cloud (scope purge marker)

    private fun scopeOf(url: String, user: String): String =
        com.gpo.yoin.data.sync.identity.AccountFingerprints.deterministicId(
            requireNotNull(com.gpo.yoin.data.sync.identity.AccountFingerprints.subsonic(url, user)),
        )

    private fun Device.status(profileId: String): SyncAccountStatus? =
        manager.state.value.accounts.firstOrNull { it.localProfileId == profileId }?.status

    @Test
    fun should_stopEveryDeviceFromReuploading_when_anAccountIsRemovedFromTheCloud() = runBlocking {
        val phone = device("Pixel 9")
        val tablet = device("Pixel Tablet")
        val p = phone.addSubsonic("https://music.example.com", "alice")
        val t = tablet.addSubsonic("https://music.example.com", "alice")
        val bob = phone.addSubsonic("https://music.example.com", "bob")
        phone.addNote(p, "n-alice", "alice's note")
        phone.addNote(bob, "n-bob", "bob's note")
        phone.turnOn()
        tablet.turnOn()
        assertEquals(setOf("n-alice"), tablet.noteIds(t))
        val alice = scopeOf("https://music.example.com", "alice")

        assertTrue(phone.manager.removeAccountFromCloud(alice).isSuccess)
        tablet.sync()
        tablet.addNote(t, "n-later", "written after the removal")
        tablet.sync()
        phone.sync()

        assertTrue(drive.files().any { it.appProperties[SyncFileProps.KIND] == SyncFileProps.KIND_PURGE })
        val contents = drive.allContents()
        assertFalse("no device publishes alice any more", contents.contains("alice's note"))
        assertFalse(contents.contains("written after the removal"))
        // The marker itself and the files' purge acknowledgements name the scope; nothing else may.
        val dataFiles = contents.lines()
            .filterNot { it.contains("\"scope\":") }
            .joinToString("\n")
            .replace(Regex("\"purges\":\\{[^}]*\\}"), "")
        assertFalse(
            "descriptor and records gone too: " + dataFiles.lines().filter { it.contains(alice) },
            dataFiles.contains(alice),
        )
        assertTrue("other accounts keep syncing", contents.contains("bob's note"))
        assertEquals(SyncAccountStatus.RemovedFromCloud, phone.status(p))
        assertEquals(SyncAccountStatus.RemovedFromCloud, tablet.status(t))
        assertEquals("local data stays", setOf("n-alice", "n-later"), tablet.noteIds(t))
        assertEquals(setOf("n-alice"), phone.noteIds(p))
        assertTrue(tablet.manager.state.value.cloudOnlyAccounts.none { it.syncProfileId == alice })
    }

    @Test
    fun should_keepANewDeviceFromUploadingIt_when_itJoinsAfterTheRemoval() = runBlocking {
        val phone = device("Pixel 9")
        val p = phone.addSubsonic("https://music.example.com", "alice")
        phone.addNote(p, "n1", "first note")
        phone.turnOn()
        assertTrue(phone.manager.removeAccountFromCloud(scopeOf("https://music.example.com", "alice")).isSuccess)

        val fresh = device("New phone")
        val f = fresh.addSubsonic("https://music.example.com", "alice")
        fresh.addNote(f, "n-fresh", "fresh device note")
        fresh.turnOn()

        assertEquals(SyncAccountStatus.RemovedFromCloud, fresh.status(f))
        assertFalse(drive.allContents().contains("fresh device note"))
        assertEquals(setOf("n-fresh"), fresh.noteIds(f))
    }

    @Test
    fun should_syncAgainWithNewVersions_when_resumedAfterTheRemoval() = runBlocking {
        val phone = device("Pixel 9")
        val tablet = device("Pixel Tablet")
        val p = phone.addSubsonic("https://music.example.com", "alice")
        val t = tablet.addSubsonic("https://music.example.com", "alice")
        phone.addNote(p, "n1", "kept note")
        phone.turnOn()
        tablet.turnOn()
        val alice = scopeOf("https://music.example.com", "alice")
        assertTrue(phone.manager.removeAccountFromCloud(alice).isSuccess)
        tablet.sync()

        phone.manager.resumeAccountSync(p)
        phone.awaitIdle()
        phone.sync()

        assertEquals(SyncAccountStatus.Syncing, phone.status(p))
        assertTrue("re-published after the purge epoch", drive.allContents().contains("kept note"))
        tablet.sync()
        assertEquals("still removed on the tablet until resumed there", SyncAccountStatus.RemovedFromCloud, tablet.status(t))
    }

    @Test
    fun should_pauseTheAccountElsewhere_when_removedFromADeviceThatOnlySeesItInDrive() = runBlocking {
        val phone = device("Pixel 9")
        val tablet = device("Pixel Tablet")
        val p = phone.addSubsonic("https://music.example.com", "alice")
        phone.addNote(p, "n1", "phone only note")
        phone.turnOn()
        tablet.turnOn()
        val alice = scopeOf("https://music.example.com", "alice")
        assertTrue(tablet.manager.state.value.cloudOnlyAccounts.any { it.syncProfileId == alice })

        assertTrue(tablet.manager.removeAccountFromCloud(alice).isSuccess)
        phone.sync()
        phone.sync()

        assertTrue(tablet.manager.state.value.cloudOnlyAccounts.isEmpty())
        assertEquals(SyncAccountStatus.RemovedFromCloud, phone.status(p))
        assertFalse(drive.allContents().contains("phone only note"))
        assertEquals(setOf("n1"), phone.noteIds(p))
    }

    @Test
    fun should_deletePurgeMarkersToo_when_cloudDataIsDeleted() = runBlocking {
        val phone = device("Pixel 9")
        val p = phone.addSubsonic("https://music.example.com", "alice")
        phone.addNote(p, "n1", "one")
        phone.turnOn()
        assertTrue(phone.manager.removeAccountFromCloud(scopeOf("https://music.example.com", "alice")).isSuccess)

        assertTrue(phone.manager.deleteCloudData().isSuccess)

        assertTrue(drive.onlyResetMarkers())
        phone.turnOn()
        assertEquals("a reset clears the removal: the account syncs again", SyncAccountStatus.Syncing, phone.status(p))
        assertTrue(drive.allContents().contains("\"one\""))
    }

    // ---- harness

    @Test
    fun should_notPublishArtifactShards_when_firstStateCreateFails() = runBlocking {
        val phone = device("Pixel 9")
        val p = phone.addSubsonic("https://music.example.com", "alice")
        phone.addNote(p, "n1", "one")
        phone.addTranslation("trk-1", "eins")
        drive.failCreatesOfKind = SyncFileProps.KIND_STATE
        drive.failDelayMs = 150
        phone.turnOn()

        assertEquals(CloudSyncPhase.StorageFull, phone.phase)
        val artifacts = drive.files().filter { it.appProperties[SyncFileProps.KIND] == SyncFileProps.KIND_ARTIFACTS }
        assertTrue("artifact shards published without a state file: ${artifacts.map { it.name }}", artifacts.isEmpty())
    }

    @Test
    fun should_notReuploadState_when_onlyArtifactUploadFailedLastCycle() = runBlocking {
        val phone = device("Pixel 9")
        val p = phone.addSubsonic("https://music.example.com", "alice")
        phone.addNote(p, "n1", "one")
        phone.addTranslation("trk-1", "eins")
        phone.turnOn()
        assertEquals(CloudSyncPhase.UpToDate, phone.phase)

        phone.addNote(p, "n2", "two")
        phone.addTranslation("trk-2", "zwei")
        drive.failWritesOfKind = SyncFileProps.KIND_ARTIFACTS
        drive.failDelayMs = 150
        phone.sync()
        val stateWritesAfterFailedCycle = drive.writesByKind[SyncFileProps.KIND_STATE]?.get() ?: 0

        drive.failWritesOfKind = null
        phone.sync()
        val stateWritesAfterRetry = drive.writesByKind[SyncFileProps.KIND_STATE]?.get() ?: 0

        assertEquals(CloudSyncPhase.UpToDate, phone.phase)
        assertEquals(
            "the state file already went up in the failed cycle; retry should only send the artifact",
            stateWritesAfterFailedCycle,
            stateWritesAfterRetry,
        )
    }

    @Test
    fun should_notReuploadSucceededArtifactShards_when_siblingArtifactShardFails() = runBlocking {
        val phone = device("Pixel 9")
        val p = phone.addSubsonic("https://music.example.com", "alice")
        phone.addNote(p, "n1", "one")
        phone.turnOn()
        assertEquals(CloudSyncPhase.UpToDate, phone.phase)

        // Populate enough distinct translation keys to cover multiple artifact shards, then fail shard 1 only.
        (1..20).forEach { i -> phone.addTranslation("multi-shard-trk-$i", "de-$i") }
        drive.failArtifactShard = 1
        drive.failDelayMs = 50
        phone.sync()
        assertEquals(CloudSyncPhase.Offline, phone.phase)
        val artifactWritesAfterPartialFailure = drive.writesByKind[SyncFileProps.KIND_ARTIFACTS]?.get() ?: 0
        assertTrue("sibling shards should have succeeded", artifactWritesAfterPartialFailure >= 2)

        drive.failArtifactShard = null
        phone.sync()
        assertEquals(CloudSyncPhase.UpToDate, phone.phase)
        val artifactWritesAfterRetry = drive.writesByKind[SyncFileProps.KIND_ARTIFACTS]?.get() ?: 0
        assertEquals(
            "retry should only upload the single failed shard",
            artifactWritesAfterPartialFailure + 1,
            artifactWritesAfterRetry,
        )
    }

    @Test
    fun should_reportAccountMismatch_evenWhen_parallelListFails() = runBlocking {
        val phone = device("Pixel 9")
        val p = phone.addSubsonic("https://music.example.com", "alice")
        phone.addNote(p, "n1", "one")
        phone.turnOn()
        assertEquals(CloudSyncPhase.UpToDate, phone.phase)

        val wrongAccountDrive = FakeDrive(permissionId = "perm-other").apply {
            failListWith = SyncTransportException.RateLimited("429", null)
        }
        phone.useDrive(wrongAccountDrive)
        phone.sync()

        assertEquals(CloudSyncPhase.NeedsReauth, phone.phase)
    }

    @Test
    fun should_surfaceHigherPriorityUploadError_when_multipleArtifactShardsFailWithDifferentErrors() = runBlocking {
        val phone = device("Pixel 9")
        val p = phone.addSubsonic("https://music.example.com", "alice")
        phone.addNote(p, "n1", "one")
        phone.turnOn()
        assertEquals(CloudSyncPhase.UpToDate, phone.phase)

        (1..20).forEach { i -> phone.addTranslation("prio-trk-$i", "de-$i") }
        // Shard 0 fails with 429 (Offline), shard 2 fails with StorageFull -> StorageFull must win.
        drive.shardErrorOverrides[0] = SyncTransportException.RateLimited("429", null)
        drive.shardErrorOverrides[2] = SyncTransportException.StorageFull("quota")
        phone.sync()

        assertEquals(CloudSyncPhase.StorageFull, phone.phase)
    }

    @Test
    fun should_deduplicateConcurrentTokenIdentityChecks_andRejectMismatchedToken() = runBlocking {
        val innerCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val aboutCalls = java.util.concurrent.atomic.AtomicInteger(0)
        var currentToken = "tok-1"
        var remotePerm = "perm-1"
        val inner = object : SyncTokenProvider {
            override suspend fun token(): String {
                innerCalls.incrementAndGet()
                return currentToken
            }

            override suspend fun invalidate(token: String) = Unit
        }
        val provider = IdentityCheckedTokenProvider(
            inner = inner,
            expectedPermissionId = { "perm-1" },
            tokenPermissionId = {
                aboutCalls.incrementAndGet()
                kotlinx.coroutines.delay(50)
                remotePerm
            },
        )

        val resolved = coroutineScope {
            (1..6).map { async { provider.token() } }.awaitAll()
        }
        assertEquals(List(6) { "tok-1" }, resolved)
        assertEquals("fresh token should be verified only once across concurrent callers", 1, aboutCalls.get())
        assertEquals(6, innerCalls.get())

        // Cached verified token incurs zero additional about() calls.
        assertEquals("tok-1", provider.token())
        assertEquals(1, aboutCalls.get())

        // Rotated token belonging to another Google account must throw Unauthorized.
        currentToken = "tok-wrong"
        remotePerm = "perm-other"
        val thrown = runCatching { provider.token() }.exceptionOrNull()
        assertTrue("expected Unauthorized, got $thrown", thrown is SyncTransportException.Unauthorized)
    }

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

        suspend fun addTranslation(trackId: String, text: String) {
            db.syncDomainDao().writeLyricsCache(
                com.gpo.yoin.data.local.LyricsCache(
                    trackProvider = "spotify",
                    trackRawId = trackId,
                    lyricsProvider = "qq",
                    lyricsProviderSongId = "s-$trackId",
                    lrc = "[00:01.00]line one",
                    cachedAt = clock.get(),
                ),
            )
            db.syncDomainDao().insertTranslationIfAbsent(
                com.gpo.yoin.data.local.LyricsTranslationCache(
                    trackProvider = "spotify",
                    trackRawId = trackId,
                    sourceHash = "h-$trackId",
                    targetLanguage = "German",
                    model = "gemini-3.1-flash-lite",
                    translationsJson = "[\"$text\"]",
                    cachedAt = clock.addAndGet(1_000),
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

        override suspend fun authorizeInteractively(accountEmail: String?) = token

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

        @Volatile var failWritesOfKind: String? = null

        @Volatile var failArtifactShard: Int? = null

        val shardErrorOverrides = java.util.concurrent.ConcurrentHashMap<Int, SyncTransportException>()

        @Volatile var failDelayMs: Long = 0

        val writesByKind = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

        private fun countKind(kind: String?) {
            writesByKind.getOrPut(kind.orEmpty()) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
        }

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
            val kind = appProperties[SyncFileProps.KIND]
            val shard = appProperties[SyncFileProps.SHARD]?.toIntOrNull()
            if (kind == failCreatesOfKind) {
                if (failDelayMs > 0) kotlinx.coroutines.delay(failDelayMs)
                throw SyncTransportException.StorageFull("full")
            }
            if (kind == SyncFileProps.KIND_ARTIFACTS && shard != null) {
                shardErrorOverrides[shard]?.let { throw it }
            }
            if (kind == failWritesOfKind || (kind == SyncFileProps.KIND_ARTIFACTS && shard == failArtifactShard)) {
                if (failDelayMs > 0) kotlinx.coroutines.delay(failDelayMs)
                throw SyncTransportException.RateLimited("429", null)
            }
            countKind(kind)
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
            val props = synchronized(this) { files[fileId]?.file?.appProperties }
            val kind = props?.get(SyncFileProps.KIND)
            val shard = props?.get(SyncFileProps.SHARD)?.toIntOrNull()
            if (kind == SyncFileProps.KIND_ARTIFACTS && shard != null) {
                shardErrorOverrides[shard]?.let { throw it }
            }
            if (kind != null &&
                (kind == failWritesOfKind || (kind == SyncFileProps.KIND_ARTIFACTS && shard == failArtifactShard))
            ) {
                if (failDelayMs > 0) kotlinx.coroutines.delay(failDelayMs)
                throw SyncTransportException.RateLimited("429", null)
            }
            if (kind != null) countKind(kind)
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
