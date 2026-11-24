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
    fun should_frameTheNameWithItsCoverAndRoom_inOnePill() {
        val tapped = mutableListOf<NoteMention>()
        val built = buildNoteMarkup(NoteMarkup.parse(text), style, onMentionClick = { tapped += it })

        val mention = built.mentions.single()
        val inline = built.text.getStringAnnotations(0, built.text.length)
            .filter { it.tag == "androidx.compose.foundation.text.inlineContent" }
            .map { it.item }
        assertEquals(listOf(mentionThumbId(0), mentionTailId(0)), inline)
        // the link (and the pill) cover thumbnail + name + room, so a tap on the cover opens it too
        val link = built.text.getLinkAnnotations(0, built.text.length).single()
        assertEquals(mention.start, link.start)
        assertEquals(mention.end, link.end)
        assertTrue(built.text.text.substring(mention.start, mention.end).contains("New York"))
        assertNotNull(drawnMentionAt(built.mentions, mention.start))
    }

    @Test
    fun should_drawNoCoverOrPill_forAnUnresolvedMention() {
        val built = buildNoteMarkup(NoteMarkup.parse(text), style, isMentionResolved = { false }, onMentionClick = {})

        assertTrue(built.mentions.isEmpty())
        assertEquals("像 New York 那样", built.text.text)
    }
}
