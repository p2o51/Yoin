package com.gpo.yoin.shared.portability

import com.gpo.yoin.shared.portability.memories.MigrationIds
import com.gpo.yoin.shared.sync.ActiveBinding
import com.gpo.yoin.shared.sync.SyncBindingEntity
import com.gpo.yoin.shared.sync.SyncFormat
import com.gpo.yoin.shared.sync.SyncKinds
import com.gpo.yoin.shared.sync.SyncMetaEntity
import com.gpo.yoin.shared.sync.SyncMetaKeys
import com.gpo.yoin.shared.sync.WireRecord
import com.gpo.yoin.shared.sync.engine.SyncJson
import com.gpo.yoin.shared.sync.engine.toEntity
import com.gpo.yoin.shared.sync.testing.assertEquals
import com.gpo.yoin.shared.sync.testing.assertFalse
import com.gpo.yoin.shared.sync.testing.assertTrue
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The backup core end to end over the shared adapters (what iOS runs), mirroring Android's Room tests. */
class BackupRoundTripTest {
    private val url = "https://music.example.com"
    private val scope = BackupTestDevice.scopeOf(url, "alice")

    private fun seed(phone: BackupTestDevice): String {
        val p = phone.addSubsonic("p1", url, "alice")
        phone.note(p, "n1", "t1", "first listen, rain outside", at = 2_000)
        phone.rate(p, "t1", 9.0, at = 2_100)
        phone.rate(p, "t2", 7.5, at = 2_200)
        phone.albumRating(p, "al1", 8.0, "A long review.", at = 2_300)
        phone.albumNote(p, "an1", "al1", "album note text", at = 2_400)
        phone.link(p, "t1", "al1", at = 2_700)
        return p
    }

    @Test
    fun should_writeManifestSnapshotAndReadable_when_exportingWithoutSync() = runTest {
        val phone = BackupTestDevice()
        seed(phone)

        val files = phone.export()
        val manifest = files.manifest

        assertEquals(BackupFormat.FORMAT, manifest.format)
        assertEquals(BackupFormat.sha256Hex(files.snapshotBytes), manifest.snapshot.sha256)
        val account = manifest.accounts.single()
        assertEquals(scope, account.scope)
        assertEquals("alice @ music.example.com", account.hint)
        assertEquals(1, account.counts[SyncKinds.SONG_NOTE])
        assertEquals(2, account.counts[SyncKinds.TRACK_RATING])
        assertEquals(1, account.counts[BackupKinds.TRACK_ALBUM_LINK])
        assertEquals(listOf("album_note", "album_rating", "album_review", "song_note", "track_album_link", "track_rating"), account.counts.keys.toList())
        val note = BackupTestDevice.parse(files).snapshot.records.single { it.kind == SyncKinds.SONG_NOTE }
        assertEquals("versions are the row's own time with no replica", 2_000L, note.ts)
        assertEquals(scope, note.scope)
        assertEquals(files.readable.size, BackupTestDevice.parse(files).readableFiles)
    }

    @Test
    fun should_rejectTamperedOrForeignFiles_when_read() = runTest {
        val phone = BackupTestDevice()
        seed(phone)
        val files = phone.export()
        val snapshot = files.snapshotBytes.decodeToString()
        val manifest = files.manifestBytes.decodeToString()

        assertFailsWith<BackupReadException.ChecksumMismatch> {
            BackupParser.parse(files.manifestBytes, snapshot.replace("first listen", "FIRST LISTEN").encodeToByteArray(), 0)
        }
        assertFailsWith<BackupReadException.TooNew> {
            BackupParser.parse(manifest.replace("\"formatVersion\": 1", "\"formatVersion\": 99").encodeToByteArray(), files.snapshotBytes, 0)
        }
        assertFailsWith<BackupReadException.NotABackup> { BackupParser.parse(null, files.snapshotBytes, 0) }
        assertFailsWith<BackupReadException.NotABackup> { BackupParser.parse("hello".encodeToByteArray(), files.snapshotBytes, 0) }
        assertFailsWith<BackupReadException.Corrupt> { BackupParser.parse(files.manifestBytes, null, 0) }
    }

    @Test
    fun should_importIntoTheSameAccount_andChangeNothingTheSecondTime() = runTest {
        val phone = BackupTestDevice()
        seed(phone)
        val backup = BackupTestDevice.parse(phone.export())
        val tablet = BackupTestDevice("dev-b", "Tablet")
        val t = tablet.addSubsonic("t1", url, "alice")

        val plan = ImportPlanner.plan(backup, tablet.domain.profiles(), null)
        assertEquals(ImportTarget.IntoProfile(t), plan.accounts.single().target)
        assertTrue(plan.accounts.single().sameAccount)
        assertTrue(plan.needsChoice.isEmpty())
        val first = tablet.merger().apply(plan, emptyMap(), tablet.domain.profiles(), null, importGlobal = true)

        assertTrue(first.changed)
        assertEquals("n1", tablet.domain.notes(t).single().id)
        assertEquals(9.0, tablet.domain.trackRating(t, "subsonic", "t1")?.rating)
        assertEquals("A long review.", tablet.domain.albumRating(t, "subsonic", "al1")?.review)
        assertEquals("al1", tablet.links[t to "subsonic:t1"]?.albumId)

        val again = tablet.import(backup)
        assertFalse(again.changed)
        assertEquals(0, again.byScope.values.sumOf { it.written })
    }

    @Test
    fun should_copyNotesWithMigrationIds_when_importedIntoAnotherAccount() = runTest {
        val phone = BackupTestDevice()
        seed(phone)
        val backup = BackupTestDevice.parse(phone.export())
        val tablet = BackupTestDevice("dev-b", "Tablet")
        val b = tablet.addSubsonic("b1", "https://other.example.com", "bob")

        val plan = ImportPlanner.plan(backup, tablet.domain.profiles(), null)
        assertEquals(ImportTarget.Skip, plan.accounts.single().target)
        assertEquals(listOf("b1"), plan.accounts.single().options.map { it.localProfileId })
        tablet.import(backup, choices = mapOf(scope to ImportTarget.IntoProfile(b)))

        val dst = BackupTestDevice.scopeOf("https://other.example.com", "bob")
        assertEquals(MigrationIds.derive(scope, "n1", dst), tablet.domain.notes(b).single().id)
        assertEquals(MigrationIds.derive(scope, "an1", dst), tablet.domain.albumNotes(b).single().id)
    }

    @Test
    fun should_keepBothReviewsAndNeverDelete_when_theTargetHasItsOwn() = runTest {
        val phone = BackupTestDevice()
        seed(phone)
        val backup = BackupTestDevice.parse(phone.export())
        val tablet = BackupTestDevice("dev-b", "Tablet")
        val t = tablet.addSubsonic("t1", url, "alice")
        tablet.albumRating(t, "al1", 3.0, "Tablet review.", at = 9_000)
        tablet.rate(t, "t1", 4.0, at = 9_000)

        val result = tablet.import(backup)

        assertEquals("newer local score stays", 4.0, tablet.domain.trackRating(t, "subsonic", "t1")?.rating)
        val review = tablet.domain.albumRating(t, "subsonic", "al1")?.review.orEmpty()
        assertTrue(review, review.startsWith("Tablet review."))
        assertTrue(review, review.contains("A long review."))
        assertEquals(1, result.byScope.getValue(scope).keptBoth)
    }

    @Test
    fun should_keepReplicaVersionsUnknownKeysAndTombstones_when_syncHasRun() = runTest {
        val phone = BackupTestDevice()
        val p = phone.addSubsonic("p1", url, "alice")
        phone.note(p, "n1", "t1", "synced", at = 2_000)
        phone.note(p, "n2", "t2", "to be deleted", at = 2_000)
        phone.rate(p, "t1", 6.0, at = 2_000)
        phone.store.upsertBinding(SyncBindingEntity(p, scope, "subsonic", "fp", SyncBindingEntity.STATE_ACTIVE, null, 1))
        phone.engine().capture(listOf(ActiveBinding(p, scope, "subsonic")))
        // A cloud-only account with a payload key this version doesn't know, and a tombstone.
        val future = Json.parseToJsonElement(
            """{"trackId":"q","provider":"subsonic","content":"other","createdAt":1,"updatedAt":1,"title":"Q","artist":"A","positionMs":null,"mood":{"x":1}}""",
        ).jsonObject
        phone.store.upsertRecord(WireRecord(SyncKinds.SONG_NOTE, 1, "s-other", "x1", 3_000, "peer", payload = future).toEntity())
        phone.store.upsertRecord(WireRecord(SyncKinds.SONG_NOTE, 1, "s-other", "x2", 3_100, "peer", deleted = true).toEntity())
        phone.store.putMeta(SyncMetaEntity(SyncMetaKeys.PURGED_SCOPE_PREFIX + "s-gone", "42"))
        // Local changes after the last sync.
        phone.rate(p, "t1", 9.0, at = 6_000)
        phone.domain.deleteNote("n2", p)

        val files = phone.export(now = 20_000, replica = phone.replica())
        val snapshot = BackupTestDevice.parse(files).snapshot
        val records = snapshot.records.associateBy { Triple(it.kind, it.scope, it.key) }

        assertEquals("unchanged row keeps its synced version", 2_000L, records.getValue(Triple(SyncKinds.SONG_NOTE, scope, "n1")).ts)
        val rating = records.getValue(Triple(SyncKinds.TRACK_RATING, scope, SyncFormat.compositeKey("subsonic", "t1")))
        assertEquals(20_000L, rating.ts)
        assertEquals(2_000L, rating.prevTs)
        assertTrue(records.getValue(Triple(SyncKinds.SONG_NOTE, scope, "n2")).deleted)
        assertEquals(future, records.getValue(Triple(SyncKinds.SONG_NOTE, "s-other", "x1")).payload)
        assertTrue("tombstones are carried", records.getValue(Triple(SyncKinds.SONG_NOTE, "s-other", "x2")).deleted)
        assertEquals(mapOf("s-gone" to 42L), snapshot.purges)
        assertFalse(files.manifest.accounts.single { it.scope == "s-other" }.onDevice)

        // Cloud-only into another device's replica: unknown keys survive the merge too.
        val tablet = BackupTestDevice("dev-b", "Tablet")
        val replica = tablet.replica()
        val backup = BackupTestDevice.parse(files)
        val plan = ImportPlanner.plan(backup, tablet.domain.profiles(), replica)
        assertEquals(ImportTarget.CloudOnly, plan.accounts.single { it.account.scope == "s-other" }.target)
        assertEquals(ImportTarget.CloudOnly, plan.accounts.single { it.account.scope == scope }.target)
        val result = tablet.merger().apply(plan, emptyMap(), tablet.domain.profiles(), replica, importGlobal = true)
        assertTrue(result.cloudOnlyRefused.isEmpty())
        assertTrue(result.cloudOnlyMerged > 0)
        val merged = assertNotNull(tablet.store.getRecord(SyncKinds.SONG_NOTE, "s-other", "x1"))
        assertEquals(future, SyncJson.decodeFromString(JsonObject.serializer(), merged.payload!!))

        // Without a sync engine a cloud-only choice is refused, not dropped silently.
        val bare = BackupTestDevice("dev-c", "Laptop")
        val refused = bare.merger().apply(
            ImportPlanner.plan(backup, bare.domain.profiles(), bare.replica(withEngine = false)),
            emptyMap(),
            bare.domain.profiles(),
            bare.replica(withEngine = false),
            importGlobal = true,
        )
        assertEquals(setOf(scope, "s-other"), refused.cloudOnlyRefused.toSet())
    }
}
