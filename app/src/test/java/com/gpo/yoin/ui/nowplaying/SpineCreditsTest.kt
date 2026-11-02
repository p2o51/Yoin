package com.gpo.yoin.ui.nowplaying

import com.gpo.yoin.data.local.SongAboutEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpineCreditsTest {

    private fun row(kind: String, key: String, answer: String) = SongAboutEntry(
        titleKey = "t", artistKey = "a", albumKey = "al",
        titleDisplay = "T", artistDisplay = "A", albumDisplay = "AL",
        kind = kind, entryKey = key, promptText = null, titleText = null,
        answerText = answer, createdAt = 0L, updatedAt = 0L,
    )

    @Test
    fun should_listCanonicalCreditsInAboutOrder_when_cached() {
        val entries = listOf(
            row(SongAboutEntry.KIND_CANONICAL, SongAboutEntry.CANON_PRODUCER, "P"),
            row(SongAboutEntry.KIND_CANONICAL, SongAboutEntry.CANON_LYRICIST, "L\nL2"),
            row(SongAboutEntry.KIND_CANONICAL, SongAboutEntry.CANON_REVIEW, "a long review"),
            row(SongAboutEntry.KIND_ASK, SongAboutEntry.CANON_COMPOSER, "not a credit"),
        )
        assertEquals("lyricist  L L2     producer  P", spineCredits(entries) { it })
    }

    @Test
    fun should_returnNull_when_noCreditIsCached() {
        val entries = listOf(row(SongAboutEntry.KIND_CANONICAL, SongAboutEntry.CANON_COMPOSER, "  "))
        assertNull(spineCredits(entries) { it })
        assertNull(spineCredits(emptyList()) { it })
    }
}
