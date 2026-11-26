package com.gpo.yoin.ui.notes.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import com.gpo.yoin.shared.notes.MentionTrigger
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.experience.topBars
import kotlin.math.roundToInt
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
    enabled: Boolean = true,
    romanizer: MentionRomanizer = IcuMentionRomanizer,
) {
    val haptics = rememberYoinHaptics()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var fieldTopPx by remember { mutableIntStateOf(0) }
    val fieldBounds = remember { FieldBounds() }
    val output = remember(colors) { NoteEditorOutputTransformation(colors) }
    val trigger = state.trigger
    var rows by remember { mutableStateOf(MentionCandidates.Empty) }
    var loading by remember { mutableStateOf(false) }
    // Every row this editor has been offered (what's playing, recent plays, earlier searches): each letter
    // typed after the @ filters it at once, before the search for the new words answers.
    var pool by remember(candidates) { mutableStateOf(MentionCandidates.Empty) }
    val query = trigger?.query
    LaunchedEffect(query, candidates) {
        if (query == null) return@LaunchedEffect
        loading = true
        if (query.isNotEmpty()) {
            rows = mentionRowsWhileSearching(query, pool, romanizer)
            state.highlighted = 0
            delay(QueryDebounceMs)
        }
        val found = candidates.candidates(query, PopupRowsPerKind)
        pool = pool.merge(found)
        rows = mentionRowsAfterSearch(query, found, pool, romanizer)
        state.highlighted = 0
        loading = false
    }
    LaunchedEffect(state) {
        snapshotFlow { state.fieldState.text.toString() to state.fieldState.selection }.collect { state.syncDismissal() }
    }
    // Mention mode ends with the field's focus too (a tap elsewhere in the app): the popup itself never
    // closes on an outside touch, as a tap on the soft keyboard is one (MentionPopupProperties).
    var focused by remember { mutableStateOf(false) }
    val popupOpen = mentionPopupOpen(mentionPopup, trigger, focused, state.searchOpen)
    // What a soft keyboard's Enter picks (its newline becomes this mention).
    // Read here, in composition, so new rows or a moved highlight hand Enter the row now on top.
    val enterPick = if (popupOpen) rows.all.getOrNull(state.highlighted)?.mention else null
    // A tap anywhere else in this window ends mention mode (the keyboard's and the popup's own touches never
    // arrive here: they are other windows).
    val windowTaps = LocalWindowTaps.current
    DisposableEffect(windowTaps, popupOpen, state) {
        val stop = if (windowTaps != null && popupOpen) {
            windowTaps.listen { at ->
                val container = state.containerCoordinates?.takeIf { it.isAttached }?.boundsInWindow()
                if (tapEndsMention(fieldBounds.inWindow(), at, container)) state.dismissPopup()
            }
        } else {
            null
        }
        onDispose { stop?.invoke() }
    }
    SideEffect { state.enterPick = enterPick }
    val pick: (com.gpo.yoin.shared.notes.NoteMention) -> Unit = { mention ->
        haptics.performConfirm()
        state.insertMention(mention)
    }

    BasicTextField(
        state = state.fieldState,
        enabled = enabled,
        modifier = modifier.onFocusChanged { focused = it.isFocused }.onPreviewKeyEvent { event ->
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
        inputTransformation = state.inputTransformation,
        // A keyboard that sends Enter as an editor action instead of a newline: the same pick while @ is open.
        onKeyboardAction = { performDefault ->
            val row = enterPick
            if (popupOpen && row != null) pick(row) else performDefault()
        },
        outputTransformation = output,
        onTextLayout = { result ->
            result()?.let {
                layout = it
                onTextLayout(it)
            }
        },
        decorator = TextFieldDecorator { inner ->
            Box(
                modifier = Modifier.onGloballyPositioned {
                    fieldTopPx = it.positionInWindow().y.roundToInt()
                    fieldBounds.coordinates = it
                },
            ) {
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
                if (popupOpen && trigger != null) {
                    val caret = remember(trigger, layout) { caretRect(state, trigger, layout) }
                    // Clear of the keyboard and the status bar; the rows shrink to the roomier side if need be.
                    val density = LocalDensity.current
                    val bottomLimit = LocalWindowInfo.current.containerSize.height - WindowInsets.ime.getBottom(density)
                    val topLimit = WindowInsets.topBars.getTop(density)
                    val listMax = with(density) {
                        mentionPopupListMax(
                            caretTop = fieldTopPx + caret.top.roundToInt(),
                            caretBottom = fieldTopPx + caret.bottom.roundToInt(),
                            topLimit = topLimit,
                            bottomLimit = bottomLimit,
                            margin = PopupMargin.roundToPx(),
                            chrome = PopupChrome.roundToPx(),
                            min = PopupMinListHeight.roundToPx(),
                            max = PopupMaxListHeight.roundToPx(),
                        ).toDp()
                    }
                    MentionPopup(
                        listMaxHeight = listMax,
                        topLimitPx = topLimit,
                        bottomLimitPx = bottomLimit,
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

/** The pool filtered for [query] at once (rows the account already knew drop out when they don't match). */
internal fun mentionRowsWhileSearching(query: String, pool: MentionCandidates, romanizer: MentionRomanizer): MentionCandidates =
    MentionRanking.rank(query, pool.withoutSearchRows(), romanizer).capped()

/** The search's rows for [query], with any row from [pool] that matches better ranked in among them. */
internal fun mentionRowsAfterSearch(
    query: String,
    found: MentionCandidates,
    pool: MentionCandidates,
    romanizer: MentionRomanizer,
): MentionCandidates {
    if (query.isEmpty()) return found
    return MentionRanking.rank(query, found.merge(pool.withoutSearchRows()), romanizer).capped()
}

/** An earlier search's rows: listed after the current search's, and only while they still match. */
private fun MentionCandidates.withoutSearchRows(): MentionCandidates {
    fun List<MentionCandidate>.known() = map {
        if (it.source == MentionCandidate.Source.Search) it.copy(source = MentionCandidate.Source.Seen) else it
    }
    return copy(tracks = tracks.known(), albums = albums.known(), artists = artists.known())
}

private fun MentionCandidates.capped(): MentionCandidates =
    copy(tracks = tracks.take(PopupRowsPerKind), albums = albums.take(PopupRowsPerKind), artists = artists.take(PopupRowsPerKind))

/** Where the field is laid out, read when a tap lands (not state: nothing redraws for it). */
private class FieldBounds {
    var coordinates: LayoutCoordinates? = null

    fun inWindow(): Rect? = coordinates?.takeIf { it.isAttached }?.boundsInWindow()
}

/** The @ popup shows while an `@` is typed at the caret of the focused field (and the search sheet isn't up). */
internal fun mentionPopupOpen(enabled: Boolean, trigger: MentionTrigger?, focused: Boolean, searchOpen: Boolean): Boolean =
    enabled && trigger != null && focused && !searchOpen

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
