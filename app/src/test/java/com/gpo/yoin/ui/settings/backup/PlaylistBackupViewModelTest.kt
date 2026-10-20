package com.gpo.yoin.ui.settings.backup

import com.gpo.yoin.data.backup.BackupTrack
import com.gpo.yoin.data.backup.PlaylistBackupContent
import com.gpo.yoin.data.backup.PlaylistBackupRecord
import com.gpo.yoin.data.backup.PlaylistBackupStore
import com.gpo.yoin.data.backup.PlaylistRestoreRecord
import com.gpo.yoin.data.backup.PlaylistSnapshots
import com.gpo.yoin.data.backup.RestoreOutcome
import com.gpo.yoin.data.backup.RestoreTarget
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.transfer.MatchDecision
import com.gpo.yoin.data.transfer.MatchMethod
import com.gpo.yoin.data.transfer.PlaylistWrite
import com.gpo.yoin.data.transfer.ReviewRow
import com.gpo.yoin.data.transfer.TrackResolution
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistBackupViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val backup = PlaylistBackupRecord(
        id = "b1",
        name = "Road trip",
        sourceProvider = MediaId.PROVIDER_SPOTIFY,
        sourceProfileLabel = "Chen",
        sourcePlaylistId = "spotify:p1",
        sourceProfileId = null,
        playlistCreatedAt = null,
        firstBackedUpAt = 1L,
        lastBackedUpAt = 1L,
        lastChangedAt = 1L,
        sourceGoneAt = null,
        content = PlaylistBackupContent(
            tracks = listOf(
                BackupTrack("Creep", listOf("Radiohead"), null, 238, null, "spotify:t1"),
                BackupTrack("Lemon", listOf("Kenshi Yonezu"), null, 255, null, "spotify:t2"),
                BackupTrack("Demo", listOf("Me"), null, 100, null, "spotify:t3"),
            ),
        ),
        restores = emptyList(),
        updatedAt = 1L,
    )
    private val target = RestoreTarget("am", "Apple Music", MediaId.PROVIDER_APPLE_MUSIC)
    private val store = mockk<PlaylistBackupStore>(relaxed = true).also {
        every { it.observe("b1") } returns MutableStateFlow(backup)
    }

    @Test
    fun should_keepMatchingAndReview_when_sheetIsClosedMidway() = runTest {
        val release = CompletableDeferred<Unit>()
        val viewModel = viewModel(match = { _, _, onProgress, _ ->
            onProgress(1, 3)
            release.await()
            resolutions()
        })
        advanceUntilIdle()

        viewModel.openRestore()
        assertEquals(RestoreUiState.ChoosingTarget, viewModel.restore.value)
        viewModel.restoreTo(target)
        advanceUntilIdle()
        assertEquals(RestoreUiState.Matching(target, done = 1, total = 3), viewModel.restore.value)

        viewModel.dismissSheet()
        release.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.sheetOpen.value)
        val review = viewModel.restore.value as RestoreUiState.Review
        assertEquals(listOf(true, false, false), review.rows.map { it.checked })

        // Restore reopens the waiting review rather than starting over.
        viewModel.openRestore()
        assertTrue(viewModel.sheetOpen.value)
        assertEquals(review, viewModel.restore.value)
    }

    @Test
    fun should_writeCheckedRowsAndShowRecord_when_creating() = runTest {
        var written: List<ReviewRow>? = null
        val record = PlaylistRestoreRecord(
            at = 9L,
            targetProvider = MediaId.PROVIDER_APPLE_MUSIC,
            targetLabel = "Apple Music",
            added = 2,
            skipped = 0,
            notFound = 1,
        )
        val viewModel = viewModel(
            match = { _, _, _, _ -> resolutions() },
            create = { _, _, rows ->
                written = rows
                RestoreOutcome(record, PlaylistWrite(created = null, added = 2, requested = 2))
            },
        )
        advanceUntilIdle()
        viewModel.openRestore()
        viewModel.restoreTo(target)
        advanceUntilIdle()

        viewModel.choose(position = 1, index = 0)
        viewModel.createPlaylist()
        advanceUntilIdle()

        assertEquals(listOf(true, true, false), written?.map { it.checked })
        assertEquals(RestoreUiState.Done(target, record), viewModel.restore.value)
        viewModel.dismissSheet()
        assertEquals(RestoreUiState.Idle, viewModel.restore.value)
    }

    @Test
    fun should_showFailure_when_targetCantBeReached() = runTest {
        val viewModel = viewModel(match = { _, _, _, _ -> throw IllegalStateException("Profile unavailable") })
        advanceUntilIdle()

        viewModel.openRestore()
        viewModel.restoreTo(target)
        advanceUntilIdle()

        assertEquals(RestoreUiState.Failed(target), viewModel.restore.value)
    }

    private fun resolutions(): List<TrackResolution> {
        val tracks = backup.content.tracks.map(PlaylistSnapshots::toTrack)
        return listOf(
            TrackResolution(0, tracks[0], MatchDecision.Matched(candidate("c0"), MatchMethod.Search)),
            TrackResolution(1, tracks[1], MatchDecision.Ambiguous(listOf(candidate("c1"), candidate("c2")))),
            TrackResolution(2, tracks[2], MatchDecision.NotFound),
        )
    }

    private fun viewModel(
        match: suspend (PlaylistBackupRecord, RestoreTarget, (Int, Int) -> Unit, (TrackResolution) -> Unit) -> List<TrackResolution>,
        create: suspend (PlaylistBackupRecord, RestoreTarget, List<ReviewRow>) -> RestoreOutcome = { _, _, _ ->
            error("not expected")
        },
    ) = PlaylistBackupViewModel(
        backupId = "b1",
        store = store,
        restoreTargets = flowOf(listOf(target)),
        match = match,
        create = create,
    )

    private fun candidate(rawId: String) = Track(
        id = MediaId(MediaId.PROVIDER_APPLE_MUSIC, rawId),
        title = rawId,
        artist = "A",
        artistId = null,
        album = null,
        albumId = null,
        coverArt = null,
        durationSec = 240,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
    )
}
