package com.gpo.yoin.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The heart's two kinds of change (D4): a tap animates the same symbol; anything else is a quiet flip. */
class FavoriteGlyphTest {

    @Test
    fun should_keepItsFlips_when_theUserTaps() {
        val tapped = FavoriteGlyph(favorite = false, quietFlips = 2).next(true, fromUser = true)

        assertEquals(FavoriteGlyph(favorite = true, quietFlips = 2), tapped)
    }

    @Test
    fun should_countAQuietFlip_when_theChangeWasNotTapped() {
        val confirmed = FavoriteGlyph(favorite = false).next(true, fromUser = false)
        val unlikedElsewhere = FavoriteGlyph(favorite = true, quietFlips = 1).next(false, fromUser = false)

        assertEquals(FavoriteGlyph(favorite = true, quietFlips = 1), confirmed)
        assertEquals(FavoriteGlyph(favorite = false, quietFlips = 2), unlikedElsewhere)
    }

    @Test
    fun should_stayAsItIs_when_theStateDidNotChange() {
        val glyph = FavoriteGlyph(favorite = true, quietFlips = 3)

        assertSame(glyph, glyph.next(true, fromUser = false))
        assertSame(glyph, glyph.next(true, fromUser = true))
    }
}
