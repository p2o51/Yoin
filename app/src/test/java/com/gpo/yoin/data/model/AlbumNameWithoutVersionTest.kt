package com.gpo.yoin.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumNameWithoutVersionTest {
    @Test
    fun should_dropTheSuffix_when_nameEndsWithTheVersionInParentheses() {
        assertEquals("再生", albumNameWithoutVersion("再生 (全景声)", "全景声"))
        assertEquals("Abbey Road", albumNameWithoutVersion("Abbey Road (Deluxe)", " Deluxe "))
    }

    @Test
    fun should_keepTheName_when_itDoesNotEndWithTheVersion() {
        assertEquals("再生", albumNameWithoutVersion("再生", "全景声"))
        assertEquals("再生（全景声）", albumNameWithoutVersion("再生（全景声）", "全景声"))
        assertEquals("再生 (全景声) Live", albumNameWithoutVersion("再生 (全景声) Live", "全景声"))
        assertEquals("再生(全景声)", albumNameWithoutVersion("再生(全景声)", "全景声"))
    }

    @Test
    fun should_keepTheName_when_noVersionOrNothingWouldBeLeft() {
        assertEquals("再生 (全景声)", albumNameWithoutVersion("再生 (全景声)", null))
        assertEquals("再生 (全景声)", albumNameWithoutVersion("再生 (全景声)", ""))
        assertEquals(" (Live)", albumNameWithoutVersion(" (Live)", "Live"))
    }
}
