package com.gpo.yoin.ui.component

import com.gpo.yoin.shared.notes.NoteMention

/*
 * How a drawn mention answers a finger (notes 0.6.4, owner 2026-11-24), pure so it is unit-tested on its own:
 * a tap opens what it names (the text's link stays for TalkBack), a long press shows the peek card while the finger
 * stays down and hides it on release without opening anything; a drag that moves past the touch slop before
 * the long press is a scroll and does neither. Where notes are read at a glance (Now Playing collapsed) the
 * text is not interactive at all: the tap goes to the host, which expands.
 */

/** A mention as drawn: its place in the built text ([start] until [end], thumbnail and padding included). */
internal data class DrawnMention(val ordinal: Int, val mention: NoteMention, val start: Int, val end: Int)

/** The mention drawn over text offset [offset], if any. */
internal fun drawnMentionAt(mentions: List<DrawnMention>, offset: Int): DrawnMention? =
    mentions.firstOrNull { offset >= it.start && offset < it.end }

/**
 * Whether (and how) mentions open: never where the text isn't [interactive]; otherwise the host's own
 * [explicit] callback, else the window's ([fromController]).
 */
internal fun noteMentionClick(
    interactive: Boolean,
    explicit: ((NoteMention) -> Unit)?,
    fromController: ((NoteMention) -> Unit)?,
): ((NoteMention) -> Unit)? = if (!interactive) null else explicit ?: fromController

/** One finger on a drawn mention, from down to release. */
internal class MentionPressTracker {
    sealed interface State {
        data object Idle : State

        data class Pressed(val mention: DrawnMention) : State

        data class Peeking(val mention: DrawnMention) : State
    }

    /** What a release means. */
    enum class Release {
        /** A tap: the mention opens. */
        Tap,

        /** The peek card closes; nothing opens, and the release is kept from the link and the host. */
        EndPeek,

        /** Nothing was being tracked. */
        Nothing,
    }

    var state: State = State.Idle
        private set

    /** A finger down on [hit] (null: not on a mention, left to the host). True when it is tracked. */
    fun down(hit: DrawnMention?): Boolean {
        state = hit?.let(State::Pressed) ?: State.Idle
        return hit != null
    }

    /** The long-press timeout passed with the finger still down: the mention to peek at, if pressed. */
    fun longPress(): DrawnMention? {
        val pressed = state as? State.Pressed ?: return null
        state = State.Peeking(pressed.mention)
        return pressed.mention
    }

    /** The finger moved past the touch slop: before a peek it is a scroll; a peek stays until release. */
    fun movedPastSlop() {
        if (state is State.Pressed) state = State.Idle
    }

    fun up(): Release {
        val release = when (state) {
            is State.Pressed -> Release.Tap
            is State.Peeking -> Release.EndPeek
            State.Idle -> Release.Nothing
        }
        state = State.Idle
        return release
    }

    fun cancel() {
        state = State.Idle
    }
}
