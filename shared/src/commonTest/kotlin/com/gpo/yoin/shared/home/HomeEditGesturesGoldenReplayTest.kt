package com.gpo.yoin.shared.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every home-edit-gestures golden vector answered again from the file's own inputs, on the JVM and the iOS simulator. */
class HomeEditGesturesGoldenReplayTest {

    @Test
    fun should_answerEveryGoldenVector_when_replayedFromTheFile() {
        val mismatches = HomeEditGesturesGolden.replay(HOME_EDIT_GESTURES_GOLDEN)
        assertEquals(emptyList(), mismatches, mismatches.take(40).joinToString("\n"))
    }

    @Test
    fun should_coverEverySection() {
        HomeEditGesturesGolden.sections.forEach { section -> assertTrue(HOME_EDIT_GESTURES_GOLDEN.contains("\"$section\": ["), section) }
    }
}
