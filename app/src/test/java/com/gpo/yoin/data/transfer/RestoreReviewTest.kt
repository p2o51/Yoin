package com.gpo.yoin.data.transfer

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertEquals("u1", rows[1].choice?.id?.rawId)
        assertEquals(3, rows[1].candidates.size)
        assertEquals(ReviewSummary(matched = 2, toCheck = 1, notFound = 1), RestoreReview.summary(rows))
    }

    @Test
    fun should_checkRowAndChooseCandidate_when_userPicksAnother() {
        val picked = RestoreReview.choose(rows, position = 1, index = 2)

        assertTrue(picked[1].checked)
        assertEquals("u3", picked[1].choice?.id?.rawId)
        assertEquals(listOf("m0", "u3", "m3"), RestoreReview.trackIdsToWrite(picked).map { it.rawId })
    }

    @Test
    fun should_writeOnlyCheckedInBackupOrder_when_creating() {
        val reviewed = RestoreReview.setChecked(RestoreReview.setChecked(rows, 1, true), 0, false)

        assertEquals(listOf("u1", "m3"), RestoreReview.trackIdsToWrite(reviewed).map { it.rawId })
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

    private fun track(rawId: String) = Track(
        id = MediaId("t", rawId),
        title = rawId,
        artist = "A",
        artistId = null,
        album = null,
        albumId = null,
        coverArt = null,
        durationSec = 200,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
    )
}
