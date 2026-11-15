package com.gpo.yoin.shared.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

/** Ported from Android's YoinRepositoryLyricsTranslationTest (the `buildProviderTranslations` cases). */
class LyricTranslationsTest {

    @Test
    fun should_pairDuplicateTimestampsByPosition_when_groupSizesMatch() {
        val lyrics = LrcParser.parse("[00:00.00]Title - Artist\n[00:00.00]First line\n[00:04.00]Second")

        val translations = LyricTranslations.align(lyrics, "[00:00.00]//\n[00:00.00]第一行\n[00:04.00]第二")

        assertEquals(mapOf(1 to "第一行", 2 to "第二"), translations)
    }

    @Test
    fun should_shareGroupTranslation_when_groupSizesDiffer() {
        // QQ drops its "//" lines, so its groups can be smaller.
        val lyrics = LrcParser.parse("[00:00.00]Title - Artist\n[00:00.00]First line\n[00:04.00]Second")

        val translations = LyricTranslations.align(lyrics, "[00:00.00]第一行\n[00:04.00]第二")

        assertEquals(mapOf(0 to "第一行", 1 to "第一行", 2 to "第二"), translations)
    }

    @Test
    fun should_zipByIndex_when_noTimestampMatches() {
        val lyrics = LrcParser.parse("[00:01.00]One\n[00:02.00]Two")

        val translations = LyricTranslations.align(lyrics, "[00:01.50]一\n[00:02.50]//")

        assertEquals(mapOf(0 to "一"), translations)
    }

    @Test
    fun should_zipTrimmedNonEmptyLines_when_lyricsAreUnsynced() {
        val lyrics = LrcParser.parse("One\n\n  Two  ")

        val translations = LyricTranslations.align(lyrics, "一\n二")

        assertEquals(mapOf(0 to "一", 1 to "二"), translations)
    }

    @Test
    fun should_stripTagAndLineMarkers_when_cleaningLineTranslation() {
        assertEquals("你好", LyricTranslations.cleanLineTranslation("[L1] 你好 [/L1]"))
        assertEquals("你好", LyricTranslations.cleanLineTranslation("1. 你好"))
        assertEquals("你好", LyricTranslations.cleanLineTranslation("- (2) 你好"))
        assertEquals("你好 1.5", LyricTranslations.cleanLineTranslation("你好 1.5"))
    }

    @Test
    fun should_notPairByPosition_when_creditsCameOffOneSideOnly() {
        // No timestamp in common: the fallback would pair by position, and the original lost its credits.
        val lyrics = LrcParser.parse("[00:00.00]作词：A\n[00:00.50]作曲：B\n[00:05.00]One\n[00:06.00]Two")
        assertEquals(listOf("One", "Two"), lyrics.lines.map { it.text })

        val translations = LyricTranslations.align(lyrics, "[00:00.10]//\n[00:00.60]//\n[00:05.10]一\n[00:06.10]二")

        assertEquals(emptyMap<Int, String>(), translations)
    }

    @Test
    fun should_stillPairByPosition_when_bothSidesLostTheSameCredits() {
        val lyrics = LrcParser.parse("作词：A\n作曲：B\nOne\nTwo")
        val translations = LyricTranslations.align(lyrics, "作词：甲\n作曲：乙\n一\n二")

        assertEquals(mapOf(0 to "一", 1 to "二"), translations)
    }
}
