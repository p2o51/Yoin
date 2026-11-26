package com.gpo.yoin.ui.notes.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.formatNotePosition
import com.gpo.yoin.ui.component.handPointer
import com.gpo.yoin.ui.detail.KeepAboveKeyboard
import com.gpo.yoin.ui.detail.journalRail
import com.gpo.yoin.ui.detail.userTextStyle

/**
 * The note card's colours: the listener's paper ([paper], [onPaper], [mark] for the moment, the rail and the
 * mentions) and the ink of its Done ([ink], [onInk]). The listener's own words are primary everywhere.
 */
@Immutable
internal data class NoteEditCardColors(
    val paper: Color,
    val onPaper: Color,
    val mark: Color,
    val ink: Color,
    val onInk: Color,
)

/** The listener's paper from the theme: primaryContainer, its ink primary (the scrapbook's note roles). */
@Composable
internal fun noteEditCardColors(): NoteEditCardColors {
    val scheme = MaterialTheme.colorScheme
    return NoteEditCardColors(
        paper = scheme.primaryContainer,
        onPaper = scheme.onPrimaryContainer,
        mark = scheme.onPrimaryContainer,
        ink = scheme.primary,
        onInk = scheme.onPrimary,
    )
}

/**
 * One note open for editing — the note menu. Its moment, the words in the note editor (@ mentions as chips,
 * list / to-do / heading shortcuts), the formatting row, and its actions under them: Delete (Cancel for a new
 * note), Align to now with the moment it would move to (only where a playhead exists, [nowMs]), and Done.
 * [onShare] puts Share as image in the corner beside the moment.
 *
 * The one note menu of the app: the album scrapbook's track sheet opens it in place of a note (and for a new
 * one), and a long press on a note in Now Playing opens it in a sheet (`NoteMenuSheet`). An emptied note
 * saved with Done is the host's delete.
 */
@Composable
internal fun NoteEditCard(
    editor: NoteEditorState,
    positionMs: Long?,
    colors: NoteEditCardColors,
    canDelete: Boolean,
    onDelete: () -> Unit,
    onDone: (String) -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
    nowMs: (() -> Long)? = null,
    onAlignToNow: ((text: String, atMs: Long) -> Unit)? = null,
    onShare: (() -> Unit)? = null,
) {
    val focus = remember { FocusRequester() }
    val actions = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) { if (autoFocus) runCatching { focus.requestFocus() } }
    KeepAboveKeyboard(actions)
    val at = positionMs?.let(::formatNotePosition)
    Column(
        modifier = modifier
            .fillMaxWidth()
            // The whole card (moment, formatting row, buttons) is the field, for @ mode.
            .noteEditorContainer(editor)
            .clip(NoteEditCardShape)
            .background(colors.paper)
            .journalRail(colors.mark)
            .padding(start = 24.dp, end = 14.dp, top = 10.dp, bottom = 12.dp),
    ) {
        if (at != null || onShare != null) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (at != null) NoteEditStamp(at, colors.mark)
                Spacer(Modifier.weight(1f))
                if (onShare != null) {
                    IconButton(
                        onClick = onShare,
                        modifier = Modifier.size(36.dp).handPointer().testTag(NoteEditShareTag),
                    ) {
                        Icon(
                            imageVector = YoinSymbols.Share,
                            contentDescription = stringResource(R.string.np_share_sheet_title),
                            tint = colors.onPaper,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        NoteMarkupField(
            state = editor,
            textStyle = userTextStyle(),
            // Mentions as the note draws them once saved: in the paper's mark.
            colors = noteEditorColors(
                text = colors.onPaper,
                chip = colors.mark.copy(alpha = 0.14f),
                chipText = colors.mark,
            ),
            placeholder = at?.let { stringResource(R.string.detail_scrap_note_hint, it) }
                ?: stringResource(R.string.detail_scrap_note_hint_plain),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .focusRequester(focus),
        )
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
                .height(2.dp)
                .background(colors.mark.copy(alpha = 0.6f)),
        )
        // The card is narrow on a phone: the formatting row scrolls on a line of its own, the buttons under it.
        NoteFormatToolbar(
            state = editor,
            modifier = Modifier
                .padding(top = 6.dp)
                .horizontalScroll(rememberScrollState()),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .bringIntoViewRequester(actions),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canDelete) {
                NoteEditButton(
                    label = stringResource(R.string.detail_scrap_delete),
                    icon = YoinSymbols.Delete,
                    container = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                    content = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )
            } else {
                TextButton(onClick = onDelete, contentPadding = NoteEditButtonPadding, modifier = Modifier.handPointer()) {
                    Text(stringResource(R.string.detail_scrap_cancel))
                }
            }
            // What Delete and Done leave: Align to now's words give way (ellipsis) before its moment does.
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (nowMs != null && onAlignToNow != null) {
                    AlignToNowButton(nowMs = nowMs, colors = colors, onClick = { at -> onAlignToNow(editor.text, at) })
                }
            }
            Button(
                onClick = { onDone(editor.text) },
                colors = ButtonDefaults.buttonColors(containerColor = colors.ink, contentColor = colors.onInk),
                contentPadding = NoteEditButtonPadding,
                modifier = Modifier.handPointer(),
            ) {
                Text(stringResource(R.string.detail_scrap_done), maxLines = 1)
            }
        }
    }
}

/** "Align to now" and the moment it would file the note at, read once a second (tabular digits). */
@Composable
private fun AlignToNowButton(nowMs: () -> Long, colors: NoteEditCardColors, onClick: (Long) -> Unit) {
    val latest by rememberUpdatedState(nowMs)
    val seconds by remember { derivedStateOf { latest() / 1_000L } }
    FilledTonalButton(
        onClick = { onClick(seconds * 1_000L) },
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
            contentColor = colors.onPaper,
        ),
        contentPadding = NoteEditButtonPadding,
        modifier = Modifier.handPointer().testTag(NoteEditAlignTag),
    ) {
        Text(
            text = stringResource(R.string.np_note_realign),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = formatNotePosition(seconds * 1_000L),
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            color = colors.onPaper.copy(alpha = 0.7f),
            maxLines = 1,
        )
    }
}

@Composable
internal fun NoteEditStamp(at: String, color: Color) {
    Text(
        text = at,
        style = MaterialTheme.typography.labelMedium.copy(
            fontFeatureSettings = "tnum",
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            letterSpacing = 0.3.sp,
        ),
        color = color,
    )
}

@Composable
private fun NoteEditButton(label: String, icon: ImageVector, container: Color, content: Color, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = container, contentColor = content),
        contentPadding = NoteEditButtonPadding,
        modifier = Modifier.handPointer(),
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1)
    }
}

private val NoteEditCardShape = RoundedCornerShape(16.dp)
private val NoteEditButtonPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)

internal const val NoteEditShareTag = "noteEditShare"
internal const val NoteEditAlignTag = "noteEditAlign"
