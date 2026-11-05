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
}
