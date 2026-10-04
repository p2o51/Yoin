package com.gpo.yoin.data.sync.engine

import com.gpo.yoin.data.sync.CanonicalJson
import com.gpo.yoin.data.sync.ConflictPolicy
import com.gpo.yoin.data.sync.FileClass
import com.gpo.yoin.data.sync.RecordVersion
import com.gpo.yoin.data.sync.RemoteFile
import com.gpo.yoin.data.sync.SkipReason
import com.gpo.yoin.data.sync.SyncBindingEntity
import com.gpo.yoin.data.sync.SyncEnvelope
import com.gpo.yoin.data.sync.SyncFormat
import com.gpo.yoin.data.sync.SyncKinds
import com.gpo.yoin.data.sync.SyncMetaEntity
import com.gpo.yoin.data.sync.SyncMetaKeys
import com.gpo.yoin.data.sync.SyncSettingKeys
import com.gpo.yoin.data.sync.WireRecord
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {

    private val drive = FakeDrive()
    private val devices = mutableListOf<SimDevice>()

    @After
    fun tearDown() {
        devices.forEach { it.close() }
    }

    private fun device(
        id: String,
        name: String,
        start: Long,
        bind: Boolean = true,
        adapters: (FakeDomain) -> List<FakeAdapter> = ::standardAdapters,
    ): SimDevice = SimDevice(id, name, start, adapters).also { device ->
        devices += device
        if (bind) device.bind(device.profile, SCOPE)
    }

    private val SimDevice.profile: String get() = "$deviceId-p"
    private val SimDevice.notes: FakeAdapter get() = adapter(SyncKinds.SONG_NOTE)
    private val SimDevice.ratings: FakeAdapter get() = adapter(SyncKinds.TRACK_RATING)
    private val SimDevice.albumRatings: FakeAdapter get() = adapter(SyncKinds.ALBUM_RATING)
    private val SimDevice.reviews: FakeAdapter get() = adapter(SyncKinds.ALBUM_REVIEW)
    private val SimDevice.settings: FakeAdapter get() = adapter(SyncKinds.SETTING)
    private val SimDevice.translations: FakeAdapter get() = adapter(SyncKinds.LYRICS_TRANSLATION)

    private fun SimDevice.note(key: String, rowTs: Long, content: String = "note $key") =
        notes.put(profile, key, rowTs, "trackId" to "track-$key", "content" to content)

    private fun SimDevice.rate(key: String, rating: Int, rowTs: Long) =
        ratings.put(profile, key, rowTs, "songId" to key, "rating" to rating)

    private fun SimDevice.review(key: String, text: String, rowTs: Long) =
        reviews.put(profile, key, rowTs, "albumId" to key, "review" to text)

    private fun SimDevice.rating(key: String): String? = ratings.value(profile, key, "rating")

    private suspend fun SimDevice.tombstonesWrittenHere(): Int =
        dao.allRecords().count { it.deleted && it.device == deviceId }

    // ---------------------------------------------------------------- convergence

    @Test
    fun should_convergeNotes_when_threeDevicesAddAndDeleteNotes() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        val b = device("dev-b", "Tablet", 20_000)
        val c = device("dev-c", "Laptop", 30_000)
        a.note("n1", 100)
        a.note("n2", 200)
        a.note("n3", 300)

        a.cycle(drive)
        b.cycle(drive)
        c.cycle(drive)
        for (d in listOf(b, c)) assertEquals(setOf("n1", "n2", "n3"), d.notes.keys(d.profile))

        b.notes.remove(b.profile, "n1")
        assertEquals(1, b.cycle(drive).capture.totalTombstones)
        a.cycle(drive)
        c.cycle(drive)
        c.note("n4", c.now)
        c.cycle(drive)
        repeat(2) { listOf(a, b, c).forEach { it.cycle(drive) } }

        for (d in listOf(a, b, c)) assertEquals(setOf("n2", "n3", "n4"), d.notes.keys(d.profile))
        assertEquals(a.replica(), b.replica())
        assertEquals(a.replica(), c.replica())
        assertTrue(a.record(SyncKinds.SONG_NOTE, SCOPE, "n1")!!.deleted)
        // Converged: another round uploads nothing.
        assertEquals(0, listOf(a, b, c).sumOf { it.cycle(drive).uploads })
    }

    @Test
    fun should_keepNewestRating_when_twoDevicesRateConcurrently() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        val b = device("dev-b", "Tablet", 20_000)
        a.rate("t1", 8, rowTs = 100)
        a.cycle(drive)
        b.cycle(drive)
        assertEquals("8", b.rating("t1"))

        a.now = 40_000
        a.rate("t1", 3, rowTs = 40_000)
        a.capture()
        b.now = 50_000
        b.rate("t1", 5, rowTs = 50_000)
        b.cycle(drive)
        val report = a.cycle(drive)
        b.cycle(drive)

        assertEquals(1, report.merges.sumOf { it.conflicts })
        assertEquals(1, report.merges.sumOf { it.localChangesDropped })
        assertEquals("5", a.rating("t1"))
        assertEquals("5", b.rating("t1"))
        assertEquals(a.replica(), b.replica())
        assertEquals(0, a.cycle(drive).uploads + b.cycle(drive).uploads)
    }

    @Test
    fun should_adoptCloudSetting_when_joiningDeviceHasDifferentExplicitValue() = runTest {
        val a = device("dev-a", "Phone", 10_000, bind = false)
        a.settings.put(null, SyncSettingKeys.TRANSLATION_LANGUAGE, null, "value" to "Japanese")
        a.cycle(drive)

        val b = device("dev-b", "Tablet", 20_000, bind = false)
        b.settings.put(null, SyncSettingKeys.TRANSLATION_LANGUAGE, null, "value" to "Chinese")
        val report = b.cycle(drive)

        assertEquals("Japanese", b.settings.value(null, SyncSettingKeys.TRANSLATION_LANGUAGE, "value"))
        assertEquals(0, report.capture.totalNewVersions)
        assertEquals(1, report.capture.cloudWins)
        assertEquals(0, a.cycle(drive).capture.totalNewVersions)
        assertEquals("Japanese", a.settings.value(null, SyncSettingKeys.TRANSLATION_LANGUAGE, "value"))
    }

    @Test
    fun should_convergeInOneExchange_when_twoDevicesTurnOnConcurrently() = runTest {
        val a = device("dev-a", "Phone", 1_000, bind = false)
        val b = device("dev-b", "Tablet", 2_000, bind = false)
        a.settings.put(null, SyncSettingKeys.SEAM_TOP_STYLE, null, "value" to "dots")
        b.settings.put(null, SyncSettingKeys.SEAM_TOP_STYLE, null, "value" to "tide")
        a.capture()
        b.capture()
        a.publish(drive)
        b.publish(drive)

        a.cycle(drive)
        b.cycle(drive)

        assertEquals("tide", a.settings.value(null, SyncSettingKeys.SEAM_TOP_STYLE, "value"))
        assertEquals("tide", b.settings.value(null, SyncSettingKeys.SEAM_TOP_STYLE, "value"))
        val second = listOf(a.cycle(drive), b.cycle(drive))
        assertEquals(0, second.sumOf { it.uploads })
        assertTrue(second.none { it.capture.changed })
    }

    // ---------------------------------------------------------------- binding safety

    @Test
    fun should_neverTombstone_when_bindingJoinsScopeThatAlreadyHasCloudRecords() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        a.note("n1", 100, "first")
        a.note("n2", 200, "second")
        a.note("n3", 300, "third")
        a.rate("t1", 8, rowTs = 1_000)
        a.rate("t2", 6, rowTs = 1_000)
        a.cycle(drive)
        a.notes.remove(a.profile, "n2")
        assertEquals(1, a.cycle(drive).capture.totalTombstones)

        // Restored device: stale rows (n2 still present, older t1) plus its own newer t2 and a new note.
        val b = device("dev-b", "Tablet", 20_000, bind = false)
        b.note("n1", 100, "first")
        b.note("n2", 200, "second")
        b.note("n4", 5_000, "fourth")
        b.rate("t1", 4, rowTs = 500)
        b.rate("t2", 9, rowTs = 2_000)
        b.cycle(drive)
        b.bind(b.profile, SCOPE)
        val report = b.cycle(drive)

        assertEquals(0, report.capture.totalTombstones)
        assertEquals(0, b.tombstonesWrittenHere())
        assertEquals(1, report.capture.adopted)
        assertEquals(setOf("n1", "n3", "n4"), b.notes.keys(b.profile))
        assertEquals("8", b.rating("t1"))
        assertEquals("9", b.rating("t2"))

        a.cycle(drive)
        assertEquals(setOf("n1", "n3", "n4"), a.notes.keys(a.profile))
        assertEquals("8", a.rating("t1"))
        assertEquals("9", a.rating("t2"))
    }

    @Test
    fun should_reapplyInsteadOfTombstone_when_accountReaddedWithNewLocalProfileId() = runTest {
        val a = device("dev-a", "Phone", 10_000, bind = false)
        a.bind("old-profile", SCOPE)
        for (i in 1..5) a.notes.put("old-profile", "n$i", i * 100L, "trackId" to "t$i", "content" to "c$i")
        a.ratings.put("old-profile", "t1", 100, "songId" to "t1", "rating" to 7)
        a.ratings.put("old-profile", "t2", 100, "songId" to "t2", "rating" to 9)
        a.cycle(drive)

        // Profile deleted and re-added: orphan rows stay under the old id, the scope now binds the new one.
        a.bind("new-profile", SCOPE)
        val report = a.cycle(drive)

        assertEquals(0, report.capture.totalTombstones)
        assertEquals(0, report.capture.heldTombstones)
        assertTrue(a.dao.allRecords().none { it.deleted })
        assertTrue(a.dao.localStatesInScope(SCOPE).all { it.boundProfileId == "new-profile" })
        assertEquals(2, report.apply.appliedLive.count(SCOPE, SyncKinds.TRACK_RATING))
        assertEquals(setOf("t1", "t2"), a.ratings.keys("new-profile"))
        // Note ids still live under the orphaned profile: never re-parented, and not retried.
        assertEquals(5, report.apply.skipped[SkipReason.OWNED_ELSEWHERE])
        val calls = a.notes.applyCalls.size
        assertTrue(a.cycle(drive).apply.skipped.isEmpty())
        assertEquals(calls, a.notes.applyCalls.size)

        val b = device("dev-b", "Tablet", 20_000)
        b.cycle(drive)
        assertEquals((1..5).map { "n$it" }.toSet(), b.notes.keys(b.profile))
    }

    @Test
    fun should_neverTombstone_when_smallAccountRebindsBelowValveAndResetThresholds() = runTest {
        val a = device("dev-a", "Phone", 10_000, bind = false)
        a.bind("old-profile", SCOPE)
        a.notes.put("old-profile", "n1", 100, "trackId" to "t1", "content" to "only note")
        a.ratings.put("old-profile", "t1", 100, "songId" to "t1", "rating" to 7)
        a.cycle(drive)

        // Profile removed together with its rows, then the same account added again.
        a.domain.clearProfile("old-profile")
        a.bind("new-profile", SCOPE)
        val report = a.cycle(drive)

        assertEquals(0, report.capture.totalTombstones)
        assertEquals(0, a.tombstonesWrittenHere())
        assertTrue(report.capture.domainResets.isEmpty())
        assertEquals(setOf("n1"), a.notes.keys("new-profile"))
        assertEquals("7", a.ratings.value("new-profile", "t1", "rating"))
    }

    @Test
    fun should_neverTombstone_when_domainRowsOfNonDeletableKindsVanish() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        val b = device("dev-b", "Tablet", 20_000)
        a.rate("t1", 1, 100)
        a.rate("t2", 2, 100)
        a.rate("t3", 3, 100)
        a.albumRatings.put(a.profile, "al1", 100, "albumId" to "al1", "rating" to 8)
        a.note("n1", 100)
        a.note("n2", 200)
        a.cycle(drive)
        b.cycle(drive)

        a.domain.clearProfile(a.profile) // main DB recreated
        val report = a.cycle(drive)

        assertEquals(listOf(SCOPE), report.capture.domainResets)
        assertEquals(0, report.capture.totalTombstones)
        assertEquals(0, a.tombstonesWrittenHere())
        assertEquals(setOf("n1", "n2"), a.notes.keys(a.profile))
        assertEquals(setOf("t1", "t2", "t3"), a.ratings.keys(a.profile))
        b.cycle(drive)
        assertEquals(setOf("n1", "n2"), b.notes.keys(b.profile))
    }

    // ---------------------------------------------------------------- valve

    @Test
    fun should_holdMassDeleteAndRestore_when_valveTripsAndUserRestores() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        val b = device("dev-b", "Tablet", 20_000)
        for (i in 1..4) a.note("n$i", i * 100L)
        a.cycle(drive)
        b.cycle(drive)

        listOf("n1", "n2", "n3").forEach { a.notes.remove(a.profile, it) }
        val report = a.cycle(drive)

        assertEquals(0, report.capture.totalTombstones)
        assertEquals(3, report.capture.heldTombstones)
        val held = a.engine.heldReview()
        assertEquals(HeldReview(SyncKinds.SONG_NOTE, SCOPE, listOf("n1", "n2", "n3")), held)
        assertEquals(held, report.capture.heldReview)
        assertTrue(a.dao.allRecords().none { it.deleted })
        assertEquals(0, a.cycle(drive).capture.totalTombstones) // stays held

        assertEquals(3, a.engine.resolveHeldReview(restore = true))
        a.cycle(drive)

        assertNull(a.engine.heldReview())
        assertEquals((1..4).map { "n$it" }.toSet(), a.notes.keys(a.profile))
        b.cycle(drive)
        assertEquals((1..4).map { "n$it" }.toSet(), b.notes.keys(b.profile))
    }

    @Test
    fun should_deleteEverywhere_when_heldReviewResolvedAsDelete() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        val b = device("dev-b", "Tablet", 20_000)
        a.note("n1", 100)
        a.note("n2", 200)
        a.cycle(drive)
        b.cycle(drive)

        a.notes.remove(a.profile, "n1")
        a.notes.remove(a.profile, "n2")
        assertEquals(2, a.cycle(drive).capture.heldTombstones) // all of >= 2 live notes

        assertEquals(2, a.engine.resolveHeldReview(restore = false))
        assertTrue(a.engine.hasPendingChanges())
        a.cycle(drive)
        b.cycle(drive)

        assertNull(a.engine.heldReview())
        assertFalse(a.engine.hasPendingChanges())
        assertTrue(a.notes.keys(a.profile).isEmpty())
        assertTrue(b.notes.keys(b.profile).isEmpty())
        assertEquals(2, b.dao.allRecords().count { it.deleted })
    }

    @Test
    fun should_tombstoneWithoutReview_when_singleNoteDeleted() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        for (i in 1..4) a.note("n$i", i * 100L)
        a.cycle(drive)
        a.notes.remove(a.profile, "n2")

        val report = a.cycle(drive)

        assertEquals(1, report.capture.tombstonesCreated.count(SCOPE, SyncKinds.SONG_NOTE))
        assertNull(report.capture.heldReview)
    }

    // ---------------------------------------------------------------- crash repair / stale files

    @Test
    fun should_notCreateVersion_when_domainAlreadyEqualsReplicaAfterCrash() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        val b = device("dev-b", "Tablet", 20_000)
        a.rate("t1", 8, 100)
        a.cycle(drive)
        b.cycle(drive)
        b.now = 50_000
        b.rate("t1", 5, 50_000)
        b.cycle(drive)
        a.pull(drive)

        // The domain write committed, the replica's local state did not.
        a.rate("t1", 5, 50_000)
        val capture = a.capture()

        assertEquals(1, capture.repaired)
        assertEquals(0, capture.totalNewVersions)
        assertEquals(RecordVersion(50_000, "dev-b"), a.record(SyncKinds.TRACK_RATING, SCOPE, "t1")!!.version)
        val calls = a.ratings.applyCalls.size
        a.applyReplica()
        assertEquals(calls, a.ratings.applyCalls.size)
        assertFalse(a.engine.hasPendingChanges())
    }

    @Test
    fun should_notResurrectDeletedNote_when_staleDeviceFileIsMergedLater() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        val b = device("dev-b", "Tablet", 20_000)
        val c = device("dev-c", "Old laptop", 30_000)
        a.note("n1", 100)
        a.note("n2", 200)
        a.note("n3", 300)
        a.cycle(drive)
        c.cycle(drive)
        b.cycle(drive)
        val staleC = drive.file("dev-c")!! // C goes offline for good

        b.notes.remove(b.profile, "n1")
        b.cycle(drive)
        a.cycle(drive)
        assertFalse("n1" in a.notes.keys(a.profile))

        a.engine.mergeRemote(staleC.remote(), SnapshotCodec.decode(staleC.bytes))
        a.applyReplica()
        assertTrue(a.record(SyncKinds.SONG_NOTE, SCOPE, "n1")!!.deleted)
        assertFalse("n1" in a.notes.keys(a.profile))

        // A new device reading the stale file before the tombstone ends up in the same place.
        val d = device("dev-d", "New phone", 40_000)
        d.engine.mergeRemote(staleC.remote(), SnapshotCodec.decode(staleC.bytes))
        val bFile = drive.file("dev-b")!!
        d.engine.mergeRemote(bFile.remote(), SnapshotCodec.decode(bFile.bytes))
        d.capture()
        d.applyReplica()
        assertTrue(d.record(SyncKinds.SONG_NOTE, SCOPE, "n1")!!.deleted)
        assertEquals(setOf("n2", "n3"), d.notes.keys(d.profile))
    }

    // ---------------------------------------------------------------- unknown kinds / versions

    @Test
    fun should_carryAndRepublishUnknownKinds_when_peerRunsNewerAdapters() = runTest {
        val newer: (FakeDomain) -> List<FakeAdapter> = { domain ->
            standardAdapters(domain).map { adapter ->
                if (adapter.kind == SyncKinds.TRACK_RATING) {
                    FakeAdapter(domain, SyncKinds.TRACK_RATING, listOf("songId", "rating", "scale"), kindVersion = 2)
                } else {
                    adapter
                }
            } + FakeAdapter(domain, "song_about", listOf("text"), perAccount = false)
        }
        val n = device("dev-n", "Newer phone", 10_000, adapters = newer)
        n.adapter("song_about").put(null, "about-1", 100, "text" to "hello")
        n.ratings.put(n.profile, "t9", 100, "songId" to "t9", "rating" to 7, "scale" to 10)
        n.cycle(drive)

        val a = device("dev-a", "Old phone", 20_000)
        a.rate("t9", 2, rowTs = 50)
        val report = a.cycle(drive)

        assertEquals(2, report.merges.sumOf { it.opaque })
        assertEquals(0, report.capture.totalNewVersions)
        assertFalse("t9" in a.ratings.applyCalls)
        assertEquals("2", a.rating("t9"))
        val aState = SnapshotCodec.decode(drive.file("dev-a")!!.bytes)
        assertTrue(aState.records.any { it.kind == "song_about" })
        assertTrue(aState.records.any { it.kind == SyncKinds.TRACK_RATING && it.kindVersion == 2 })

        drive.remove("dev-n")
        val c = device("dev-c", "Newer tablet", 30_000, adapters = newer)
        c.cycle(drive)
        assertEquals("hello", c.adapter("song_about").value(null, "about-1", "text"))
        assertEquals("7", c.ratings.value(c.profile, "t9", "rating"))
    }

    @Test
    fun should_pickSamePayloadOnAllDevices_when_sameVersionCarriesDifferentPayloads() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        val b = device("dev-b", "Tablet", 20_000)
        val p1 = json("songId" to "t1", "rating" to 3)
        val p2 = json("songId" to "t1", "rating" to 9)
        val winner = if (CanonicalJson.hash(p1) > CanonicalJson.hash(p2)) p1 else p2
        val loser = if (winner == p1) p2 else p1
        val f1 = foreignFile("f1", "dev-x1", foreignRecord(SyncKinds.TRACK_RATING, 1, "t1", 500, p1))
        val f2 = foreignFile("f2", "dev-x2", foreignRecord(SyncKinds.TRACK_RATING, 1, "t1", 500, p2))
        // Higher kindVersion beats a larger hash.
        val f3 = foreignFile("f3", "dev-x3", foreignRecord(SyncKinds.ALBUM_RATING, 2, "al1", 500, winner))
        val f4 = foreignFile("f4", "dev-x4", foreignRecord(SyncKinds.ALBUM_RATING, 3, "al1", 500, loser))

        a.engine.mergeRemote(f1.first, f1.second)
        a.applyReplica()
        a.engine.mergeRemote(f2.first, f2.second)
        a.engine.mergeRemote(f3.first, f3.second)
        a.engine.mergeRemote(f4.first, f4.second)
        a.applyReplica()
        b.engine.mergeRemote(f4.first, f4.second)
        b.engine.mergeRemote(f2.first, f2.second)
        b.engine.mergeRemote(f3.first, f3.second)
        b.engine.mergeRemote(f1.first, f1.second)
        b.applyReplica()

        assertEquals(CanonicalJson.hash(winner), a.record(SyncKinds.TRACK_RATING, SCOPE, "t1")!!.payloadHash)
        assertEquals(a.replica(), b.replica())
        assertEquals(3, a.record(SyncKinds.ALBUM_RATING, SCOPE, "al1")!!.kindVersion)
        val expectedRating = winner["rating"]!!.jsonPrimitive.content
        assertEquals(expectedRating, a.rating("t1"))
        assertEquals(expectedRating, b.rating("t1"))
        assertEquals(0, a.capture().totalNewVersions)
    }

    // ---------------------------------------------------------------- conflict policies

    @Test
    fun should_keepBothTexts_when_reviewEditedConcurrentlyOnTwoDevices() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        val b = device("dev-b", "Tablet", 20_000)
        a.review("al1", "Base", 100)
        a.cycle(drive)
        b.cycle(drive)

        a.now = 10_000
        a.review("al1", "Phone thoughts", 10_000)
        a.capture()
        b.now = 20_000
        b.review("al1", "Tablet thoughts", 20_000)
        b.cycle(drive)
        val report = a.cycle(drive)
        b.cycle(drive)

        val expected = "Tablet thoughts\n\n— Phone · 1970-01-01 —\nPhone thoughts"
        assertEquals(1, report.merges.sumOf { it.keptBoth })
        assertEquals(expected, a.reviews.value(a.profile, "al1", "review"))
        assertEquals(expected, b.reviews.value(b.profile, "al1", "review"))
        assertEquals(a.replica(), b.replica())
        assertEquals(0, a.cycle(drive).uploads + b.cycle(drive).uploads)
    }

    @Test
    fun should_labelLoserWithKnownDeviceName_when_concurrentEditArrivesRelayedThroughAnotherDevice() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        val b = device("dev-b", "Tablet", 20_000)
        val c = device("dev-c", "Laptop", 30_000)
        a.review("al1", "Base", 100)
        listOf(a, b, c, a).forEach { it.cycle(drive) } // A has seen C's file (and its name)

        c.now = 30_000
        c.review("al1", "Laptop text", 30_000)
        c.cycle(drive)
        b.cycle(drive) // B relays C's version
        drive.remove("dev-c")
        a.now = 40_000
        a.review("al1", "Phone text", 40_000)
        a.capture()
        val report = a.cycle(drive)

        assertEquals(1, report.merges.sumOf { it.keptBoth })
        assertEquals(
            "Phone text\n\n— Laptop · 1970-01-01 —\nLaptop text",
            a.reviews.value(a.profile, "al1", "review"),
        )
    }

    @Test
    fun should_keepBothTexts_when_joiningDeviceHasDifferentReview() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        a.review("al1", "Cloud review", 1_000)
        a.cycle(drive)
        val b = device("dev-b", "Tablet", 20_000, bind = false)
        b.review("al1", "Local review", 2_000)
        b.bind(b.profile, SCOPE)

        val report = b.cycle(drive)
        a.cycle(drive)

        val expected = "Local review\n\n— Phone · 1970-01-01 —\nCloud review"
        assertEquals(1, report.capture.keptBoth)
        assertEquals(expected, b.reviews.value(b.profile, "al1", "review"))
        assertEquals(expected, a.reviews.value(a.profile, "al1", "review"))
    }

    @Test
    fun should_keepLiveNote_when_concurrentTombstoneHasNewerVersion() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        val b = device("dev-b", "Tablet", 20_000)
        a.note("n1", 100)
        a.note("n2", 200)
        a.note("n3", 300)
        a.cycle(drive)
        b.cycle(drive)

        a.now = 10_000
        a.note("n1", 10_000, "edited")
        a.capture()
        b.now = 20_000
        b.notes.remove(b.profile, "n1")
        assertEquals(1, b.cycle(drive).capture.totalTombstones)
        a.cycle(drive)
        b.cycle(drive)

        val record = a.record(SyncKinds.SONG_NOTE, SCOPE, "n1")!!
        assertFalse(record.deleted)
        assertEquals(RecordVersion(20_001, "dev-a"), record.version)
        assertEquals("edited", b.notes.value(b.profile, "n1", "content"))
        assertEquals(a.replica(), b.replica())
    }

    @Test
    fun should_convergeOnLiveNote_when_unacknowledgedTombstoneMeetsConcurrentEdit() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        val b = device("dev-b", "Tablet", 20_000)
        val c = device("dev-c", "Laptop", 30_000)
        a.note("n1", 100)
        a.note("n2", 200)
        a.note("n3", 300)
        listOf(a, b, c).forEach { it.cycle(drive) }

        b.now = 40_000
        b.note("n1", 40_000, "edited on tablet")
        b.capture()
        b.publish(drive)
        a.now = 50_000
        a.notes.remove(a.profile, "n1")
        assertEquals(1, a.capture().totalTombstones)
        // A's upload reached Drive but the app died before markUploaded: the tombstone stays pending.
        a.engine.outgoingFiles(0, a.now, 1).forEach { drive.upload(a.deviceId, it.fileKind, it.shard, it.bytes) }
        c.cycle(drive)
        assertTrue(c.record(SyncKinds.SONG_NOTE, SCOPE, "n1")!!.deleted)

        val report = a.cycle(drive)
        listOf(b, c, a, b, c).forEach { it.cycle(drive) }

        assertEquals(1, report.merges.sumOf { it.conflicts })
        assertFalse(a.record(SyncKinds.SONG_NOTE, SCOPE, "n1")!!.deleted)
        for (d in listOf(a, b, c)) assertEquals("edited on tablet", d.notes.value(d.profile, "n1", "content"))
        assertEquals(a.replica(), b.replica())
        assertEquals(a.replica(), c.replica())
    }

    // ---------------------------------------------------------------- publish bookkeeping

    @Test
    fun should_keepContentHash_when_onlyWrittenAtAndDeviceNameChange() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        a.note("n1", 100)
        a.rate("t1", 4, 100)
        a.translations.put(null, "tr-1", 100, "lines" to "[]")
        a.capture()

        val first = a.engine.outgoingFiles(epoch = 3, writtenAt = 1_000, appVersionCode = 1)
        a.name = "Renamed phone"
        val second = a.engine.outgoingFiles(epoch = 3, writtenAt = 999_999, appVersionCode = 2)

        assertEquals(1 + SyncFormat.ARTIFACT_SHARDS, first.size)
        assertEquals(first.map { it.contentHash }, second.map { it.contentHash })
        assertFalse(first[0].bytes.contentEquals(second[0].bytes))
        val state = first.single { it.fileKind == FileClass.STATE.wireName }
        assertEquals(2, state.recordCount)
        val shard = SyncFormat.artifactShard("tr-1")
        assertEquals(1, first.single { it.fileKind == FileClass.ARTIFACTS.wireName && it.shard == shard }.recordCount)
        val decoded = SnapshotCodec.decode(state.bytes)
        assertEquals(listOf(SyncKinds.SONG_NOTE, SyncKinds.TRACK_RATING), decoded.records.map { it.kind })
    }

    @Test
    fun should_keepChangePending_when_madeAfterSerialization() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        a.rate("t1", 1, 100)
        a.capture()
        val serialized = a.engine.outgoingFiles(0, a.now, 1)

        a.now = 15_000
        a.rate("t1", 2, 15_000)
        a.capture()
        serialized.forEach { a.engine.markUploaded(it) }

        assertTrue(a.engine.hasPendingChanges())
        assertTrue(a.dao.getLocalState(SyncKinds.TRACK_RATING, SCOPE, "t1")!!.pending)
        assertEquals(serialized.first().contentHash, a.engine.lastUploadedHash(FileClass.STATE.wireName, 0))

        a.engine.outgoingFiles(0, a.now, 1).forEach { a.engine.markUploaded(it) }
        assertFalse(a.engine.hasPendingChanges())
    }

    @Test
    fun should_advanceSeenVersionOnlyWithSuccessfulMerge_when_mergeFails() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        val b = device("dev-b", "Tablet", 20_000)
        a.review("al1", "Base", 100)
        a.cycle(drive)
        b.cycle(drive)
        a.cycle(drive)
        val bFileId = drive.file("dev-b")!!.id
        val seenBefore = a.dao.remoteFile(bFileId)!!.version

        a.now = 30_000
        a.review("al1", "Phone edit", 30_000)
        a.capture()
        b.now = 40_000
        b.review("al1", "Tablet edit", 40_000)
        // Sorted before the failing review record, so it is merged first and must be rolled back.
        b.albumRatings.put(b.profile, "al0", 40_000, "albumId" to "al0", "rating" to 7)
        b.cycle(drive)
        a.reviews.failMergeConcurrent = true
        try {
            a.pull(drive)
            fail("expected the merge to fail")
        } catch (e: IllegalStateException) {
            // expected
        }

        assertEquals(seenBefore, a.dao.remoteFile(bFileId)!!.version)
        assertEquals("dev-a", a.record(SyncKinds.ALBUM_REVIEW, SCOPE, "al1")!!.device)
        assertNull(a.record(SyncKinds.ALBUM_RATING, SCOPE, "al0"))

        a.reviews.failMergeConcurrent = false
        val merges = a.pull(drive)
        val seen = a.dao.remoteFile(bFileId)!!
        assertEquals(1, merges.sumOf { it.keptBoth })
        assertEquals(drive.file("dev-b")!!.version, seen.version)
        assertEquals("Tablet", seen.deviceName)
        assertEquals("dev-b", seen.deviceId)
    }

    @Test
    fun should_rejectEnvelope_when_schemaIsNewerThanSupported() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        val future = SyncEnvelope(
            schema = SyncFormat.SCHEMA + 1,
            deviceId = "dev-z",
            deviceName = "Future phone",
            fileClass = FileClass.STATE.wireName,
            writtenAt = 1,
            appVersionCode = 99,
            records = listOf(foreignRecord(SyncKinds.TRACK_RATING, 1, "t1", 5, json("rating" to 1), device = "dev-z")),
        )
        val remote = RemoteFile("fz", "fz", 3, null, null, emptyMap())

        try {
            SnapshotCodec.decode(SnapshotCodec.encode(future))
            fail("expected SyncSchemaTooNewException from decode")
        } catch (e: SyncSchemaTooNewException) {
            assertEquals("Future phone", e.deviceName)
        }
        try {
            a.engine.mergeRemote(remote, future)
            fail("expected SyncSchemaTooNewException from merge")
        } catch (e: SyncSchemaTooNewException) {
            assertEquals(SyncFormat.SCHEMA + 1, e.schema)
        }
        assertNull(a.dao.remoteFile("fz"))
        assertTrue(a.dao.allRecords().isEmpty())
    }

    // ---------------------------------------------------------------- capture / apply edge cases

    @Test
    fun should_skipOnlyFailingKind_when_domainReadThrows() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        a.note("n1", 100)
        a.rate("t1", 1, 100)
        a.rate("t2", 2, 100)
        a.cycle(drive)

        a.ratings.failReads = true
        a.note("n2", 200)
        val report = a.cycle(drive)

        assertEquals(listOf(SyncKinds.TRACK_RATING to SCOPE), report.capture.failures.map { it.kind to it.scope })
        assertEquals(1, report.capture.newVersions.count(SCOPE, SyncKinds.SONG_NOTE))
        assertTrue(report.capture.domainResets.isEmpty())
        assertEquals(2, a.dao.localStatesFor(SyncKinds.TRACK_RATING, SCOPE).count { it.localHash != null })
    }

    @Test
    fun should_markAppliedWithLocalHash_when_applyPolicySkips() = runTest {
        val a = device("dev-a", "Phone", 10_000, bind = false)
        a.settings.put(null, SyncSettingKeys.SPOTIFY_CLIENT_ID, null, "value" to "cloud-id")
        a.cycle(drive)
        val b = device("dev-b", "Tablet", 20_000, bind = false)
        b.settings.policy = { key, _ -> if (key == SyncSettingKeys.SPOTIFY_CLIENT_ID) SkipReason.POLICY else null }
        b.settings.put(null, SyncSettingKeys.SPOTIFY_CLIENT_ID, null, "value" to "my-id")

        val first = b.cycle(drive)
        val calls = b.settings.applyCalls.size
        val second = b.cycle(drive)

        assertEquals(1, first.apply.skipped[SkipReason.POLICY])
        assertEquals(0, first.capture.totalNewVersions)
        assertEquals(0, second.capture.totalNewVersions)
        assertEquals(calls, b.settings.applyCalls.size)
        assertEquals("my-id", b.settings.value(null, SyncSettingKeys.SPOTIFY_CLIENT_ID, "value"))
    }

    @Test
    fun should_leaveLocalStateUntouched_when_applyFindsRowChangedLocally() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        val b = device("dev-b", "Tablet", 20_000)
        a.rate("t1", 8, 100)
        a.cycle(drive)
        b.cycle(drive)
        a.now = 30_000
        a.rate("t1", 3, 30_000)
        a.cycle(drive)
        b.pull(drive)

        b.rate("t1", 6, b.now) // user edit between capture and apply
        val before = b.dao.getLocalState(SyncKinds.TRACK_RATING, SCOPE, "t1")
        val apply = b.applyReplica()

        assertEquals(1, apply.skippedChangedLocally)
        assertEquals(before, b.dao.getLocalState(SyncKinds.TRACK_RATING, SCOPE, "t1"))
        assertEquals("6", b.rating("t1"))
        b.now = 40_000
        b.cycle(drive)
        a.cycle(drive)
        assertEquals("6", a.rating("t1"))
    }

    @Test
    fun should_preserveUnknownKeysAndDecorate_when_capturingLocalEdit() = runTest {
        val decorating: (FakeDomain) -> List<FakeAdapter> = { domain ->
            standardAdapters(domain).map { adapter ->
                if (adapter.kind == SyncKinds.TRACK_RATING) {
                    FakeAdapter(domain, SyncKinds.TRACK_RATING, listOf("songId", "rating"), decorateKey = "by")
                } else {
                    adapter
                }
            }
        }
        val a = device("dev-a", "Phone", 10_000, adapters = decorating)
        val remote = foreignFile(
            "fx",
            "dev-x",
            foreignRecord(
                SyncKinds.TRACK_RATING,
                1,
                "t1",
                100,
                json("songId" to "t1", "rating" to 4, "pinned" to true),
            ),
        )
        a.engine.mergeRemote(remote.first, remote.second)
        a.capture()
        a.applyReplica()
        assertEquals("4", a.rating("t1"))

        a.now = 5_000
        a.rate("t1", 9, 5_000)
        a.capture()

        val payload = parsePayload(a.record(SyncKinds.TRACK_RATING, SCOPE, "t1")!!.payload)!!
        assertTrue(payload["pinned"]!!.jsonPrimitive.boolean)
        assertEquals("9", payload["rating"]!!.jsonPrimitive.content)
        assertEquals("Phone", payload["by"]!!.jsonPrimitive.content)
        assertEquals(RecordVersion(5_000, "dev-a"), a.record(SyncKinds.TRACK_RATING, SCOPE, "t1")!!.version)
    }

    // ---------------------------------------------------------------- maintenance

    @Test
    fun should_purgeScopeWithoutTombstones_when_startingFreshAsAnotherAccount() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        a.note("n1", 100)
        a.note("n2", 200)
        a.rate("t1", 5, 100)
        a.cycle(drive)

        val purged = a.engine.purgeScopeLocally(SCOPE, a.profile)

        assertEquals(3, purged)
        assertTrue(a.notes.keys(a.profile).isEmpty())
        assertTrue(a.ratings.keys(a.profile).isEmpty())
        assertTrue(a.dao.localStatesInScope(SCOPE).isEmpty())
        a.bind(a.profile, "s-bob")
        val report = a.cycle(drive)
        assertEquals(0, report.capture.totalTombstones)
        assertTrue(a.dao.allRecords().none { it.deleted })
        assertTrue(a.notes.keys(a.profile).isEmpty())
    }

    @Test
    fun should_clearReplicaButKeepMetaAndBindings_when_clearing() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        val b = device("dev-b", "Tablet", 20_000)
        a.note("n1", 100)
        a.cycle(drive)
        b.cycle(drive)
        a.cycle(drive)
        a.dao.putMeta(SyncMetaEntity(SyncMetaKeys.DEVICE_ID, "dev-a"))
        a.dao.upsertBinding(
            SyncBindingEntity(a.profile, SCOPE, "subsonic", "fp", SyncBindingEntity.STATE_ACTIVE, null, boundAt = 1),
        )
        assertNotNull(a.engine.lastUploadedHash(FileClass.STATE.wireName, 0))

        a.engine.clearReplica()

        assertTrue(a.dao.allRecords().isEmpty())
        assertTrue(a.dao.localStatesInScope(SCOPE).isEmpty())
        assertTrue(a.dao.remoteFiles().isEmpty())
        assertEquals("dev-a", a.dao.meta(SyncMetaKeys.DEVICE_ID))
        assertEquals(1, a.dao.bindings().size)
        assertNull(a.engine.lastUploadedHash(FileClass.STATE.wireName, 0))
        assertFalse(a.engine.hasPendingChanges())
    }

    // ---------------------------------------------------------------- adversarial review regressions

    /** The upload reached Drive but the app died before markUploaded: the change stays pending here. */
    private suspend fun SimDevice.uploadWithoutAck() {
        engine.outgoingFiles(0, now, 1).forEach { drive.upload(deviceId, it.fileKind, it.shard, it.bytes) }
    }

    @Test
    fun should_deleteEverywhere_when_peerDeletesNoteVersionItAlreadyReceived() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        val b = device("dev-b", "Tablet", 20_000)
        a.note("n1", 100)
        a.note("n2", 200)
        a.note("n3", 300)
        a.cycle(drive)
        b.cycle(drive)

        a.now = 40_000
        a.note("n1", 40_000, "edited")
        a.capture()
        a.uploadWithoutAck()
        b.cycle(drive)
        assertEquals("edited", b.notes.value(b.profile, "n1", "content"))
        b.notes.remove(b.profile, "n1") // a deliberate delete of the version the tablet was shown
        assertEquals(1, b.cycle(drive).capture.totalTombstones)

        val report = a.cycle(drive)
        b.cycle(drive)

        assertEquals(0, report.merges.sumOf { it.conflicts })
        assertTrue(a.record(SyncKinds.SONG_NOTE, SCOPE, "n1")!!.deleted)
        for (d in listOf(a, b)) assertFalse("n1" in d.notes.keys(d.profile))
        assertEquals(a.replica(), b.replica())
        assertFalse(a.engine.hasPendingChanges())
    }

    @Test
    fun should_takePeerRevision_when_peerRewroteReviewItAlreadyReceived() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        val b = device("dev-b", "Tablet", 20_000)
        a.review("al1", "Base", 100)
        a.cycle(drive)
        b.cycle(drive)

        a.now = 30_000
        a.review("al1", "Phone text", 30_000)
        a.capture()
        a.uploadWithoutAck()
        b.cycle(drive)
        assertEquals("Phone text", b.reviews.value(b.profile, "al1", "review"))
        b.now = 40_000
        b.review("al1", "Tablet rewrite", 40_000)
        b.cycle(drive)

        val report = a.cycle(drive)
        b.cycle(drive)

        assertEquals(0, report.merges.sumOf { it.keptBoth + it.conflicts })
        for (d in listOf(a, b)) assertEquals("Tablet rewrite", d.reviews.value(d.profile, "al1", "review"))
        assertEquals(a.replica(), b.replica())
    }

    @Test
    fun should_ignoreOwnOlderVersion_when_peerRelaysItWhileNewerEditIsPending() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        val b = device("dev-b", "Tablet", 20_000)
        a.review("al1", "Base", 100)
        a.cycle(drive)
        b.cycle(drive)

        a.now = 30_000
        a.review("al1", "First thoughts", 30_000)
        a.capture()
        a.uploadWithoutAck()
        b.cycle(drive) // the tablet now republishes the phone's first edit
        a.now = 40_000
        a.review("al1", "Second thoughts", 40_000)
        a.capture()

        val merges = a.pull(drive)

        assertEquals(0, merges.sumOf { it.conflicts + it.keptBoth })
        assertEquals(RecordVersion(40_000, "dev-a"), a.record(SyncKinds.ALBUM_REVIEW, SCOPE, "al1")!!.version)
        a.cycle(drive)
        b.cycle(drive)
        for (d in listOf(a, b)) assertEquals("Second thoughts", d.reviews.value(d.profile, "al1", "review"))
    }

    @Test
    fun should_keepBothTexts_when_localEditWasNotCapturedBeforePeerEditArrived() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        val b = device("dev-b", "Tablet", 20_000)
        a.review("al1", "Base", 100)
        a.cycle(drive)
        b.cycle(drive)

        a.review("al1", "Phone thoughts", 30_000) // the app died before the debounced capture
        b.now = 40_000
        b.review("al1", "Tablet thoughts", 40_000)
        b.cycle(drive)
        a.now = 50_000
        val report = a.cycle(drive) // merge first, then capture meets the uncaptured edit
        b.cycle(drive)

        val expected = "Phone thoughts\n\n— Tablet · 1970-01-01 —\nTablet thoughts"
        assertEquals(1, report.capture.keptBoth)
        for (d in listOf(a, b)) assertEquals(expected, d.reviews.value(d.profile, "al1", "review"))
        assertEquals(a.replica(), b.replica())
        assertEquals(0, a.cycle(drive).uploads + b.cycle(drive).uploads)
    }

    @Test
    fun should_restoreNote_when_deletedLocallyBeforePeerEditWasApplied() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        val b = device("dev-b", "Tablet", 20_000)
        a.note("n1", 100)
        a.note("n2", 200)
        a.note("n3", 300)
        a.cycle(drive)
        b.cycle(drive)

        b.now = 40_000
        b.note("n1", 40_000, "edited on tablet")
        b.cycle(drive)
        a.notes.remove(a.profile, "n1") // deleted before the phone ever saw the tablet's edit

        val report = a.cycle(drive)
        b.cycle(drive)

        assertEquals(0, report.capture.totalTombstones)
        for (d in listOf(a, b)) assertEquals("edited on tablet", d.notes.value(d.profile, "n1", "content"))
        assertTrue(a.dao.allRecords().none { it.deleted })
        assertEquals(a.replica(), b.replica())
    }

    @Test
    fun should_keepHeldNotesHeld_when_notesAreAddedOrDeletedBeforeReview() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        for (i in 1..4) a.note("n$i", i * 100L)
        a.cycle(drive)
        listOf("n1", "n2", "n3").forEach { a.notes.remove(a.profile, it) }
        assertEquals(3, a.cycle(drive).capture.heldTombstones)
        val heldKeys = listOf("n1", "n2", "n3")

        // Adding notes lowers the ratio below the valve: the held delete must still wait for the user.
        for (i in 5..10) a.note("n$i", a.now + i)
        val afterAdding = a.cycle(drive)
        assertEquals(0, afterAdding.capture.totalTombstones)
        assertEquals(3, afterAdding.capture.heldTombstones)
        assertEquals(heldKeys, a.engine.heldReview()!!.keys)

        // A genuine single delete meanwhile goes through on its own; the held keys stay held.
        a.notes.remove(a.profile, "n5")
        val singleDelete = a.cycle(drive)
        assertEquals(1, singleDelete.capture.tombstonesCreated.count(SCOPE, SyncKinds.SONG_NOTE))
        assertTrue(a.record(SyncKinds.SONG_NOTE, SCOPE, "n5")!!.deleted)
        assertEquals(heldKeys, a.engine.heldReview()!!.keys)
        assertTrue(heldKeys.none { a.record(SyncKinds.SONG_NOTE, SCOPE, it)!!.deleted })

        assertEquals(3, a.engine.resolveHeldReview(restore = true))
        a.cycle(drive)
        assertNull(a.engine.heldReview())
        assertEquals((1..10).map { "n$it" }.toSet() - "n5", a.notes.keys(a.profile))
    }

    @Test
    fun should_dropHeldReview_when_itsAccountIsUnboundFromThisDevice() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        for (i in 1..4) a.note("n$i", i * 100L)
        a.cycle(drive)
        listOf("n1", "n2", "n3").forEach { a.notes.remove(a.profile, it) }
        a.cycle(drive)
        assertNotNull(a.engine.heldReview())

        // Paused (still bound on record, just not active this cycle): the question stays.
        a.bindings.clear()
        assertNotNull(a.capture().heldReview)

        // The profile is removed: the binder unbinds it and deletes its local state.
        a.dao.deleteLocalStatesForProfile(a.profile)
        val capture = a.capture()

        assertNull(capture.heldReview)
        assertNull(a.engine.heldReview())
        assertTrue(a.dao.allRecords().none { it.deleted })
    }

    @Test
    fun should_notTreatGlobalScopeAsDomainReset_when_twoSettingsAreUnset() = runTest {
        val a = device("dev-a", "Phone", 10_000, bind = false)
        a.settings.put(null, SyncSettingKeys.SEAM_TOP_STYLE, null, "value" to "dots")
        a.settings.put(null, SyncSettingKeys.TRANSLATION_LANGUAGE, null, "value" to "Japanese")
        a.translations.put(null, "tr-1", 100, "lines" to "[]")
        a.cycle(drive)
        a.translations.put(null, "tr-2", 200, "lines" to "[1]")
        a.capture() // debounced capture: tr-2 is pending, not uploaded yet

        // Both settings back at their defaults: no longer explicit, so no longer read as rows.
        a.settings.remove(null, SyncSettingKeys.SEAM_TOP_STYLE)
        a.settings.remove(null, SyncSettingKeys.TRANSLATION_LANGUAGE)
        val capture = a.capture()

        assertTrue(capture.domainResets.isEmpty())
        assertEquals(0, capture.adopted)
        assertEquals(2, capture.droppedLocalStates)
        assertTrue(a.dao.getLocalState(SyncKinds.LYRICS_TRANSLATION, SyncFormat.GLOBAL_SCOPE, "tr-2")!!.pending)
    }

    @Test
    fun should_neverTombstone_when_purgeIsInterruptedAndSyncResumes() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        for (i in 1..4) a.note("n$i", i * 100L)
        a.rate("t1", 5, 100)
        a.cycle(drive)
        a.notes.failDeletesAfter = 2

        try {
            a.engine.purgeScopeLocally(SCOPE, a.profile)
            fail("expected the purge to be interrupted")
        } catch (e: IOException) {
            // expected
        }
        assertEquals(2, a.notes.keys(a.profile).size)
        // "Keep syncing as before": the binding resumes with the purge half done.
        a.notes.failDeletesAfter = null
        val report = a.cycle(drive)

        assertEquals(0, report.capture.totalTombstones)
        assertEquals(0, report.capture.heldTombstones)
        assertTrue(a.dao.allRecords().none { it.deleted })
        assertEquals((1..4).map { "n$it" }.toSet(), a.notes.keys(a.profile))
    }

    @Test
    fun should_neverTombstone_when_adapterReportsOwnerHashForNoteOwnedElsewhere() = runTest {
        val sloppy: (FakeDomain) -> List<FakeAdapter> = { domain ->
            standardAdapters(domain).map { adapter ->
                if (adapter.kind == SyncKinds.SONG_NOTE) {
                    FakeAdapter(
                        domain,
                        SyncKinds.SONG_NOTE,
                        fields = listOf("trackId", "content"),
                        propagatesDeletes = true,
                        conflictPolicy = ConflictPolicy.LWW_LIVE_WINS,
                        keysUniqueAcrossProfiles = true,
                        ownedElsewhereReportsOwnerHash = true,
                    )
                } else {
                    adapter
                }
            }
        }
        val a = device("dev-a", "Phone", 10_000, bind = false, adapters = sloppy)
        a.bind("old-profile", SCOPE)
        a.notes.put("old-profile", "n1", 100, "trackId" to "t1", "content" to "only note")
        a.cycle(drive)
        a.bind("new-profile", SCOPE)

        val first = a.cycle(drive)
        val second = a.cycle(drive)

        assertEquals(1, first.apply.skipped[SkipReason.OWNED_ELSEWHERE])
        assertEquals(0, first.capture.totalTombstones + second.capture.totalTombstones)
        assertTrue(a.dao.allRecords().none { it.deleted })
        assertEquals(setOf("n1"), a.notes.keys("old-profile"))
    }

    @Test
    fun should_applyRemainingRecords_when_oneRecordFailsToApply() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        val remote = foreignFile(
            "fx",
            "dev-x",
            foreignRecord(SyncKinds.TRACK_RATING, 1, "t1", 100, json("songId" to "t1", "rating" to 1)),
            foreignRecord(SyncKinds.TRACK_RATING, 1, "t2", 100, json("songId" to "t2", "rating" to 2)),
            foreignRecord(SyncKinds.TRACK_RATING, 1, "t3", 100, json("songId" to "t3", "rating" to 3)),
        )
        a.engine.mergeRemote(remote.first, remote.second)
        a.ratings.failApplyFor = setOf("t1")

        val first = a.applyReplica()

        assertEquals(listOf(SyncKinds.TRACK_RATING to SCOPE), first.failures.map { it.kind to it.scope })
        assertEquals(2, first.appliedLive.count(SCOPE, SyncKinds.TRACK_RATING))
        assertEquals(setOf("t2", "t3"), a.ratings.keys(a.profile))
        a.ratings.failApplyFor = emptySet()
        assertEquals(1, a.applyReplica().appliedLive.count(SCOPE, SyncKinds.TRACK_RATING))
        assertEquals("1", a.rating("t1"))
    }

    @Test
    fun should_rollBackOnlyThatScope_when_adapterHookThrowsDuringCapture() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        a.note("n1", 100)
        a.rate("t1", 5, 100)
        a.settings.put(null, SyncSettingKeys.SEAM_TOP_STYLE, null, "value" to "dots")
        a.ratings.failDecorate = true

        val capture = a.capture()

        assertEquals(setOf(SCOPE), capture.failures.map { it.scope }.toSet())
        assertTrue(SyncKinds.TRACK_RATING in capture.failures.map { it.kind })
        assertTrue(a.dao.recordsInScope(SCOPE).isEmpty()) // the whole scope rolled back, nothing half-written
        assertTrue(a.dao.localStatesInScope(SCOPE).isEmpty())
        assertEquals(0, capture.newVersions.count(SCOPE, SyncKinds.SONG_NOTE))
        assertEquals(1, capture.newVersions.count(SyncFormat.GLOBAL_SCOPE, SyncKinds.SETTING))

        a.ratings.failDecorate = false
        assertEquals(2, a.capture().newVersions[SCOPE]!!.values.sum())
    }

    @Test
    fun should_pickHigherKindVersionOnAllDevices_when_sameVersionAndPayloadDifferOnlyInKindVersion() = runTest {
        val a = device("dev-a", "Phone", 10_000)
        val b = device("dev-b", "Tablet", 20_000)
        val payload = json("albumId" to "al1", "rating" to 6)
        val f1 = foreignFile("f1", "dev-x1", foreignRecord(SyncKinds.ALBUM_RATING, 1, "al1", 500, payload))
        val f2 = foreignFile("f2", "dev-x2", foreignRecord(SyncKinds.ALBUM_RATING, 2, "al1", 500, payload))

        a.engine.mergeRemote(f1.first, f1.second)
        a.engine.mergeRemote(f2.first, f2.second)
        b.engine.mergeRemote(f2.first, f2.second)
        b.engine.mergeRemote(f1.first, f1.second)

        assertEquals(2, a.record(SyncKinds.ALBUM_RATING, SCOPE, "al1")!!.kindVersion)
        assertEquals(a.replica(), b.replica())
    }

    private fun foreignRecord(
        kind: String,
        kindVersion: Int,
        key: String,
        ts: Long,
        payload: JsonObject,
        device: String = "dev-x",
    ) = WireRecord(kind, kindVersion, SCOPE, key, ts, device, payload = payload)

    private fun foreignFile(id: String, deviceId: String, vararg records: WireRecord): Pair<RemoteFile, SyncEnvelope> =
        RemoteFile(id, id, 1, null, null, emptyMap()) to SyncEnvelope(
            deviceId = deviceId,
            deviceName = deviceId,
            fileClass = FileClass.STATE.wireName,
            writtenAt = 1,
            appVersionCode = 1,
            records = records.toList(),
        )

    private companion object {
        const val SCOPE = "s-alice"
    }
}
