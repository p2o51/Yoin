package com.gpo.yoin.shared.release

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every release-marks golden vector answered again from the file's own inputs, on every target (the JVM, and the
 * iOS simulator: Kotlin/Native's strings and float rounding must answer as the JVM's did).
 */
class ReleaseMarksGoldenReplayTest {

    @Test
    fun should_answerEveryGoldenVector_when_replayedFromTheFile() {
        val mismatches = ReleaseMarksGolden.replay(RELEASE_MARKS_GOLDEN)
        assertEquals(emptyList(), mismatches, mismatches.joinToString("\n"))
    }

    @Test
    fun should_coverEverySection() {
        ReleaseMarksGolden.sections.forEach { section ->
            assertTrue(RELEASE_MARKS_GOLDEN.contains("\"$section\": ["), section)
        }
    }
}
