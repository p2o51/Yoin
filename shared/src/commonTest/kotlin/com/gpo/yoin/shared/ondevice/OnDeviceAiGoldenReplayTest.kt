package com.gpo.yoin.shared.ondevice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every on-device AI golden vector answered again from the file's own inputs, on every target (the JVM, and the
 * iOS simulator: Kotlin/Native's strings, regexes and Unicode tables must answer as the JVM's did).
 */
class OnDeviceAiGoldenReplayTest {

    @Test
    fun should_answerEveryGoldenVector_when_replayedFromTheFile() {
        val mismatches = OnDeviceAiGolden.replay(ONDEVICE_AI_GOLDEN)
        assertEquals(emptyList(), mismatches, mismatches.joinToString("\n"))
    }

    @Test
    fun should_coverEverySection() {
        OnDeviceAiGolden.sections.forEach { section ->
            assertTrue(ONDEVICE_AI_GOLDEN.contains("\"$section\": ["), section)
        }
    }
}
