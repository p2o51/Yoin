package com.gpo.yoin.ui.notes.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import kotlinx.coroutines.delay

/** Where editors get their @ rows (the active account's library and recent plays); none in previews. */
val LocalMentionCandidates = staticCompositionLocalOf<MentionCandidateSource> { MentionCandidateSource.None }

/** The editor's span colours (the chip's tint follows the host's palette). */
data class NoteEditorColors(
    val text: Color,
    val chip: Color,
    val chipText: Color,
    val marker: Color,
)

@Composable
fun noteEditorColors(
    text: Color = MaterialTheme.colorScheme.onSurface,
    chip: Color = MaterialTheme.colorScheme.secondaryContainer,
    chipText: Color = MaterialTheme.colorScheme.onSecondaryContainer,
): NoteEditorColors = NoteEditorColors(text = text, chip = chip, chipText = chipText, marker = text.copy(alpha = 0.38f))

/**
 * The note editor's field: the stored text through [NoteEditorInputTransformation] (atomic mentions, list
 * continuation, shortcuts) and shown through [NoteEditorDisplay] (mention chips, list glyphs, styled
 * Markdown). Typing `@` opens the caret-anchored [MentionPopup] (↑ ↓ / Enter / Tab / Esc on a keyboard);
 * its "展开搜索" opens [MentionSearchSheet]. The host draws the frame; [decorator] wraps the inner field.
 */
@Composable
fun NoteMarkupField(
    state: NoteEditorState,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    colors: NoteEditorColors = noteEditorColors(),
    placeholder: String? = null,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.MultiLine(),
    onTextLayout: (TextLayoutResult) -> Unit = {},
    candidates: MentionCandidateSource = LocalMentionCandidates.current,
    // False: no @ popup of its own (a Tips demo draws its rows in the phone).
    mentionPopup: Boolean = true,
) {
    val haptics = rememberYoinHaptics()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val output = remember(colors) { NoteEditorOutputTransformation(colors) }
    val trigger = state.trigger
    var rows by remember { mutableStateOf(MentionCandidates.Empty) }
    var loading by remember { mutableStateOf(false) }
    val query = trigger?.query
    LaunchedEffect(query, candidates) {
        if (query == null) return@LaunchedEffect
        loading = true
        if (query.isNotEmpty()) delay(QueryDebounceMs)
        rows = candidates.candidates(query, PopupRowsPerKind)
        state.highlighted = 0
        loading = false
    }
    val popupOpen = mentionPopup && trigger != null && !state.searchOpen
    val pick: (com.gpo.yoin.shared.notes.NoteMention) -> Unit = { mention ->
        haptics.performConfirm()
        state.insertMention(mention)
    }

    BasicTextField(
        state = state.fieldState,
        modifier = modifier.onPreviewKeyEvent { event ->
            if (!popupOpen || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val all = rows.all
            when (event.key) {
                Key.DirectionDown -> {
                    if (all.isNotEmpty()) state.highlighted = (state.highlighted + 1) % all.size
                    true
                }
                Key.DirectionUp -> {
                    if (all.isNotEmpty()) state.highlighted = (state.highlighted - 1 + all.size) % all.size
                    true
                }
                Key.Enter, Key.NumPadEnter, Key.Tab -> {
                    val row = all.getOrNull(state.highlighted) ?: return@onPreviewKeyEvent false
                    pick(row.mention)
                    true
                }
                Key.Escape -> {
                    state.dismissPopup()
                    true
                }
                else -> false
            }
        },
        textStyle = textStyle.copy(color = colors.text),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        lineLimits = lineLimits,
        inputTransformation = NoteEditorInputTransformation,
        outputTransformation = output,
        onTextLayout = { result ->
            result()?.let {
                layout = it
                onTextLayout(it)
            }
        },
        decorator = TextFieldDecorator { inner ->
            Box {
                if (placeholder != null && state.fieldState.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = textStyle,
                        color = colors.text.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
                if (popupOpen) {
                    val caret = remember(trigger, layout) { caretRect(state, trigger, layout) }
                    MentionPopup(
                        anchor = caret,
                        rows = rows,
                        loading = loading,
                        query = trigger.query,
                        highlighted = state.highlighted,
                        onPick = pick,
                        onExpand = {
                            haptics.performTick()
                            state.searchOpen = true
                        },
                        onDismiss = state::dismissPopup,
                    )
                }
            }
        },
    )
    if (state.searchOpen) {
        MentionSearchSheet(
            initialQuery = trigger?.query.orEmpty(),
            candidates = candidates,
            onPick = pick,
            onDismiss = { state.searchOpen = false },
        )
    }
}

private fun caretRect(state: NoteEditorState, trigger: MentionTrigger, layout: TextLayoutResult?): Rect {
    val result = layout ?: return Rect.Zero
    val display = NoteEditorDisplay.of(state.text).toDisplay(trigger.start)
    return result.getCursorRect(display.coerceIn(0, result.layoutInput.text.length))
}

private const val QueryDebounceMs = 160L
private const val PopupRowsPerKind = 5

/** The stored text as the editor shows it: [NoteEditorDisplay]'s replacements and spans. */
internal class NoteEditorOutputTransformation(private val colors: NoteEditorColors) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        val display = NoteEditorDisplay.of(asCharSequence().toString())
        display.replacements.asReversed().forEach { replace(it.start, it.end, it.shown) }
        display.styles.forEach { range ->
            val style = when (range.kind) {
                NoteEditorDisplay.Kind.Chip -> SpanStyle(
                    color = colors.chipText,
                    background = colors.chip,
                    fontWeight = FontWeight.Medium,
                )
                NoteEditorDisplay.Kind.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
                NoteEditorDisplay.Kind.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
                NoteEditorDisplay.Kind.Heading -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.1.em)
                NoteEditorDisplay.Kind.Marker -> SpanStyle(color = colors.marker)
                NoteEditorDisplay.Kind.ListGlyph -> SpanStyle(color = colors.chipText, fontWeight = FontWeight.Bold)
                NoteEditorDisplay.Kind.DoneLine -> SpanStyle(color = colors.marker, textDecoration = TextDecoration.LineThrough)
            }
            try {
                addStyle(style, range.start.coerceIn(0, length), range.end.coerceIn(0, length))
            } catch (_: IllegalStateException) {
                // Styling unavailable here: the words still show, unstyled.
            } catch (_: IllegalArgumentException) {
                // A range the buffer no longer holds (a race with the IME): skip it.
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is NoteEditorOutputTransformation && other.colors == colors

    override fun hashCode(): Int = colors.hashCode()
}
