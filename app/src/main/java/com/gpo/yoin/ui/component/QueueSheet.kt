package com.gpo.yoin.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.gpo.yoin.R
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.player.QueueEdit
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.symbols.rememberEqualizerSymbolPainter
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.nowplaying.QueueItem
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinTheme
import kotlin.math.roundToInt

/** What the queue sheet may ask the player to change ([QueueEdit] gates which apply). */
@Immutable
class QueueEditActions(
    val onMove: (from: Int, to: Int) -> Unit,
    val onRemove: (index: Int) -> Unit,
    val onClearQueued: () -> Unit,
) {
    companion object {
        val None = QueueEditActions(onMove = { _, _ -> }, onRemove = {}, onClearQueued = {})
    }
}

/** One queue entry in the sheet: its index in the player's list and what it shows. */
@Immutable
internal data class QueueRow(val index: Int, val item: QueueItem)

/**
 * The sheet's three parts, Spotify's queue: what plays now, what the user
 * added (Play next / Add to queue), and the rest of the album or playlist —
 * both in the order they will play. Played songs are left out.
 */
@Immutable
internal data class QueueSections(val now: QueueRow?, val queued: List<QueueRow>, val next: List<QueueRow>)

/** [queue] split into [QueueSections]; [upcoming] is the play order after [currentIndex] (empty = list order). */
internal fun queueSections(queue: List<QueueItem>, currentIndex: Int, upcoming: List<Int>): QueueSections {
    val now = queue.getOrNull(currentIndex)?.let { QueueRow(currentIndex, it) }
    val order = upcoming.ifEmpty { if (currentIndex >= 0) (currentIndex + 1 until queue.size).toList() else queue.indices.toList() }
    val rows = order.mapNotNull { index -> queue.getOrNull(index)?.let { QueueRow(index, it) } }
    val (queued, next) = rows.partition { it.item.userQueued }
    return QueueSections(now, queued, next)
}

/** "Next from: Lantern Letters" — or "Next up" when the songs came from no album or playlist. */
internal fun queueNextFromLabel(
    context: ActivityContext,
    resources: android.content.res.Resources? = null,
): String {
    if (resources == null) {
        return when (context) {
            is ActivityContext.Album ->
                "Next from: ${context.albumName}" // i18n-allow: QueueSectionsTest asserts this English
            is ActivityContext.Playlist ->
                "Next from: ${context.playlistName}" // i18n-allow: QueueSectionsTest asserts this English
            else -> "Next up" // i18n-allow: QueueSectionsTest asserts this English
        }
    }
    return when (context) {
        is ActivityContext.Album -> resources.getString(R.string.cmp_queue_next_from_album, context.albumName)
        is ActivityContext.Playlist -> resources.getString(R.string.cmp_queue_next_from_playlist, context.playlistName)
        else -> resources.getString(R.string.cmp_queue_next_up)
    }
}

/** The header's name for what is playing; null without an album or playlist. */
private fun queuePlayingFrom(context: ActivityContext): String? = when (context) {
    is ActivityContext.Album -> context.albumName
    is ActivityContext.Playlist -> context.playlistName
    else -> null
}

/** Every row is this tall, so a drag reads its slot from its offset alone. */
private val QueueRowHeight = 64.dp

/**
 * The play queue (owner Q1, 2026-10-06: its own button, Spotify's UX, a nicer
 * look): Now playing, Next in queue (what the user added, clearable), Next
 * from the album or playlist. Tap a song to play it. Where [edit] allows,
 * drag a row's handle to move it within its part and swipe it left to take
 * it out; Spotify's queue is read only, MusicKit's can't be reordered, and
 * nothing is reordered while shuffle plays (the list isn't the play order).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    queue: List<QueueItem>,
    currentIndex: Int,
    upcoming: List<Int>,
    activityContext: ActivityContext,
    isPlaying: Boolean,
    edit: QueueEdit,
    shuffling: Boolean,
    onItemClick: (Int) -> Unit,
    editor: QueueEditActions,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val haptics = rememberYoinHaptics()
    val sections = remember(queue, currentIndex, upcoming) { queueSections(queue, currentIndex, upcoming) }
    val canRemove = edit != QueueEdit.None
    val canMove = edit == QueueEdit.Full && !shuffling

    // Conventions for every ModalBottomSheet in this codebase: no outer
    // bottom padding and no height cap; the nav-bar inset is folded into the
    // list's contentPadding so the sheet runs edge to edge.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = {
            BottomSheetDefaults.modalWindowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        },
        modifier = modifier,
    ) {
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val resources = LocalContext.current.resources
        val reorderQueued = rememberQueueReorderState(sections.queued)
        val reorderNext = rememberQueueReorderState(sections.next)
        LazyColumn(
            state = rememberLazyListState(),
            contentPadding = PaddingValues(bottom = 16.dp + navBottom),
        ) {
            item(key = "header") {
                QueueHeader(playingFrom = queuePlayingFrom(activityContext))
            }
            sections.now?.let { now ->
                item(key = "now-label") { QueueSectionLabel(stringResource(R.string.cmp_queue_section_now)) }
                item(key = "now:${now.item.entryId}") {
                    QueueTrackRow(
                        row = now,
                        current = true,
                        isPlaying = isPlaying,
                        onClick = null,
                    )
                }
            }
            if (sections.queued.isNotEmpty()) {
                item(key = "queued-label") {
                    QueueSectionLabel(
                        text = stringResource(R.string.cmp_queue_section_queued),
                        action = if (canRemove) {
                            {
                                haptics.performConfirm()
                                editor.onClearQueued()
                            }
                        } else {
                            null
                        },
                    )
                }
                queueSection(sections.queued, reorderQueued, canMove, canRemove, haptics::performTick, onItemClick, editor)
            }
            if (sections.next.isNotEmpty()) {
                item(key = "next-label") { QueueSectionLabel(queueNextFromLabel(activityContext, resources)) }
                queueSection(sections.next, reorderNext, canMove, canRemove, haptics::performTick, onItemClick, editor)
            }
        }
    }
}

/**
 * One part's drag: which row is held, the slot it hovers and how far it has
 * travelled, plus the order a drop asked for — shown until the player reports
 * its next list, so the dropped row never flicks back to its old slot.
 */
private class QueueReorderState {
    var pendingOrder by mutableStateOf<List<String>?>(null)
    var dragging by mutableIntStateOf(-1)
    var target by mutableIntStateOf(-1)
    var offset by mutableFloatStateOf(0f)
}

/** [rows] with a pending drop applied (the player's order once it reports a new list). */
private fun QueueReorderState.shown(rows: List<QueueRow>): List<QueueRow> {
    val order = pendingOrder ?: return rows
    val byId = rows.associateBy { it.item.entryId }
    return order.mapNotNull(byId::get).takeIf { it.size == rows.size } ?: rows
}

@Composable
private fun rememberQueueReorderState(rows: List<QueueRow>): QueueReorderState {
    val state = remember { QueueReorderState() }
    // The player answered (with the dropped order or anything newer): its list wins.
    androidx.compose.runtime.LaunchedEffect(rows) { if (state.dragging < 0) state.pendingOrder = null }
    return state
}

private fun androidx.compose.foundation.lazy.LazyListScope.queueSection(
    rows: List<QueueRow>,
    state: QueueReorderState,
    canMove: Boolean,
    canRemove: Boolean,
    onSlot: () -> Unit,
    onItemClick: (Int) -> Unit,
    editor: QueueEditActions,
) {
    val shown = state.shown(rows)
    items(shown, key = { it.item.entryId }) { row ->
        QueueEditableRow(
            row = row,
            position = shown.indexOf(row),
            shown = shown,
            state = state,
            canMove = canMove,
            canRemove = canRemove,
            onSlot = onSlot,
            onClick = { onItemClick(row.index) },
            editor = editor,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LazyItemScope.QueueEditableRow(
    row: QueueRow,
    position: Int,
    shown: List<QueueRow>,
    state: QueueReorderState,
    canMove: Boolean,
    canRemove: Boolean,
    onSlot: () -> Unit,
    onClick: () -> Unit,
    editor: QueueEditActions,
) {
    val rowPx = with(LocalDensity.current) { QueueRowHeight.toPx() }
    val dragging = state.dragging == position
    val anyDrag = state.dragging >= 0
    // Neighbours make way while a row is held, and snap (not glide) at the
    // drop: the new order lands in the same frame.
    val shift = when {
        !anyDrag || dragging -> 0f
        state.dragging < position && position <= state.target -> -rowPx
        state.target <= position && position < state.dragging -> rowPx
        else -> 0f
    }
    val shiftShown by animateFloatAsState(
        targetValue = shift,
        animationSpec = if (anyDrag) YoinMotion.spatialSpring() else snap(),
        label = "queueRowShift",
    )
    val latestRow by rememberUpdatedState(row)
    val latestShown by rememberUpdatedState(shown)
    val handle = if (canMove) {
        Modifier.pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragStart = {
                    state.dragging = latestShown.indexOfFirst { it.item.entryId == latestRow.item.entryId }
                    state.target = state.dragging
                    state.offset = 0f
                    onSlot()
                },
                onVerticalDrag = { change, dy ->
                    change.consume()
                    state.offset += dy
                    val slot = (state.dragging + (state.offset / rowPx).roundToInt())
                        .coerceIn(0, latestShown.lastIndex)
                    if (slot != state.target) {
                        state.target = slot
                        onSlot()
                    }
                },
                onDragEnd = { drop(state, latestShown, editor) },
                onDragCancel = { drop(state, latestShown, editor, commit = false) },
            )
        }
    } else {
        null
    }
    // One structure whatever the drag does, so the handle's gesture survives
    // the press: swipe-to-remove only pauses while a row is held.
    val dismiss = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                editor.onRemove(latestRow.index)
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = canRemove,
        gesturesEnabled = canRemove && !anyDrag,
        // Only a swipe shows the red: a held row's rounded corners would let it peek through.
        backgroundContent = { if (canRemove && !anyDrag) QueueRemoveBackdrop() },
        modifier = Modifier
            .animateItem(
                fadeInSpec = YoinMotion.effectsSpring(),
                // A placement glide would replay the drop from the old slot.
                placementSpec = if (anyDrag || state.pendingOrder != null) null else YoinMotion.spatialSpring(),
                fadeOutSpec = YoinMotion.effectsSpring(),
            )
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = if (dragging) state.offset else shiftShown },
    ) {
        Box(
            modifier = if (dragging) {
                Modifier
                    .shadow(8.dp, MaterialTheme.shapes.large)
                    .clip(MaterialTheme.shapes.large)
            } else {
                Modifier
            },
        ) {
            QueueTrackRow(
                row = row,
                current = false,
                isPlaying = false,
                onClick = onClick,
                handle = handle,
                lifted = dragging,
            )
        }
    }
}

/** The drop: the player moves the row, and the sheet shows the new order until the player reports it. */
private fun drop(state: QueueReorderState, shown: List<QueueRow>, editor: QueueEditActions, commit: Boolean = true) {
    val from = state.dragging
    val to = state.target
    if (commit && from >= 0 && to >= 0 && from != to && from in shown.indices && to in shown.indices) {
        val order = shown.map { it.item.entryId }.toMutableList()
        order.add(to, order.removeAt(from))
        state.pendingOrder = order
        editor.onMove(shown[from].index, shown[to].index)
    }
    state.dragging = -1
    state.target = -1
    state.offset = 0f
}

@Composable
private fun QueueRemoveBackdrop() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            imageVector = YoinSymbols.Delete,
            contentDescription = stringResource(R.string.cmp_queue_cd_remove),
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun QueueHeader(playingFrom: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 4.dp),
    ) {
        if (playingFrom != null) {
            Text(
                text = stringResource(R.string.cmp_queue_playing_from),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = playingFrom,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = stringResource(R.string.cmp_queue_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun QueueSectionLabel(text: String, action: (() -> Unit)? = null) {
    val clearLabel = stringResource(R.string.cmp_queue_clear)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp, top = 18.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            TextButton(onClick = action) { Text(clearLabel) }
        }
    }
}

/**
 * One song: cover, title over artist, and — where it can move — a drag
 * handle ([handle] carries the gesture). The current song reads in the
 * primary colour with the equalizer.
 */
@Composable
private fun QueueTrackRow(
    row: QueueRow,
    current: Boolean,
    isPlaying: Boolean,
    onClick: (() -> Unit)?,
    handle: Modifier? = null,
    // Held by a drag: the row lifts onto the brighter container.
    lifted: Boolean = false,
) {
    val item = row.item
    val scheme = MaterialTheme.colorScheme
    val playingDescription = stringResource(R.string.cmp_queue_cd_playing)
    val pausedDescription = stringResource(R.string.cmp_queue_cd_paused)
    val moveDescription = stringResource(R.string.cmp_queue_cd_move, item.title)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(QueueRowHeight)
            .background(if (lifted) scheme.surfaceContainerHighest else scheme.surfaceContainer)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 24.dp, end = if (handle != null) 8.dp else 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QueueCover(url = item.coverArtUrl, size = 48.dp)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = if (current) scheme.primary else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (current) {
            Icon(
                painter = rememberEqualizerSymbolPainter(playing = isPlaying),
                contentDescription = if (isPlaying) playingDescription else pausedDescription,
                tint = scheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        if (handle != null) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .then(handle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = YoinSymbols.DragHandle,
                    contentDescription = moveDescription,
                    tint = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun QueueCover(url: String?, size: Dp) {
    // Missing or failed art falls back to a note on the variant box, never a blank tile.
    var failed by remember(url) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(size)
            .clip(YoinArtworkShapes.Thumb)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null && !failed) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
                onError = { failed = true },
            )
        } else {
            Icon(
                imageVector = YoinSymbols.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Previews ────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun QueueTrackRowPreview() {
    YoinTheme {
        Column {
            QueueTrackRow(
                row = QueueRow(0, QueueItem(songId = "1", title = "Starlight", artist = "Muse", coverArtUrl = null)),
                current = true,
                isPlaying = true,
                onClick = null,
            )
            QueueTrackRow(
                row = QueueRow(1, QueueItem(songId = "2", title = "Supermassive Black Hole", artist = "Muse", coverArtUrl = null)),
                current = false,
                isPlaying = false,
                onClick = {},
                handle = Modifier,
            )
        }
    }
}
