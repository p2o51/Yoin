package com.gpo.yoin.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlainTextFromHtmlTest {

    @Test
    fun should_dropTagsAndKeepBreaks_when_readingAppleNotes() {
        val html = "<i>Blonde</i> arrived after four years.<br /><br /><br />Its songs &amp; interludes<p>drift.</p>Then &#8220;Nights&#8221; &mdash; the hinge."

        assertEquals(
            "Blonde arrived after four years.\n\nIts songs & interludes\n\ndrift.\n\nThen “Nights” — the hinge.",
            plainTextFromHtml(html),
        )
    }

    @Test
    fun should_foldSpaces_andDecodeAmpLast() {
        assertEquals("a b &lt; c", plainTextFromHtml("  a&nbsp;  b   &amp;lt; c "))
    }

    @Test
    fun should_giveNothing_when_onlyTagsAreLeft() {
        assertNull(plainTextFromHtml("<br/> <p></p>"))
    }
}
