package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.NoteDraft
import com.gpo.yoin.ui.component.NoteDraftState
import com.gpo.yoin.ui.component.NoteLine
import com.gpo.yoin.ui.component.NoteLineEmphasis
import com.gpo.yoin.ui.component.NoteSortMode
import com.gpo.yoin.ui.component.NoteSortToggle
import com.gpo.yoin.ui.component.NoteStampColumnWidth
import com.gpo.yoin.ui.component.NoteTarget
import com.gpo.yoin.ui.component.YoinDropdownMenu
import com.gpo.yoin.ui.component.YoinDropdownMenuItem
import com.gpo.yoin.ui.component.currentAnchoredNoteId
import com.gpo.yoin.ui.component.formatNoteDate
import com.gpo.yoin.ui.component.formatNotePosition
import com.gpo.yoin.ui.component.verticalEdgeFadeOnScroll
import com.gpo.yoin.ui.experience.LocalWindowCovered
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinTheme
import kotlin.math.abs

/**
 * The expanded Note page: the song's notes read like its lyrics — a time
 * stamp, then the words, the line the playhead is inside lit up, the list
 * gliding to keep it ~22% down (Timeline order) — with the Timeline ⇄ Created
 * switch above. No cards, no rails, no per-row buttons: tap an anchored line
 * to seek to its moment, long-press it for "对齐到现在" / "删除" (a deleted
 * line offers 撤销 in its place for a few seconds). A finger on the list is
 * reported through [onListHeldChange] — the undo window waits for it to lift
 * — and when the undo row does collapse, a tap that comes down on a row still
 * gliding into its place is dropped ([NoteCollapseTapGuard]).
 *
 * Writing happens in the write bar docked below the page (the accessory slot).
 * While it is open ([writing]), the list holds still, and the draft shows
 * where it will land — "正在写…" at its moment — as long as it belongs to this
 * song. When the keyboard leaves the page too short for one whole line, the
 * list fades out entire rather than showing a sliver.
 */
@Composable
fun NoteFullscreenPane(
    notes: List<SongNote>,
    sortMode: NoteSortMode,
    onSortModeChange: (NoteSortMode) -> Unit,
    positionMs: () -> Long,
    onSeekToMs: (Long) -> Unit,
    draftState: NoteDraftState,
    current: NoteTarget,
    writing: Boolean,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
    deleted: SongNote? = null,
    onUndoDelete: () -> Unit = {},
    onRealign: ((SongNote, Long) -> Unit)? = null,
    onListHeldChange: (Boolean) -> Unit = {},
) {
    val density = LocalDensity.current
    val listMinPx = with(density) { NoteListMinViewport.roundToPx() }
    val emptyMinPx = with(density) { NoteEmptyMinHeight.roundToPx() }
    val notesListState = rememberLazyListState()
    // Only the draft's moment (and whether it is this song's) reaches the
    // rows — typing doesn't recompose the list.
    val draftAnchor by remember(draftState, current.songId) {
        derivedStateOf { draftState.draft.takeIf { it.belongsTo(current.songId) }?.anchorMs }
    }
    val draftHere by remember(draftState, current.songId) {
        derivedStateOf { draftState.draft.belongsTo(current.songId) }
    }
    val showDraft = writing && draftHere
    val rows = remember(notes, sortMode, deleted, draftAnchor, showDraft) {
        noteRows(notes, sortMode, deleted, draftAnchor, showDraft)
    }
    val currentPositionMs by rememberUpdatedState(positionMs)
    // derivedStateOf absorbs the 4Hz tick — rows recompose only when the
    // playhead crosses into another note's stretch.
    val currentNoteId by remember(notes) {
        derivedStateOf { currentAnchoredNoteId(notes, currentPositionMs()) }
    }
    val currentIndex by remember(rows) {
        derivedStateOf { rows.indexOfFirst { it is NoteRow.Line && it.note.id == currentNoteId } }
    }
    val draftIndex = rows.indexOfFirst { it is NoteRow.Draft }

    // Height of the page; too short for a whole line (the keyboard is up on a
    // short window) and it fades out entire.
    var regionHeightPx by remember { mutableIntStateOf(Int.MAX_VALUE) }
    val empty = rows.isEmpty()
    val regionFits by remember(empty, listMinPx, emptyMinPx) {
        derivedStateOf {
            if (empty) {
                regionHeightPx >= emptyMinPx
            } else {
                // Before the first measure the viewport reads 0 — trust the region.
                val viewport = notesListState.layoutInfo.viewportSize.height
                regionHeightPx >= listMinPx && (viewport == 0 || viewport >= listMinPx)
            }
        }
    }
    val regionAlpha = animateFloatAsState(
        targetValue = if (regionFits) 1f else 0f,
        animationSpec = YoinMotion.effectsSpring(),
        label = "noteRegionAlpha",
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { regionHeightPx = it.height }
            .graphicsLayer { alpha = regionAlpha.value }
            // Faded out = out of reach too: a tap there must not land on an
            // unseen line's seek or menu.
            .then(if (regionFits) Modifier else Modifier.swallowTouches()),
    ) {
        if (empty) {
            NoteEmptyState(songId = current.songId, modifier = Modifier.weight(1f))
        } else {
            NoteRowsList(
                notes = notes,
                rows = rows,
                sortMode = sortMode,
                onSortModeChange = onSortModeChange,
                deleted = deleted,
                notesListState = notesListState,
                currentNoteId = currentNoteId,
                currentIndex = currentIndex,
                draftIndex = draftIndex,
                writing = writing,
                positionMs = positionMs,
                onSeekToMs = onSeekToMs,
                onDelete = onDelete,
                onUndoDelete = onUndoDelete,
                onRealign = onRealign,
                onListHeldChange = onListHeldChange,
            )
        }
    }
}

@Composable
private fun ColumnScope.NoteRowsList(
    notes: List<SongNote>,
    rows: List<NoteRow>,
    sortMode: NoteSortMode,
    onSortModeChange: (NoteSortMode) -> Unit,
    deleted: SongNote?,
    notesListState: LazyListState,
    currentNoteId: String?,
    currentIndex: Int,
    draftIndex: Int,
    writing: Boolean,
    positionMs: () -> Long,
    onSeekToMs: (Long) -> Unit,
    onDelete: (String) -> Unit,
    onUndoDelete: () -> Unit,
    onRealign: ((SongNote, Long) -> Unit)?,
    onListHeldChange: (Boolean) -> Unit,
) {
    if (notes.isNotEmpty() || deleted != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pluralStringResource(R.plurals.np_note_count, notes.size, notes.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NoteSortToggle(mode = sortMode, onModeChange = onSortModeChange)
        }
    }

    // Lyrics-style follow: glide to the active note as playback moves,
    // but a user drag takes the wheel until the note list changes, and
    // writing holds the list still.
    var userScrolled by remember(notes, sortMode) { mutableStateOf(false) }
    LaunchedEffect(notesListState) {
        notesListState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) userScrolled = true
        }
    }
    val followOffset = {
        -(notesListState.layoutInfo.viewportSize.height * NoteFollowAnchorFraction).toInt()
    }
    // Covered by a Yoin window (its clock frozen): land at once, nothing glides on reveal.
    val windowCovered = LocalWindowCovered.current
    LaunchedEffect(rows, sortMode, currentIndex, writing) {
        if (shouldFollowCurrentNote(sortMode, currentIndex, userScrolled, writing)) {
            if (windowCovered.value) {
                notesListState.scrollToItem(currentIndex, followOffset())
            } else {
                notesListState.animateScrollToItem(currentIndex, followOffset())
            }
        }
    }
    // Writing: keep the draft's spot in view — it is where the words land.
    LaunchedEffect(writing, draftIndex) {
        if (writing && draftIndex >= 0) {
            notesListState.animateScrollToItem(draftIndex, followOffset())
        }
    }
    // One placement spring for the rows, and the guard's window read off it:
    // how long a row stays displaced gliding up by the undo row's height.
    val placementSpec = YoinMotion.spatialSpring<IntOffset>()
    val density = LocalDensity.current
    val glideMs = remember(placementSpec, density) {
        placementGlideMillis(placementSpec, with(density) { NoteDeletedRowHeight.roundToPx() })
    }
    val tapGuard = remember { NoteCollapseTapGuard() }
    val guardScope = rememberCoroutineScope()
    // Right after the frame that applies the new rows, before any touch can
    // land on them.
    SideEffect { tapGuard.onRows(rows, glideMs, guardScope) }
    val latestOnListHeldChange by rememberUpdatedState(onListHeldChange)
    LazyColumn(
        state = notesListState,
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .noteListTouch(
                onDown = tapGuard::onDown,
                onHeldChange = { latestOnListHeldChange(it) },
            )
            .verticalEdgeFadeOnScroll(
                notesListState,
                top = NoteListTopFade,
                bottom = NoteListBottomFade,
            ),
        contentPadding = PaddingValues(top = 4.dp, bottom = NoteListBottomPadding),
    ) {
        itemsIndexed(items = rows, key = { _, row -> row.key }, contentType = { _, row -> row::class }) { index, row ->
            val rowModifier = Modifier.animateItem(
                fadeInSpec = YoinMotion.effectsSpring(),
                placementSpec = placementSpec,
                fadeOutSpec = YoinMotion.effectsSpring(),
            )
            when (row) {
                is NoteRow.Line -> NotePageLine(
                    note = row.note,
                    isActive = row.note.id == currentNoteId,
                    distance = if (currentIndex >= 0) abs(index - currentIndex) else null,
                    showDate = sortMode == NoteSortMode.Created,
                    positionMs = positionMs,
                    onSeekToMs = onSeekToMs,
                    onDelete = onDelete,
                    onRealign = onRealign,
                    tapIgnored = { tapGuard.ignores(index) },
                    modifier = rowModifier,
                )
                is NoteRow.Deleted -> NoteDeletedLine(onUndo = onUndoDelete, modifier = rowModifier)
                is NoteRow.Draft -> NoteDraftLine(anchorMs = row.anchorMs, modifier = rowModifier)
            }
        }
    }
}

/** A draft without a song yet, or one started on this song, lands in this list. */
internal fun NoteDraft.belongsTo(songId: String): Boolean = target == null || target.songId == songId

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotePageLine(
    note: SongNote,
    isActive: Boolean,
    distance: Int?,
    showDate: Boolean,
    positionMs: () -> Long,
    onSeekToMs: (Long) -> Unit,
    onDelete: (String) -> Unit,
    onRealign: ((SongNote, Long) -> Unit)?,
    tapIgnored: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    val noteDateResources = LocalContext.current.resources
    val interaction = remember { MutableInteractionSource() }
    var menuOpen by remember { mutableStateOf(false) }
    // "Now" as of the long-press: what the menu shows is what it files.
    var menuNowMs by remember { mutableLongStateOf(0L) }
    val anchor = note.positionMs
    Box(modifier = modifier) {
        NoteLine(
            stamp = anchor?.let(::formatNotePosition),
            text = note.content,
            isActive = isActive,
            emphasis = NoteLineEmphasis.Page,
            distance = distance,
            meta = if (showDate) formatNoteDate(note.createdAt, resources = noteDateResources) else null,
            modifier = Modifier.combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = anchor?.let {
                    stringResource(R.string.np_cd_note_play_from, formatNotePosition(it))
                },
                onLongClickLabel = stringResource(R.string.np_cd_note_actions),
                onLongClick = {
                    haptics.performLongPress()
                    menuNowMs = positionMs()
                    menuOpen = true
                },
                onClick = {
                    if (anchor != null && !tapIgnored()) {
                        haptics.performTick()
                        onSeekToMs(anchor)
                    }
                },
            ),
        )
        YoinDropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            if (onRealign != null) {
                YoinDropdownMenuItem(
                    text = stringResource(R.string.np_note_realign, formatNotePosition(menuNowMs)),
                    leadingIcon = { Icon(YoinSymbols.MusicNote, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        haptics.performTick()
                        onRealign(note, menuNowMs)
                    },
                )
            }
            YoinDropdownMenuItem(
                text = stringResource(R.string.np_note_delete),
                leadingIcon = { Icon(YoinSymbols.Delete, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    haptics.performReject()
                    onDelete(note.id)
                },
            )
        }
    }
}

/** A just-deleted note's place: "已删除" and the way back, until the undo window closes. */
@Composable
private fun NoteDeletedLine(onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberYoinHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NoteDeletedRowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(NoteStampColumnWidth))
        Text(
            text = stringResource(R.string.np_note_deleted),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                haptics.performTick()
                onUndo()
            },
        ) {
            Text(text = stringResource(R.string.np_note_undo), fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Where the note being written will land: its moment, "正在写…" on a dotted line. */
@Composable
private fun NoteDraftLine(anchorMs: Long?, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = anchorMs?.let(::formatNotePosition).orEmpty(),
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = primary.copy(alpha = 0.8f),
            maxLines = 1,
            modifier = Modifier
                .width(NoteStampColumnWidth)
                .alignByBaseline(),
        )
        Text(
            text = stringResource(R.string.np_note_writing),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .alignByBaseline()
                .padding(bottom = 3.dp)
                .drawBehind {
                    val y = size.height
                    drawLine(
                        color = primary,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                    )
                },
        )
    }
}

/**
 * No notes on this song: one of the [NoteDoodle]s (picked at random per song)
 * over a plain label — no prompt (2026-10-08 owner). The doodle drops out when
 * the region is too short for it (keyboard up on a short window); the label
 * alone still fits [NoteEmptyMinHeight].
 */
@Composable
private fun NoteEmptyState(songId: String, modifier: Modifier = Modifier) {
    val doodle = remember(songId) { NoteDoodle.entries.random() }
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val doodleWidth = minOf(maxWidth, NoteDoodleMaxWidth)
        val doodleHeight = doodleWidth * (NoteDoodleViewBoxHeight / NoteDoodleViewBoxWidth)
        val showDoodle = maxHeight >= doodleHeight + NoteEmptyMinHeight
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showDoodle) {
                NoteEmptyDoodle(doodle = doodle, modifier = Modifier.size(doodleWidth, doodleHeight))
            }
            Text(
                text = stringResource(R.string.np_note_empty),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A finger on the list: [onDown] at each first touch-down (Initial pass, before
 * any row sees it), [onHeldChange] true then, false once every finger has
 * lifted — Final pass, so a tap's own click (撤销) has already run — or the
 * gesture is torn down.
 */
private fun Modifier.noteListTouch(onDown: () -> Unit, onHeldChange: (Boolean) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            onDown()
            onHeldChange(true)
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                } while (event.changes.any { it.pressed })
            } finally {
                onHeldChange(false)
            }
        }
    }

/** Consumes every pointer change on the way in (Initial pass), so nothing inside reacts. */
private fun Modifier.swallowTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

/** The undo row's height — also how far the rows below glide when it collapses. */
private val NoteDeletedRowHeight = 48.dp

/** Shortest list viewport that still holds one whole line — below it, no sliver. */
private val NoteListMinViewport = 56.dp

/** Height of the empty-state label; a shorter region hides it rather than clip it. */
private val NoteEmptyMinHeight = 64.dp

/** The empty-state doodle's widest; narrower columns shrink it at its aspect ratio. */
private val NoteDoodleMaxWidth = 240.dp

private val NoteListTopFade = 24.dp
private val NoteListBottomFade = 64.dp
private val NoteListBottomPadding = 48.dp

@Preview(name = "Note page", showBackground = true, widthDp = 380, heightDp = 520)
@Composable
private fun NoteFullscreenPanePreview() {
    val song = NoteTarget(songId = "subsonic:preview", title = "Streetlight Waltz", artist = "Mira Kade")
    fun note(id: String, at: Long?, text: String) = SongNote(
        id = id,
        trackId = "preview",
        content = text,
        createdAt = 1_700_000_000_000L + id.hashCode(),
        updatedAt = 1_700_000_000_000L,
        title = song.title,
        artist = song.artist,
        positionMs = at,
    )
    YoinTheme {
        NoteFullscreenPane(
            notes = listOf(
                note("a", 12_000L, "Bass walks up the stairs in the intro"),
                note("b", 48_000L, "Everyone shouts here"),
                note("c", 83_000L, "Chorus comes in — that last summer bus"),
                note("d", 125_000L, "Clap ×4"),
                note("e", null, "An old note with no moment"),
            ),
            sortMode = NoteSortMode.Timeline,
            onSortModeChange = {},
            positionMs = { 90_000L },
            onSeekToMs = {},
            draftState = remember { NoteDraftState(NoteDraft(anchorMs = 91_000L, target = song)) },
            current = song,
            writing = true,
            onDelete = {},
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}
