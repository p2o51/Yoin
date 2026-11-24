package com.gpo.yoin.data.sync.backup

import com.gpo.yoin.data.sync.ActiveBinding
import com.gpo.yoin.data.sync.SyncBindingEntity
import com.gpo.yoin.data.sync.SyncFormat
import com.gpo.yoin.data.sync.SyncKinds
import com.gpo.yoin.data.sync.engine.SyncEngine
import com.gpo.yoin.shared.portability.BackupKinds
import com.gpo.yoin.shared.portability.ImportTarget
import com.gpo.yoin.shared.portability.ParsedBackup
import com.gpo.yoin.shared.portability.memories.MigrationIds
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupImportTest {
    private val phone = BackupTestDevice("dev-a", "Pixel 9")
    private val tablet = BackupTestDevice("dev-b", "Pixel Tablet")
    private val url = "https://music.example.com"
    private val scope = BackupTestDevice.scopeOf(url, "alice")

    @After
    fun tearDown() {
        phone.close()
        tablet.close()
    }

    private suspend fun seedPhone(): ParsedBackup {
        val p = phone.addSubsonic("p1", url, "alice")
        phone.note(p, "n1", "t1", "first listen", at = 2_000)
        phone.rate(p, "t1", 9f, at = 2_100)
        phone.albumRating(p, "al1", 8f, "Phone review.", at = 2_300)
        phone.albumNote(p, "an1", "al1", "album note text", at = 2_400)
        phone.albumTitle(p, "al1", "My Title", at = 2_500)
        phone.memory(p, "al1", "AI copy", "AI Title", at = 2_600)
        phone.link(p, "t1", "al1", at = 2_700)
        return BackupTestDevice.parse(phone.exportZip().first)
    }

    @Test
    fun should_mapTheSameAccountAndRestoreEverything_when_importedOnAnotherDevice() = runTest {
        val backup = seedPhone()
        val t = tablet.addSubsonic("t1", "http://Music.Example.com/", "Alice")

        val plan = tablet.importer().plan(backup, replica = null)
        val account = plan.accounts.single()
        assertEquals(ImportTarget.IntoProfile(t), account.target)
        assertTrue(account.sameAccount)
        assertTrue(plan.needsChoice.isEmpty())
        assertFalse("no replica: cloud-only isn't possible", account.cloudOnlyPossible)

        val result = tablet.importer().apply(plan)

        val dao = tablet.db.syncDomainDao()
        assertEquals("n1", dao.notesForProfile(t).single().id)
        assertEquals(9f, dao.trackRating(t, "t1", "subsonic")?.rating)
        val album = dao.albumRating(t, "al1", "subsonic")!!
        assertEquals(8f, album.rating)
        assertEquals("Phone review.", album.review)
        assertFalse("never pushed to NeoDB", album.ratingNeedsSync || album.reviewNeedsSync)
        assertEquals("album note text", dao.albumNoteById("an1")?.content)
        assertEquals("My Title", tablet.db.albumMemoryTitleDao().get(t, "subsonic", "al1")?.title)
        assertEquals("AI Title", dao.memoryCopy(t, "subsonic", "album", "al1")?.title)
        assertEquals("al1", dao.trackAlbumLink(t, "subsonic", "t1")?.albumId)
        assertTrue(result.changed)
    }

    @Test
    fun should_changeNothing_when_theSameFileIsImportedTwice() = runTest {
        val backup = seedPhone()
        tablet.addSubsonic("t1", url, "alice")
        tablet.importer().apply(tablet.importer().plan(backup, null))

        val again = tablet.importer().apply(tablet.importer().plan(backup, null))

        val counts = again.byScope.getValue(scope)
        assertEquals(0, counts.written)
        assertEquals(0, counts.keptBoth)
        assertEquals(0, again.global.written)
        assertFalse(again.changed)
    }

    @Test
    fun should_followSyncMergeRules_when_theTargetAlreadyHasData() = runTest {
        val backup = seedPhone()
        val t = tablet.addSubsonic("t1", url, "alice")
        tablet.rate(t, "t1", 3f, at = 9_000) // newer here: stays
        tablet.albumRating(t, "al1", 5f, "Tablet review.", at = 1_000) // older here: rating replaced, review kept both
        // An id the backup uses for a note already belongs to another profile here.
        val other = tablet.addSubsonic("other", "https://elsewhere.example.com", "zed")
        tablet.note(other, "n1", "zz", "someone else's note", at = 1)

        tablet.importer().apply(tablet.importer().plan(backup, null))

        val dao = tablet.db.syncDomainDao()
        assertEquals(3f, dao.trackRating(t, "t1", "subsonic")?.rating)
        val album = dao.albumRating(t, "al1", "subsonic")!!
        assertEquals(8f, album.rating)
        assertTrue(album.review!!.startsWith("Tablet review."))
        assertTrue(album.review!!.contains("— Pixel 9 · 1970-01-01 —\nPhone review."))
        assertEquals("someone else's note", dao.noteById("n1")?.content)
        val copied = dao.notesForProfile(t).single()
        assertEquals(MigrationIds.derive(scope, "n1", scope), copied.id)
        assertEquals("first listen", copied.content)

        val again = tablet.importer().apply(tablet.importer().plan(backup, null))
        assertEquals(0, again.byScope.getValue(scope).written + again.byScope.getValue(scope).keptBoth)
    }

    @Test
    fun should_neverResurrectANoteTheReplicaDeleted_when_importingAnOlderBackup() = runTest {
        val backup = seedPhone()
        val t = tablet.addSubsonic("t1", url, "alice")
        tablet.syncDb.syncDao().upsertBinding(SyncBindingEntity(t, scope, "subsonic", "fp", SyncBindingEntity.STATE_ACTIVE, null, 1))
        tablet.syncDb.syncDao().upsertRecord(
            com.gpo.yoin.data.sync.SyncRecordEntity(SyncKinds.SONG_NOTE, scope, "n1", 1, 50_000, "dev-c", 2_000, "dev-a", true, null, null),
        )

        tablet.importer().apply(tablet.importer().plan(backup, tablet.syncDb.syncDao()), replica = tablet.syncDb.syncDao())

        assertNull(tablet.db.syncDomainDao().noteById("n1"))
        assertEquals(9f, tablet.db.syncDomainDao().trackRating(t, "t1", "subsonic")?.rating)
    }

    @Test
    fun should_keepUnknownAccountsCloudOnly_when_aReplicaExists() = runTest {
        val backup = seedPhone()
        val dao = tablet.syncDb.syncDao()
        val engine = SyncEngine(tablet.syncDb, tablet.adapters, "dev-b", { "Pixel Tablet" }, { 30_000 })

        val plan = tablet.importer().plan(backup, dao)
        assertEquals(ImportTarget.CloudOnly, plan.accounts.single().target)
        val result = tablet.importer().apply(plan, replica = dao, engine = engine)

        assertTrue(result.cloudOnlyMerged > 0)
        assertNotNull(dao.getRecord(SyncKinds.SONG_NOTE, scope, "n1"))
        assertNotNull("its descriptor comes along", dao.getRecord(SyncKinds.ACCOUNT, SyncFormat.GLOBAL_SCOPE, scope))
        // album notes are a sync kind: a cloud-only account's go into the replica like song notes
        assertNotNull(dao.getRecord(SyncKinds.ALBUM_NOTE, scope, "an1"))
        assertNull("backup-only kinds never enter the replica", dao.getRecord(BackupKinds.TRACK_ALBUM_LINK, scope, SyncFormat.compositeKey("subsonic", "t1")))
        assertTrue(dao.remoteFiles().isEmpty())

        val again = tablet.importer().apply(tablet.importer().plan(backup, dao), replica = dao, engine = engine)
        assertEquals(0, again.cloudOnlyMerged)
    }

    @Test
    fun should_beCapturedAsLocalChanges_when_importedIntoASyncingProfile() = runTest {
        val backup = seedPhone()
        val t = tablet.addSubsonic("t1", url, "alice")
        tablet.importer().apply(tablet.importer().plan(backup, null))
        val engine = SyncEngine(tablet.syncDb, tablet.adapters, "dev-b", { "Pixel Tablet" }, { 40_000 })

        val capture = engine.capture(listOf(ActiveBinding(t, scope, "subsonic")))

        assertEquals(1, capture.newVersions[scope]?.get(SyncKinds.SONG_NOTE))
        assertEquals(1, capture.newVersions[scope]?.get(SyncKinds.ALBUM_REVIEW))
    }

    @Test
    fun should_askForAChoice_when_noLocalProfileIsTheSameAccount() = runTest {
        val backup = seedPhone()
        val bob = tablet.addSubsonic("bob", url, "bob")

        val plan = tablet.importer().plan(backup, null)

        val account = plan.accounts.single()
        assertEquals(ImportTarget.Skip, account.target)
        assertEquals(listOf(bob), account.options.map { it.localProfileId })
        assertEquals(1, plan.needsChoice.size)

        tablet.importer().apply(plan, choices = mapOf(scope to ImportTarget.IntoProfile(bob)))
        val note = tablet.db.syncDomainDao().notesForProfile(bob).single()
        // Another account's notes never keep their ids, even when the original isn't on this device.
        assertEquals(MigrationIds.derive(scope, "n1", BackupTestDevice.scopeOf(url, "bob")), note.id)
        assertEquals(MigrationIds.derive(scope, "an1", BackupTestDevice.scopeOf(url, "bob")), tablet.db.syncDomainDao().albumNotesForProfile(bob).single().id)
    }

    @Test
    fun should_writeNothingOfAKind_when_itsTableCantBeRead() = runTest {
        val backup = seedPhone()
        val t = tablet.addSubsonic("t1", url, "alice")
        val adapters = tablet.adapters.map { adapter ->
            if (adapter.kind != SyncKinds.TRACK_RATING) {
                adapter
            } else {
                object : com.gpo.yoin.data.sync.SyncAdapter by adapter {
                    override suspend fun readAll(localProfileId: String?) = throw java.io.IOException("disk")
                }
            }
        }
        val importer = BackupImporter(tablet.db, adapters, tablet::profiles) { tablet.credentials[it.id] }

        val result = importer.apply(importer.plan(backup, null))

        val counts = result.byScope.getValue(scope)
        assertEquals(setOf(SyncKinds.TRACK_RATING), counts.failedKinds)
        assertNull(tablet.db.syncDomainDao().trackRating(t, "t1", "subsonic"))
        assertEquals("other kinds still import", 1, tablet.db.syncDomainDao().notesForProfile(t).size)
    }

    @Test
    fun should_notOfferCloudOnly_when_theAccountWasRemovedFromTheCloud() = runTest {
        val backup = seedPhone()
        val dao = tablet.syncDb.syncDao()
        val engine = SyncEngine(tablet.syncDb, tablet.adapters, "dev-b", { "Pixel Tablet" }, { 30_000 })
        engine.applyScopePurge(scope, epoch = 20_000)

        val plan = tablet.importer().plan(backup, dao)
        val account = plan.accounts.single()
        assertTrue(account.wasPurged)
        assertFalse(account.cloudOnlyPossible)
        assertEquals(ImportTarget.Skip, account.target)

        val result = tablet.importer().apply(plan, choices = mapOf(scope to ImportTarget.CloudOnly), replica = dao, engine = engine)

        assertEquals(listOf(scope), result.cloudOnlyRefused)
        assertEquals(0, result.cloudOnlyMerged)
        assertTrue(dao.recordsInScope(scope).isEmpty())
    }
}
