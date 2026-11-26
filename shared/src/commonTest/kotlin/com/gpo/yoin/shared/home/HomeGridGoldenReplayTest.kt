package com.gpo.yoin.shared.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every home-grid golden vector answered again from the file's own inputs, on the JVM and the iOS simulator. */
class HomeGridGoldenReplayTest {

    @Test
    fun should_answerEveryGoldenVector_when_replayedFromTheFile() {
        val mismatches = HomeGridGolden.replay(HOME_GRID_GOLDEN)
        assertEquals(emptyList(), mismatches, mismatches.take(40).joinToString("\n"))
    }

    @Test
    fun should_coverEverySection() {
        HomeGridGolden.sections.forEach { section -> assertTrue(HOME_GRID_GOLDEN.contains("\"$section\": ["), section) }
    }
}
