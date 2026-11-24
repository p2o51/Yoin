package com.gpo.yoin.ui.component

import androidx.compose.ui.graphics.Color
import com.gpo.yoin.shared.notes.MentionKind
import com.gpo.yoin.shared.notes.NoteMarkup
import com.gpo.yoin.shared.notes.NoteMention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NoteMentionPressTest {

    private val newYork = NoteMention(MentionKind.Track, "subsonic", "t1", "New York", "St. Vincent", "Masseduction")
    private val text = "像 ${NoteMarkup.serializeMention(newYork)} 那样"
    private val style = noteMentionStyle(Color.Red)

    // ── what a press does ───────────────────────────────────────────────

    private val drawn = DrawnMention(0, newYork, start = 2, end = 12)

    @Test
    fun should_tap_when_releasedBeforeTheLongPress() {
        val tracker = MentionPressTracker()
        assertTrue(tracker.down(drawn))
        assertEquals(MentionPressTracker.Release.Tap, tracker.up())
        assertEquals(MentionPressTracker.State.Idle, tracker.state)
    }

    @Test
    fun should_peekWhileHeld_andOpenNothingOnRelease() {
        val tracker = MentionPressTracker()
        tracker.down(drawn)
        assertSame(drawn, tracker.longPress())
        assertEquals(MentionPressTracker.State.Peeking(drawn), tracker.state)
        // moving while the card is up keeps it
        tracker.movedPastSlop()
        assertEquals(MentionPressTracker.State.Peeking(drawn), tracker.state)
        assertEquals(MentionPressTracker.Release.EndPeek, tracker.up())
    }

    @Test
    fun should_beAScroll_when_theFingerMovesBeforeTheLongPress() {
        val tracker = MentionPressTracker()
        tracker.down(drawn)
        tracker.movedPastSlop()
        assertNull(tracker.longPress())
        assertEquals(MentionPressTracker.Release.Nothing, tracker.up())
    }

    @Test
    fun should_leaveTheTouchToTheHost_when_itIsNotOnAMention() {
        val tracker = MentionPressTracker()
        assertEquals(false, tracker.down(null))
        assertNull(tracker.longPress())
        assertEquals(MentionPressTracker.Release.Nothing, tracker.up())
    }

    @Test
    fun should_findTheMentionUnderAnOffset() {
        val mentions = listOf(drawn, DrawnMention(1, newYork, 20, 25))
        assertNull(drawnMentionAt(mentions, 1))
        assertEquals(0, drawnMentionAt(mentions, 2)?.ordinal)
        assertEquals(0, drawnMentionAt(mentions, 11)?.ordinal)
        assertNull(drawnMentionAt(mentions, 12))
        assertEquals(1, drawnMentionAt(mentions, 24)?.ordinal)
    }

    // ── collapsed: not a link ───────────────────────────────────────────

    @Test
    fun should_notOpen_when_theTextIsNotInteractive() {
        val explicit: (NoteMention) -> Unit = {}
        val window: (NoteMention) -> Unit = {}
        assertNull(noteMentionClick(interactive = false, explicit = explicit, fromController = window))
        assertSame(explicit, noteMentionClick(interactive = true, explicit = explicit, fromController = window))
        assertSame(window, noteMentionClick(interactive = true, explicit = null, fromController = window))
    }

    @Test
    fun should_drawTheMentionWithoutALink_when_collapsed() {
        // the collapsed window builds with no click: the pill and cover are there, no link to take the tap
        val built = buildNoteMarkup(NoteMarkup.parse(text), style, onMentionClick = null)

        assertTrue(built.text.getLinkAnnotations(0, built.text.length).isEmpty())
        assertEquals(1, built.mentions.size)
        assertTrue(built.text.text.contains("New York"))
    }

    // ── how a mention is drawn ──────────────────────────────────────────

    @Test
    fun should_drawTheMentionAsOneUnbreakableSlot_namedByItsTitle() {
        val tapped = mutableListOf<NoteMention>()
        val built = buildNoteMarkup(NoteMarkup.parse(text), style, onMentionClick = { tapped += it })

        val mention = built.mentions.single()
        val inline = built.text.getStringAnnotations(0, built.text.length)
            .filter { it.tag == "androidx.compose.foundation.text.inlineContent" }
        // one slot (cover + name on the pill): the line can only break before or after it, never inside
        assertEquals(listOf(mentionPillId(0)), inline.map { it.item })
        assertEquals(mention.start, inline.single().start)
        assertEquals(mention.end, inline.single().end)
        // its alternate text is the name, so the text (and TalkBack) still reads it
        assertEquals("New York", built.text.text.substring(mention.start, mention.end))
        val link = built.text.getLinkAnnotations(0, built.text.length).single()
        assertEquals(mention.start, link.start)
        assertEquals(mention.end, link.end)
        assertNotNull(drawnMentionAt(built.mentions, mention.start))
    }

    @Test
    fun should_keepAHeadingsMentionAtTheHeadingsSize() {
        val built = buildNoteMarkup(NoteMarkup.parse("# ${NoteMarkup.serializeMention(newYork)}\n林间 ${NoteMarkup.serializeMention(newYork)}"), style)

        assertEquals(listOf(1.15f, 1f), built.mentions.map { it.scale })
    }

    // ── a pill never breaks; a name longer than the line is cut inside it ─────

    @Test
    fun should_sizeThePillToItsCoverAndName_whenItFits() {
        val pill = mentionPillGeometry(fontPx = 40f, nameWidthPx = 200, lineWidthPx = 1000)

        assertEquals(false, pill.truncated)
        // lead + cover + gap + tail (in ems) + the name's own width
        assertEquals((40f * 1.97f + 201f) / 40f, pill.widthEm, 0.01f)
    }

    @Test
    fun should_holdThePillToTheLine_andCutTheName_whenItIsLongerThanALine() {
        val pill = mentionPillGeometry(fontPx = 40f, nameWidthPx = 5_000, lineWidthPx = 600)

        assertEquals(true, pill.truncated)
        assertTrue(pill.widthEm * 40f <= 600f)
    }

    @Test
    fun should_stillShowTheCoverAndALetter_onAVeryNarrowLine() {
        val pill = mentionPillGeometry(fontPx = 40f, nameWidthPx = 300, lineWidthPx = 10)

        assertTrue(pill.widthEm >= 1.97f + 1f - 0.01f)
    }

    @Test
    fun should_drawNoCoverOrPill_forAnUnresolvedMention() {
        val built = buildNoteMarkup(NoteMarkup.parse(text), style, isMentionResolved = { false }, onMentionClick = {})

        assertTrue(built.mentions.isEmpty())
        assertEquals("像 New York 那样", built.text.text)
    }
}
