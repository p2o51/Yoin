package com.gpo.yoin.ui.component

import com.gpo.yoin.data.repository.FavoriteState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The heart's two kinds of change (D4): only the service's answer coming in
 * late and flipping it is a quiet flip; everything else animates the same
 * symbol.
 */
class FavoriteGlyphTest {

    @Test
    fun should_keepItsFlips_when_theUserTaps() {
        val tapped = FavoriteGlyph(favorite = false, quietFlips = 2).next(FavoriteState(true, fromUser = true))

        assertEquals(FavoriteGlyph(favorite = true, quietFlips = 2), tapped)
    }

    @Test
    fun should_countAQuietFlip_when_aLateAnswerFlipsIt() {
        val confirmed = FavoriteGlyph(favorite = false).next(answer(true, atMs = 1L))
        val unlikedElsewhere = FavoriteGlyph(favorite = true, quietFlips = 1, answeredAtMs = 1L)
            .next(answer(false, atMs = 2L))

        assertEquals(FavoriteGlyph(favorite = true, quietFlips = 1, answeredAtMs = 1L), confirmed)
        assertEquals(FavoriteGlyph(favorite = false, quietFlips = 2, answeredAtMs = 2L), unlikedElsewhere)
    }

    @Test
    fun should_keepItsFlips_when_aFailedWriteFallsBackToAnAnswerItShowed() {
        val answered = FavoriteGlyph(favorite = false).next(answer(false, atMs = 1L))
        val tapped = answered.next(FavoriteState(true, fromUser = true, answeredAtMs = 1L))

        val rolledBack = tapped.next(answer(false, atMs = 1L))

        assertEquals(FavoriteGlyph(favorite = false, quietFlips = 0, answeredAtMs = 1L), rolledBack)
    }

    @Test
    fun should_keepItsFlips_when_aLibrarySyncFlipsIt() {
        val synced = FavoriteGlyph(favorite = false, quietFlips = 1).next(FavoriteState(true))

        assertEquals(FavoriteGlyph(favorite = true, quietFlips = 1), synced)
    }

    @Test
    fun should_keepItsFlips_when_anotherTracksStateIsAnEarlierAnswer() {
        val nextTrack = FavoriteGlyph(favorite = false, quietFlips = 1).forTrack(answer(true, atMs = 9L))

        assertEquals(FavoriteGlyph(favorite = true, quietFlips = 1, answeredAtMs = 9L), nextTrack)
        // Its answers count from there.
        assertEquals(1, nextTrack.next(answer(true, atMs = 9L)).quietFlips)
        assertEquals(2, nextTrack.next(answer(false, atMs = 10L)).quietFlips)
    }

    @Test
    fun should_stayAsItIs_when_theStateDidNotChange() {
        val glyph = FavoriteGlyph(favorite = true, quietFlips = 3, answeredAtMs = 4L)

        assertSame(glyph, glyph.next(FavoriteState(true)))
        assertSame(glyph, glyph.next(FavoriteState(true, fromUser = true)))
        assertSame(glyph, glyph.next(answer(true, atMs = 4L)))
        assertSame(glyph, glyph.forTrack(answer(true, atMs = 4L)))
    }

    private fun answer(isStarred: Boolean, atMs: Long) =
        FavoriteState(isStarred = isStarred, fromAnswer = true, answeredAtMs = atMs)
}
