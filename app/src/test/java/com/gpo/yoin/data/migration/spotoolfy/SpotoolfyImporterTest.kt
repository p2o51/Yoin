package com.gpo.yoin.data.migration.spotoolfy

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.local.AlbumRating
import com.gpo.yoin.data.local.ImportBatch
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.MediaId
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpotoolfyImporterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var database: YoinDatabase
    private lateinit var importer: SpotoolfyImporter
    private lateinit var directory: File

    private val trackA = "4uLU6hMCjMI75M1A2tKUQC"
    private val trackB = "7qiZfU4dY1lWllzX7mPBI3"

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), YoinDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        directory = temp.newFolder("spotoolfy-import")
        importer = SpotoolfyImporter(
            database = database,
            directory = directory,
            segmenter = { text -> text.split(" ") },
            clock = { 42L },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun fixtureBytes(): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("spotoolfy/export_v1.json")).use { it.readBytes() }

    private fun receive(bytes: ByteArray = fixtureBytes()) =
        importer.receive(ByteArrayInputStream(bytes))

    private suspend fun stageFixture(): SpotoolfyImporter.StageResult.Staged {
        assertEquals(SpotoolfyImporter.ReceiveResult.Received, receive())
        return importer.stagePending() as SpotoolfyImporter.StageResult.Staged
    }

    @Test
    fun should_stageWithRecapAndKeepFile_when_spotoolfyFileIsReceived() = runTest {
        val staged = stageFixture()

        assertEquals(2, staged.recap.noteCount)
        assertEquals(2, staged.recap.ratedTrackCount)
        assertFalse(importer.hasPending())
        assertTrue(File(directory, "${staged.batchId}.json").isFile)
        val batch = importer.staged()!!
        assertEquals(ImportBatch.STATUS_STAGED, batch.status)
        assertNull(batch.profileId)
        assertEquals(42L, batch.importedAt)
        assertEquals(staged.recap, importer.recap(staged.batchId))
        // Nothing reached an account yet.
        assertEquals(0, database.songNoteDao().countNonBlankNotes(MediaId.PROVIDER_SPOTIFY, "profile-a"))
    }

    @Test
    fun should_keepTheSameBatch_when_theSameFileIsSharedAgain() = runTest {
        val first = stageFixture()
        val again = stageFixture()

        assertEquals(first.batchId, again.batchId)
        assertEquals(listOf("${first.batchId}.json"), directory.list()!!.toList())
    }

    @Test
    fun should_replaceStagedBatch_when_anotherFileIsStaged() = runTest {
        val first = stageFixture()
        val other = String(fixtureBytes()).replace("前奏一响", "副歌一响").toByteArray()
        receive(other)

        val second = importer.stagePending() as SpotoolfyImporter.StageResult.Staged

        assertTrue(first.batchId != second.batchId)
        assertNull(importer.batch(first.batchId))
        assertFalse(File(directory, "${first.batchId}.json").exists())
    }

    @Test
    fun should_explainWhy_when_fileCannotBeStaged() = runTest {
        receive("""{"hello":"world"}""".toByteArray())
        assertEquals(SpotoolfyImporter.StageResult.NotSpotoolfy, importer.stagePending())

        receive("""{"tracks":[""".toByteArray())
        assertEquals(SpotoolfyImporter.StageResult.Corrupt, importer.stagePending())

        // A fresh Spotoolfy install exports only its sample rows.
        val samples = """{"tracks":[],"translations":[],"play_contexts":[],"records":[""" +
            """{"trackId":"spotify:track:sample1","noteContent":"This is a sample note.","rating":5,"recordedAt":1}]}"""
        receive(samples.toByteArray())
        assertEquals(SpotoolfyImporter.StageResult.Empty, importer.stagePending())

        assertNull(importer.staged())
        assertTrue(directory.list()!!.isEmpty())
    }

    @Test
    fun should_refuseFile_when_overTheSizeLimit() {
        val tooBig = object : java.io.InputStream() {
            override fun read(): Int = ' '.code
        }

        assertEquals(SpotoolfyImporter.ReceiveResult.TooLarge, importer.receive(tooBig))
        assertFalse(importer.hasPending())
    }

    @Test
    fun should_writeNotesAndRatingsToAccount_when_committed() = runTest {
        val staged = stageFixture()

        val result = importer.commit(staged.batchId, "profile-a", unknownTitle = "Unknown")

        assertEquals(SpotoolfyImporter.CommitResult.Committed(noteCount = 2, ratingCount = 2), result)
        val tracks = listOf(trackA, trackB)
        val notes = database.songNoteDao().getForTracks(tracks, MediaId.PROVIDER_SPOTIFY, "profile-a")
        assertEquals(setOf(trackA, trackB), notes.map { it.trackId }.toSet())
        val noteA = notes.single { it.trackId == trackA }
        assertEquals(1_730_000_000_000L, noteA.createdAt)
        assertEquals(1_730_000_000_000L, noteA.updatedAt)
        assertEquals("Placeholder Song", noteA.title)
        assertEquals(83_000L, noteA.positionMs)
        val ratings = database.localRatingDao().getRatings(tracks, MediaId.PROVIDER_SPOTIFY, "profile-a")
        assertEquals(mapOf(trackA to 10f, trackB to 5f), ratings.associate { it.songId to it.rating })
        assertEquals(mapOf(trackA to 5, trackB to 3), ratings.associate { it.songId to it.serverRating })
        assertTrue(ratings.none { it.needsSync })
        val batch = importer.batch(staged.batchId)!!
        assertEquals(ImportBatch.STATUS_COMMITTED, batch.status)
        assertEquals("profile-a", batch.profileId)
        assertFalse(File(directory, "${staged.batchId}.json").exists())
        assertEquals(batch, importer.observeLatestCommitted("profile-a").first())
        // The recap stays readable from Settings.
        assertEquals(staged.recap, importer.recap(staged.batchId))
    }

    @Test
    fun should_reportAlreadyImported_when_committedFileIsSharedAgain() = runTest {
        val staged = stageFixture()
        importer.commit(staged.batchId, "profile-a", unknownTitle = "Unknown")

        receive()

        assertEquals(SpotoolfyImporter.StageResult.AlreadyImported, importer.stagePending())
    }

    @Test
    fun should_refuseCommit_when_accountAlreadyHasUserData() = runTest {
        val staged = stageFixture()
        database.albumRatingDao().upsert(
            AlbumRating(
                profileId = "profile-a",
                albumId = "album",
                provider = MediaId.PROVIDER_SPOTIFY,
                rating = 8f,
                review = null,
                neoDbReviewUuid = null,
            ),
        )

        assertFalse(importer.isEligible("profile-a"))
        assertEquals(
            SpotoolfyImporter.CommitResult.NotEligible,
            importer.commit(staged.batchId, "profile-a", unknownTitle = "Unknown"),
        )
        assertEquals(ImportBatch.STATUS_STAGED, importer.batch(staged.batchId)!!.status)
    }

    @Test
    fun should_takeBackEveryRow_when_committedBatchIsDiscarded() = runTest {
        val staged = stageFixture()
        importer.commit(staged.batchId, "profile-a", unknownTitle = "Unknown")

        importer.discard(staged.batchId)

        val tracks = listOf(trackA, trackB)
        assertTrue(database.songNoteDao().getForTracks(tracks, MediaId.PROVIDER_SPOTIFY, "profile-a").isEmpty())
        assertTrue(database.localRatingDao().getRatings(tracks, MediaId.PROVIDER_SPOTIFY, "profile-a").isEmpty())
        assertNull(importer.batch(staged.batchId))
        assertTrue(importer.isEligible("profile-a"))
    }

    @Test
    fun should_leaveNotesWithTheirAccount_when_anotherAccountAlreadyHoldsThem() = runTest {
        val first = stageFixture()
        importer.commit(first.batchId, "profile-a", unknownTitle = "Unknown")
        // Exported again later: another file, the same notes.
        receive(fixtureBytes() + "\n".toByteArray())
        val second = importer.stagePending() as SpotoolfyImporter.StageResult.Staged

        val result = importer.commit(second.batchId, "profile-b", unknownTitle = "Unknown")

        assertEquals(SpotoolfyImporter.CommitResult.Committed(noteCount = 0, ratingCount = 2), result)
        val tracks = listOf(trackA, trackB)
        assertEquals(2, database.songNoteDao().getForTracks(tracks, MediaId.PROVIDER_SPOTIFY, "profile-a").size)
        assertTrue(database.songNoteDao().getForTracks(tracks, MediaId.PROVIDER_SPOTIFY, "profile-b").isEmpty())
        assertEquals(0, importer.batch(second.batchId)!!.noteCount)

        // Taking the second file back out leaves the first account's notes alone.
        importer.discard(second.batchId)

        assertEquals(2, database.songNoteDao().getForTracks(tracks, MediaId.PROVIDER_SPOTIFY, "profile-a").size)
        assertEquals(2, database.localRatingDao().getRatings(tracks, MediaId.PROVIDER_SPOTIFY, "profile-a").size)
        assertTrue(database.localRatingDao().getRatings(tracks, MediaId.PROVIDER_SPOTIFY, "profile-b").isEmpty())
    }

    @Test
    fun should_writeNothing_when_everyNoteIsAlreadyInAnotherAccount() = runTest {
        val notesOnly = """{"tracks":[],"translations":[],"play_contexts":[],"records":[""" +
            """{"trackId":"$trackA","noteContent":"only words","recordedAt":1730000000000}]}"""
        receive(notesOnly.toByteArray())
        val first = importer.stagePending() as SpotoolfyImporter.StageResult.Staged
        importer.commit(first.batchId, "profile-a", unknownTitle = "Unknown")
        receive((notesOnly + "\n").toByteArray())
        val second = importer.stagePending() as SpotoolfyImporter.StageResult.Staged

        val result = importer.commit(second.batchId, "profile-b", unknownTitle = "Unknown")

        assertEquals(SpotoolfyImporter.CommitResult.AlreadyInYoin, result)
        assertEquals(ImportBatch.STATUS_STAGED, importer.batch(second.batchId)!!.status)
        assertTrue(importer.isEligible("profile-b"))
    }

    @Test
    fun should_leaveNothing_when_fileIsDroppedWhileItIsBeingCommitted() = runTest {
        val staged = stageFixture()

        val commit = async { importer.commit(staged.batchId, "profile-a", unknownTitle = "Unknown") }
        val drop = async { importer.discard(staged.batchId) }
        commit.await()
        drop.await()

        // Whichever went first, the file is not in the account and no batch is left behind.
        val tracks = listOf(trackA, trackB)
        assertTrue(database.songNoteDao().getForTracks(tracks, MediaId.PROVIDER_SPOTIFY, "profile-a").isEmpty())
        assertTrue(database.localRatingDao().getRatings(tracks, MediaId.PROVIDER_SPOTIFY, "profile-a").isEmpty())
        assertNull(importer.batch(staged.batchId))
        assertTrue(importer.isEligible("profile-a"))
    }

    @Test
    fun should_reportMissing_when_committingADroppedBatch() = runTest {
        val staged = stageFixture()
        importer.discard(staged.batchId)

        assertEquals(
            SpotoolfyImporter.CommitResult.Missing,
            importer.commit(staged.batchId, "profile-a", unknownTitle = "Unknown"),
        )
        assertTrue(importer.isEligible("profile-a"))
    }

    @Test
    fun should_writeStagedFile_when_firstRunEndedAfterSpotifyConnected() = runTest {
        val staged = stageFixture()

        val result = importer.resumeInterrupted("profile-a", unknownTitle = "Unknown")

        assertEquals(SpotoolfyImporter.CommitResult.Committed(noteCount = 2, ratingCount = 2), result)
        assertEquals(ImportBatch.STATUS_COMMITTED, importer.batch(staged.batchId)!!.status)
        assertNull(importer.staged())
        assertTrue(directory.list()!!.isEmpty())
    }

    @Test
    fun should_dropStagedFile_when_firstRunEndedWithoutSpotify() = runTest {
        val staged = stageFixture()
        receive()

        assertNull(importer.resumeInterrupted(spotifyProfileId = null, unknownTitle = "Unknown"))
        assertNull(importer.batch(staged.batchId))
        assertFalse(importer.hasPending())
        assertTrue(directory.list()!!.isEmpty())
    }

    @Test
    fun should_doNothing_when_noFileWasStaged() = runTest {
        assertNull(importer.resumeInterrupted("profile-a", unknownTitle = "Unknown"))
        assertTrue(importer.isEligible("profile-a"))
    }

    @Test
    fun should_dropStagedBatchesAndFiles_when_landingEndsWithoutAnAccount() = runTest {
        stageFixture()

        importer.discardStaged()

        assertNull(importer.staged())
        assertTrue(directory.list()!!.isEmpty())
    }
}
