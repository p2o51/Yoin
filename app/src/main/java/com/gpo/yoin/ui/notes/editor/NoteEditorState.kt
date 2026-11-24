package com.gpo.yoin.ui.notes.editor

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import com.gpo.yoin.shared.notes.MentionTrigger
import com.gpo.yoin.shared.notes.NoteEdit
import com.gpo.yoin.shared.notes.NoteEditorText
import com.gpo.yoin.shared.notes.NoteMention
import com.gpo.yoin.shared.notes.NoteTool

/**
 * One note editor (the Now Playing write bar, an album sticky, the review field): the stored text in a
 * [TextFieldState], the @ being typed ([trigger]) and the popup's highlighted row. Edits that come from
 * the keyboard pass [NoteEditorInputTransformation]; the editor's own actions (a mention picked, a tool)
 * write canonical text directly.
 */
@Stable
class NoteEditorState(initialText: String = "") {
    val fieldState = TextFieldState(initialText, TextRange(initialText.length))

    /** The stored text as it stands. */
    val text: String get() = fieldState.text.toString()

    /** The `@` the reader dismissed (Esc, tap away): the popup stays shut until another `@`. */
    private var dismissedAt by mutableStateOf<Int?>(null)

    /** The popup's highlighted row (↑ ↓ move it, Enter picks it). */
    var highlighted by mutableIntStateOf(0)

    /** The host's decorated box around the field ([noteEditorContainer]); taps in it don't end @ mode. */
    internal var containerCoordinates: androidx.compose.ui.layout.LayoutCoordinates? = null

    /** The full-screen search sheet is open ("展开搜索"). */
    var searchOpen by mutableStateOf(false)

    /** The `@` being typed at the caret, dismissed or not (letters a keyboard is still composing count). */
    private val typedTrigger: MentionTrigger? by derivedStateOf {
        val selection = fieldState.selection
        if (!selection.collapsed) return@derivedStateOf null
        val composing = fieldState.composition?.takeUnless { it.collapsed }?.let { it.min until it.max }
        NoteEditorText.trigger(fieldState.text.toString(), selection.start, composing)
    }

    /**
     * The row Enter picks while the popup is open (its top or highlighted row); the field keeps it current,
     * null while the popup is shut. A soft keyboard's Enter types a newline: [inputTransformation] turns
     * that newline into this mention.
     */
    var enterPick: NoteMention? = null

    /** Keyboard edits: Enter at an open popup picks [enterPick]; everything else [NoteEditorInputTransformation]. */
    val inputTransformation: InputTransformation = object : InputTransformation {
        override fun TextFieldBuffer.transformInput() {
            val pick = enterPick
            val at = trigger
            if (pick != null && at != null) {
                val fix = NoteEditorText.enterPicksMention(originalText.toString(), asCharSequence().toString(), at, pick)
                if (fix != null) {
                    replaceMinimally(fix.text)
                    selection = TextRange(fix.selectionStart.coerceIn(0, length))
                    return
                }
            }
            with(NoteEditorInputTransformation) { transformInput() }
        }
    }

    /** The `@` being typed at the caret, unless dismissed. */
    val trigger: MentionTrigger? by derivedStateOf { typedTrigger?.takeIf { it.start != dismissedAt } }

    /**
     * Ends a dismissal once its `@` is gone or the caret has left it, so the next `@` (even typed at the
     * same place) opens the popup again. The field calls it on every text / caret change (not from
     * composition).
     */
    fun syncDismissal() {
        val at = dismissedAt ?: return
        if (typedTrigger?.start != at) dismissedAt = null
    }

    /** The words before the caret's `@` the popup searches for (empty right after the `@`). */
    val query: String? get() = trigger?.query

    fun dismissPopup() {
        dismissedAt = trigger?.start
    }

    /** Inserts [mention] in place of the `@` being typed (or at the caret, from the search sheet). */
    fun insertMention(mention: NoteMention) {
        val selection = fieldState.selection
        val edit = NoteEditorText.insertMention(text, selection.start, selection.end, trigger, mention)
        write(edit)
        highlighted = 0
        searchOpen = false
    }

    fun apply(tool: NoteTool) {
        val selection = fieldState.selection
        write(NoteEditorText.apply(tool, text, selection.start, selection.end))
        if (tool == NoteTool.Mention) dismissedAt = null
    }

    /** Ticks or unticks the to-do on [lineIndex] (a tap on its box). */
    fun toggleChecklist(lineIndex: Int) {
        val selection = fieldState.selection
        val out = com.gpo.yoin.shared.notes.NoteMarkup.toggleChecklist(text, lineIndex)
        if (out != text) write(NoteEdit(out, selection.start, selection.end))
    }

    /** Replaces the words (a draft cleared after saving, a note opened for editing); caret at the end. */
    fun setText(value: String) {
        if (value != text) fieldState.setTextAndPlaceCursorAtEnd(value)
        dismissedAt = null
        highlighted = 0
    }

    /** Which tools read as pressed at the caret. */
    val activeTools: Set<NoteTool> by derivedStateOf {
        val selection = fieldState.selection
        val value = fieldState.text.toString()
        NoteEditorText.inlineAt(value, selection.start, selection.end) +
            listOfNotNull(NoteEditorText.blockAt(value, selection.start))
    }

    private fun write(edit: NoteEdit) {
        fieldState.edit {
            replaceMinimally(edit.text)
            selection = TextRange(edit.selectionStart.coerceIn(0, length), edit.selectionEnd.coerceIn(0, length))
        }
    }

    companion object {
        val Saver: Saver<NoteEditorState, String> = Saver(save = { it.text }, restore = { NoteEditorState(it) })
    }
}

@Composable
fun rememberNoteEditorState(initialText: String = ""): NoteEditorState =
    rememberSaveable(saver = NoteEditorState.Saver) { NoteEditorState(initialText) }

/**
 * Keyboard edits through [NoteEditorText.reconcile]: a mention stays whole, Enter continues a list, the
 * `[] ` / `【】 ` / `＃` shortcuts land.
 */
object NoteEditorInputTransformation : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        val selection = selection
        val fix = NoteEditorText.reconcile(
            original = originalText.toString(),
            edited = asCharSequence().toString(),
            editedSelectionStart = selection.start,
            editedSelectionEnd = selection.end,
        ) ?: return
        replaceMinimally(fix.text)
        this.selection = TextRange(fix.selectionStart.coerceIn(0, length), fix.selectionEnd.coerceIn(0, length))
    }
}

/** Replaces only the part of the buffer that differs from [target] (keeps the rest, and the IME's place). */
internal fun TextFieldBuffer.replaceMinimally(target: String) {
    val current = asCharSequence()
    var prefix = 0
    val max = minOf(current.length, target.length)
    while (prefix < max && current[prefix] == target[prefix]) prefix++
    var suffix = 0
    while (suffix < max - prefix && current[current.length - 1 - suffix] == target[target.length - 1 - suffix]) suffix++
    replace(prefix, current.length - suffix, target.substring(prefix, target.length - suffix))
}
