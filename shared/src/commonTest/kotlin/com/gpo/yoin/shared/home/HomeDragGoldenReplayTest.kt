package com.gpo.yoin.shared.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every home-drag golden vector answered again from the file's own inputs, on the JVM and the iOS simulator. */
class HomeDragGoldenReplayTest {

    @Test
    fun should_answerEveryGoldenVector_when_replayedFromTheFile() {
        val mismatches = HomeDragGolden.replay(HOME_DRAG_GOLDEN)
        assertEquals(emptyList(), mismatches, mismatches.take(40).joinToString("\n"))
    }

    @Test
    fun should_coverEverySection() {
        HomeDragGolden.sections.forEach { section -> assertTrue(HOME_DRAG_GOLDEN.contains("\"$section\": ["), section) }
    }
}
