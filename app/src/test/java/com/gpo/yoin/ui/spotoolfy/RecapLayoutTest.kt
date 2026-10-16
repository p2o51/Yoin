package com.gpo.yoin.ui.spotoolfy

import com.gpo.yoin.data.migration.spotoolfy.RecapEmoji
import com.gpo.yoin.data.migration.spotoolfy.RecapKeyword
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecapLayoutTest {

    @Test
    fun should_placeFirstWordAtCentre_when_areaIsEmpty() {
        val boxes = spiralPlacements(listOf(100f to 40f), 300f, 400f, obstacles = emptyList(), gap = 6f, step = 4f)

        assertEquals(RecapBox(100f, 180f, 100f, 40f), boxes.single())
    }

    @Test
    fun should_neverOverlapWordsOrCards_when_manyWordsArePlaced() {
        val card = RecapBox(0f, 0f, 200f, 80f)
        val sizes = List(30) { i -> (60f + (i % 4) * 20f) to (30f + (i % 3) * 8f) }

        val boxes = spiralPlacements(sizes, width = 320f, height = 420f, obstacles = listOf(card), gap = 6f, step = 4f)

        val placed = boxes.filterNotNull()
        assertTrue(placed.size > 10)
        placed.forEachIndexed { i, box ->
            assertFalse(box.overlaps(card, 6f))
            assertTrue(box.x >= 0f && box.y >= 0f && box.x + box.width <= 320f && box.y + box.height <= 420f)
            placed.drop(i + 1).forEach { other -> assertFalse(box.overlaps(other, 6f)) }
        }
    }

    @Test
    fun should_leaveWordOut_when_itCannotFit() {
        val boxes = spiralPlacements(listOf(500f to 40f), 300f, 400f, obstacles = emptyList(), gap = 6f, step = 4f)

        assertNull(boxes.single())
    }

    @Test
    fun should_placeTheSameWay_when_laidOutAgain() {
        val sizes = List(12) { i -> (50f + i * 3f) to 32f }
        val first = spiralPlacements(sizes, 300f, 300f, emptyList(), 6f, 4f)

        assertEquals(first, spiralPlacements(sizes, 300f, 300f, emptyList(), 6f, 4f))
    }

    @Test
    fun should_sizeWordsByRank_when_buildingTheBurst() {
        val keywords = List(20) { RecapKeyword("w$it", 20 - it, emptyList()) }

        val words = recapWords(keywords, listOf(RecapEmoji("🔥", 9)))

        assertEquals(21, words.size)
        assertEquals("🔥", words[4].text)
        assertEquals(RecapWordTier.Largest, words[0].tier)
        assertEquals(RecapWordTier.Large, words.first { it.text == "w3" }.tier)
        assertEquals(RecapWordTier.Small, words.last().tier)
    }
}
