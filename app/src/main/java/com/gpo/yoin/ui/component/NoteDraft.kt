package com.gpo.yoin.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * The song a note is written FOR, captured together with its song-moment
 * anchor when writing begins. [songId] is the Now Playing song id
 * (`MediaId.toString()`); [title] / [artist] travel with it so a draft can
 * still be filed — and named — after the player has moved on.
 */
@Immutable
data class NoteTarget(
    val songId: String,
    val title: String,
    val artist: String,
)

/**
 * A finished draft on its way to storage: the words plus the song and the
 * moment it was STARTED on. A skip between the first word and 记下 must not
 * move the note to the next song (with the previous song's anchor).
 */
@Immutable
data class NoteSaveRequest(
    val content: String,
    val anchorMs: Long?,
    val target: NoteTarget?,
)

/**
 * A note being written. While it has no words it follows what's playing; the
 * moment writing begins (focus with an empty draft, or the first word) binds
 * it to that song and playhead, and the binding holds until it's saved,
 * cleared or explicitly re-aligned.
 */
@Immutable
data class NoteDraft(
    val text: String = "",
    val anchorMs: Long? = null,
    val target: NoteTarget? = null,
) {
    /** True while the draft carries words that belong to a song other than [current]. */
    fun isForOtherTrack(current: NoteTarget): Boolean =
        text.isNotEmpty() && target != null && target.songId != current.songId

    /** No words yet: (re)bind to [current] at [positionMs]. Written words keep their binding. */
    fun followIfEmpty(current: NoteTarget, positionMs: Long): NoteDraft =
        if (text.isEmpty()) copy(anchorMs = positionMs, target = current) else this

    /**
     * Typing. An unbound draft — or an emptied one still bound to a song that
     * has since ended — binds to [current] at [positionMs] with its first word.
     */
    fun edit(newText: String, current: NoteTarget, positionMs: Long): NoteDraft = when {
        target == null || (text.isEmpty() && target.songId != current.songId) ->
            NoteDraft(text = newText, anchorMs = positionMs, target = current)
        else -> copy(text = newText)
    }

    /** "This moment": the anchor chip re-aligns the draft to [current] at [positionMs], words kept. */
    fun realign(current: NoteTarget, positionMs: Long): NoteDraft = copy(anchorMs = positionMs, target = current)

    /** Null while there is nothing worth saving. */
    fun toSaveRequest(): NoteSaveRequest? = text.trim()
        .takeIf { it.isNotEmpty() }
        ?.let { NoteSaveRequest(content = it, anchorMs = anchorMs, target = target) }

    /** After a save: an empty draft for what's playing now. */
    fun cleared(current: NoteTarget, positionMs: Long): NoteDraft = NoteDraft(anchorMs = positionMs, target = current)

    internal fun toSaveable(): List<Any?> = listOf(text, anchorMs, target?.songId, target?.title, target?.artist)

    internal companion object {
        fun fromSaveable(saved: List<Any?>): NoteDraft {
            val songId = saved.getOrNull(2) as? String
            return NoteDraft(
                text = saved.getOrNull(0) as? String ?: "",
                anchorMs = saved.getOrNull(1) as? Long,
                target = songId?.let {
                    NoteTarget(
                        songId = it,
                        title = saved.getOrNull(3) as? String ?: "",
                        artist = saved.getOrNull(4) as? String ?: "",
                    )
                },
            )
        }
    }
}

/**
 * Now Playing's one note draft, hoisted to the screen root so the words
 * survive a pager page being recycled, the Expanded → Compact collapse, a
 * layout swap, a song change and rotation. Written through the Note page's
 * write bar (`NoteWriteBar`).
 */
@Stable
class NoteDraftState(initial: NoteDraft = NoteDraft()) {
    var draft: NoteDraft by mutableStateOf(initial)
        private set

    fun followIfEmpty(current: NoteTarget, positionMs: Long) {
        draft = draft.followIfEmpty(current, positionMs)
    }

    fun edit(newText: String, current: NoteTarget, positionMs: Long) {
        draft = draft.edit(newText, current, positionMs)
    }

    fun realign(current: NoteTarget, positionMs: Long) {
        draft = draft.realign(current, positionMs)
    }

    /** The request to save — and the draft reset for [current] — or null when blank. */
    fun takeSaveRequest(current: NoteTarget, positionMs: Long): NoteSaveRequest? {
        val request = draft.toSaveRequest() ?: return null
        draft = draft.cleared(current, positionMs)
        return request
    }

    /**
     * Drops the draft — words, song and moment. A profile switch: profiles are
     * independent data sources, and a draft (and its song id) must never be
     * filed under another one.
     */
    fun discard() {
        draft = NoteDraft()
    }

    companion object {
        val Saver: Saver<NoteDraftState, Any> = listSaver(
            save = { it.draft.toSaveable() },
            restore = { NoteDraftState(NoteDraft.fromSaveable(it)) },
        )
    }
}

@Composable
fun rememberNoteDraftState(): NoteDraftState = rememberSaveable(saver = NoteDraftState.Saver) { NoteDraftState() }

/**
 * The write bar field's value for the draft's [text]. While [held] (the
 * field's last value) still shows that text it is kept as is — caret,
 * selection, IME composition. Otherwise the field is new over an existing
 * draft (the Note page came back, the layout swapped) or the draft changed
 * elsewhere, and the caret goes to the END: writing continues the draft
 * instead of prepending to it.
 */
internal fun draftFieldValue(text: String, held: TextFieldValue?): TextFieldValue =
    if (held != null && held.text == text) held else TextFieldValue(text, TextRange(text.length))
