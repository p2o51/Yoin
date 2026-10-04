package com.gpo.yoin.data.repository

import com.gpo.yoin.data.model.LibraryMembership
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.MusicWriteActions
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class YoinRepositoryLibraryTest {
    private var addResult: suspend () -> Result<LibraryMembership> = { Result.success(LibraryMembership.Pending) }
    private var membershipResult = Result.success(LibraryMembership.Added)
    private var addCalls = 0
    private val writes = object : MusicWriteActions by mockk(relaxed = true) {
        override suspend fun addToLibrary(trackId: MediaId): Result<LibraryMembership> {
            addCalls++
            return addResult()
        }
        override suspend fun libraryMembership(trackId: MediaId): Result<LibraryMembership> = membershipResult
    }
    private val source = mockk<MusicSource> {
        every { id } returns MediaId.PROVIDER_APPLE_MUSIC
        every { capabilities } returns setOf(Capability.LIBRARY_ADD)
        every { writeActions() } returns writes
    }
    private val activeSource = MutableStateFlow<MusicSource?>(source)
    private val profileId = MutableStateFlow<String?>("first-account")
    private val track = Track(
        id = MediaId(MediaId.PROVIDER_APPLE_MUSIC, "123"),
        title = "Song", artist = "Artist", artistId = null, album = null, albumId = null,
        coverArt = null, durationSec = null, trackNumber = null, year = null, genre = null, userRating = null,
    )

    private fun TestScope.repository() = YoinRepository(
        activeSource = activeSource,
        activeProfileId = profileId,
        database = mockk(relaxed = true),
        geminiService = mockk(relaxed = true),
        songAboutEntryDao = mockk(relaxed = true),
        geminiConfigDao = mockk(relaxed = true),
        lyricsCacheDao = mockk(relaxed = true),
        lyricsTranslationCacheDao = mockk(relaxed = true),
        songNoteDao = mockk(relaxed = true),
        albumNoteDao = mockk(relaxed = true),
        albumRatingDao = mockk(relaxed = true),
        memoryCopyCacheDao = mockk(relaxed = true),
        neoDbSyncService = mockk(relaxed = true),
        repositoryScope = backgroundScope,
    )

    @Test
    fun should_keepPending_when_additionHasOnlyBeenAccepted() = runTest {
        val repository = repository()
        assertEquals(LibraryMembership.Pending, repository.addToLibrary(track).getOrThrow())
        assertEquals(LibraryMembership.Pending, repository.observeLibraryMembership(track.id).first())
        assertEquals(0L, repository.libraryRevision.first())
    }

    @Test
    fun should_refreshLibrary_when_pendingAdditionIsConfirmedByLaterRead() = runTest {
        val repository = repository()
        repository.addToLibrary(track).getOrThrow()
        repository.refreshLibraryMembership(track).getOrThrow()
        assertEquals(1L, repository.libraryRevision.first())
        repository.refreshLibraryMembership(track).getOrThrow()
        assertEquals(1L, repository.libraryRevision.first())
    }

    @Test
    fun should_isolateMembership_when_accountsShareCatalogId() = runTest {
        val repository = repository()
        repository.refreshLibraryMembership(track).getOrThrow()
        profileId.value = "second-account"
        assertEquals(LibraryMembership.Unknown, repository.observeLibraryMembership(track.id).first())
    }

    @Test
    fun should_discardCompletion_when_profileChangesDuringWrite() = runTest {
        val repository = repository()
        val completion = CompletableDeferred<LibraryMembership>()
        addResult = { Result.success(completion.await()) }
        val write = async { repository.addToLibrary(track) }
        runCurrent()
        profileId.value = "second-account"
        completion.complete(LibraryMembership.Added)
        write.await().getOrThrow()
        assertEquals(LibraryMembership.Unknown, repository.observeLibraryMembership(track.id).first())
    }

    @Test
    fun should_rejectWrongProvider_withoutCallingSource() = runTest {
        val repository = repository()
        val other = track.copy(id = MediaId.spotify("123"))
        assertTrue(repository.addToLibrary(other).isFailure)
        assertEquals(0, addCalls)
    }

    @Test
    fun should_propagateCancellation_when_sourceReturnsCancelledResult() = runTest {
        val repository = repository()
        addResult = { Result.failure(CancellationException("cancelled")) }
        var cancelled = false
        try {
            repository.addToLibrary(track)
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }
}
