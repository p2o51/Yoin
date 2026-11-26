package com.gpo.yoin.shared.albumabout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every golden vector answered again from the file's own inputs, on every target (the JVM, and the iOS
 * simulator: Kotlin/Native's regex and string functions must answer as the JVM's did).
 */
class AlbumAboutGoldenReplayTest {

    @Test
    fun should_answerEveryGoldenVector_when_replayedFromTheFile() {
        val mismatches = AlbumAboutGolden.replay(ALBUM_ABOUT_GOLDEN)
        assertEquals(emptyList(), mismatches, mismatches.joinToString("\n"))
    }

    @Test
    fun should_coverEverySection() {
        AlbumAboutGolden.sections.forEach { section ->
            assertTrue(ALBUM_ABOUT_GOLDEN.contains("\"$section\": ["), section)
        }
    }
}
