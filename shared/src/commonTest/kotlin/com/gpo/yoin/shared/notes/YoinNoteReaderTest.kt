package com.gpo.yoin.shared.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YoinNoteReaderTest {

    private val andromeda = NoteMention(MentionKind.Track, "applemusic", "1445555555", "Andromeda", "Weyes Blood", "Titanic Rising")
    private val link = NoteMarkup.serializeMention(andromeda)

    @Test
    fun should_flattenBlocksAndInlines_when_readingThePrototypesNotes() {
        val lines = YoinNoteReader.read("# 现场版要记得\n- [x] 开头那段哼唱\n- [ ] 第二段\n• 一条\n**这一句**每次 _轻_\n")
        assertEquals(
            listOf(YoinNoteLineKind.Heading, YoinNoteLineKind.Checklist, YoinNoteLineKind.Checklist, YoinNoteLineKind.Bullet,
                YoinNoteLineKind.Paragraph, YoinNoteLineKind.Paragraph),
            lines.map { it.kind },
        )
        assertEquals(1, lines[0].level)
        assertEquals(listOf(true, false), lines.subList(1, 3).map { it.checked })
        assertEquals(
            listOf(YoinNoteRun("这一句", true, false, null), YoinNoteRun("每次 ", false, false, null), YoinNoteRun("轻", false, true, null)),
            lines[4].runs,
        )
        assertTrue(lines[5].runs.isEmpty())
    }

    @Test
    fun should_carryTheMentionAndItsCanonicalLink_when_itSitsInsideBold() {
        val runs = YoinNoteReader.read("**像 $link 一样**").single().runs
        assertEquals(listOf("像 ", "Andromeda", " 一样"), runs.map { it.text })
        assertTrue(runs.all { it.bold })
        val mention = runs[1].mention!!
        assertEquals(MentionKind.Track, mention.kind)
        assertEquals("1445555555", mention.id)
        assertEquals("Weyes Blood", mention.artist)
        assertEquals("yoin:track/applemusic:1445555555?a=Weyes%20Blood&al=Titanic%20Rising", mention.link)
        assertEquals(link, "[@Andromeda](${mention.link})")
    }

    @Test
    fun should_canonicaliseTheLink_when_theMentionWasHandWritten() {
        val mention = YoinNoteReader.mentions("[@X](yoin:song/applemusic:weyes.2?al=A B&a=C)").single()
        assertEquals("yoin:track/applemusic:weyes.2?a=C&al=A%20B", mention.link)
    }

    @Test
    fun should_keepMalformedMarkupAsPlainRuns() {
        listOf("[@Title](yoin:movie/spotify:1)", "**unclosed", "snake_case_name", "#nospace").forEach { text ->
            val runs = YoinNoteReader.read(text).single().runs
            assertEquals(listOf(YoinNoteRun(text, false, false, null)), runs, text)
        }
    }

    @Test
    fun should_projectPlainTextAndSnippets() {
        val text = "# 标题\n- [x] **做完** $link\n_斜_"
        assertEquals("标题\n做完 Andromeda\n斜", YoinNoteReader.plainText(text))
        assertEquals("标题 · 做完 Andromeda · 斜", YoinNoteReader.snippet(text))
        assertTrue(YoinNoteReader.hasMentions(text))
        assertFalse(YoinNoteReader.hasMentions("plain"))
    }

    @Test
    fun should_matchNames_and_refuseInvalidMentionsWithoutThrowing() {
        val mention = YoinNoteReader.mentions(link).single()
        assertTrue(YoinNoteReader.namesMatchTrack(mention, "andromeda", "Weyes Blood", null))
        assertFalse(YoinNoteReader.namesMatchAlbum(mention, "Andromeda", "Weyes Blood"))
        val invalid = mention.copy(provider = "bad provider", id = "")
        assertFalse(YoinNoteReader.namesMatchTrack(invalid, "Andromeda", "Weyes Blood", null))
        assertEquals("Stay", YoinNoteReader.searchPrefix("Stay (feat. Justin Bieber)"))
        assertNull(YoinNoteReader.searchPrefix("  "))
    }
}
