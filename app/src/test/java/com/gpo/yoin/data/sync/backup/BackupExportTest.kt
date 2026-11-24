package com.gpo.yoin.data.sync.backup

import com.gpo.yoin.data.local.GeminiConfig
import com.gpo.yoin.data.sync.ActiveBinding
import com.gpo.yoin.data.sync.CanonicalJson
import com.gpo.yoin.data.sync.SyncBindingEntity
import com.gpo.yoin.data.sync.SyncFormat
import com.gpo.yoin.data.sync.SyncKinds
import com.gpo.yoin.data.sync.SyncRecordEntity
import com.gpo.yoin.data.sync.engine.SyncEngine
import com.gpo.yoin.shared.portability.BackupFormat
import com.gpo.yoin.shared.portability.BackupKinds
import com.gpo.yoin.shared.portability.BackupReadException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupExportTest {
    private val phone = BackupTestDevice()
    private val url = "https://music.example.com"
    private val scope = BackupTestDevice.scopeOf(url, "alice")

    @After
    fun tearDown() = phone.close()

    private suspend fun seed() {
        val p = phone.addSubsonic("p1", url, "alice")
        phone.note(p, "n1", "t1", "first listen, rain outside", at = 2_000)
        phone.rate(p, "t1", 9f, at = 2_100)
        phone.rate(p, "t2", 7.5f, at = 2_200)
        phone.albumRating(p, "al1", 8f, "A long review.", at = 2_300)
        phone.albumNote(p, "an1", "al1", "album note text", at = 2_400)
        phone.albumTitle(p, "al1", "My Rainy Album", at = 2_500)
        phone.memory(p, "al1", "AI copy", "AI Title", at = 2_600)
        phone.link(p, "t1", "al1", at = 2_700)
        phone.played(p, "t2", "Second Song", "al1", "Rain Album", at = 2_800)
        phone.db.geminiConfigDao().upsert(GeminiConfig(apiKey = "APIKEY-SENTINEL", targetLanguage = "Japanese"))
    }

    @Test
    fun should_writeManifestSnapshotAndReadable_when_exportingWithoutSync() = runTest {
        seed()

        val (zip, summary) = phone.exportZip()
        val entries = BackupTestDevice.entries(zip)

        assertEquals(BackupFormat.MANIFEST_PATH, entries.keys.first())
        assertTrue(BackupFormat.SNAPSHOT_PATH in entries)
        val manifest = summary.manifest
        assertEquals(BackupFormat.FORMAT, manifest.format)
        assertEquals(BackupFormat.FORMAT_VERSION, manifest.formatVersion)
        assertEquals("dev-a", manifest.source.deviceId)
        assertEquals(BackupFormat.sha256Hex(entries.getValue(BackupFormat.SNAPSHOT_PATH).toByteArray()), manifest.snapshot.sha256)
        val account = manifest.accounts.single()
        assertEquals(scope, account.scope)
        assertEquals("alice @ music.example.com", account.hint)
        assertTrue(account.onDevice)
        assertEquals(1, account.counts[SyncKinds.SONG_NOTE])
        assertEquals(2, account.counts[SyncKinds.TRACK_RATING])
        assertEquals(1, account.counts[SyncKinds.ALBUM_RATING])
        assertEquals(1, account.counts[SyncKinds.ALBUM_REVIEW])
        assertEquals(1, account.counts[SyncKinds.ALBUM_NOTE])
        assertEquals(1, account.counts[SyncKinds.ALBUM_TITLE])
        assertEquals(1, account.counts[SyncKinds.MEMORY_TITLE])
        assertEquals(1, account.counts[SyncKinds.MEMORY_COPY])
        assertEquals(1, account.counts[BackupKinds.TRACK_ALBUM_LINK])
        assertEquals(1, manifest.globalCounts[SyncKinds.ACCOUNT])

        val snapshot = BackupTestDevice.parse(zip).snapshot
        val note = snapshot.records.single { it.kind == SyncKinds.SONG_NOTE }
        assertEquals("versions are the row's own time with no replica", 2_000L, note.ts)
        assertEquals("dev-a", note.device)
        assertEquals(scope, note.scope)
    }

    @Test
    fun should_neverContainSecrets_when_exported() = runTest {
        seed()

        val (zip, _) = phone.exportZip()
        val everything = BackupTestDevice.entries(zip).values.joinToString("\n")

        assertFalse(everything.contains("PW-SENTINEL"))
        assertFalse(everything.contains("APIKEY-SENTINEL"))
        assertTrue("the language is synced, so it is backed up", everything.contains("Japanese"))
    }

    @Test
    fun should_renderAlbumPagesWithSongsReviewAndNotes_when_readableIsWritten() = runTest {
        seed()

        val (zip, summary) = phone.exportZip()
        val entries = BackupTestDevice.entries(zip)
        val dir = summary.manifest.accounts.single().readableDir!!
        val page = entries.filterKeys { it.startsWith(dir) && it.endsWith(".md") }.values.single()

        assertTrue(page, page.startsWith("# My Rainy Album"))
        assertTrue(page.contains("_Rain Album_"))
        assertTrue(page.contains("Rating: 8 / 10"))
        assertTrue(page.contains("A long review."))
        assertTrue(page.contains("album note text"))
        assertTrue("song named by its note", page.contains("### Title t1 — Artist"))
        assertTrue("song named by a play", page.contains("### Second Song"))
        assertTrue(page.contains("Rating: 7.5 / 10"))
        assertTrue(page.contains("1970-01-01 00:00 · 1:01"))
        assertTrue(page.contains("> first listen, rain outside"))
        assertEquals(1, summary.readableFiles)
    }

    @Test
    fun should_keepReplicaVersionsAndOverlayLocalEdits_when_syncHasRun() = runTest {
        val p = phone.addSubsonic("p1", url, "alice")
        phone.note(p, "n1", "t1", "synced", at = 2_000)
        phone.note(p, "n2", "t2", "to be deleted", at = 2_000)
        phone.rate(p, "t1", 6f, at = 2_000)
        phone.syncDb.syncDao().upsertBinding(SyncBindingEntity(p, scope, "subsonic", "fp", SyncBindingEntity.STATE_ACTIVE, null, 1))
        val engine = SyncEngine(phone.syncDb, phone.adapters, "dev-a", { "Pixel 9" }, { 5_000 })
        engine.capture(listOf(ActiveBinding(p, scope, "subsonic")))
        // A cloud-only account and a tombstone from another device.
        phone.syncDb.syncDao().upsertRecord(record(SyncKinds.SONG_NOTE, "s-other", "x1", 3_000, """{"trackId":"q","provider":"subsonic","content":"other","createdAt":1,"updatedAt":1,"title":"Q","artist":"A","positionMs":null}"""))
        phone.syncDb.syncDao().upsertRecord(record(SyncKinds.SONG_NOTE, "s-other", "x2", 3_100, null))
        phone.syncDb.syncDao().upsertRecord(record(SyncKinds.ACCOUNT, "", "s-other", 3_000, """{"provider":"spotify","displayName":"Bob","createdAt":1}"""))
        // Local changes after the last sync.
        phone.rate(p, "t1", 9f, at = 6_000)
        phone.db.syncDomainDao().deleteNote("n2", p)

        val content = phone.builder(now = 20_000, replica = true).build()
        val records = content.snapshot.records.associateBy { Triple(it.kind, it.scope, it.key) }

        assertEquals("unchanged row keeps its synced version", 2_000L, records.getValue(Triple(SyncKinds.SONG_NOTE, scope, "n1")).ts)
        val rating = records.getValue(Triple(SyncKinds.TRACK_RATING, scope, SyncFormat.compositeKey("subsonic", "t1")))
        assertEquals(20_000L, rating.ts)
        assertEquals(2_000L, rating.prevTs)
        assertTrue(records.getValue(Triple(SyncKinds.SONG_NOTE, scope, "n2")).deleted)
        assertNotNull(records[Triple(SyncKinds.SONG_NOTE, "s-other", "x1")])
        assertTrue("tombstones are carried", records.getValue(Triple(SyncKinds.SONG_NOTE, "s-other", "x2")).deleted)
        val other = content.accounts.single { it.scope == "s-other" }
        assertFalse(other.onDevice)
        assertEquals("Bob", other.displayName)
        assertEquals("spotify", other.provider)
    }

    @Test
    fun should_giveEachProfileItsOwnScope_when_twoProfilesAreOneAccount() = runTest {
        phone.addSubsonic("p1", url, "alice", createdAt = 1)
        phone.addSubsonic("p2", url, "alice", createdAt = 2)
        phone.addProfile("apple", "applemusic", "Apple")

        val accounts = phone.builder().build().accounts

        assertEquals(listOf(scope, "local-p2", "local-apple"), accounts.map { it.scope })
    }

    @Test
    fun should_nameTheFileByDate() {
        assertEquals("yoin-backup-2026-11-23.zip", BackupFormat.fileName(1_795_400_000_000, ZoneOffset.UTC))
    }

    @Test
    fun should_rejectTamperedOrForeignFiles_when_read() = runTest {
        seed()
        val (zip, _) = phone.exportZip()
        val entries = BackupTestDevice.entries(zip)

        val tampered = rezip(entries + (BackupFormat.SNAPSHOT_PATH to entries.getValue(BackupFormat.SNAPSHOT_PATH).replace("first listen", "FIRST LISTEN")))
        expect<BackupReadException.ChecksumMismatch> { BackupTestDevice.parse(tampered) }

        val newer = rezip(entries + (BackupFormat.MANIFEST_PATH to entries.getValue(BackupFormat.MANIFEST_PATH).replace("\"formatVersion\": 1", "\"formatVersion\": 99")))
        expect<BackupReadException.TooNew> { BackupTestDevice.parse(newer) }

        expect<BackupReadException.NotABackup> { BackupTestDevice.parse(rezip(mapOf("hello.txt" to "hi"))) }
        expect<BackupReadException> { BackupReader.read(ByteArrayInputStream("not a zip".toByteArray())) }
        assertEquals(1, BackupTestDevice.parse(zip).readableFiles)
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit) {
        try {
            block()
            fail("expected ${T::class.simpleName}")
        } catch (e: Throwable) {
            if (e !is T) throw AssertionError("expected ${T::class.simpleName}, got $e", e)
        }
    }

    private fun rezip(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun record(kind: String, scope: String, key: String, ts: Long, payload: String?) = SyncRecordEntity(
        kind = kind,
        scope = scope,
        key = key,
        kindVersion = 1,
        ts = ts,
        device = "peer",
        prevTs = null,
        prevDevice = null,
        deleted = payload == null,
        payload = payload,
        payloadHash = payload?.let { CanonicalJson.sha256Hex(it) },
    )
}
