package com.gpo.yoin.ui.component

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The user's top seam style: stable keys, the tide by default. */
class SeamTopStyleTest {
    @After
    fun restore() {
        SeamTopPreference.preview(null)
    }

    @Test
    fun should_readEveryStoredKey_when_known() {
        assertEquals(SeamTopStyle.Tide, SeamTopStyle.fromKey("tide"))
        assertEquals(SeamTopStyle.Dots, SeamTopStyle.fromKey("dots"))
        assertEquals(SeamTopStyle.Cookie, SeamTopStyle.fromKey("cookie"))
    }

    @Test
    fun should_fallBackToTheTide_when_theKeyIsUnknownOrMissing() {
        assertEquals(SeamTopStyle.Tide, SeamTopStyle.Default)
        assertEquals(SeamTopStyle.Tide, SeamTopStyle.fromKey(null))
        assertEquals(SeamTopStyle.Tide, SeamTopStyle.fromKey("soft"))
        assertEquals(SeamTopStyle.Tide, SeamTopStyle.fromKey("Dots"))
    }

    @Test
    fun should_keepTextInThePrint_when_underTheTide() {
        assertFalse(SeamTopStyle.Tide.liftsText)
        assertTrue(SeamTopStyle.Dots.liftsText)
        assertTrue(SeamTopStyle.Cookie.liftsText)
    }

    @Test
    fun should_returnToTheDefault_when_aPreviewIsCleared() {
        SeamTopPreference.preview(SeamTopStyle.Cookie)
        assertEquals(SeamTopStyle.Cookie, SeamTopPreference.style)
        SeamTopPreference.preview(null)
        assertEquals(SeamTopStyle.Default, SeamTopPreference.style)
    }
}
