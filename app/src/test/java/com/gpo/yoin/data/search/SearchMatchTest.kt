package com.gpo.yoin.data.search

import com.gpo.yoin.shared.search.SearchQuery
import com.gpo.yoin.shared.search.MatchStrength
import com.gpo.yoin.shared.search.MatchTier
import com.gpo.yoin.shared.search.SearchScore
import com.gpo.yoin.shared.search.fragment
import com.gpo.yoin.shared.search.FRAGMENT_MAX_CHARS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Folding and matching: case, width and accent-insensitive, strengths, and the fragment shown. */
class SearchMatchTest {

    private fun strength(query: String, text: String) = SearchQuery(query).match(text).strength

    @Test
    fun `strength grades exact, prefix, word prefix and contains`() {
        assertEquals(MatchStrength.Exact, strength("ヨルシカ", "ヨルシカ"))
        assertEquals(MatchStrength.Prefix, strength("ヨル", "ヨルシカ"))
        assertEquals(MatchStrength.WordPrefix, strength("dark", "The Dark Side of the Moon"))
        assertEquals(MatchStrength.Contains, strength("ark", "The Dark Side"))
        assertEquals(MatchStrength.Contains, strength("シカ", "ヨルシカ"))
        assertEquals(MatchStrength.None, strength("swim", "ヨルシカ"))
    }

    @Test
    fun `case, width and spacing fold away`() {
        assertEquals(MatchStrength.Exact, strength("ＲＡＤＷＩＭＰＳ", "radwimps"))
        assertEquals(MatchStrength.Exact, strength("ｶﾞｰﾙ", "ガール"))
        assertEquals(MatchStrength.Exact, strength("  the   band ", "The Band"))
        assertEquals(MatchStrength.Prefix, strength("落日", "落日飛車 Sunset Rollercoaster"))
    }

    @Test
    fun `latin accents fold, kana voicing marks do not`() {
        assertEquals(MatchStrength.Exact, strength("bjork", "Björk"))
        assertEquals(MatchStrength.Exact, strength("BJÖRK", "björk"))
        assertEquals(MatchStrength.None, strength("か", "が"))
        assertEquals(MatchStrength.Exact, strength("が", "が"))
        assertEquals(MatchStrength.None, strength("가", "각"))
    }

    @Test
    fun `blank queries and fields match nothing`() {
        assertEquals(MatchStrength.None, strength("   ", "anything"))
        assertEquals(MatchStrength.None, SearchQuery("a").match(null).strength)
        assertTrue(SearchQuery(" ").isBlank)
    }

    @Test
    fun `a score ranks tier before strength, and any hit over none`() {
        val titleContains = SearchScore(MatchTier.Primary, MatchStrength.Contains)
        val bodyExact = SearchScore(MatchTier.Body, MatchStrength.Exact)
        assertTrue(titleContains > bodyExact)
        assertTrue(bodyExact > SearchScore(MatchTier.Incidental, MatchStrength.Exact))
        assertTrue(SearchScore(MatchTier.Incidental, MatchStrength.Contains) > SearchScore(MatchTier.Primary, MatchStrength.None))
        assertTrue(SearchScore(MatchTier.Primary, MatchStrength.Prefix).isStrongName)
        assertTrue(!SearchScore(MatchTier.Secondary, MatchStrength.Exact).isStrongName)
    }

    @Test
    fun `a hit maps back to the original text, through width folding`() {
        val query = SearchQuery("radio")
        val match = query.match("Ｍｙ ＲＡＤＩＯ song")
        assertEquals("ＲＡＤＩＯ", match.source!!.original.substring(match.originalRange.first, match.originalRange.last + 1))
    }

    @Test
    fun `a short text is its own fragment, the hit marked`() {
        val query = SearchQuery("夏")
        val fragment = query.fragment(query.match("あの夏の\n帰り道"))!!
        assertEquals("あの夏の 帰り道", fragment.text)
        assertEquals(2..2, fragment.highlight)
    }

    @Test
    fun `a long text is cut around the hit, with ellipses`() {
        val text = "a".repeat(100) + " Peter Pan " + "b".repeat(100)
        val query = SearchQuery("peter pan")
        val fragment = query.fragment(query.match(text))!!
        assertTrue(fragment.text.startsWith("…"))
        assertTrue(fragment.text.endsWith("…"))
        assertTrue(fragment.text.length <= FRAGMENT_MAX_CHARS + 2)
        assertEquals("Peter Pan", fragment.text.substring(fragment.highlight.first, fragment.highlight.last + 1))
    }
}
