package com.gpo.yoin.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** How the saved-library search folds text before comparing ([foldForLibrarySearch]). */
class LibrarySearchFoldTest {

    @Test
    fun should_dropTheAccents_when_foldingLatinLetters() {
        assertEquals("thien duong voi nguoi thuong", foldForLibrarySearch("Thiên Đường Với Người Thương"))
        assertEquals("cafe creme", foldForLibrarySearch("Café Crème"))
        assertEquals("bjork", foldForLibrarySearch("Björk"))
        assertEquals("sigur ros", foldForLibrarySearch("Sigur Rós"))
        assertEquals("motorhead", foldForLibrarySearch("Motörhead"))
        assertEquals("oyvind", foldForLibrarySearch("Øyvind"))
        assertEquals("lodz", foldForLibrarySearch("Łódź"))
    }

    @Test
    fun should_keepLettersOfTheirOwn_when_foldingKanaAndHangul() {
        // A voicing mark makes another kana, not an accented one; Hangul
        // syllables stay whole.
        assertEquals("がっこう", foldForLibrarySearch("がっこう"))
        assertEquals("パン", foldForLibrarySearch("パン"))
        assertEquals("한국", foldForLibrarySearch("한국"))
        assertEquals("周杰伦", foldForLibrarySearch("周杰伦"))
    }

    @Test
    fun should_lowerTheCase_when_foldingPlainText() {
        assertEquals("radiohead", foldForLibrarySearch("RadioHead"))
        assertEquals("ss26", foldForLibrarySearch("SS26"))
    }
}
