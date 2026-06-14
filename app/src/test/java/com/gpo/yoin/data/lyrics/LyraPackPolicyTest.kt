package com.gpo.yoin.data.lyrics

import com.gpo.yoin.data.model.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyraPackPolicyTest {

    private data class LyraPackRow(
        val productState: String,
        val consumptionMode: String,
        val safeToShowInApp: Boolean,
        val safeToShowAsDraft: Boolean,
        val rawLrc: String? = null,
    )

    private sealed interface LyraPackDecision {
        data class AppSynced(val lineCount: Int) : LyraPackDecision
        data class InternalDraft(val lineCount: Int) : LyraPackDecision
        data object HiddenBlocked : LyraPackDecision
        data object HiddenLowConfidence : LyraPackDecision
        data object MissingPayload : LyraPackDecision
        data object PolicyRejected : LyraPackDecision
        data object UnsyncedPayload : LyraPackDecision
    }

    @Test
    fun usableProductionRowParsesAsAppSyncedLyrics() {
        val decision = consumeLyraPackRow(
            LyraPackRow(
                productState = "usable",
                consumptionMode = "production_synced_lyrics",
                safeToShowInApp = true,
                safeToShowAsDraft = false,
                rawLrc = toySyncedLrc,
            ),
        )

        assertEquals(LyraPackDecision.AppSynced(lineCount = 2), decision)
    }

    @Test
    fun draftRowIsInternalOnlyEvenWhenPayloadParses() {
        val decision = consumeLyraPackRow(
            LyraPackRow(
                productState = "draft",
                consumptionMode = "internal_draft_review",
                safeToShowInApp = false,
                safeToShowAsDraft = true,
                rawLrc = toySyncedLrc,
            ),
        )

        assertEquals(LyraPackDecision.InternalDraft(lineCount = 2), decision)
    }

    @Test
    fun blockedAndLowConfidenceRowsAreRejectedBeforeParsingPayload() {
        val blocked = consumeLyraPackRow(
            LyraPackRow(
                productState = "blocked",
                consumptionMode = "hidden_blocked",
                safeToShowInApp = false,
                safeToShowAsDraft = false,
                rawLrc = toySyncedLrc,
            ),
        )
        val lowConfidence = consumeLyraPackRow(
            LyraPackRow(
                productState = "low_confidence",
                consumptionMode = "hidden_low_confidence",
                safeToShowInApp = false,
                safeToShowAsDraft = false,
                rawLrc = toySyncedLrc,
            ),
        )

        assertEquals(LyraPackDecision.HiddenBlocked, blocked)
        assertEquals(LyraPackDecision.HiddenLowConfidence, lowConfidence)
    }

    @Test
    fun usableRowWithoutPayloadIsRejected() {
        val decision = consumeLyraPackRow(
            LyraPackRow(
                productState = "usable",
                consumptionMode = "production_synced_lyrics",
                safeToShowInApp = true,
                safeToShowAsDraft = false,
                rawLrc = null,
            ),
        )

        assertEquals(LyraPackDecision.MissingPayload, decision)
    }

    private fun consumeLyraPackRow(row: LyraPackRow): LyraPackDecision {
        return when (row.productState) {
            "blocked" -> LyraPackDecision.HiddenBlocked
            "low_confidence" -> LyraPackDecision.HiddenLowConfidence
            "draft" -> consumeDraft(row)
            "usable" -> consumeUsable(row)
            else -> LyraPackDecision.PolicyRejected
        }
    }

    private fun consumeUsable(row: LyraPackRow): LyraPackDecision {
        if (!row.safeToShowInApp || row.safeToShowAsDraft || row.consumptionMode != "production_synced_lyrics") {
            return LyraPackDecision.PolicyRejected
        }
        val rawLrc = row.rawLrc ?: return LyraPackDecision.MissingPayload
        val lyrics = LrcParser.parse(rawLrc)
        return when (lyrics) {
            is Lyrics.Synced -> LyraPackDecision.AppSynced(lyrics.lines.size)
            is Lyrics.Unsynced -> LyraPackDecision.UnsyncedPayload
        }
    }

    private fun consumeDraft(row: LyraPackRow): LyraPackDecision {
        if (row.safeToShowInApp || !row.safeToShowAsDraft || row.consumptionMode != "internal_draft_review") {
            return LyraPackDecision.PolicyRejected
        }
        val rawLrc = row.rawLrc ?: return LyraPackDecision.MissingPayload
        val lyrics = LrcParser.parse(rawLrc)
        assertTrue("Draft payload should remain out of normal app display", !row.safeToShowInApp)
        return when (lyrics) {
            is Lyrics.Synced -> LyraPackDecision.InternalDraft(lyrics.lines.size)
            is Lyrics.Unsynced -> LyraPackDecision.UnsyncedPayload
        }
    }

    private companion object {
        private val toySyncedLrc = """
            [00:00.00]fixture line one
            [00:02.00]fixture line two
        """.trimIndent()
    }
}
