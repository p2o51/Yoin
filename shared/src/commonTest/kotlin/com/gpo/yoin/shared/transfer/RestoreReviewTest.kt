package com.gpo.yoin.shared.transfer

import com.gpo.yoin.shared.sync.testing.assertEquals
import com.gpo.yoin.shared.sync.testing.assertFalse
import com.gpo.yoin.shared.sync.testing.assertTrue
import kotlin.test.Test

/** Android's RestoreReviewTest, against [MatchTrack]. */
class RestoreReviewTest {

    private val rows = RestoreReview.rows(
        listOf(
            resolution(2, MatchDecision.NotFound),
            resolution(0, MatchDecision.Matched(track("m0"), MatchMethod.Isrc)),
            resolution(1, MatchDecision.Ambiguous(listOf(track("u1"), track("u2"), track("u3"), track("u4")))),
            resolution(3, MatchDecision.Matched(track("m3"), MatchMethod.Search)),
        ),
    )

    @Test
    fun should_checkMatchedAndLeaveUncertainUnchecked_when_reviewOpens() {
        assertEquals(listOf(0, 1, 2, 3), rows.map { it.position })
        assertEquals(
            listOf(ReviewStatus.Matched, ReviewStatus.Uncertain, ReviewStatus.NotFound, ReviewStatus.Matched),
            rows.map { it.status },
        )
        assertEquals(listOf(true, false, false, true), rows.map { it.checked })
        // The best candidate is chosen for an uncertain row, and only three are offered.
        assertEquals("u1", rows[1].choice?.id)
        assertEquals(3, rows[1].candidates.size)
        assertEquals(ReviewSummary(matched = 2, toCheck = 1, notFound = 1), RestoreReview.summary(rows))
    }

    @Test
    fun should_checkRowAndChooseCandidate_when_userPicksAnother() {
        val picked = RestoreReview.choose(rows, position = 1, index = 2)

        assertTrue(picked[1].checked)
        assertEquals("u3", picked[1].choice?.id)
        assertEquals(listOf("m0", "u3", "m3"), RestoreReview.trackIdsToWrite(picked))
    }

    @Test
    fun should_writeOnlyCheckedInBackupOrder_when_creating() {
        val reviewed = RestoreReview.setChecked(RestoreReview.setChecked(rows, 1, true), 0, false)

        assertEquals(listOf("u1", "m3"), RestoreReview.trackIdsToWrite(reviewed))
    }

    @Test
    fun should_stayUnchecked_when_notFoundRowIsChecked() {
        val reviewed = RestoreReview.setChecked(rows, 2, true)

        assertFalse(reviewed[2].checked)
        assertFalse(reviewed[2].checkable)
        assertEquals(rows, RestoreReview.choose(rows, position = 2, index = 0))
    }

    private fun resolution(position: Int, decision: MatchDecision) =
        TrackResolution(position = position, source = track("s$position"), decision = decision)

    private fun track(rawId: String) =
        MatchTrack(id = rawId, title = rawId, artist = "A", album = null, durationSec = 200, isrc = null)

    @Test
    fun should_checkThePickedSong_when_theUserFindsANotFoundSong() {
        val found = MatchTrack(id = "am:x1", title = "Hikari", artist = "Utada", album = null, durationSec = 300, isrc = null)
        val rows = listOf(ReviewRow(0, ReviewStatus.NotFound, emptyList(), 0, false))

        val picked = RestoreReview.pick(rows, 0, found)

        assertEquals(ReviewStatus.Picked, picked.single().status)
        assertEquals(listOf(found.id), RestoreReview.trackIdsToWrite(picked))
        assertEquals(1, RestoreReview.summary(picked).picked)
        assertEquals(0, RestoreReview.summary(picked).notFound)
    }

    @Test
    fun should_countAddedSkippedAndNotFound_when_recordingARestore() {
        // Android PlaylistBackupService.restoreRecord: 4 rows, 1 not found, 1 of the 3 others left out.
        val reviewed = RestoreReview.setChecked(rows, 3, false)
        val ids = RestoreReview.trackIdsToWrite(reviewed)

        val record = RestoreReview.restoreRecord("subsonic", "demo", reviewed, PlaylistWrite(true, ids.size, ids.size), 9L)
        val failed = RestoreReview.restoreRecord("subsonic", "demo", reviewed, PlaylistWrite(false, 0, ids.size), 9L)

        assertEquals(1, record.added)
        assertEquals(2, record.skipped)
        assertEquals(1, record.notFound)
        assertFalse(record.failed)
        assertEquals(3, failed.skipped)
        assertTrue(failed.failed)
    }
}
