package com.gpo.yoin.ui.notes.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.gpo.yoin.R
import com.gpo.yoin.shared.notes.MentionKind
import com.gpo.yoin.shared.notes.NoteMention
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.MetaGroup
import com.gpo.yoin.ui.component.MetaLine
import com.gpo.yoin.ui.component.YoinModalBottomSheet
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The @ popup (prototype scenario 2): anchored at the caret's `@`, above the line when there's room
 * (the write bar sits at the bottom), below otherwise. Rows by kind, the highlighted one tinted; a tap
 * or Enter inserts it. It never takes focus: the keyboard stays with the field.
 */
@Composable
internal fun MentionPopup(
    anchor: Rect,
    rows: MentionCandidates,
    loading: Boolean,
    query: String,
    highlighted: Int,
    onPick: (NoteMention) -> Unit,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    // The rows' room: the popup stays clear of the keyboard and the status bar ([topLimitPx] / [bottomLimitPx]).
    listMaxHeight: Dp = PopupMaxListHeight,
    topLimitPx: Int = 0,
    bottomLimitPx: Int = Int.MAX_VALUE,
) {
    val marginPx = with(LocalDensity.current) { PopupMargin.roundToPx() }
    val position = remember(anchor, marginPx, topLimitPx, bottomLimitPx) {
        CaretPopupPosition(anchor, marginPx, topLimitPx, bottomLimitPx)
    }
    val visible = remember { MutableTransitionState(false) }.apply { targetState = true }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = MentionPopupProperties,
    ) {
        AnimatedVisibility(
            visibleState = visible,
            enter = YoinMotion.scaleIn(
                role = YoinMotionRole.Expressive,
                initialScale = 0.86f,
                transformOrigin = TransformOrigin(0f, 1f),
            ) + YoinMotion.fadeIn(role = YoinMotionRole.Expressive),
        ) {
            Surface(
                modifier = Modifier.width(PopupWidth),
                shape = YoinShapeTokens.LargeIncreased,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 6.dp,
                tonalElevation = 2.dp,
            ) {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.notes_mention_popup_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onExpand) {
                            Icon(YoinSymbols.OpenInFull, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.notes_mention_expand))
                        }
                    }
                    Column(modifier = Modifier.heightIn(max = listMaxHeight).verticalScroll(rememberScrollState())) {
                        MentionRows(rows = rows, query = query, highlighted = highlighted, onPick = onPick)
                        if (rows.isEmpty) {
                            Text(
                                text = stringResource(
                                    if (loading) R.string.notes_mention_loading else R.string.notes_mention_empty,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The rows by kind with section labels; [highlighted] indexes [MentionCandidates.all]. */
@Composable
private fun MentionRows(rows: MentionCandidates, query: String, highlighted: Int, onPick: (NoteMention) -> Unit) {
    var index = 0
    // The kinds in the rows' order: once something is typed, the best match's kind comes first.
    rows.order.map { kind -> kind.sectionLabel() to rows.of(kind) }.forEach { (label, list) ->
        if (list.isEmpty()) return@forEach
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
        )
        list.forEach { row ->
            MentionRow(row = row, query = query, highlighted = index == highlighted, onClick = { onPick(row.mention) })
            index++
        }
    }
}

private fun MentionKind.sectionLabel(): Int = when (this) {
    MentionKind.Track -> R.string.notes_mention_section_tracks
    MentionKind.Album -> R.string.notes_mention_section_albums
    MentionKind.Artist -> R.string.notes_mention_section_artists
}

@Composable
internal fun MentionRow(row: MentionCandidate, query: String, highlighted: Boolean, onClick: () -> Unit) {
    val kindLabel = stringResource(
        when (row.mention.kind) {
            MentionKind.Track -> R.string.notes_mention_cd_track
            MentionKind.Album -> R.string.notes_mention_cd_album
            MentionKind.Artist -> R.string.notes_mention_cd_artist
        },
        row.mention.title,
    )
    val tint = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .background(tint, YoinShapeTokens.Large)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = kindLabel }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ExpressiveMediaArtwork(
            model = row.coverUrl,
            contentDescription = null,
            shape = if (row.mention.kind == MentionKind.Artist) CircleShape else YoinArtworkShapes.Thumb,
            fallbackIcon = when (row.mention.kind) {
                MentionKind.Track -> YoinSymbols.MusicNote
                MentionKind.Album -> YoinSymbols.Album
                MentionKind.Artist -> YoinSymbols.Artist
            },
            modifier = Modifier.size(40.dp),
            tonalElevation = 0.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightMatch(row.mention.title, query),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Artist, then the album muted: the app's meta line (groups drop from the end when short).
            val meta = listOfNotNull(row.artist?.let { MetaGroup.Plain(it) }, row.album?.let { MetaGroup.Plain(it, muted = true) })
            if (meta.isNotEmpty()) {
                MetaLine(groups = meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun highlightMatch(title: String, query: String) = buildAnnotatedString {
    val at = if (query.isBlank()) -1 else title.indexOf(query.trim(), ignoreCase = true)
    if (at < 0) {
        append(title)
        return@buildAnnotatedString
    }
    append(title.substring(0, at))
    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) {
        append(title.substring(at, at + query.trim().length))
    }
    append(title.substring(at + query.trim().length))
}

/**
 * Places the popup so it never covers the line being written (2026-11-24 tablet QA: on a phone it hid the draft's
 * first line): above the whole field when there's room (the write bar's words stay in view), else above the
 * caret's line, else below it. Horizontally it starts at the caret.
 */
private class CaretPopupPosition(
    private val caret: Rect,
    private val marginPx: Int,
    private val topLimitPx: Int,
    private val bottomLimitPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = mentionPopupOffset(
        anchorBounds, caret, windowSize, popupContentSize, marginPx,
        topLimit = topLimitPx,
        bottomLimit = bottomLimitPx.coerceAtMost(windowSize.height),
    )
}

/**
 * The @ popup's window: it never takes focus (the keyboard stays with the field), and a touch outside it — a key
 * on the soft keyboard above all (2026-11-24 tablet QA: the first key tapped closed it) — doesn't close it. The
 * editor ends mention mode itself: the caret leaving the `@` word, the field losing focus, Esc, a pick.
 */
internal val MentionPopupProperties = PopupProperties(
    focusable = false,
    dismissOnClickOutside = false,
    clippingEnabled = true,
)

/**
 * [CaretPopupPosition]'s placement: [field] in window pixels, [caret] relative to it; the popup stays between
 * [topLimit] (the status bar) and [bottomLimit] (the keyboard's top). Above the field if it fits, else above
 * the caret's line, else below it; when neither side holds it whole (its rows are then held to the larger
 * side, [mentionPopupListMax]), the side with more room.
 */
internal fun mentionPopupOffset(
    field: IntRect,
    caret: Rect,
    window: IntSize,
    popup: IntSize,
    margin: Int,
    topLimit: Int = 0,
    bottomLimit: Int = window.height,
): IntOffset {
    val caretTop = field.top + caret.top.toInt()
    val caretBottom = field.top + caret.bottom.toInt()
    val top = topLimit + margin
    val bottom = bottomLimit - margin
    val x = (field.left + caret.left.toInt() - margin * 2)
        .coerceIn(margin, (window.width - popup.width - margin).coerceAtLeast(margin))
    val aboveField = field.top - popup.height - margin
    val aboveLine = caretTop - popup.height - margin
    val below = caretBottom + margin
    val y = when {
        aboveField >= top -> aboveField
        aboveLine >= top -> aboveLine
        below + popup.height <= bottom -> below
        caretTop - top >= bottom - caretBottom -> maxOf(top, aboveLine)
        else -> below
    }
    return IntOffset(x, y)
}

/**
 * How tall the rows may be: what fits on the roomier side of the caret's line ([caretTop] / [caretBottom] in
 * window pixels) between [topLimit] and [bottomLimit], less the popup's own [chrome], within [min]..[max].
 */
internal fun mentionPopupListMax(
    caretTop: Int,
    caretBottom: Int,
    topLimit: Int,
    bottomLimit: Int,
    margin: Int,
    chrome: Int,
    min: Int,
    max: Int,
): Int = (maxOf(caretTop - topLimit, bottomLimit - caretBottom) - margin * 2 - chrome).coerceIn(min, max)

private val PopupWidth = 312.dp
internal val PopupMargin = 8.dp
internal val PopupMaxListHeight = 300.dp
internal val PopupMinListHeight = 96.dp

/** The popup around its rows: the title row and padding. */
internal val PopupChrome = 64.dp

/**
 * "展开搜索": the whole search as a sheet (prototype scenario 2): a search field, then songs, albums and
 * artists. A pick goes back into the note at the `@`.
 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
internal fun MentionSearchSheet(
    initialQuery: String,
    candidates: MentionCandidateSource,
    onPick: (NoteMention) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val field = rememberTextFieldState(initialQuery)
    var rows by remember { mutableStateOf(MentionCandidates.Empty) }
    var loading by remember { mutableStateOf(true) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(candidates) {
        snapshotFlow { field.text.toString() }
            .distinctUntilChanged()
            .debounce(220)
            .collect { q ->
                loading = true
                rows = candidates.candidates(q, SheetRowsPerKind)
                loading = false
            }
    }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { focus.requestFocus() }
    }
    YoinModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            OutlinedTextField(
                state = field,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .focusRequester(focus),
                placeholder = { Text(stringResource(R.string.notes_mention_search_hint)) },
                leadingIcon = { Icon(YoinSymbols.Search, contentDescription = null) },
                lineLimits = TextFieldLineLimits.SingleLine,
                shape = YoinShapeTokens.ExtraLarge,
            )
            val sections = rows.order.map { kind -> kind.sectionLabel() to rows.of(kind) }.filter { it.second.isNotEmpty() }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                sections.forEach { (label, list) ->
                    item(key = "h$label") {
                        Text(
                            text = stringResource(label),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(list, key = { it.mention.mediaId + it.mention.kind }) { row ->
                        Column(Modifier.padding(horizontal = 8.dp)) {
                            MentionRow(row = row, query = field.text.toString(), highlighted = false, onClick = { onPick(row.mention) })
                        }
                    }
                }
                if (sections.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(if (loading) R.string.notes_mention_loading else R.string.notes_mention_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
        }
    }
}

private const val SheetRowsPerKind = 12
