package com.gpo.yoin.ui.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.gpo.yoin.R
import com.gpo.yoin.data.album.AlbumScrapbookAboutRef
import com.gpo.yoin.data.album.AlbumScrapbookAsk
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.RatingSlider
import com.gpo.yoin.ui.component.markdownBoldAnnotatedString
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole

/*
 * Page 2's track sheet (owner 2026-10-29: manage the AI content and the scores in the scrapbook). One sheet
 * per track: its score on the same horizontal bar as the album's rate sheet, then every question and About
 * fact in full, each with its own Edit. Opened by long-pressing a ticket, or by tapping the track's question
 * card, About card or a fact tag (which used to open a read-only full-text sheet).
 */

/** The track a sheet is about, and the Ask / About it holds (null: a score-only ticket). */
@Immutable
internal data class ScrapTrackSheetTarget(val track: ScrapTrack, val about: ScrapAboutSheet?)

/** The first piece on the page holding [songId]'s ticket; null once it has left the page. */
internal fun AlbumScrapbook.trackSheetFor(songId: String): ScrapTrackSheetTarget? {
    for (piece in pieces) {
        when (piece) {
            is ScrapPiece.TrackCluster ->
                if (piece.track.songId == songId) return ScrapTrackSheetTarget(piece.track, piece.sheet)
            is ScrapPiece.ContactSheet ->
                piece.tracks.firstOrNull { it.songId == songId }?.let { return ScrapTrackSheetTarget(it, null) }
            else -> Unit
        }
    }
    return null
}

@Composable
internal fun ScrapTrackSheet(
    target: ScrapTrackSheetTarget,
    colors: ScrapbookColors,
    onEdit: (ScrapEdit) -> Unit,
    onDismiss: () -> Unit,
    // A row just removed, with the text that brings it back ("Deleted · Undo", the way Google Photos does it).
    onRemoved: (restore: ScrapEdit.About) -> Unit = {},
    snackbar: SnackbarHostState? = null,
) {
    val track = target.track
    // The bar's own value; saved when the finger lifts, like the album's rate sheet.
    var live by remember(track.songId) { mutableFloatStateOf(track.score ?: 0f) }
    var saved by remember(track.songId) { mutableFloatStateOf(track.score ?: 0f) }
    fun commit(score: Float) {
        if (score == saved) return
        saved = score
        onEdit(ScrapEdit.Score(track.songId, score))
    }
    // One row at a time is open for editing.
    var editing by remember(track.songId) { mutableStateOf<AlbumScrapbookAboutRef?>(null) }
    // [wasTitle] / [wasAnswer]: the row as it stood, for Undo when this edit removes it.
    fun save(ref: AlbumScrapbookAboutRef, title: String?, answer: String, wasTitle: String?, wasAnswer: String) {
        editing = null
        onEdit(ScrapEdit.About(ref, title, answer))
        if (answer.isBlank()) onRemoved(ScrapEdit.About(ref, wasTitle, wasAnswer))
    }

    ScrapSheet(onDismiss = onDismiss) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
                    // A removed question folds away instead of the sheet snapping shorter.
                    .animateContentSize(YoinMotion.defaultSpatialSpec()),
            ) {
                AlbumSectionLabel(text = "${track.number.toString().padStart(2, '0')}  ${track.title}")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        // The button's height, held when it fades out: clearing a score must not shift the sheet.
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.detail_scrap_score),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.ink,
                        modifier = Modifier.weight(1f),
                    )
                    AnimatedVisibility(
                        visible = live > 0f,
                        enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard),
                        exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard),
                    ) {
                        TextButton(
                            onClick = {
                                live = 0f
                                commit(0f)
                            },
                        ) {
                            Text(stringResource(R.string.detail_scrap_clear_score))
                        }
                    }
                }
                RatingSlider(
                    rating = live,
                    onRatingChange = { live = it },
                    orientation = Orientation.Horizontal,
                    // The sheet scrolls and drags away: a swipe across the bar is the sheet's, not a score.
                    claimOnDown = false,
                    onInteractionChange = { active -> if (!active) commit(live) },
                    gestureKey = track.songId,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
                target.about?.asks?.forEachIndexed { i, ask ->
                    key(ask.ref ?: ask.question) {
                        AskEntry(
                            ask = ask,
                            first = i == 0,
                            editing = ask.ref != null && ask.ref == editing,
                            onStartEdit = { editing = ask.ref },
                            onCancel = { editing = null },
                            onSave = { title, answer ->
                                ask.ref?.let { save(it, title, answer, ask.title, ask.answer) }
                            },
                        )
                    }
                }
                target.about?.facts?.forEach { fact ->
                    key(fact.ref ?: fact.label) {
                        FactEntry(
                            fact = fact,
                            colors = colors,
                            editing = fact.ref != null && fact.ref == editing,
                            onStartEdit = { editing = fact.ref },
                            onCancel = { editing = null },
                            onSave = { answer ->
                                fact.ref?.let { save(it, null, answer, null, fact.value) }
                            },
                        )
                    }
                }
            }
            // Over the sheet's foot while it is open; the page shows the same host once it closes.
            snackbar?.let { host ->
                SnackbarHost(
                    hostState = host,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                )
            }
        }
    }
}

/** A question in full: the listener's words, then Gemini's title and answer, or the editor for them. */
@Composable
private fun AskEntry(
    ask: AlbumScrapbookAsk,
    first: Boolean,
    editing: Boolean,
    onStartEdit: () -> Unit,
    onCancel: () -> Unit,
    onSave: (title: String?, answer: String) -> Unit,
) {
    Column(modifier = Modifier.padding(top = if (first) 20.dp else 28.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = ask.question,
                style = userTextStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 6.dp),
            )
            EditButton(visible = ask.ref != null && !editing, onClick = onStartEdit)
        }
        AnimatedContent(
            targetState = editing,
            transitionSpec = {
                YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                    YoinMotion.fadeOut(role = YoinMotionRole.Standard)
            },
            label = "scrapAskEdit",
        ) { open ->
            if (open) {
                AboutEditor(
                    title = ask.title.orEmpty(),
                    withTitle = true,
                    text = ask.answer,
                    onCancel = onCancel,
                    onSave = onSave,
                )
            } else {
                Column {
                    ask.title?.let { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    Text(
                        text = markdownBoldAnnotatedString(ask.answer),
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 1.6.em),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/** One About fact in full (label, value), or the editor for its value. */
@Composable
private fun FactEntry(
    fact: ScrapTag,
    colors: ScrapbookColors,
    editing: Boolean,
    onStartEdit: () -> Unit,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = scrapFactLabel(fact.label),
                style = MaterialTheme.typography.titleSmall,
                color = colors.ink,
                modifier = Modifier.weight(1f),
            )
            EditButton(visible = fact.ref != null && !editing, onClick = onStartEdit)
        }
        AnimatedContent(
            targetState = editing,
            transitionSpec = {
                YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                    YoinMotion.fadeOut(role = YoinMotionRole.Standard)
            },
            label = "scrapFactEdit",
        ) { open ->
            if (open) {
                AboutEditor(
                    title = "",
                    withTitle = false,
                    text = fact.value,
                    onCancel = onCancel,
                    onSave = { _, answer -> onSave(answer) },
                )
            } else {
                Text(
                    text = markdownBoldAnnotatedString(fact.value),
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 1.5.em),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Keeps its slot while hidden, so the row beside it never jumps when an editor opens. */
@Composable
private fun EditButton(visible: Boolean, onClick: () -> Unit) {
    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = visible,
            enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard),
            exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard),
        ) {
            IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = YoinSymbols.Edit,
                    contentDescription = stringResource(R.string.detail_scrap_cd_edit),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * The listener's version of an AI text. Save keeps what is in the fields (an emptied answer removes the row,
 * the same as Delete); Delete removes it from the page and from Now Playing's About.
 */
@Composable
private fun AboutEditor(
    title: String,
    withTitle: Boolean,
    text: String,
    onCancel: () -> Unit,
    onSave: (title: String?, answer: String) -> Unit,
) {
    val titleField = rememberTextFieldState(title)
    val textField = rememberTextFieldState(text)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        textField.edit { placeCursorAtEnd() }
        focus.requestFocus()
    }
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (withTitle) {
            EditField(
                state = titleField,
                hint = stringResource(R.string.detail_scrap_edit_title_hint),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                singleLine = true,
            )
        }
        EditField(
            state = textField,
            hint = stringResource(R.string.detail_scrap_edit_text_hint),
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 1.5.em),
            modifier = Modifier.focusRequester(focus),
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { onSave(null, "") },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(imageVector = YoinSymbols.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.detail_scrap_delete))
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.detail_scrap_cancel))
            }
            FilledTonalButton(
                onClick = {
                    onSave(
                        titleField.text.toString().takeIf { withTitle },
                        textField.text.toString(),
                    )
                },
            ) {
                Text(stringResource(R.string.detail_scrap_save))
            }
        }
    }
}

@Composable
private fun EditField(
    state: TextFieldState,
    hint: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    BasicTextField(
        state = state,
        textStyle = style.copy(color = scheme.onSurface),
        cursorBrush = SolidColor(scheme.primary),
        lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = modifier.fillMaxWidth(),
        decorator = { field ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(scheme.surfaceContainerHigh)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (state.text.isEmpty()) {
                    Text(text = hint, style = style, color = scheme.onSurfaceVariant)
                }
                field()
            }
        },
    )
}
