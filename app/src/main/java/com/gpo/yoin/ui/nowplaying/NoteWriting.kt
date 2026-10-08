package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntOffset
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.player.PlaybackState
import com.gpo.yoin.ui.component.NoteSortMode
import com.gpo.yoin.ui.component.NoteTarget
import com.gpo.yoin.ui.component.NoteWriteBarState
import com.gpo.yoin.ui.component.sortNotes
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The song this screen is drawing, as a note target. */
internal fun NowPlayingUiState.Playing.noteTarget(): NoteTarget =
    NoteTarget(songId = songId, title = songTitle, artist = artist, coverArtUrl = coverArtUrl)

/**
 * Keyboard up over the expanded Note page: the write bar owns the bottom edge.
 * The title steps aside (into the top bar) so the bar sits on the keyboard
 * instead of under a title it has to look past.
 */
internal fun noteComposerOwnsBottom(
    stageMode: NowPlayingStageMode,
    page: NowPlayingDetailPage,
    imeVisible: Boolean,
): Boolean = imeVisible &&
    stageMode == NowPlayingStageMode.Expanded &&
    page == NowPlayingDetailPage.Note

/**
 * Timeline follow for the expanded note list: glide to the note the playhead
 * is inside — unless the reader dragged the list, or is writing (the list
 * must not jump under the words being typed; it shows where the draft will
 * land instead).
 */
internal fun shouldFollowCurrentNote(
    sortMode: NoteSortMode,
    currentIndex: Int,
    userScrolled: Boolean,
    writing: Boolean,
): Boolean = sortMode == NoteSortMode.Timeline &&
    currentIndex >= 0 &&
    !userScrolled &&
    !writing

/**
 * The track a saved note is filed under: the one its draft was started on,
 * even after the player has moved on — the current (or pending) track, else
 * that track in the queue, else a minimal track rebuilt from the draft's own
 * snapshot (a note keeps only id, title and artist). No target = the current
 * track.
 */
internal fun resolveNoteTrack(target: NoteTarget?, playback: PlaybackState): Track? {
    if (target == null) return playback.currentTrack
    val known = listOfNotNull(playback.currentTrack, playback.pendingTrack) + playback.queue
    known.firstOrNull { it.id.toString() == target.songId }?.let { return it }
    val id = MediaId.parseOrNull(target.songId) ?: return null
    return Track(
        id = id,
        title = target.title,
        artist = target.artist,
        artistId = null,
        album = null,
        albumId = null,
        coverArt = null,
        durationSec = null,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
    )
}

/** Whether the keyboard is up (or rising) — flips once per show / hide. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun isImeUp(): Boolean = WindowInsets.isImeVisible

/**
 * Steps an element aside by [fraction] (0 = in place, 1 = gone): its height
 * shrinks and it fades. Read in the layout / draw phases only, so an animated
 * fraction never recomposes the caller.
 */
internal fun Modifier.yieldHeight(fraction: () -> Float): Modifier = this
    // Outside the layout step, so the clip is the SHRUNK height — inside it,
    // it would clip at the full height and the row would spill onto its
    // neighbour while it yields.
    .clipToBounds()
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val kept = 1f - fraction().coerceIn(0f, 1f)
        layout(placeable.width, (placeable.height * kept).roundToInt()) {
            placeable.placeRelative(0, 0)
        }
    }
    .graphicsLayer { alpha = 1f - fraction().coerceIn(0f, 1f) }

/**
 * The Write pill's keyboard waits for the stage to (nearly) finish expanding:
 * one movement at a time — the page lands, then the keyboard rises.
 */
internal const val NoteWriteFocusStageProgress = 0.9f

/** A Write-pill request the page never landed for (the reader went elsewhere) expires. */
internal const val NoteWriteRequestTimeoutMs = 2_000L

/**
 * Drops a pending [NoteWriteBarState.requestWriting] that hasn't been taken up
 * within [NoteWriteRequestTimeoutMs], so visiting the Note page later doesn't
 * raise a keyboard nobody asked for just then.
 */
@Composable
internal fun ExpirePendingNoteWrite(state: NoteWriteBarState) {
    LaunchedEffect(state, state.pendingFocus) {
        if (state.pendingFocus) {
            delay(NoteWriteRequestTimeoutMs)
            state.cancelPendingFocus()
        }
    }
}

/** Where the followed line rests in the note list, as in the compact lyrics window. */
internal const val NoteFollowAnchorFraction = 0.22f

/** How long a deleted note can be taken back before it is gone. */
internal const val NoteUndoWindowMs = 5_000L

/** True when [note] belongs to the Now Playing song [songId] (`MediaId.toString()`). */
internal fun SongNote.isForSong(songId: String): Boolean = MediaId(provider, trackId).toString() == songId

/** A row of the expanded Note page. */
internal sealed interface NoteRow {
    val key: Any

    /** A written note. */
    data class Line(val note: SongNote) : NoteRow {
        override val key: Any get() = note.id
    }

    /** A note just deleted: "已删除 · 撤销" in its place until the undo window closes. */
    data class Deleted(val note: SongNote) : NoteRow {
        override val key: Any get() = note.id
    }

    /** The note being written, at the moment it will be filed at. */
    data class Draft(val anchorMs: Long?) : NoteRow {
        override val key: Any get() = DraftRowKey
    }
}

private const val DraftRowKey = "note-draft"

/**
 * The page's rows: the notes in [mode] order, the [deleted] note back in its
 * place as an undo row, and — while writing — the draft at [draftAnchorMs]:
 * after every note at or before its moment on the timeline (un-anchored notes
 * stay last), at the end of the journal order.
 */
internal fun noteRows(
    notes: List<SongNote>,
    mode: NoteSortMode,
    deleted: SongNote? = null,
    draftAnchorMs: Long? = null,
    showDraft: Boolean = false,
): List<NoteRow> {
    val all = if (deleted != null && notes.none { it.id == deleted.id }) notes + deleted else notes
    val rows = sortNotes(all, mode).mapTo(ArrayList<NoteRow>()) { note ->
        if (note.id == deleted?.id) NoteRow.Deleted(note) else NoteRow.Line(note)
    }
    if (!showDraft) return rows
    val draft = NoteRow.Draft(draftAnchorMs)
    val at = when {
        mode == NoteSortMode.Created || draftAnchorMs == null -> rows.size
        else -> rows.indexOfFirst { row ->
            val anchor = row.noteOrNull()?.positionMs
            anchor == null || anchor > draftAnchorMs
        }.takeIf { it >= 0 } ?: rows.size
    }
    rows.add(at, draft)
    return rows
}

private fun NoteRow.noteOrNull(): SongNote? = when (this) {
    is NoteRow.Line -> note
    is NoteRow.Deleted -> note
    is NoteRow.Draft -> null
}

/**
 * Delete with undo for Now Playing's notes. A deleted note is hidden at once
 * everywhere (both panes, the seek-bar notches) and offered back in its place
 * on the Note page; the store deletes it only when the undo window closes
 * ([commit]) — so an undo restores the very same row, nothing is re-created.
 * A committed note stays hidden until the store's list drops it ([prune]).
 * One pending deletion at a time: deleting another commits the first.
 */
@Stable
internal class NoteDeletionState {
    var pending: SongNote? by mutableStateOf(null)
        private set

    var hiddenIds: Set<String> by mutableStateOf(emptySet())
        private set

    fun visible(notes: List<SongNote>): List<SongNote> =
        if (hiddenIds.isEmpty()) notes else notes.filterNot { it.id in hiddenIds }

    fun delete(note: SongNote, commit: (String) -> Unit) {
        val previous = pending
        if (previous != null && previous.id != note.id) commit(previous.id)
        pending = note
        hiddenIds = hiddenIds + note.id
    }

    /** Takes the pending deletion back; null when there was none. */
    fun undo(): SongNote? {
        val note = pending ?: return null
        pending = null
        hiddenIds = hiddenIds - note.id
        return note
    }

    /** The undo window closed (or Now Playing left): delete for real. */
    fun commit(commit: (String) -> Unit) {
        val note = pending ?: return
        pending = null
        commit(note.id)
    }

    /** Forget hidden ids the store has dropped. */
    fun prune(notes: List<SongNote>) {
        if (hiddenIds.isEmpty()) return
        val present = notes.mapTo(HashSet()) { it.id }
        val kept = hiddenIds.filterTo(HashSet()) { it == pending?.id || it in present }
        if (kept != hiddenIds) hiddenIds = kept
    }

    // A finger on the Note page's list. Not drawn, so a plain flow, not
    // snapshot state.
    private val listHeld = MutableStateFlow(false)

    /** The Note page reports a finger down on its list (true) and every finger lifted (false). */
    fun onListHeldChange(held: Boolean) {
        listHeld.value = held
    }

    /**
     * The pending deletion's undo window: [windowMs], then — if a finger is
     * on the list right then — until it lifts, then [commit]. Closing the
     * window collapses the undo row and slides the next note up; under a
     * finger, a tap on its way to 撤销 landed on that note and sought instead
     * (2026-10-05 QA). Run per pending deletion (a new one restarts it).
     */
    suspend fun runUndoWindow(windowMs: Long = NoteUndoWindowMs, onCommit: (String) -> Unit) {
        if (pending == null) return
        delay(windowMs)
        listHeld.first { !it }
        commit(onCommit)
    }
}

/**
 * Where the undo row in [before] collapsed for good — its note gone from
 * [after], not undone back into a line — or -1. Rows from that index on in
 * [after] are the ones that glided up into its place.
 */
internal fun collapsedUndoRowIndex(before: List<NoteRow>, after: List<NoteRow>): Int {
    val index = before.indexOfFirst { it is NoteRow.Deleted }
    if (index < 0) return -1
    val key = before[index].key
    return if (after.none { it.key == key }) index else -1
}

/**
 * How long [placementSpec] keeps a row visibly displaced while it glides
 * [travelPx] back into place: the last moment its pixel-rounded offset is
 * not zero (an overshoot that swings back out still counts). Read off the
 * list's own placement spring, so it follows the motion scheme.
 */
internal fun placementGlideMillis(placementSpec: FiniteAnimationSpec<IntOffset>, travelPx: Int): Long {
    val converter = IntOffset.VectorConverter
    val glide = placementSpec.vectorize(converter)
    val from = converter.convertToVector(IntOffset(0, travelPx))
    val to = converter.convertToVector(IntOffset.Zero)
    val velocity = converter.convertToVector(IntOffset.Zero)
    val totalMs = glide.getDurationNanos(from, to, velocity) / NanosPerMilli
    var lastMovingMs = 0L
    for (ms in 0..totalMs) {
        val offset = converter.convertFromVector(glide.getValueFromNanos(ms * NanosPerMilli, from, to, velocity))
        if (offset != IntOffset.Zero) lastMovingMs = ms
    }
    return lastMovingMs
}

private const val NanosPerMilli = 1_000_000L

/**
 * Taps on rows that just moved under the finger. When the undo row's window
 * closes it collapses and the rows below glide up into its place; a tap that
 * comes down on them while they glide was aimed at what stood there (QA: a
 * late tap on 撤销 sought the next note). Such a tap is dropped; rows above
 * the collapse never moved and still answer, and every tap that comes down
 * once the glide has settled behaves normally.
 *
 * [onRows] sees each new row list and starts the guard on a collapse, for
 * [placementGlideMillis] of the collapsed row; [onDown] stamps each touch
 * down; [ignores] is asked by a row's click.
 */
@Stable
internal class NoteCollapseTapGuard {
    private var lastRows: List<NoteRow> = emptyList()
    private var movedFrom = Int.MAX_VALUE
    private var gliding = false
    private var downWhileGliding = false
    private var glideJob: Job? = null

    fun onRows(rows: List<NoteRow>, glideMs: Long, scope: CoroutineScope) {
        if (rows === lastRows) return
        val collapsedAt = collapsedUndoRowIndex(lastRows, rows)
        lastRows = rows
        if (collapsedAt < 0) return
        movedFrom = collapsedAt
        gliding = true
        glideJob?.cancel()
        glideJob = scope.launch {
            try {
                delay(glideMs)
            } finally {
                gliding = false
            }
        }
    }

    fun onDown() {
        downWhileGliding = gliding
    }

    /** A click on row [index] of the current rows: true when its touch came down on a row gliding into place. */
    fun ignores(index: Int): Boolean = downWhileGliding && index >= movedFrom
}

/** Now Playing's delete-with-undo, provided at the screen root for the Note page. */
internal val LocalNoteDeletion = staticCompositionLocalOf<NoteDeletionState?> { null }

/**
 * Re-anchors a note to a new moment (the page's "对齐到现在"); null where the
 * host can't — the menu then offers delete only.
 */
internal val LocalNoteRealign = compositionLocalOf<((SongNote, Long) -> Unit)?> { null }
