package com.gpo.yoin.data.sync.backup

import com.gpo.yoin.data.sync.CloudDataViews
import com.gpo.yoin.data.sync.SyncKinds
import com.gpo.yoin.data.sync.engine.SyncEngine
import com.gpo.yoin.data.transfer.memories.MemoryItems
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Album notes are a sync kind now: a backup carries each once, and an older backup's still imports. */
@RunWith(RobolectricTestRunner::class)
class AlbumNoteBackupTest {
    private val phone = BackupTestDevice()
    private val tablet = BackupTestDevice()
    private val url = "https://music.example.com"
    private val scope = BackupTestDevice.scopeOf(url, "alice")

    @After
    fun tearDown() {
        phone.close()
        tablet.close()
    }

    private suspend fun seed(): String {
        val p = phone.addSubsonic("p1", url, "alice")
        phone.note(p, "n1", "t1", "song note", at = 2_000)
        phone.albumNote(p, "an1", "al1", "first album note", at = 2_400)
        phone.albumNote(p, "an2", "al1", "second album note", at = 2_500)
        return p
    }

    @Test
    fun should_carryEachAlbumNoteOnce_andImportItOnce() = runTest {
        seed()
        val (zip, summary) = phone.exportZip()
        val backup = BackupTestDevice.parse(zip)

        val records = backup.snapshot.records.filter { it.kind == SyncKinds.ALBUM_NOTE }
        assertEquals(listOf("an1", "an2"), records.map { it.key }.sorted())
        assertEquals(2, summary.manifest.accounts.single().counts[SyncKinds.ALBUM_NOTE])
        // the readable copy lists each note once
        val readable = BackupTestDevice.entries(zip).filterKeys { it.startsWith("readable/") }.values.joinToString("\n")
        assertEquals(1, Regex("first album note").findAll(readable).count())

        val t = tablet.addSubsonic("t1", url, "alice")
        val first = tablet.importer().apply(tablet.importer().plan(backup, replica = null))
        assertEquals(2, tablet.db.syncDomainDao().albumNotesForProfile(t).size)
        assertEquals(3, first.byScope.values.sumOf { it.written })

        val again = tablet.importer().apply(tablet.importer().plan(backup, replica = null))
        assertEquals(0, again.byScope.values.sumOf { it.written })
        assertEquals(2, tablet.db.syncDomainDao().albumNotesForProfile(t).size)
    }

    @Test
    fun should_importAnOlderBackupsBackupOnlyAlbumNotes_once() = runTest {
        seed()
        val current = BackupTestDevice.parse(phone.exportZip().first)
        // What the 0.6.x builder wrote: album_note as a backup-only record (no previous version, its own ts).
        val old = current.copy(
            snapshot = current.snapshot.copy(
                records = current.snapshot.records.map { record ->
                    if (record.kind == SyncKinds.ALBUM_NOTE) record.copy(prevTs = null, prevDevice = null) else record
                },
            ),
        )

        val t = tablet.addSubsonic("t1", url, "alice")
        tablet.importer().apply(tablet.importer().plan(old, replica = null))
        tablet.importer().apply(tablet.importer().plan(old, replica = null))

        val notes = tablet.db.syncDomainDao().albumNotesForProfile(t).sortedBy { it.id }
        assertEquals(listOf("an1", "an2"), notes.map { it.id })
        assertEquals("first album note", notes.first().content)
    }

    @Test
    fun should_putACloudOnlyAccountsAlbumNotesInTheReplica_once() = runTest {
        seed()
        val backup = BackupTestDevice.parse(phone.exportZip().first)
        val dao = tablet.syncDb.syncDao()
        val engine = SyncEngine(tablet.syncDb, tablet.adapters, "dev-b", { "Pixel Tablet" }, { 30_000 })

        tablet.importer().apply(tablet.importer().plan(backup, dao), replica = dao, engine = engine)

        assertNotNull(dao.getRecord(SyncKinds.ALBUM_NOTE, scope, "an1"))
        assertEquals(2, dao.allRecords().count { it.kind == SyncKinds.ALBUM_NOTE })
    }

    @Test
    fun should_countAlbumNotesOnce_inTheCloudSummary() = runTest {
        val p = seed()
        val counts = CloudDataViews.countsOf(MemoryItems.fromProfile(phone.db, p))
        assertEquals("one song note and two album notes", 3, counts.notes)
        assertTrue(counts.total >= 3)
    }
}
