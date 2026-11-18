package com.gpo.yoin.shared.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The multiplatform replacements for java.* helpers (same results on Android and iOS). */
class LyricTextTest {

    @Test
    fun should_matchJavaUrlEncoder_when_encodingForm() {
        assertEquals("Anti-Hero+Taylor+Swift", urlEncodeForm("Anti-Hero Taylor Swift"))
        assertEquals("a.b-c*d_e", urlEncodeForm("a.b-c*d_e"))
        assertEquals("%E6%99%B4%E5%A4%A9", urlEncodeForm("晴天"))
        assertEquals("%7C%25%2B", urlEncodeForm("|%+"))
        assertEquals("%F0%9F%8E%B5", urlEncodeForm("🎵"))
    }

    @Test
    fun should_roundTrip_when_decodingForm() {
        for (text in listOf("晴天 米津玄師", "アイドル(偶像) YOASOBI | live", "🎵🎵 a+b%c")) {
            assertEquals(text, urlDecodeForm(urlEncodeForm(text)))
        }
        assertEquals("a b", urlDecodeForm("a+b"))
    }

    @Test
    fun should_rejectBrokenEscapes_when_decodingForm() {
        assertFailsWith<IllegalArgumentException> { urlDecodeForm("%E3%8") }
        assertFailsWith<IllegalArgumentException> { urlDecodeForm("%") }
        assertFailsWith<IllegalArgumentException> { urlDecodeForm("%zz") }
    }

    @Test
    fun should_countAndTakeCodePoints_when_surrogatePairsPresent() {
        assertEquals(3, codePointCount("a🎵b"))
        assertEquals("a🎵", takeCodePoints("a🎵b", 2))
        assertEquals("a🎵b", takeCodePoints("a🎵b", 10))
        assertEquals("", takeCodePoints("a🎵b", 0))
    }

    @Test
    fun should_classifyScripts_when_namesMixWritingSystems() {
        assertEquals(Script.LATIN, scriptOf('é'.code))
        assertEquals(Script.HAN, scriptOf('晴'.code))
        assertEquals(Script.HIRAGANA, scriptOf('あ'.code))
        assertEquals(Script.KATAKANA, scriptOf('ア'.code))
        assertEquals(Script.COMMON, scriptOf('ー'.code))
        assertEquals(Script.COMMON, scriptOf('・'.code))
        assertEquals(Script.HANGUL, scriptOf('뉴'.code))
        assertEquals(Script.CYRILLIC, scriptOf('Я'.code))
        assertEquals(Script.HAN, scriptOf(0x20BB7)) // 𠮷
        assertTrue(containsHan("Lemon 米津"))
        assertFalse(containsHan("テイラー・スウィフト"))
    }

    @Test
    fun should_dropDiacritics_when_reducingToBaseLatin() {
        assertEquals("e", baseLatin('é'.code))
        assertEquals("n", baseLatin('ñ'.code))
        assertEquals("a", baseLatin('a'.code))
    }

    @Test
    fun should_normalizeWidth_when_nfkc() {
        assertEquals("APT.", normalizeNfkc("ＡＰＴ．"))
    }

    @Test
    fun should_parseOnlyHttpUrls_when_checkingAddresses() {
        assertNotNull(parseHttpUrl("https://example.com/a.lrc?t=1"))
        assertNull(parseHttpUrl("ftp://example.com/a"))
        assertNull(parseHttpUrl("not a url"))
        assertNull(parseHttpUrl(""))
    }

    @Test
    fun should_keepMostRecentlyUsed_when_lruOverflows() = runTest {
        val lru = LruMemory<String, Int>(2)
        lru.remember("a", 1)
        lru.remember("b", 2)
        assertEquals(1, lru.recall("a")) // a is now the most recent
        lru.remember("c", 3) // evicts b
        assertNull(lru.recall("b"))
        assertEquals(1, lru.recall("a"))
        assertEquals(3, lru.recall("c"))
        lru.forget("a")
        assertNull(lru.recall("a"))
    }
}
