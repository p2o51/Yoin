package com.gpo.yoin.ui.notes.editor

import com.gpo.yoin.shared.notes.MentionKind
import com.gpo.yoin.shared.notes.NoteMarkup
import com.gpo.yoin.shared.notes.NoteMention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteEditorTextTest {

    private val andromeda = NoteMention(MentionKind.Track, "subsonic", "t2", "Andromeda", "Weyes Blood", "Titanic Rising")
    private val link = NoteMarkup.serializeMention(andromeda)

    // ── @ trigger ───────────────────────────────────────────────────────

    @Test
    fun should_openTheTrigger_when_anAtStartsAWord() {
        assertEquals(MentionTrigger(0, 1, ""), NoteEditorText.trigger("@", 1))
        assertEquals(MentionTrigger(6, 10, "coc"), NoteEditorText.trigger("和那句 x @coc", 10))
        assertEquals("他夏", NoteEditorText.trigger("副歌前那口气，和@他夏", 11)?.query)
        assertEquals("cla", NoteEditorText.trigger("line\n＠cla", 9)?.query)
    }

    @Test
    fun should_stayShut_when_theAtIsAnAddressASpaceFollowsOrItIsInsideAMention() {
        assertNull(NoteEditorText.trigger("mail me at a@b", 14))
        assertNull(NoteEditorText.trigger("@two words", 10))
        assertNull(NoteEditorText.trigger("plain", 5))
        val text = "x $link"
        // a caret just inside the link's own "[@" never opens it again
        assertNull(NoteEditorText.trigger(text, 4))
        assertNull(NoteEditorText.trigger("@" + "a".repeat(NoteEditorText.MAX_QUERY + 1), NoteEditorText.MAX_QUERY + 2))
    }

    @Test
    fun should_replaceTheTriggerWithTheLinkAndASpace_when_aMentionIsPicked() {
        val text = "像 @andr 的"
        val trigger = NoteEditorText.trigger(text, 7)!!
        val edit = NoteEditorText.insertMention(text, 7, 7, trigger, andromeda)
        assertEquals("像 $link 的", edit.text)
        assertEquals(2 + link.length + 1, edit.selectionStart)
        // from the search sheet with no trigger: at the caret
        assertEquals("$link x", NoteEditorText.insertMention("x", 0, 0, null, andromeda).text)
    }

    // ── atomic chips ────────────────────────────────────────────────────

    @Test
    fun should_deleteTheWholeMention_when_backspaceTakesItsLastCharacter() {
        val original = "a $link b"
        val edited = original.removeRange(2 + link.length - 1, 2 + link.length)
        val fix = NoteEditorText.reconcile(original, edited, 2 + link.length - 1, 2 + link.length - 1)!!
        assertEquals("a  b", fix.text)
        assertEquals(2, fix.selectionStart)
    }

    @Test
    fun should_deleteTheWholeMention_when_aSelectionCutsIntoIt() {
        val original = "ab $link cd"
        val edited = "ab" + original.substring(3 + 5)
        val fix = NoteEditorText.reconcile(original, edited, 2, 2)!!
        assertEquals("ab cd", fix.text)
    }

    @Test
    fun should_moveTypingOutOfAMention_when_insertedInsideIt() {
        val original = "$link."
        val edited = original.substring(0, 4) + "zz" + original.substring(4)
        val fix = NoteEditorText.reconcile(original, edited, 6, 6)!!
        assertEquals("${link}zz.", fix.text)
        assertEquals(link.length + 2, fix.selectionStart)
    }

    @Test
    fun should_leaveEditsAlone_when_theyDontTouchAMention() {
        assertNull(NoteEditorText.reconcile("hello $link", "hello! $link", 6, 6))
        assertNull(NoteEditorText.reconcile("$link x", "$link xy", link.length + 3, link.length + 3))
        // the whole chip deleted at once (the field's own mapping): stands as typed
        assertNull(NoteEditorText.reconcile("a $link b", "a  b", 2, 2))
    }

    // ── lists and shortcuts ─────────────────────────────────────────────

    @Test
    fun should_continueTheList_when_enterEndsAnItem() {
        val fix = NoteEditorText.reconcile("- one", "- one\n", 6, 6)!!
        assertEquals(NoteEdit("- one\n- ", 8), fix)
        val todo = NoteEditorText.reconcile("- [x] done", "- [x] done\n", 11, 11)!!
        assertEquals(NoteEdit("- [x] done\n- [ ] ", 17), todo)
        assertNull(NoteEditorText.reconcile("# Title", "# Title\n", 8, 8))
    }

    @Test
    fun should_leaveTheList_when_enterLandsOnAnEmptyItem() {
        assertEquals(NoteEdit("- one\n", 6), NoteEditorText.reconcile("- one\n- ", "- one\n- \n", 9, 9))
        assertEquals(NoteEdit("", 0), NoteEditorText.reconcile("- [ ] ", "- [ ] \n", 7, 7))
    }

    @Test
    fun should_makeTodosAndHeadings_when_aShortcutIsTypedAtALineStart() {
        assertEquals(NoteEdit("- [ ] ", 6), NoteEditorText.reconcile("[]", "[] ", 3, 3))
        assertEquals(NoteEdit("x\n- [ ] ", 8), NoteEditorText.reconcile("x\n【】", "x\n【】 ", 5, 5))
        assertEquals(NoteEdit("- [x] ", 6), NoteEditorText.reconcile("[x]", "[x] ", 4, 4))
        assertEquals(NoteEdit("# ", 2), NoteEditorText.reconcile("＃", "＃ ", 2, 2))
        // mid-line it's just a space
        assertNull(NoteEditorText.reconcile("a []", "a [] ", 5, 5))
    }

    // ── the formatting row ──────────────────────────────────────────────

    @Test
    fun should_wrapAndUnwrap_when_boldOrItalicIsTapped() {
        val bold = NoteEditorText.apply(NoteTool.Bold, "the hook", 4, 8)
        assertEquals(NoteEdit("the **hook**", 6, 10), bold)
        assertEquals(NoteEdit("the hook", 4, 8), NoteEditorText.apply(NoteTool.Bold, bold.text, 6, 10))
        assertEquals(NoteEdit("副歌_轻_", 3, 4), NoteEditorText.apply(NoteTool.Italic, "副歌轻", 2, 3))
        // inside an ASCII word `_` wouldn't read as italic: `*`
        assertEquals("ab*c*", NoteEditorText.apply(NoteTool.Italic, "abc", 2, 3).text)
        // an empty selection: the markers with the caret between them
        assertEquals(NoteEdit("****", 2), NoteEditorText.apply(NoteTool.Bold, "", 0, 0))
    }

    @Test
    fun should_setAndClearBlocks_overEverySelectedLine() {
        val listed = NoteEditorText.apply(NoteTool.Bullet, "one\ntwo", 0, 7)
        assertEquals("- one\n- two", listed.text)
        assertEquals("one\ntwo", NoteEditorText.apply(NoteTool.Bullet, listed.text, 3, 9).text)
        assertEquals("- [ ] one", NoteEditorText.apply(NoteTool.Checklist, "- one", 3, 3).text)
        assertEquals("# one", NoteEditorText.apply(NoteTool.Heading, "one", 1, 1).text)
        assertEquals(NoteTool.Heading, NoteEditorText.blockAt("# one", 3))
    }

    @Test
    fun should_insertAnAt_withASpaceAfterAnAsciiWord() {
        assertEquals(NoteEdit("hi @", 4), NoteEditorText.apply(NoteTool.Mention, "hi", 2, 2))
        assertEquals(NoteEdit("副歌@", 3), NoteEditorText.apply(NoteTool.Mention, "副歌", 2, 2))
    }

    @Test
    fun should_reportBoldAndItalic_whereTheCaretSits() {
        assertTrue(NoteTool.Bold in NoteEditorText.inlineAt("a **bold** b", 5, 5))
        assertTrue(NoteEditorText.inlineAt("a **bold** b", 0, 0).isEmpty())
    }

    // ── what the field shows ────────────────────────────────────────────

    @Test
    fun should_showAMentionAsOneChip_and_listMarkersAsGlyphs() {
        val text = "- [ ] 听 $link\n- 一条"
        val display = NoteEditorDisplay.of(text)
        val shown = display.replacements.map { it.shown }
        assertEquals(listOf(NoteEditorDisplay.TODO, chipText("Andromeda"), NoteEditorDisplay.BULLET), shown)
        // the caret after the chip's link sits after the chip's words
        val afterLink = text.indexOf(link) + link.length
        assertEquals(NoteEditorDisplay.TODO.length + 2 + chipText("Andromeda").length, display.toDisplay(afterLink))
        // inside the link: snapped to the chip's end
        assertEquals(display.toDisplay(afterLink), display.toDisplay(afterLink - 3))
        val chip = display.styles.single { it.kind == NoteEditorDisplay.Kind.Chip }
        assertEquals(andromeda, chip.mention)
        assertEquals(chipText("Andromeda").length, chip.end - chip.start)
    }

    // ── @ mode: composing text and Enter (notes 0.6.4) ───────────────────

    @Test
    fun should_keepTheTriggerOpen_acrossSpacesTheKeyboardIsStillComposing() {
        // a pinyin keyboard shows "niu yue" in the field (composing) before it commits 纽约
        val text = "像 @niu yue"
        assertEquals("niu yue", NoteEditorText.trigger(text, text.length, composition = 3 until text.length)?.query)
        // committed, the same space ends the mention
        assertNull(NoteEditorText.trigger(text, text.length))
        // a composition elsewhere doesn't excuse a committed space before it
        assertNull(NoteEditorText.trigger("@a b", 4, composition = 3 until 4))
    }

    @Test
    fun should_turnTheNewlineIntoTheTopRow_when_enterIsPressedAtAnOpenPopup() {
        val original = "像 @andr 的"
        val trigger = NoteEditorText.trigger(original, 7)!!
        val edited = "像 @andr\n 的"

        val fix = NoteEditorText.enterPicksMention(original, edited, trigger, andromeda)!!

        assertEquals("像 $link 的", fix.text)
        assertEquals("像 $link ".length, fix.selectionStart)
    }

    @Test
    fun should_leaveOtherEdits_when_thePopupIsOpen() {
        val original = "@an"
        val trigger = NoteEditorText.trigger(original, 3)!!

        // typing goes on as typed; a newline anywhere in the token is Enter (see the delivery shapes below)
        assertNull(NoteEditorText.enterPicksMention(original, "@and", trigger, andromeda))
        assertNull(NoteEditorText.enterPicksMention(original, "@a", trigger, andromeda))
    }

    @Test
    fun should_pickOnEnter_whateverShapeTheKeyboardDeliversItIn() {
        val original = "x @Ni y"
        val trigger = NoteEditorText.trigger(original, 5)!!
        val picked = "x $link y"

        // a bare newline at the token's end
        assertEquals(picked, NoteEditorText.enterPicksMention(original, "x @Ni\n y", trigger, andromeda)?.text)
        // the composing word committed (auto-corrected / completed) with the newline in one batch
        assertEquals(picked, NoteEditorText.enterPicksMention(original, "x @Nice\n y", trigger, andromeda)?.text)
        // the composing word replaced by the newline
        assertEquals(picked, NoteEditorText.enterPicksMention(original, "x @\n y", trigger, andromeda)?.text)
        // a newline and a space (some keyboards add one) after the word
        assertEquals(picked, NoteEditorText.enterPicksMention(original, "x @Ni \n y", trigger, andromeda)?.text)
    }

    @Test
    fun should_pickOnEnter_beforeANewlineAlreadyAfterTheToken() {
        val original = "@an\nnext line"
        val trigger = NoteEditorText.trigger(original, 3)!!

        val fix = NoteEditorText.enterPicksMention(original, "@an\n\nnext line", trigger, andromeda)!!

        assertEquals("$link \nnext line", fix.text)
    }

    @Test
    fun should_leaveNewlinesOutsideTheToken_asTyped() {
        val original = "a\n@an"
        val trigger = NoteEditorText.trigger(original, 5)!!

        // before the @ (another line), or touching text past the token: not Enter at the popup
        assertNull(NoteEditorText.enterPicksMention(original, "\na\n@an", trigger, andromeda))
        assertNull(NoteEditorText.enterPicksMention("@an b", "@an\n", NoteEditorText.trigger("@an b", 3)!!, andromeda))
    }

    @Test
    fun should_openTheTrigger_onAFullWidthAt_followedByCommittedChinese() {
        // Gboard's Chinese keyboard types "＠" and commits characters from its own candidate strip
        assertEquals(MentionTrigger(0, 3, "灯塔"), NoteEditorText.trigger("＠灯塔", 3))
        assertEquals("灯塔", NoteEditorText.trigger("这首像＠灯塔", 6)?.query)
        assertEquals("", NoteEditorText.trigger("这首像＠", 4)?.query)
        val fix = NoteEditorText.insertMention("这首像＠灯塔", 6, 6, NoteEditorText.trigger("这首像＠灯塔", 6), andromeda)
        assertEquals("这首像$link ", fix.text)
    }

    @Test
    fun should_holdAChipTogether_soTheEditorNeverBreaksInsideIt() {
        val shown = chipText("Northern Field Recordings")

        // no ordinary space, and a word joiner between every two characters: no break opportunity inside
        assertEquals(false, shown.contains(' '))
        assertEquals("@Northern Field Recordings", shown.replace("\u2060", "").replace('\u00A0', ' '))
        assertEquals(shown.length, "@Northern Field Recordings".length * 2 - 1)
        assertEquals("@\u2060林\u2060间", chipText("林间"))
        // an emoji's surrogate pair and an accent mark stay one character
        assertEquals("@\u2060\uD83C\uDF0A", chipText("\uD83C\uDF0A"))
        assertEquals("@\u2060e\u0301", chipText("e\u0301"))
    }
}
