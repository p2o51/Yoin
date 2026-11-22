package com.gpo.yoin.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontWeight
import com.gpo.yoin.shared.notes.NoteMarkup
import com.gpo.yoin.shared.notes.NoteMention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NoteMarkupTextTest {

    private val text = "# 现场版\n- [x] 哼唱\n- 一条\n**这一句** 像 [@Andromeda](yoin:track/applemusic:1?a=Weyes%20Blood)"
    private val style = noteMentionStyle(Color.Red)

    @Test
    fun should_drawWordsGlyphsAndNames_when_buildingTheText() {
        val built = buildNoteAnnotatedString(NoteMarkup.parse(text), style)

        assertEquals(
            "现场版\n${NoteMarkupGlyphs.DONE}哼唱\n${NoteMarkupGlyphs.BULLET}一条\n这一句 像 Andromeda",
            built.text,
        )
        assertTrue(built.spanStyles.any { it.item.fontWeight == FontWeight.Bold && built.text.substring(it.start, it.end) == "这一句" })
        assertTrue(built.spanStyles.any { it.item.color == Color.Red && built.text.substring(it.start, it.end) == "Andromeda" })
        assertTrue(built.getLinkAnnotations(0, built.length).isEmpty())
    }

    @Test
    fun should_makeResolvedMentionsLinks_when_aCallbackIsGiven() {
        val tapped = mutableListOf<NoteMention>()
        val built = buildNoteAnnotatedString(NoteMarkup.parse(text), style, onMentionClick = { tapped += it })

        val link = built.getLinkAnnotations(0, built.length).single()
        assertEquals("Andromeda", built.text.substring(link.start, link.end))
        val clickable = link.item as LinkAnnotation.Clickable
        clickable.linkInteractionListener?.onClick(clickable)
        assertEquals(listOf("1"), tapped.map(NoteMention::id))
    }

    @Test
    fun should_drawUnresolvedMentionsAsPlainWords() {
        val built = buildNoteAnnotatedString(
            NoteMarkup.parse(text),
            style,
            isMentionResolved = { false },
            onMentionClick = {},
        )

        assertTrue(built.text.endsWith("像 Andromeda"))
        assertTrue(built.getLinkAnnotations(0, built.length).isEmpty())
        assertTrue(built.spanStyles.none { it.item.color == Color.Red })
    }
}
