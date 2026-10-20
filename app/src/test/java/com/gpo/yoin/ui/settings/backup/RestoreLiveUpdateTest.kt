package com.gpo.yoin.ui.settings.backup

import com.gpo.yoin.data.transfer.ReviewStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class RestoreLiveUpdateTest {

    @Test
    fun should_mergeRunsAndLeaveTheRestPlain_when_partlyMatched() {
        val outcomes = listOf(
            ReviewStatus.Matched,
            ReviewStatus.Matched,
            ReviewStatus.Uncertain,
            ReviewStatus.NotFound,
            ReviewStatus.Matched,
        )

        val segments = RestoreLiveUpdate.liveSegments(outcomes, total = 8)

        assertEquals(
            listOf(
                LiveSegment(2, RestoreLiveUpdate.COLOR_MATCHED),
                LiveSegment(1, RestoreLiveUpdate.COLOR_TO_CHECK),
                LiveSegment(1, RestoreLiveUpdate.COLOR_NOT_FOUND),
                LiveSegment(1, RestoreLiveUpdate.COLOR_MATCHED),
                LiveSegment(3, null),
            ),
            segments,
        )
    }

    @Test
    fun should_beOnePlainSegment_when_nothingResolvedYet() {
        assertEquals(listOf(LiveSegment(6, null)), RestoreLiveUpdate.liveSegments(emptyList(), total = 6))
    }
}
