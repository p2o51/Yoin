package com.gpo.yoin.shared.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every home-layout golden vector answered again from the file's own inputs, on the JVM and the iOS simulator. */
class HomeLayoutGoldenReplayTest {

    @Test
    fun should_answerEveryGoldenVector_when_replayedFromTheFile() {
        val mismatches = HomeLayoutGolden.replay(HOME_LAYOUT_GOLDEN)
        assertEquals(emptyList(), mismatches, mismatches.take(40).joinToString("\n"))
    }

    @Test
    fun should_coverEverySection() {
        HomeLayoutGolden.sections.forEach { section ->
            assertTrue(HOME_LAYOUT_GOLDEN.contains("\"$section\": ["), section)
        }
    }
}
