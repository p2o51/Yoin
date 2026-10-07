package com.gpo.yoin.ui.detail

import com.gpo.yoin.data.memory.AlbumMemoryTitleSource
import com.gpo.yoin.ui.memories.ResolvedMemoryTitle
import com.gpo.yoin.ui.memories.copy.MemoryProseLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrapTitleMappingTest {

    private fun resolved(
        source: AlbumMemoryTitleSource,
        text: String = "Ferry lights after midnight",
        canRestore: Boolean = false,
        generated: AlbumMemoryTitleSource? = null,
    ) = ResolvedMemoryTitle(
        text = text,
        source = source,
        canRestoreGenerated = canRestore,
        generatedText = generated?.let { "Yoin's own" },
        generatedSource = generated,
        proseLanguage = MemoryProseLanguage.EN,
        albumName = "Night QA Tapes",
    )

    @Test
    fun should_showNoTitleOnPage2_when_onlyTheAlbumNameIsLeft() {
        assertNull(resolved(AlbumMemoryTitleSource.ALBUM, text = "Night QA Tapes").toScrapTitle())
    }

    @Test
    fun should_offerTheResolversRestoreLabel_when_theUserRenamedIt() {
        val title = resolved(AlbumMemoryTitleSource.USER, text = "Night ferry", canRestore = true, generated = AlbumMemoryTitleSource.MOTIF)
            .toScrapTitle()!!

        assertTrue(title.edited)
        assertTrue(title.canRestore)
        assertEquals("Restore Yoin's title", title.restoreLabel)
        assertTrue(title.serif)
    }

    @Test
    fun should_readInTheAppFace_when_itIsYoinsMotif() {
        val title = resolved(AlbumMemoryTitleSource.MOTIF, text = "Since February").toScrapTitle()!!

        assertFalse(title.edited)
        assertFalse(title.serif)
        assertEquals("Since February", title.text)
    }
}
