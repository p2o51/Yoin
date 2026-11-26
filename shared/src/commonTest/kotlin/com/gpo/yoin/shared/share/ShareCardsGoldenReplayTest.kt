package com.gpo.yoin.shared.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every golden vector answered again from the file's own inputs, on every target (the JVM, and the iOS
 * simulator: Kotlin/Native's strings, floats and HCT must answer as the JVM's did).
 */
class ShareCardsGoldenReplayTest {

    @Test
    fun should_answerEveryGoldenVector_when_replayedFromTheFile() {
        val mismatches = ShareCardsGolden.replay(SHARE_CARDS_GOLDEN)
        assertEquals(emptyList(), mismatches, mismatches.joinToString("\n"))
    }

    @Test
    fun should_coverEverySection() {
        ShareCardsGolden.sections.forEach { section ->
            assertTrue(SHARE_CARDS_GOLDEN.contains("\"$section\": ["), section)
        }
    }
}
