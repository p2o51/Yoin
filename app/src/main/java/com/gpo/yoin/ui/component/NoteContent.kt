package com.gpo.yoin.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinShapeTokens
import java.util.concurrent.TimeUnit
import java.text.DateFormat
import java.util.Date

/**
 * How a track's notes are ordered in the Note panes.
 * - [Timeline]: by the song-position anchor ([SongNote.positionMs]),
 *   un-anchored notes last — for call-guide / listen-along reading.
 * - [Created]: by when the user wrote them (oldest first) — a journal.
 */
enum class NoteSortMode { Timeline, Created }

fun sortNotes(notes: List<SongNote>, mode: NoteSortMode): List<SongNote> = when (mode) {
    NoteSortMode.Timeline -> notes.sortedWith(
        compareBy<SongNote, Long?>(nullsLast()) { it.positionMs }.thenBy { it.createdAt },
    )
    NoteSortMode.Created -> notes.sortedBy { it.createdAt }
}

/**
 * The note the playhead is currently "inside": the latest anchored note whose
 * position is at or before [positionMs] — same rule as the lyrics current
 * line. Returns null when nothing is anchored yet or the playhead sits before
 * the first anchor. Order of [notes] doesn't matter.
 */
fun currentAnchoredNoteId(notes: List<SongNote>, positionMs: Long): String? {
    var best: SongNote? = null
    for (note in notes) {
        val anchor = note.positionMs ?: continue
        if (anchor <= positionMs && (best?.positionMs ?: -1L) <= anchor) best = note
    }
    return best?.id
}

fun formatNotePosition(ms: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0L))
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * Individual note row. Shared between the compact preview pane and the
 * fullscreen editable pane.
 *
 * [isActive] mirrors the lyrics current-line treatment: the accent rail and
 * the position stamp light up and the card washes toward the primary
 * container while the playhead is inside this note's stretch of the song.
 * [onClick] (when the note is anchored) seeks to the note's moment.
 */
@Composable
fun NoteCard(
    note: SongNote,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    onDelete: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val haptics = rememberYoinHaptics()
    val railColor by animateColorAsState(
        targetValue = if (isActive) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "noteRail",
    )
    val containerColor by animateColorAsState(
        targetValue = if (isActive) {
            lerp(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                MaterialTheme.colorScheme.primaryContainer,
                0.45f,
            )
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "noteContainer",
    )
    val metaColor by animateColorAsState(
        targetValue = if (isActive) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "noteMeta",
    )
    val clickInteraction = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.noRippleClickable(interactionSource = clickInteraction) {
                        haptics.performTick()
                        onClick()
                    }
                } else {
                    Modifier
                },
            ),
        shape = YoinShapeTokens.Large,
        color = containerColor,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .height(IntrinsicSize.Min)
                .padding(
                    start = 14.dp,
                    end = if (onDelete != null) 6.dp else 16.dp,
                    top = 14.dp,
                    bottom = 14.dp,
                ),
            verticalAlignment = Alignment.Top,
        ) {
            // Journal-style accent rail down the left edge — turns a flat grey box
            // into something that reads as a written note.
            Box(
                modifier = Modifier
                    .padding(top = 2.dp, bottom = 2.dp, end = 14.dp)
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(railColor),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = note.content,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.size(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    note.positionMs?.let { anchor ->
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = metaColor,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = formatNotePosition(anchor),
                            style = MaterialTheme.typography.labelSmall,
                            color = metaColor,
                        )
                        Text(
                            text = "  ·  ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = formatRelativeTime(note.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onDelete != null) {
                IconButton(
                    onClick = {
                        haptics.performReject()
                        onDelete()
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "Delete note",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * The Timeline ⇄ Created order switch, shared by the compact and fullscreen
 * Note panes: a micro segmented pill in the product's capsule language.
 */
@Composable
fun NoteSortToggle(
    mode: NoteSortMode,
    onModeChange: (NoteSortMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    Surface(
        modifier = modifier,
        shape = YoinShapeTokens.Full,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
    ) {
        Row(modifier = Modifier.padding(3.dp)) {
            NoteSortOption(
                label = "时间线",
                selected = mode == NoteSortMode.Timeline,
                onClick = {
                    if (mode != NoteSortMode.Timeline) {
                        haptics.performTick()
                        onModeChange(NoteSortMode.Timeline)
                    }
                },
            )
            NoteSortOption(
                label = "先后",
                selected = mode == NoteSortMode.Created,
                onClick = {
                    if (mode != NoteSortMode.Created) {
                        haptics.performTick()
                        onModeChange(NoteSortMode.Created)
                    }
                },
            )
        }
    }
}

@Composable
private fun NoteSortOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val container by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0f)
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "noteSortOption",
    )
    val content by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "noteSortOptionText",
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .clip(YoinShapeTokens.Full)
            .background(container)
            .noRippleClickable(interactionSource = interaction, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
    }
}

/**
 * Inline "write a new note" composer, in the product's note language: a
 * filled Large-radius writing surface carrying the same journal accent rail
 * as [NoteCard] (it brightens with focus), a song-moment anchor chip, and a
 * capsule save pill. No outlined borders — those read as stock forms.
 *
 * The anchor is captured from [positionMs] the moment the field gains focus
 * with an empty draft (= the moment the user decides to write). Tapping the
 * chip toggles: anchored → un-anchored → re-captured at the current playhead.
 */
@Composable
fun NoteComposer(
    onSave: (String, Long?) -> Unit,
    positionMs: () -> Long,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
) {
    var draft by remember { mutableStateOf("") }
    var anchorMs by remember { mutableStateOf<Long?>(null) }
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val haptics = rememberYoinHaptics()

    if (autoFocus) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
    }

    val railColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.primary.copy(alpha = if (focused) 1f else 0.4f),
        animationSpec = YoinMotion.effectsSpring(),
        label = "composerRail",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = YoinShapeTokens.Large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .height(IntrinsicSize.Min)
                    .padding(start = 14.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp, bottom = 2.dp, end = 14.dp)
                        .width(3.dp)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(railColor),
                )
                Box(modifier = Modifier.weight(1f)) {
                    if (draft.isEmpty()) {
                        Text(
                            text = "这首歌让你想到什么？",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 88.dp)
                            .focusRequester(focusRequester)
                            .onFocusChanged { state ->
                                focused = state.isFocused
                                // The moment writing begins is the moment the
                                // note means — snapshot the playhead then.
                                if (state.isFocused && draft.isEmpty()) {
                                    anchorMs = positionMs()
                                }
                            },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 10,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NoteAnchorChip(
                anchorMs = anchorMs,
                onToggle = {
                    haptics.performTick()
                    anchorMs = if (anchorMs != null) null else positionMs()
                },
            )
            NoteSavePill(
                enabled = draft.isNotBlank(),
                onClick = {
                    val trimmed = draft.trim()
                    if (trimmed.isNotEmpty()) {
                        haptics.performConfirm()
                        onSave(trimmed, anchorMs)
                        draft = ""
                        anchorMs = null
                    }
                },
            )
        }
    }
}

/**
 * Song-moment anchor for the note being written. Anchored = tinted capsule
 * showing the captured m:ss; tap clears it; tap again re-captures "now".
 */
@Composable
private fun NoteAnchorChip(
    anchorMs: Long?,
    onToggle: () -> Unit,
) {
    val anchored = anchorMs != null
    val container by animateColorAsState(
        targetValue = if (anchored) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "anchorChip",
    )
    val content = if (anchored) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    }
    val interaction = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier.noRippleClickable(interactionSource = interaction, onClick = onToggle),
        shape = YoinShapeTokens.Full,
        color = container,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = if (anchored) "取消时间点" else "标记时间点",
                tint = content,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = anchorMs?.let(::formatNotePosition) ?: "不标时间点",
                style = MaterialTheme.typography.labelMedium,
                color = content,
            )
        }
    }
}

@Composable
private fun NoteSavePill(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val container by animateColorAsState(
        targetValue = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "savePill",
    )
    val content = if (enabled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    val interaction = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier.noRippleClickable(
            interactionSource = interaction,
            enabled = enabled,
            onClick = onClick,
        ),
        shape = YoinShapeTokens.Full,
        color = container,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Send,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "记下",
                style = MaterialTheme.typography.labelMedium,
                color = content,
            )
        }
    }
}

private fun formatRelativeTime(epochMs: Long): String {
    val now = System.currentTimeMillis()
    val delta = (now - epochMs).coerceAtLeast(0L)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    val hours = TimeUnit.MILLISECONDS.toHours(delta)
    val days = TimeUnit.MILLISECONDS.toDays(delta)
    return when {
        minutes < 1L -> "刚刚"
        minutes < 60L -> "$minutes 分钟前"
        hours < 24L -> "$hours 小时前"
        days < 7L -> "$days 天前"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))
    }
}

/**
 * Lightweight modal sheet that wraps [NoteComposer] for the Write pill in
 * Now Playing. Mirrors the Devices / Queue sheet pattern so the bottom row
 * of pills behaves consistently — tap to open a sheet, save or dismiss.
 *
 * The text field auto-focuses on open so the keyboard is up right away —
 * which also snapshots the playhead as the note's song-moment anchor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WriteNoteSheet(
    onSave: (String, Long?) -> Unit,
    positionMs: () -> Long,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    trackTitle: String? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = {
            BottomSheetDefaults.modalWindowInsets.only(
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
            )
        },
        modifier = modifier,
    ) {
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(
                    start = 24.dp,
                    end = 24.dp,
                    top = 8.dp,
                    bottom = 16.dp + navBottom,
                ),
        ) {
            Text(
                text = "记笔记",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = trackTitle?.takeIf { it.isNotBlank() }?.let { "写给《$it》的此刻" }
                    ?: "写下这首歌让你想到的",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.size(14.dp))
            NoteComposer(
                onSave = { text, anchor ->
                    onSave(text, anchor)
                    onDismiss()
                },
                positionMs = positionMs,
                autoFocus = true,
            )
        }
    }
}
