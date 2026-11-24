package com.gpo.yoin.shared.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The editor rules through the Swift facade: UTF-16 offsets, flat mentions, no defaults. */
class YoinNoteEditorTest {

    private val newYork = YoinNoteEditor.mention(MentionKind.Track, "subsonic", "t1", "New York", "St. Vincent", "Masseduction")!!
    private val link = "[@New York](yoin:track/subsonic:t1?a=St.%20Vincent&al=Masseduction)"

    @Test
    fun should_buildAndSerializeAMention_withItsCanonicalLink() {
        assertEquals(link, YoinNoteEditor.serializeMention(newYork))
        assertEquals("yoin:track/subsonic:t1?a=St.%20Vincent&al=Masseduction", newYork.link)
        assertNull(YoinNoteEditor.mention(MentionKind.Track, "bad provider", "t1", "x", null, null))
        assertNull(YoinNoteEditor.mention(MentionKind.Album, "subsonic", "", "x", null, null))
        // an artist carries no artist / album; an album no album
        assertEquals(null, YoinNoteEditor.mention(MentionKind.Artist, "subsonic", "a", "A", "B", "C")!!.artist)
        assertEquals(null, YoinNoteEditor.mention(MentionKind.Album, "subsonic", "a", "A", "B", "C")!!.album)
    }

    @Test
    fun should_followAnAt_andIgnoreSpacesInsideTheComposingText() {
        assertEquals(MentionTrigger(3, 6, "Ne"), YoinNoteEditor.trigger("hi @Ne", 6, -1, -1))
        assertNull(YoinNoteEditor.trigger("a@b", 3, -1, -1))
        assertNull(YoinNoteEditor.trigger("@niu yue", 8, -1, -1))
        assertEquals("niu yue", YoinNoteEditor.trigger("@niu yue", 8, 1, 8)?.query)
        // UTF-16: an emoji before the @ counts as two
        assertEquals(MentionTrigger(3, 5, "N"), YoinNoteEditor.trigger("😀 @N", 5, -1, -1))
    }

    @Test
    fun should_pickTheTopRowOnReturn_andInsertTheMentionWithASpace() {
        val trigger = YoinNoteEditor.trigger("hi @Ne", 6, -1, -1)!!
        val pick = YoinNoteEditor.enterPicksMention("hi @Ne", "hi @Ne\n", trigger, newYork)
        assertEquals(NoteEdit("hi $link ", 4 + link.length), pick)
        assertNull(YoinNoteEditor.enterPicksMention("hi @Ne", "hi @Nex", trigger, newYork))
        val inserted = YoinNoteEditor.insertMention("hi @Ne", 6, 6, trigger, newYork)
        assertEquals(pick, inserted)
    }

    @Test
    fun should_keepAMentionWhole_continueLists_andApplyTools() {
        val text = "a $link b"
        // a backspace inside the link removes all of it
        val edited = text.removeRange(10, 11)
        assertEquals(NoteEdit("a  b", 2), YoinNoteEditor.reconcile(text, edited, 10, 10))
        assertEquals(NoteEdit("- one\n- ", 8), YoinNoteEditor.reconcile("- one", "- one\n", 6, 6))
        assertEquals(NoteEdit("**x**", 2, 3), YoinNoteEditor.apply(NoteTool.Bold, "x", 0, 1))
        assertEquals(NoteEdit("- [ ] x", 6, 7), YoinNoteEditor.apply(NoteTool.Checklist, "x", 0, 1))
        assertEquals(listOf(NoteTool.Bold, NoteTool.Checklist), YoinNoteEditor.activeTools("- [ ] **x**", 8, 8))
        assertEquals("- [x] x", YoinNoteEditor.toggleChecklist("- [ ] x", 0))
    }

    @Test
    fun should_layOutLinesAndSpans_inUtf16Offsets() {
        val text = "# 现场\n- [x] $link"
        val lines = YoinNoteEditor.layout(text)
        assertEquals(listOf(YoinNoteLineKind.Heading, YoinNoteLineKind.Checklist), lines.map { it.kind })
        assertEquals(2, lines[0].contentStart)
        assertEquals(11, lines[1].contentStart)
        val spans = YoinNoteEditor.mentionSpans(text)
        assertEquals(1, spans.size)
        assertEquals(link, text.substring(spans[0].start, spans[0].end))
        assertEquals(YoinNoteInlineKind.Mention, lines[1].inlines.single().kind)
        val snippet = YoinNoteReader.snippetWithMentions(text)
        assertNotNull(snippet.mentions.singleOrNull())
        assertEquals("New York", snippet.text.substring(snippet.mentions[0].start, snippet.mentions[0].end))
    }
}
