package com.gpo.yoin.shared.albumabout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Apple Music's notes, the stored credits and rows, the AI's name in copy (moved from Android's app tests). */
class AlbumAboutNotesAndRowsTest {

    @Test
    fun should_dropTagsAndKeepBreaks_when_readingAppleNotes() {
        val html = "<i>Blonde</i> arrived after four years.<br /><br /><br />Its songs &amp; interludes<p>drift.</p>Then &#8220;Nights&#8221; &mdash; the hinge."

        assertEquals(
            "Blonde arrived after four years.\n\nIts songs & interludes\n\ndrift.\n\nThen “Nights” — the hinge.",
            YoinAlbumAbout.plainTextFromHtml(html),
        )
    }

    @Test
    fun should_foldSpaces_andDecodeAmpLast() {
        assertEquals("a b &lt; c", YoinAlbumAbout.plainTextFromHtml("  a&nbsp;  b   &amp;lt; c "))
        assertEquals("🎵", YoinAlbumAbout.plainTextFromHtml("&#x1F3B5;"))
    }

    @Test
    fun should_giveNothing_when_onlyTagsAreLeft() {
        assertNull(YoinAlbumAbout.plainTextFromHtml("<br/> <p></p>"))
    }

    @Test
    fun should_preferTheStandardNotes_thenTheShort() {
        assertEquals("Long.", YoinAlbumAbout.editorialNotes("<b>Long.</b>", "Short."))
        assertEquals("Short & sweet.", YoinAlbumAbout.editorialNotes(" ", "Short &amp; sweet."))
        assertNull(YoinAlbumAbout.editorialNotes(null, null))
    }

    @Test
    fun should_roundTripTheCredits_when_storedAsJson() {
        val credits = AlbumAiCredits(producers = listOf("A"), recordedTime = "1975", source = "Akira")

        assertEquals("""{"producers":["A"],"recordedTime":"1975","source":"Akira"}""", credits.encode())
        assertEquals(credits, YoinAlbumAbout.decodeCredits(YoinAlbumAbout.encodeCredits(credits)))
        assertEquals("{}", AlbumAiCredits.None.encode())
        assertEquals(AlbumAiCredits.None, YoinAlbumAbout.decodeCredits("not json"))
        assertEquals(AlbumAiCredits.None, YoinAlbumAbout.decodeCredits(""))
    }

    private fun row(kind: String, key: String, answer: String, createdAt: Long = 1L, prompt: String? = null) =
        AlbumAboutRow(kind = kind, entryKey = key, promptText = prompt, titleText = null, answerText = answer, createdAt = createdAt)

    @Test
    fun should_readTheRowsAsThePageShowsThem() {
        val content = YoinAlbumAbout.content(
            listOf(
                row(YoinAlbumAbout.KIND_CANONICAL, YoinAlbumAbout.CANON_CREDITS, AlbumAiCredits(producers = listOf("P")).encode()),
                row(YoinAlbumAbout.KIND_CANONICAL, YoinAlbumAbout.CANON_BLURB, " The paragraph. "),
                row(YoinAlbumAbout.KIND_ASK, "old?", "Old answer.", createdAt = 10, prompt = "Old?"),
                row(YoinAlbumAbout.KIND_ASK, "new?", "New answer.", createdAt = 20, prompt = "New?"),
                row(YoinAlbumAbout.KIND_ASK, "removed?", " ", createdAt = 30, prompt = "Removed?"),
            ),
        )

        assertEquals(listOf("P"), content.credits.producers)
        assertEquals("The paragraph.", content.blurb)
        assertEquals(listOf("New?", "Old?"), content.asks.map { it.question })
        assertTrue(content.generated)
    }

    @Test
    fun should_showNoParagraph_when_theAiWroteNothing() {
        val content = YoinAlbumAbout.content(listOf(row(YoinAlbumAbout.KIND_CANONICAL, YoinAlbumAbout.CANON_BLURB, "")))

        assertNull(content.blurb)
        assertTrue(content.generated)
        assertFalse(YoinAlbumAbout.content(emptyList()).generated)
    }

    @Test
    fun should_setAChineseNameFlush_andALatinOneSpaced() {
        assertEquals("问千问", YoinAlbumAbout.aiCopy("问 %1\$s", "千问"))
        assertEquals("问 Gemini", YoinAlbumAbout.aiCopy("问 %1\$s", "Gemini"))
        assertEquals("千问没能回答，再试一次", YoinAlbumAbout.aiCopy("%1\$s 没能回答，再试一次", "千问"))
        assertEquals("Ask Qwen", YoinAlbumAbout.aiCopy("Ask %1\$s", "Qwen"))
    }
}
