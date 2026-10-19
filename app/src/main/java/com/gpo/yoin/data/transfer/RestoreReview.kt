package com.gpo.yoin.data.transfer

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track

/**
 * The review a restore shows before it writes anything
 * (docs/playlist-transfer.md §7): one row per backed-up track, with what the
 * target offers for it. Pure, so the rules are unit-tested on their own.
 *
 * - [ReviewStatus.Matched]: checked by default, its match chosen.
 * - [ReviewStatus.Uncertain]: up to [MAX_CANDIDATES] candidates, the best
 *   chosen but unchecked; the user checks it or picks another.
 * - [ReviewStatus.NotFound]: nothing to check — until the user searches the
 *   target for it and picks a song: then [ReviewStatus.Picked], checked.
 */
data class ReviewRow(
    /** The track's place in the backup. */
    val position: Int,
    val status: ReviewStatus,
    /** What the target offers, best first; empty for [ReviewStatus.NotFound]. */
    val candidates: List<Track>,
    /** Index into [candidates] of the one that would be written. */
    val chosen: Int,
    val checked: Boolean,
) {
    val choice: Track? get() = candidates.getOrNull(chosen)
    val checkable: Boolean get() = candidates.isNotEmpty()
}

enum class ReviewStatus { Matched, Uncertain, NotFound, Picked }

data class ReviewSummary(val matched: Int, val toCheck: Int, val notFound: Int, val picked: Int = 0)

object RestoreReview {
    const val MAX_CANDIDATES = 3

    fun rows(resolutions: List<TrackResolution>): List<ReviewRow> = resolutions
        .sortedBy { it.position }
        .map { resolution ->
            when (val decision = resolution.decision) {
                is MatchDecision.Matched -> ReviewRow(
                    position = resolution.position,
                    status = ReviewStatus.Matched,
                    candidates = listOf(decision.track),
                    chosen = 0,
                    checked = true,
                )
                is MatchDecision.Ambiguous -> ReviewRow(
                    position = resolution.position,
                    status = if (decision.candidates.isEmpty()) ReviewStatus.NotFound else ReviewStatus.Uncertain,
                    candidates = decision.candidates.take(MAX_CANDIDATES),
                    chosen = 0,
                    checked = false,
                )
                MatchDecision.NotFound -> ReviewRow(
                    position = resolution.position,
                    status = ReviewStatus.NotFound,
                    candidates = emptyList(),
                    chosen = 0,
                    checked = false,
                )
            }
        }

    /** [rows] with the row at [position] checked or unchecked; a row with no candidate stays unchecked. */
    fun setChecked(rows: List<ReviewRow>, position: Int, checked: Boolean): List<ReviewRow> = rows.map { row ->
        if (row.position == position && row.checkable) row.copy(checked = checked) else row
    }

    /** [rows] with candidate [index] chosen for the row at [position], and the row checked: picking is consent. */
    fun choose(rows: List<ReviewRow>, position: Int, index: Int): List<ReviewRow> = rows.map { row ->
        val picked = row.position == position && index in row.candidates.indices
        if (picked) row.copy(chosen = index, checked = true) else row
    }

    /**
     * [rows] with [track] — found by the user searching the target — as the
     * row at [position]'s song, checked. Any row can take one; it becomes
     * [ReviewStatus.Picked].
     */
    fun pick(rows: List<ReviewRow>, position: Int, track: Track): List<ReviewRow> = rows.map { row ->
        if (row.position == position) {
            row.copy(status = ReviewStatus.Picked, candidates = listOf(track), chosen = 0, checked = true)
        } else {
            row
        }
    }

    /** The ids to write, in the backup's order: checked rows' chosen candidates. */
    fun trackIdsToWrite(rows: List<ReviewRow>): List<MediaId> =
        rows.sortedBy { it.position }.filter { it.checked }.mapNotNull { it.choice?.id }

    fun summary(rows: List<ReviewRow>): ReviewSummary = ReviewSummary(
        matched = rows.count { it.status == ReviewStatus.Matched },
        toCheck = rows.count { it.status == ReviewStatus.Uncertain },
        notFound = rows.count { it.status == ReviewStatus.NotFound },
        picked = rows.count { it.status == ReviewStatus.Picked },
    )
}
