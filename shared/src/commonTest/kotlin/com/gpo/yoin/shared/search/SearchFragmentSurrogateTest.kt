package com.gpo.yoin.shared.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A fragment never cuts an emoji (a surrogate pair) in half, at either end, and its highlight stays on
 * whole code points (parity p3: Android before it could start a fragment on a pair's second half).
 */
class SearchFragmentSurrogateTest {

    private fun hasHalfPair(text: String): Boolean = text.indices.any { i ->
        val c = text[i]
        (c.isHighSurrogate() && (i + 1 >= text.length || !text[i + 1].isLowSurrogate())) ||
            (c.isLowSurrogate() && (i == 0 || !text[i - 1].isHighSurrogate()))
    }

    private fun assertWhole(fragment: SearchFragment, maxChars: Int, hitText: String?) {
        assertFalse(hasHalfPair(fragment.text), "half a pair in \"${fragment.text}\" (max $maxChars)")
        assertTrue(fragment.text.removePrefix("…").removeSuffix("…").length <= maxChars, "\"${fragment.text}\" over $maxChars")
        val h = fragment.highlight
        if (!h.isEmpty()) {
            assertTrue(h.first >= 0 && h.last < fragment.text.length)
            val marked = fragment.text.substring(h.first, h.last + 1)
            assertFalse(hasHalfPair(marked), "highlight \"$marked\" splits a pair")
            if (hitText != null) assertTrue(hitText.startsWith(marked), "highlight \"$marked\" isn't the hit \"$hitText\"")
        }
    }

    @Test
    fun should_neverStartOnASecondHalf_when_theCutIsPulledBackFromTheEnd() {
        val text = "😀".repeat(30) + " falls " + "😀".repeat(30)
        val query = SearchQuery("falls")
        val fragment = query.fragment(query.match(text))!!
        // Android before parity p3: "…\uDE00😀…" (the cut a unit into an emoji).
        assertEquals("…" + "😀".repeat(12) + " falls " + "😀".repeat(24) + "…", fragment.text)
        assertEquals("falls", fragment.text.substring(fragment.highlight.first, fragment.highlight.last + 1))
        assertWhole(fragment, FRAGMENT_MAX_CHARS, "falls")
    }

    @Test
    fun should_neverEndOnAFirstHalf_when_aTextWithoutAHitIsCut() {
        // A note met through its song's name shows its own words, unmarked, cut at maxChars.
        val words = "a" + "😀".repeat(50)
        val fragment = SearchQuery("x").fragment(FieldMatch(MatchStrength.Contains, FoldedText.of(words)), maxChars = 20)!!
        assertEquals("a" + "😀".repeat(9) + "…", fragment.text)
        assertTrue(fragment.highlight.isEmpty())
    }

    @Test
    fun should_keepEveryCutWhole_when_theHitMovesThroughEmoji() {
        val fillers = listOf("😀", "a😀", "😀b", "🌧️", "a")
        for (filler in fillers) {
            for (before in 0..40) {
                for (after in listOf(0, 3, 40)) {
                    val text = filler.repeat(before) + " rain " + filler.repeat(after)
                    val query = SearchQuery("rain")
                    val match = query.match(text)
                    for (maxChars in listOf(5, 8, 13, 20, 33, 80)) {
                        val fragment = query.fragment(match, maxChars) ?: continue
                        assertWhole(fragment, maxChars, "rain")
                    }
                    for (maxChars in listOf(5, 8, 13, 20)) {
                        val unmarked = query.fragment(FieldMatch(MatchStrength.Contains, FoldedText.of(text)), maxChars)!!
                        assertWhole(unmarked, maxChars, null)
                    }
                }
            }
        }
    }

    @Test
    fun should_highlightAnEmojiWhole_when_theQueryIsOne() {
        val text = "x".repeat(60) + " 🎧 " + "y".repeat(60)
        val query = SearchQuery("🎧")
        val fragment = query.fragment(query.match(text))!!
        assertEquals("🎧", fragment.text.substring(fragment.highlight.first, fragment.highlight.last + 1))
        assertWhole(fragment, FRAGMENT_MAX_CHARS, "🎧")
    }
}
