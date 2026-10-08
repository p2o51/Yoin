package com.gpo.yoin.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

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

/** How loudly a [NoteLine] is drawn. */
enum class NoteLineEmphasis {
    /**
     * The collapsed window: lines read at a glance, like the compact lyrics —
     * strong distance falloff and the lyric shrink away from the current line.
     */
    Glance,

    /** The expanded Note page: every line stays legible; the current one lights up. */
    Page,
}

/**
 * Row opacity. [distance] is how many rows away the current note is; null
 * when the playhead is inside no note at all.
 */
internal fun noteLineAlpha(isActive: Boolean, distance: Int?, emphasis: NoteLineEmphasis): Float = when {
    isActive -> 1f
    emphasis == NoteLineEmphasis.Page -> if (distance == null) 0.86f else 0.62f
    distance == null -> 0.28f
    distance <= 1 -> 0.55f
    distance == 2 -> 0.40f
    else -> 0.28f
}

/** Fixed width of the time-stamp column, so every note's words start on one edge. */
val NoteStampColumnWidth = 40.dp

/**
 * One note, read like a lyric line: its song moment in a fixed stamp column,
 * then the words. The line the playhead is inside ([isActive]) lights up in
 * primary; the others recede by [emphasis]. A legacy note with no moment
 * shows a small hollow dot in the stamp column. [meta] is an optional small
 * line under the words (the date, in the journal order).
 *
 * Shared by the collapsed Note window and the expanded Note page, so a note
 * looks the same in both — no cards, no rail, no per-row buttons.
 */
@Composable
fun NoteLine(
    stamp: String?,
    text: String,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    emphasis: NoteLineEmphasis = NoteLineEmphasis.Page,
    distance: Int? = 0,
    meta: String? = null,
) {
    val glance = emphasis == NoteLineEmphasis.Glance
    val scheme = MaterialTheme.colorScheme
    // Colours are read at draw time (ColorProducer): a highlight hand-over
    // repaints the two lines involved, it doesn't recompose them per frame.
    val textColor = animateColorAsState(
        targetValue = when {
            isActive -> scheme.primary
            glance -> scheme.onSurfaceVariant
            else -> scheme.onSurface
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "noteLineColor",
    )
    val stampColor = animateColorAsState(
        targetValue = if (isActive) scheme.primary else scheme.onSurfaceVariant,
        animationSpec = YoinMotion.effectsSpring(),
        label = "noteLineStamp",
    )
    val alpha = animateFloatAsState(
        targetValue = noteLineAlpha(isActive, distance, emphasis),
        animationSpec = YoinMotion.effectsSpring(),
        label = "noteLineAlpha",
    )
    val scale = animateFloatAsState(
        targetValue = when {
            isActive -> 1f
            glance -> 0.94f
            else -> 0.97f
        },
        animationSpec = YoinMotion.spatialSpring(),
        label = "noteLineScale",
    )
    val bodyStyle = when {
        glance && !isActive -> MaterialTheme.typography.bodyMedium
        else -> MaterialTheme.typography.bodyLarge
    }
    val stampStyle = if (glance) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
    val firstLineHeight = with(LocalDensity.current) { bodyStyle.lineHeight.toDp() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = if (glance) 4.dp else 6.dp)
            .graphicsLayer {
                this.alpha = alpha.value
                scaleX = scale.value
                scaleY = scale.value
                transformOrigin = TransformOrigin(0f, 0.5f)
            },
        verticalAlignment = Alignment.Top,
    ) {
        if (stamp != null) {
            BasicText(
                text = stamp,
                style = stampStyle.copy(
                    fontFeatureSettings = "tnum",
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = { stampColor.value },
                maxLines = 1,
                modifier = Modifier
                    .width(NoteStampColumnWidth)
                    .alignByBaseline(),
            )
        } else {
            // No moment (a legacy note): a quiet hollow dot on the first line.
            Box(
                modifier = Modifier
                    .width(NoteStampColumnWidth)
                    .height(firstLineHeight),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(6.dp)
                        .border(width = 1.5.dp, color = scheme.onSurfaceVariant, shape = CircleShape),
                )
            }
        }
        Column(modifier = Modifier.weight(1f).alignByBaseline()) {
            BasicText(
                text = text,
                style = bodyStyle.copy(
                    fontWeight = when {
                        glance && isActive -> FontWeight.Bold
                        glance -> FontWeight.Medium
                        isActive -> FontWeight.SemiBold
                        else -> FontWeight.Normal
                    },
                ),
                color = { textColor.value },
            )
            if (meta != null) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * The Timeline ⇄ Created order switch on the expanded Note page: a micro
 * segmented pill in the product's capsule language.
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
                label = stringResource(R.string.cmp_note_sort_timeline),
                selected = mode == NoteSortMode.Timeline,
                onClick = {
                    if (mode != NoteSortMode.Timeline) {
                        haptics.performTick()
                        onModeChange(NoteSortMode.Timeline)
                    }
                },
            )
            NoteSortOption(
                label = stringResource(R.string.cmp_note_sort_created),
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

/** Sizes of [NoteWriteBar] the host lays its slot out with. */
object NoteWriteBarDefaults {
    /** The resting capsule. */
    val IdleHeight = 56.dp

    /** Space the host leaves above the bar inside its accessory slot. */
    val TopGap = 12.dp

    internal val IdleCorner = 28.dp
    internal val OpenCorner = 20.dp
    internal val PaddingStart = 10.dp
    internal val PaddingEnd = 14.dp
    internal val PaddingVertical = 10.dp
    internal val ItemGap = 10.dp
    internal val FooterGap = 8.dp
    internal val FooterInset = 4.dp
    internal const val OpenMinLines = 2
    internal const val OpenMaxLines = 6
    internal const val InlineMaxLines = 3
}

/**
 * The write bar's state, hoisted to the Now Playing layout that hosts it: the
 * Write pill asks it for the keyboard ([requestWriting]), the layout reads
 * [open] and [height] to size the accessory slot and to step the title aside.
 * The words themselves live in [draftState] (the screen-wide draft).
 */
@Stable
class NoteWriteBarState internal constructor(val draftState: NoteDraftState) {
    /** The field holds focus. */
    var focused: Boolean by mutableStateOf(false)
        internal set

    /** Writing: focused, or words waiting in the draft (the keyboard went down mid-sentence). */
    val open: Boolean
        get() = noteWriteBarOpen(focused = focused, draftText = draftState.draft.text)

    internal var openHeightPx: Int by mutableIntStateOf(0)

    internal var pendingFocus: Boolean by mutableStateOf(false)

    /** The bar's height for the host's slot (without [NoteWriteBarDefaults.TopGap]). */
    fun height(density: Density): Dp {
        if (!open || openHeightPx <= 0) return NoteWriteBarDefaults.IdleHeight
        return with(density) { openHeightPx.toDp() }.coerceAtLeast(NoteWriteBarDefaults.IdleHeight)
    }

    /** The Write pill: take focus (and the keyboard) once the bar's page has landed. */
    fun requestWriting() {
        pendingFocus = true
    }

    /** The request is void: the reader left the Note page before it landed. */
    fun cancelPendingFocus() {
        pendingFocus = false
    }
}

@Composable
fun rememberNoteWriteBarState(draftState: NoteDraftState): NoteWriteBarState =
    remember(draftState) { NoteWriteBarState(draftState) }

/** The bar is open while it has focus or holds unsaved words. */
internal fun noteWriteBarOpen(focused: Boolean, draftText: String): Boolean = focused || draftText.isNotEmpty()

/**
 * The keyboard went down (back, or swiped away) after it had come up for the
 * bar, and nothing was written: the bar returns to its capsule.
 */
internal fun shouldCollapseNoteWriteBar(imeWasShown: Boolean, imeVisible: Boolean, draftEmpty: Boolean): Boolean =
    imeWasShown && !imeVisible && draftEmpty

/** The stamp's realign glyph appears once the playhead has moved this far from the draft's moment. */
internal const val NoteRealignHintDriftMs = 2_000L

internal fun showNoteRealignHint(anchorMs: Long?, positionMs: Long): Boolean =
    anchorMs != null && abs(positionMs - anchorMs) >= NoteRealignHintDriftMs

/**
 * Now Playing's one note writer, docked in the bottom accessory slot of the
 * Note page (where Lyrics keeps its tools and About its Ask bar).
 *
 * At rest it is a 56dp capsule: the live playhead in a stamp, "记录笔记".
 * Writing (focus, or words waiting) opens it into a card that grows with the
 * words (2–6 lines, then it scrolls inside). The stamp is the card's first
 * column: the moment the note will be filed at, so the card reads like the
 * line it becomes. Tapping the stamp re-aligns that moment to "now"; the
 * stamp itself shows when it can (no hint line). 记下 saves and keeps the
 * keyboard up for the next line.
 *
 * The words live in [NoteWriteBarState.draftState]: bound to the song and
 * moment writing began on, they survive a page switch, a collapse and a skip
 * (a carried-over draft shows its song's cover in the stamp). When the keyboard goes
 * down over an empty draft the bar returns to its capsule — the system back
 * that closed the keyboard needs no handler of its own.
 *
 * The card takes the height its host gives it (the slot animates to
 * [NoteWriteBarState.height]); however short the slot (mid-animation, or a
 * landscape keyboard), the words keep at least a line — they scroll inside —
 * and the footer fades in only as room opens under them.
 * [inline] (the landscape phone, where the keyboard leaves little height)
 * puts 记下 at the end of the line and keeps the card to 3 lines (no footer).
 * [focusGate] holds a pending [NoteWriteBarState.requestWriting] until the
 * host's page has landed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteWriteBar(
    state: NoteWriteBarState,
    current: NoteTarget,
    positionMs: () -> Long,
    onSave: (NoteSaveRequest) -> Unit,
    modifier: Modifier = Modifier,
    inline: Boolean = false,
    focusGate: Boolean = true,
) {
    val draftState = state.draftState
    val draft = draftState.draft
    val open = state.open
    val currentTarget by rememberUpdatedState(current)
    val latestPosition by rememberUpdatedState(positionMs)
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val haptics = rememberYoinHaptics()
    val scheme = MaterialTheme.colorScheme
    // The field's full value, not just its text: a String-valued field starts
    // its caret at 0 whenever it is created, so a field recreated over a
    // draft prepended the next word. A new field starts at the draft's end.
    var heldFieldValue by remember { mutableStateOf<TextFieldValue?>(null) }
    var lineCount by remember { mutableIntStateOf(1) }
    var lineHeightPx by remember { mutableIntStateOf(0) }

    // Every note gets a song-moment anchor — recording the moment IS the
    // default. An empty draft is seeded here and follows the song; words keep
    // the song they were started on.
    LaunchedEffect(current.songId) {
        draftState.followIfEmpty(current, positionMs())
    }
    // A pager page holding the focused bar can be disposed mid-swipe, before
    // the field reports losing focus: the bar is not writing once it's gone.
    DisposableEffect(state) {
        onDispose { state.focused = false }
    }
    LaunchedEffect(state.pendingFocus, focusGate) {
        if (state.pendingFocus && focusGate) {
            state.pendingFocus = false
            focusRequester.requestFocus()
        }
    }
    // Keyboard dismissed (back, swipe) over an empty draft → back to the
    // capsule. Watch the IME itself: a focus change fires before the keyboard
    // ever shows, and a hardware keyboard never shows one at all.
    val imeVisible = WindowInsets.isImeVisible
    var imeShown by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible, state.focused) {
        when {
            !state.focused -> imeShown = false
            imeVisible -> imeShown = true
            else -> {
                if (shouldCollapseNoteWriteBar(imeShown, imeVisible, draftState.draft.text.isEmpty())) {
                    focusManager.clearFocus()
                }
                imeShown = false
            }
        }
    }

    val save: () -> Unit = {
        val request = draftState.takeSaveRequest(currentTarget, latestPosition())
        if (request != null) {
            haptics.performConfirm()
            onSave(request)
        }
    }
    val corner = animateDpAsState(
        targetValue = if (open) NoteWriteBarDefaults.OpenCorner else NoteWriteBarDefaults.IdleCorner,
        animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Expressive),
        label = "noteWriteBarCorner",
    )
    val carried = draft.isForOtherTrack(current)
    val minLines = if (open && !inline) NoteWriteBarDefaults.OpenMinLines else 1
    val maxLines = when {
        !open -> 1
        inline -> NoteWriteBarDefaults.InlineMaxLines
        else -> NoteWriteBarDefaults.OpenMaxLines
    }
    val cardInteraction = remember { MutableInteractionSource() }
    val writeBarLabel = stringResource(R.string.cmp_note_write_bar_cd)

    val stampSlot: @Composable () -> Unit = {
        NoteStampChip(
            open = open,
            anchorMs = draft.anchorMs,
            carriedCoverUrl = draft.target?.coverArtUrl?.takeIf { carried },
            carried = carried,
            hasWords = draft.text.isNotBlank(),
            positionMs = positionMs,
            onClick = {
                if (open) {
                    haptics.performTick()
                    draftState.realign(currentTarget, latestPosition())
                } else {
                    focusRequester.requestFocus()
                }
            },
        )
    }
    val fieldSlot: @Composable () -> Unit = {
        val placeholder = stringResource(R.string.cmp_note_placeholder)
        BasicTextField(
            value = draftFieldValue(draft.text, heldFieldValue),
            onValueChange = { value ->
                heldFieldValue = value
                // Caret / selection moves don't touch the draft.
                if (value.text != draftState.draft.text) {
                    draftState.edit(value.text, currentTarget, latestPosition())
                }
            },
            modifier = Modifier
                .focusRequester(focusRequester)
                .onFocusChanged { focus ->
                    if (state.focused != focus.isFocused) state.focused = focus.isFocused
                    // The moment writing begins is the moment the note means —
                    // bind the song + playhead then.
                    if (focus.isFocused) draftState.followIfEmpty(currentTarget, latestPosition())
                },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            minLines = minLines,
            maxLines = maxLines,
            onTextLayout = { layout ->
                lineCount = layout.lineCount
                lineHeightPx = (layout.getLineBottom(0) - layout.getLineTop(0)).roundToInt()
            },
            decorationBox = { inner ->
                Box {
                    if (draftState.draft.text.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
    }
    val trailingSlot: @Composable () -> Unit = {
        when {
            !open -> Icon(
                imageVector = YoinSymbols.Edit,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            inline -> NoteSavePill(enabled = draft.text.isNotBlank(), onClick = save)
        }
    }
    val footerSlot: @Composable () -> Unit = {
        if (open && !inline) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.weight(1f))
                NoteSavePill(enabled = draft.text.isNotBlank(), onClick = save)
            }
        }
    }

    // One field instance in a fixed slot: switching between the capsule and
    // the card (or inline) only moves it, so focus and the IME survive.
    Layout(
        contents = listOf(stampSlot, fieldSlot, trailingSlot, footerSlot),
        modifier = modifier
            .graphicsLayer {
                shape = RoundedCornerShape(corner.value)
                clip = true
            }
            // Over the aurora: a light veil, not another solid grey block.
            .background(scheme.surfaceContainerHigh.copy(alpha = 0.92f))
            .noRippleClickable(
                interactionSource = cardInteraction,
                enabled = !open,
                onClickLabel = writeBarLabel,
                onClick = { focusRequester.requestFocus() },
            ),
    ) { measurables, constraints ->
        val (stampM, fieldM, trailingM, footerM) = measurables
        val padStart = NoteWriteBarDefaults.PaddingStart.roundToPx()
        val padEnd = NoteWriteBarDefaults.PaddingEnd.roundToPx()
        val padV = NoteWriteBarDefaults.PaddingVertical.roundToPx()
        val itemGap = NoteWriteBarDefaults.ItemGap.roundToPx()
        val footerGap = NoteWriteBarDefaults.FooterGap.roundToPx()
        val footerInset = NoteWriteBarDefaults.FooterInset.roundToPx()
        val idlePx = NoteWriteBarDefaults.IdleHeight.roundToPx()
        val width = constraints.maxWidth

        val stamp = stampM.firstOrNull()?.measure(Constraints())
        val trailing = trailingM.firstOrNull()?.measure(Constraints())
        val footer = footerM.firstOrNull()?.measure(
            Constraints(maxWidth = (width - padStart - footerInset - padEnd).coerceAtLeast(0)),
        )
        val stampW = stamp?.width ?: 0
        val stampH = stamp?.height ?: 0
        val trailingW = trailing?.width ?: 0
        val trailingH = trailing?.height ?: 0
        val footerBlock = footer?.let { footerGap + it.height } ?: 0
        val fieldW = (width - padStart - stampW - itemGap - padEnd - (if (trailing != null) trailingW + itemGap else 0))
            .coerceAtLeast(0)
        // The first line sits centred on the stamp.
        val lineOffset = ((stampH - lineHeightPx) / 2).coerceAtLeast(0)
        val boundedHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else null
        val rowRoom = boundedHeight?.let { (it - padV * 2 - footerBlock - lineOffset).coerceAtLeast(lineHeightPx) }
            ?: Constraints.Infinity
        val field = fieldM.first().measure(Constraints(minWidth = fieldW, maxWidth = fieldW, maxHeight = rowRoom))

        // The open card's natural height — its words at their own line count
        // (2–6, read from the layout just made) — for the host's slot. It
        // doesn't depend on the height given here, so the slot can't chase it.
        val fieldNatural = lineHeightPx * lineCount.coerceIn(minLines, maxLines)
        val rowNatural = maxOf(stampH, lineOffset + fieldNatural, if (inline) trailingH else 0)
        val openNatural = padV * 2 + rowNatural + footerBlock
        if (open && openNatural != state.openHeightPx) state.openHeightPx = openNatural

        // The host's slot fixes the height (it animates to [height]); loose
        // constraints (a preview, a test) wrap the bar at its own height.
        val height = if (constraints.hasFixedHeight) {
            constraints.maxHeight
        } else {
            constraints.constrainHeight(if (open) openNatural else idlePx)
        }
        val rowHeight = maxOf(stampH, lineOffset + field.height, if (inline && open) trailingH else 0)
        val rowTop = if (open) padV else ((height - rowHeight) / 2).coerceAtLeast(0)
        layout(width, height) {
            stamp?.placeRelative(padStart, rowTop)
            val fieldX = padStart + stampW + itemGap
            field.placeRelative(fieldX, rowTop + lineOffset)
            trailing?.let { t ->
                val x = width - padEnd - t.width
                // Beside the first line at rest; at the end of the last line inline.
                val y = if (open) rowTop + rowHeight - (stampH + t.height) / 2 else rowTop + (stampH - t.height) / 2
                // Never past the card's edge, however short the slot.
                t.placeRelative(x, y.coerceIn(0, (height - t.height).coerceAtLeast(0)))
            }
            footer?.let { f ->
                val y = height - padV - f.height
                // Revealed as the card grows: it fades in over the room that
                // opens under the words, never on top of them.
                val room = y - (rowTop + rowHeight + footerGap)
                placeFooter(f, padStart + footerInset, y, (1f + room.toFloat() / f.height.coerceAtLeast(1)))
            }
        }
    }
}

private fun Placeable.PlacementScope.placeFooter(footer: Placeable, x: Int, y: Int, alpha: Float) {
    footer.placeRelativeWithLayer(x, y) { this.alpha = alpha.coerceIn(0f, 1f) }
}

/**
 * The write bar's first column: the live playhead at rest (what writing would
 * anchor to right now), the draft's moment while writing. No words explain it:
 * a draft carried over from another song shows that song's cover beside its
 * moment, and once words are down and the playhead has wandered off their
 * moment a realign glyph slides in — tapping the stamp files the note at "now".
 * Only this chip reads the playhead, once a second.
 */
@Composable
private fun NoteStampChip(
    open: Boolean,
    anchorMs: Long?,
    carriedCoverUrl: String?,
    carried: Boolean,
    hasWords: Boolean,
    positionMs: () -> Long,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    val realignLabel = stringResource(R.string.cmp_note_stamp_cd_realign)
    val writeLabel = stringResource(R.string.cmp_note_stamp_cd_write)
    val latest by rememberUpdatedState(positionMs)
    val drifted by remember(anchorMs) {
        derivedStateOf { showNoteRealignHint(anchorMs?.let { it / 1_000L * 1_000L }, latest() / 1_000L * 1_000L) }
    }
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(YoinShapeTokens.Full)
            .background(scheme.primary.copy(alpha = 0.12f))
            .noRippleClickable(
                interactionSource = interaction,
                onClickLabel = if (open) realignLabel else writeLabel,
                onClick = onClick,
            )
            .padding(start = if (open && carried) 6.dp else 12.dp, end = 12.dp)
            .animateContentSize(animationSpec = YoinMotion.defaultSpatialSpec()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
        if (open && anchorMs != null) {
            if (carried) {
                ExpressiveMediaArtwork(
                    model = carriedCoverUrl,
                    contentDescription = null,
                    shape = YoinArtworkShapes.Thumb,
                    fallbackIcon = YoinSymbols.MusicNote,
                    tonalElevation = 0.dp,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
            } else if (drifted && hasWords) {
                Icon(
                    imageVector = YoinSymbols.Refresh,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = formatNotePosition(anchorMs),
                style = style,
                fontWeight = FontWeight.SemiBold,
                color = scheme.primary,
            )
        } else {
            LivePositionText(positionMs = positionMs, style = style, color = scheme.primary)
        }
    }
}

@Composable
private fun LivePositionText(positionMs: () -> Long, style: TextStyle, color: Color) {
    val latest by rememberUpdatedState(positionMs)
    // Whole seconds: the 4Hz tick recomposes nothing until the label changes.
    val seconds by remember { derivedStateOf { latest() / 1_000L } }
    Text(text = formatNotePosition(seconds * 1_000L), style = style, fontWeight = FontWeight.SemiBold, color = color)
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
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "savePill",
    )
    val content = if (enabled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    JournalSavePill(
        label = stringResource(R.string.cmp_note_save),
        containerColor = container,
        contentColor = content,
        enabled = enabled,
        onClick = onClick,
        icon = YoinSymbols.Send,
    )
}

/**
 * The journal's accent rail — the vertical stroke down a written note's
 * leading edge. The Memories diary's blank page draws it; Now Playing's notes
 * read as lyric lines and no longer do. [width] is the stroke; the caller
 * sizes its height (usually `fillMaxHeight()` beside the text) and its insets.
 */
@Composable
internal fun JournalRail(color: Color, width: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(width)
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * The journal's capsule save pill (no outline, no tonal box): [NoteWriteBar]'s
 * 记下 and the Memories diary's Save. [onClick] null leaves the press to a
 * larger hit area around it (the diary's 48dp row).
 */
@Composable
internal fun JournalSavePill(
    label: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    icon: ImageVector? = null,
    textStyle: TextStyle = MaterialTheme.typography.labelMedium,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        modifier = if (onClick != null) {
            modifier.noRippleClickable(
                interactionSource = interaction,
                enabled = enabled,
                onClick = onClick,
            )
        } else {
            modifier
        },
        shape = YoinShapeTokens.Full,
        color = containerColor,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = label,
                style = textStyle,
                color = contentColor,
            )
        }
    }
}

/** When a note was written, relative while recent ("3 天前"), then the date. */
internal fun formatNoteDate(
    epochMs: Long,
    nowMs: Long = System.currentTimeMillis(),
    resources: android.content.res.Resources? = null,
): String {
    val delta = (nowMs - epochMs).coerceAtLeast(0L)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    val hours = TimeUnit.MILLISECONDS.toHours(delta)
    val days = TimeUnit.MILLISECONDS.toDays(delta)
    if (resources == null) {
        val legacyDate = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))
        return when {
            minutes < 1L ->
                "刚刚" // i18n-allow: NoteLineTest calls formatNoteDate without Resources
            minutes < 60L ->
                "$minutes 分钟前" // i18n-allow: NoteLineTest calls formatNoteDate without Resources
            hours < 24L ->
                "$hours 小时前" // i18n-allow: NoteLineTest calls formatNoteDate without Resources
            days < 7L ->
                "$days 天前" // i18n-allow: NoteLineTest calls formatNoteDate without Resources
            else -> legacyDate // i18n-allow: NoteLineTest calls formatNoteDate without Resources
        }
    }
    return when {
        minutes < 1L -> resources.getString(R.string.cmp_note_just_now)
        minutes < 60L -> resources.getQuantityString(
            R.plurals.cmp_note_minutes_ago,
            minutes.toInt(),
            minutes.toInt(),
        )
        hours < 24L -> resources.getQuantityString(
            R.plurals.cmp_note_hours_ago,
            hours.toInt(),
            hours.toInt(),
        )
        days < 7L -> resources.getQuantityString(
            R.plurals.cmp_note_days_ago,
            days.toInt(),
            days.toInt(),
        )
        else -> {
            val locale = resources.configuration.locales[0]
            val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "yMMMd")
            val calendar = Calendar.getInstance().apply { timeInMillis = epochMs }
            android.text.format.DateFormat.format(pattern, calendar).toString()
        }
    }
}

@Preview(name = "Note lines — page", showBackground = true, widthDp = 380)
@Composable
private fun NoteLinePagePreview() {
    YoinTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            NoteLine(stamp = "0:12", text = "Bass walks up the stairs in the intro", isActive = false, distance = 1)
            NoteLine(stamp = "1:23", text = "Chorus comes in — that last summer bus", isActive = true)
            NoteLine(stamp = "2:05", text = "Clap ×4", isActive = false, distance = 1, meta = "3 天前")
            NoteLine(stamp = null, text = "An old note with no moment", isActive = false, distance = 2)
        }
    }
}

@Preview(name = "Note lines — glance", showBackground = true, widthDp = 380)
@Composable
private fun NoteLineGlancePreview() {
    YoinTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            val glance = NoteLineEmphasis.Glance
            NoteLine(stamp = "0:48", text = "Everyone shouts here", isActive = false, emphasis = glance, distance = 1)
            NoteLine(stamp = "1:23", text = "Chorus comes in", isActive = true, emphasis = glance)
            NoteLine(stamp = "2:05", text = "Clap ×4", isActive = false, emphasis = glance, distance = 2)
        }
    }
}

@Preview(name = "Write bar — rest", showBackground = true, widthDp = 380)
@Composable
private fun NoteWriteBarIdlePreview() {
    YoinTheme {
        NoteWriteBar(
            state = rememberNoteWriteBarState(remember { NoteDraftState() }),
            current = NoteTarget(songId = "subsonic:preview", title = "Streetlight Waltz", artist = "Mira Kade"),
            positionMs = { 87_000L },
            onSave = {},
            modifier = Modifier.padding(16.dp).fillMaxWidth().height(NoteWriteBarDefaults.IdleHeight),
        )
    }
}

@Preview(name = "Write bar — writing", showBackground = true, widthDp = 380)
@Composable
private fun NoteWriteBarOpenPreview() {
    val song = NoteTarget(songId = "subsonic:preview", title = "Streetlight Waltz", artist = "Mira Kade")
    YoinTheme {
        NoteWriteBar(
            state = rememberNoteWriteBarState(
                remember {
                    NoteDraftState(
                        NoteDraft(text = "Second chorus goes up a half step", anchorMs = 91_000L, target = song),
                    )
                },
            ),
            current = song,
            positionMs = { 94_000L },
            onSave = {},
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
        )
    }
}
