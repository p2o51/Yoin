package com.gpo.yoin.ui.component

import androidx.compose.ui.layout.AlignmentLine
import org.junit.Assert.assertEquals
import org.junit.Test

class MetaLineTest {

    @Test
    fun should_keepEveryGroup_when_theLineIsWideEnough() {
        assertEquals(3, metaLineVisibleCount(listOf(10, 10, 10), gapPx = 5, maxWidth = 40))
    }

    @Test
    fun should_dropFromTheEnd_when_theNextGroupDoesNotFit() {
        // 40 + 12 + 40 = 92, the third group would make 144.
        assertEquals(2, metaLineVisibleCount(listOf(40, 40, 40), gapPx = 12, maxWidth = 100))
        assertEquals(1, metaLineVisibleCount(listOf(10, 10, 100), gapPx = 12, maxWidth = 30))
    }

    @Test
    fun should_dropTheOnlyGroup_when_itIsWiderThanTheLine() {
        assertEquals(0, metaLineVisibleCount(listOf(80), gapPx = 12, maxWidth = 50))
    }

    @Test
    fun should_showNothing_when_thereIsNoWidthOrNoGroups() {
        assertEquals(0, metaLineVisibleCount(emptyList(), gapPx = 12, maxWidth = 100))
        assertEquals(0, metaLineVisibleCount(listOf(10), gapPx = 12, maxWidth = 0))
    }

    @Test
    fun should_putEveryGroupOnOneBaseline_when_fontsPlaceTheirBaselinesDifferently() {
        // "2024" (Google Sans Flex) has its baseline at 15 in a 20px box, "专辑" (CJK fallback) at 17.
        val tops = metaLineTops(heights = listOf(20, 20, 21), baselines = listOf(15, 17, 16))

        assertEquals(listOf(2, 0, 1), tops)
        assertEquals(setOf(17), tops.indices.map { tops[it] + listOf(15, 17, 16)[it] }.toSet())
    }

    @Test
    fun should_centreTheGroups_when_oneHasNoBaseline() {
        assertEquals(listOf(2, 0), metaLineTops(listOf(16, 20), listOf(12, AlignmentLine.Unspecified)))
        assertEquals(emptyList<Int>(), metaLineTops(emptyList(), emptyList()))
    }

    @Test
    fun should_keepNaturalWidths_when_sharedGroupsFit() {
        assertEquals(listOf(40, 30), share(listOf(40, 30), maxWidth = 100))
    }

    @Test
    fun should_splitThreeToTwo_when_artistAndAlbumAreBothLong() {
        // 212 - 12 gap = 200 to share: 120 (60 %) for the artist, 80 for the album.
        assertEquals(listOf(120, 80), share(listOf(500, 500), maxWidth = 212))
    }

    @Test
    fun should_giveTheArtistTheRest_when_theAlbumIsShort() {
        assertEquals(listOf(160, 40), share(listOf(500, 40), maxWidth = 212))
    }

    @Test
    fun should_giveTheAlbumTheRest_when_theArtistIsShort() {
        assertEquals(listOf(30, 170), share(listOf(30, 500), maxWidth = 212))
    }

    @Test
    fun should_keepCountsWhole_when_sharingWithText() {
        // A count (fixed) keeps its 50; the text takes the other 138.
        assertEquals(listOf(138, 50), share(listOf(400, 50), maxWidth = 200, fixed = listOf(false, true)))
    }

    @Test
    fun should_dropTheAlbum_when_itsShareIsUnderTheMinimum() {
        // 60 - 12 = 48 to share: the album's 2/5 would be 19, under 30, so it drops and the artist takes the line.
        assertEquals(listOf(60, 0), share(listOf(500, 500), maxWidth = 60, minShare = 30))
    }

    @Test
    fun should_neverDropTheFirstGroup_when_theLineIsNarrow() {
        assertEquals(listOf(10), share(listOf(500), maxWidth = 10))
        assertEquals(listOf(0, 0), share(listOf(500, 500), maxWidth = 0))
    }

    private fun share(
        natural: List<Int>,
        maxWidth: Int,
        fixed: List<Boolean> = natural.map { false },
        minShare: Int = 0,
    ) = metaLineShareWidths(natural, fixed, gapPx = 12, maxWidth = maxWidth, minSharePx = minShare)
}
