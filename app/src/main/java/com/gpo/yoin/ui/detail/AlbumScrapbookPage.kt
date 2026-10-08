@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.gpo.yoin.ui.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.then
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ParentDataModifierNode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.gpo.yoin.R
import com.gpo.yoin.data.album.AlbumScrapbookAbout
import com.gpo.yoin.data.album.AlbumScrapbookAlbumNote
import com.gpo.yoin.data.album.AlbumScrapbookAsk
import com.gpo.yoin.data.album.AlbumScrapbookData
import com.gpo.yoin.data.album.AlbumScrapbookFact
import com.gpo.yoin.data.album.AlbumScrapbookNote
import com.gpo.yoin.data.album.AlbumScrapbookPlays
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.MetaGroup
import com.gpo.yoin.ui.component.YoinModalBottomSheet
import com.gpo.yoin.ui.component.dashedOutline
import com.gpo.yoin.ui.component.expressivePageSeamBackground
import com.gpo.yoin.ui.component.formatNotePosition
import com.gpo.yoin.ui.component.formatTrackDuration
import com.gpo.yoin.ui.component.markdownBoldAnnotatedString
import com.gpo.yoin.ui.component.seamDissolve
import com.gpo.yoin.ui.component.seamDissolveViewport
import com.gpo.yoin.ui.component.seamFade
import com.gpo.yoin.ui.component.seamRemainingPx
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.ProvidePreviewWindow
import com.gpo.yoin.ui.theme.GoogleSansFlex
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinSerifTitle
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.withTabularFigures
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/*
 * Page 2 of the album page: the scrapbook (see AlbumScrapbook.kt for what goes where and why).
 *
 * Flat, no shadows; pieces are told apart by paper colour, type face and corner mark:
 *  - the listener's review: the album's primaryContainer, system face, no label, no rail (owner: colour only);
 *  - the album's one album note: a secondaryContainer sticky with a folded corner, system face, no label, no rail;
 *  - track notes: neutral paper with the journal rail (the rail is reserved for them) and time stamps;
 *  - Ask / About: tertiary index cards in GSF with a "Q" clover;
 *  - scores: the album's groove emblem on the cover's corner, and each ticket's own number in the album ink.
 * Tilts live in graphicsLayer only (layout and hit areas stay square). While the pager drags, each piece
 * lags by its depth and leans a little more, read in the layer from [pageOffset] — no recomposition.
 *
 * Back: page 2 is an in-page pager state, not a place. Back leaves the album from here exactly as from page 1
 * (same as the pulled-up track list); no handler is added.
 */

/** Masonry tokens (D3 §5.1). */
private object ScrapbookLayout {
    val LaneMin = 280.dp
    val LaneGap = 28.dp
    val RowGap = 28.dp
    val RowGapWide = 32.dp
    val MaxWidth = 1040.dp
    val Gutter = 16.dp
    val GutterWide = 24.dp
    val WideFrom = 600.dp
    val CoverSide = 148.dp
    val CoverSideWide = 184.dp
    val EmblemSize = 64.dp
    val EmblemSizeWide = 72.dp

    /** The emblem hangs off the cover's bottom-right by these fractions of its own size (as on Memories cards). */
    const val EmblemOverhangX = 0.3f
    const val EmblemOverhangY = 0.24f
    val PieceOverlap = 12.dp
    val TagOverlap = 6.dp
    val DotPitch = 16.dp
    val DotRadius = 1.2.dp
    const val ParallaxShare = 0.12f
    const val ParallaxLeanDegrees = 3f
    val StickyMaxWidth = 280.dp

    /** How far the album-note sticky rides over the review's foot. */
    val StickyTuck = 6.dp

    /** Air between the cover's emblem and a phone sticky below it. */
    val StickyEmblemGap = 6.dp

    /** The wide sticky's step in from the review's edge. */
    val StickyIndentWide = 24.dp
}

/** The page's colours, from the album's cover scheme (the same seeded scheme page 1 uses). */
@Immutable
internal data class ScrapbookColors(
    val ink: Color,
    val onInk: Color,
    val ticket: Color,
    val onTicket: Color,
    val ticketNow: Color,
    val onTicketNow: Color,
    val review: Color,
    val onReview: Color,
    val sticky: Color,
    val onSticky: Color,
    val stickyFold: Color,
    val note: Color,
    val onNote: Color,
    val ask: Color,
    val onAsk: Color,
    val askMark: Color,
    val onAskMark: Color,
    val tag: Color,
    val onTag: Color,
    val receipt: Color,
    val onReceipt: Color,
    val outline: Color,
    val muted: Color,
    val dots: Color,
)

/** [scheme]'s roles for the page, each springing on the effects spring when the cover scheme resolves. */
@Composable
internal fun rememberScrapbookColors(scheme: androidx.compose.material3.ColorScheme): ScrapbookColors {
    val spec = YoinMotion.effectsSpring<Color>()

    @Composable
    fun animated(target: Color, label: String): Color = animateColorAsState(target, spec, label = label).value
    val dark = scheme.surface.luminance() < 0.5f
    return ScrapbookColors(
        ink = animated(scheme.primary, "scrapInk"),
        onInk = animated(scheme.onPrimary, "scrapOnInk"),
        ticket = animated(scheme.surfaceContainerHigh, "scrapTicket"),
        onTicket = animated(scheme.onSurface, "scrapOnTicket"),
        ticketNow = animated(scheme.secondaryContainer, "scrapTicketNow"),
        onTicketNow = animated(scheme.onSecondaryContainer, "scrapOnTicketNow"),
        review = animated(scheme.primaryContainer, "scrapReview"),
        onReview = animated(scheme.onPrimaryContainer, "scrapOnReview"),
        sticky = animated(scheme.secondaryContainer, "scrapSticky"),
        onSticky = animated(scheme.onSecondaryContainer, "scrapOnSticky"),
        // the folded corner: the sticky's paper with a third of the secondary ink (D3)
        stickyFold = animated(lerp(scheme.secondaryContainer, scheme.secondary, StickyFoldInk), "scrapStickyFold"),
        note = animated(scheme.surfaceContainerHighest, "scrapNote"),
        onNote = animated(scheme.onSurface, "scrapOnNote"),
        ask = animated(scheme.tertiaryContainer, "scrapAsk"),
        onAsk = animated(scheme.onTertiaryContainer, "scrapOnAsk"),
        askMark = animated(scheme.tertiary, "scrapAskMark"),
        onAskMark = animated(scheme.onTertiary, "scrapOnAskMark"),
        tag = animated(scheme.secondaryContainer, "scrapTag"),
        onTag = animated(scheme.onSecondaryContainer, "scrapOnTag"),
        receipt = animated(scheme.surfaceContainer, "scrapReceipt"),
        onReceipt = animated(scheme.onSurface, "scrapOnReceipt"),
        outline = animated(scheme.outline, "scrapOutline"),
        muted = animated(scheme.onSurfaceVariant, "scrapMuted"),
        dots = animated(scheme.outlineVariant.copy(alpha = if (dark) 0.35f else 0.45f), "scrapDots"),
    )
}

/** The pager drag as the pieces read it: 0 settled on this page, 1 a page away; [widthPx] the page width. */
private class ScrapMotion(val offset: () -> Float, val widthPx: Float)

private val LocalScrapMotion = staticCompositionLocalOf { ScrapMotion({ 0f }, 0f) }

/**
 * A piece's resting tilt plus its share of the pager drag: it trails by [depth] × 12% of the page and leans
 * 3° further while away, settling to its own angle. Read in the layer only.
 */
@Composable
private fun Modifier.scrapLayer(tilt: Float, depth: Float): Modifier {
    val motion = LocalScrapMotion.current
    return graphicsLayer {
        val off = motion.offset()
        rotationZ = tilt + sign(tilt) * ScrapbookLayout.ParallaxLeanDegrees * off
        translationX = off * motion.widthPx * depth * ScrapbookLayout.ParallaxShare
    }
}

@Composable
internal fun AlbumScrapbookPage(
    state: AlbumScrapbookUiState,
    content: AlbumDetailUiState.Content,
    colors: ScrapbookColors,
    currentTrackId: String?,
    pageOffset: () -> Float,
    onSongClick: (songId: String) -> Unit,
    onNoteMomentClick: (songId: String, positionMs: Long?) -> Unit,
    onEditReview: () -> Unit,
    modifier: Modifier = Modifier,
    // The Memory title beside the cover: rename it (blank = back to Yoin's), or restore the AI one.
    onRenameTitle: (String) -> Unit = {},
    onRestoreTitle: () -> Unit = {},
    // The pager has come to rest on this page; [stamped] once its emblem ceremony has played.
    settled: Boolean = true,
    stamped: Boolean = true,
    onStamped: () -> Unit = {},
) {
    when (state) {
        // Room answers within a frame or two; a quiet page beats a spinner flash.
        AlbumScrapbookUiState.Loading -> Box(modifier.fillMaxSize())
        is AlbumScrapbookUiState.Ready -> ScrapbookPaper(
            book = state.book,
            content = content,
            colors = colors,
            currentTrackId = currentTrackId,
            pageOffset = pageOffset,
            onSongClick = onSongClick,
            onNoteMomentClick = onNoteMomentClick,
            onEditReview = onEditReview,
            onRenameTitle = onRenameTitle,
            onRestoreTitle = onRestoreTitle,
            settled = settled,
            stamped = stamped,
            onStamped = onStamped,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScrapbookPaper(
    book: AlbumScrapbook,
    content: AlbumDetailUiState.Content,
    colors: ScrapbookColors,
    currentTrackId: String?,
    pageOffset: () -> Float,
    onSongClick: (String) -> Unit,
    onNoteMomentClick: (String, Long?) -> Unit,
    onEditReview: () -> Unit,
    onRenameTitle: (String) -> Unit,
    onRestoreTitle: () -> Unit,
    settled: Boolean,
    stamped: Boolean,
    onStamped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val reduced = LocalMotionProfile.current == MotionProfile.AdaptiveReduced
    var aboutSheet by remember { mutableStateOf<ScrapAboutSheet?>(null) }
    var notesSheet by remember { mutableStateOf<ScrapPiece.TrackCluster?>(null) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val gutter = if (maxWidth >= ScrapbookLayout.WideFrom) ScrapbookLayout.GutterWide else ScrapbookLayout.Gutter
        val contentWidth = minOf(maxWidth - gutter * 2, ScrapbookLayout.MaxWidth)
        val lanes = scrapLanes(contentWidth)
        val wide = lanes > 1
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val motion = remember(widthPx, reduced, pageOffset) {
            ScrapMotion(offset = if (reduced) ({ 0f }) else pageOffset, widthPx = widthPx)
        }
        CompositionLocalProvider(LocalScrapMotion provides motion) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Scrolls under the fixed header (tide line at the page's top
                    // edge) and on under the bar (the bottom field).
                    .seamDissolveViewport(
                        background = expressivePageSeamBackground(),
                        remainingPx = { scrollState.seamRemainingPx() },
                    ) { scrollState.value.toFloat() }
                    .verticalScroll(scrollState)
                    .heightIn(min = maxHeight)
                    // The paper scrolls with the pieces.
                    .dottedPaper(colors.dots)
                    // 4 + the dots' own cell inset ≈ the 16dp above the dots.
                    .padding(top = 4.dp, bottom = 112.dp + navBottom),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ScrapMasonry(
                    lanes = lanes,
                    rowGap = if (wide) ScrapbookLayout.RowGapWide else ScrapbookLayout.RowGap,
                    modifier = Modifier.width(contentWidth),
                ) {
                    book.pieces.forEach { piece ->
                        val slot = Modifier.scrapSpan(piece.fullLine)
                        when (piece) {
                            is ScrapPiece.Opening -> Opening(
                                piece = piece,
                                content = content,
                                colors = colors,
                                wide = wide,
                                onEditReview = onEditReview,
                                onRenameTitle = onRenameTitle,
                                onRestoreTitle = onRestoreTitle,
                                settled = settled,
                                stamped = stamped,
                                onStamped = onStamped,
                                modifier = slot,
                            )
                            is ScrapPiece.TrackCluster -> TrackCluster(
                                piece = piece,
                                colors = colors,
                                playing = piece.track.songId == currentTrackId,
                                answerBudget = if (wide) ScrapbookRules.AnswerBudgetWide else ScrapbookRules.AnswerBudget,
                                onPlay = { onSongClick(piece.track.songId) },
                                onNote = { line -> onNoteMomentClick(line.songId, line.positionMs) },
                                onMoreNotes = { notesSheet = piece },
                                onAbout = { aboutSheet = piece.sheet },
                                modifier = slot,
                            )
                            is ScrapPiece.ContactSheet -> ContactSheet(
                                piece = piece,
                                colors = colors,
                                currentTrackId = currentTrackId,
                                onPlay = onSongClick,
                                modifier = slot,
                            )
                            is ScrapPiece.NotYet -> NotYet(piece, colors, onSongClick, slot)
                            is ScrapPiece.Receipt -> Receipt(piece, colors, slot)
                        }
                    }
                }
            }
        }
    }

    aboutSheet?.let { sheet ->
        ScrapAboutSheetContent(sheet = sheet, colors = colors, onDismiss = { aboutSheet = null })
    }
    notesSheet?.let { cluster ->
        ScrapNotesSheet(
            cluster = cluster,
            colors = colors,
            onNote = { line ->
                notesSheet = null
                onNoteMomentClick(line.songId, line.positionMs)
            },
            onDismiss = { notesSheet = null },
        )
    }
}

/** Lanes for a content width: as many 280dp lanes (28dp apart) as fit, 1…3. */
internal fun scrapLanes(contentWidth: Dp): Int =
    ((contentWidth + ScrapbookLayout.LaneGap) / (ScrapbookLayout.LaneMin + ScrapbookLayout.LaneGap))
        .toInt()
        .coerceIn(1, 3)

// ---------------------------------------------------------------------------
// Masonry + stacking layouts.
// ---------------------------------------------------------------------------

private object ScrapFullLine

private data class ScrapSlot(val cross: Boolean, val overlap: Dp)

private class ScrapParentDataNode(var data: Any) : Modifier.Node(), ParentDataModifierNode {
    override fun Density.modifyParentData(parentData: Any?): Any = data
}

private data class ScrapParentDataElement(val data: Any) : ModifierNodeElement<ScrapParentDataNode>() {
    override fun create() = ScrapParentDataNode(data)

    override fun update(node: ScrapParentDataNode) {
        node.data = data
    }
}

/** Masonry span: full-line items cross every lane. */
private fun Modifier.scrapSpan(fullLine: Boolean): Modifier =
    if (fullLine) this then ScrapParentDataElement(ScrapFullLine) else this

/** A piece's side in a [ScrapStack] (the cluster's main side, or the other) and how far it rides up the one above. */
private fun Modifier.scrapSlot(cross: Boolean, overlap: Dp = ScrapbookLayout.PieceOverlap): Modifier =
    this then ScrapParentDataElement(ScrapSlot(cross, overlap))

/** Full-line pieces span all lanes at the lowest lane's bottom; the rest go to the shortest lane (ties: leftmost). */
@Composable
private fun ScrapMasonry(
    lanes: Int,
    rowGap: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val laneGap = ScrapbookLayout.LaneGap.roundToPx()
        val row = rowGap.roundToPx()
        val laneWidth = ((width - laneGap * (lanes - 1)) / lanes).coerceAtLeast(0)
        val bottoms = IntArray(lanes)
        val placed = measurables.map { measurable ->
            if (lanes == 1 || measurable.parentData === ScrapFullLine) {
                val placeable = measurable.measure(Constraints(minWidth = width, maxWidth = width))
                val y = bottoms.max()
                bottoms.fill(y + placeable.height + row)
                Triple(placeable, 0, y)
            } else {
                var lane = 0
                for (i in 1 until lanes) if (bottoms[i] < bottoms[lane]) lane = i
                val placeable = measurable.measure(Constraints(minWidth = laneWidth, maxWidth = laneWidth))
                val y = bottoms[lane]
                bottoms[lane] = y + placeable.height + row
                Triple(placeable, lane * (laneWidth + laneGap), y)
            }
        }
        val height = if (placed.isEmpty()) 0 else max(0, bottoms.max() - row)
        layout(width, height) {
            placed.forEach { (placeable, x, y) -> placeable.place(x, y) }
        }
    }
}

/**
 * A cluster: pieces top to bottom, each riding up the one above by its slot's overlap (later pieces draw
 * on top), on the cluster's main side or — for [scrapSlot] cross — the other one.
 */
@Composable
private fun ScrapStack(
    mirror: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val loose = Constraints(maxWidth = width)
        val placeables = measurables.map { it.measure(loose) }
        val slots = measurables.map { it.parentData as? ScrapSlot ?: ScrapSlot(cross = false, overlap = 0.dp) }
        var y = 0
        val ys = IntArray(placeables.size)
        placeables.forEachIndexed { i, placeable ->
            if (i > 0) y -= slots[i].overlap.roundToPx()
            ys[i] = max(0, y)
            y = ys[i] + placeable.height
        }
        layout(width, max(0, y)) {
            placeables.forEachIndexed { i, placeable ->
                val end = slots[i].cross != mirror
                val x = if (end) width - placeable.width else 0
                placeable.place(x, ys[i])
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Pieces.
// ---------------------------------------------------------------------------

/**
 * The album's Memory title beside the cover (owner 2026-10-05: not a row of
 * its own above it). Tap to rename in place — the serif line becomes the
 * field, Done or leaving it saves, blank goes back to Yoin's title; once
 * renamed, the sparkle restores the AI title. No outlined field, no labels.
 */
@Composable
private fun OpeningTitle(
    title: ScrapTitle,
    wide: Boolean,
    onRename: (String) -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = MaterialTheme.typography.headlineSmall.copy(
        // Yoin's own motif reads in the app face; a title someone wrote, in the serif.
        fontFamily = if (title.serif) YoinSerifTitle else null,
        fontWeight = FontWeight.SemiBold,
        fontSize = if (wide) 28.sp else 22.sp,
        lineHeight = 1.25.em,
        letterSpacing = 0.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
    var editing by rememberSaveable { mutableStateOf(false) }
    if (!editing) {
        Text(
            text = title.text,
            style = style,
            modifier = modifier
                .seamFade()
                .clip(YoinContainerShapes.ListRow)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.detail_scrap_cd_rename)) {
                    editing = true
                },
        )
        return
    }
    val field = rememberTextFieldState(title.draftSeed)
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val commit = {
        if (editing) {
            editing = false
            val text = field.text.toString().replace('\n', ' ').trim()
            if (text != title.text) onRename(text)
        }
    }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        field.edit { selection = TextRange(0, length) }
    }
    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        BasicTextField(
            state = field,
            textStyle = style,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            // One line of meaning: pasted line breaks become spaces (the field still wraps).
            inputTransformation = InputTransformation.maxLength(ScrapTitleMaxLength).then {
                val text = asCharSequence()
                for (i in text.indices.reversed()) if (text[i] == '\n') replace(i, i + 1, " ")
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            onKeyboardAction = { commit() },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                // A hardware Enter saves too, rather than breaking the line.
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                        commit()
                        true
                    } else {
                        false
                    }
                }
                .onFocusChanged { state ->
                    if (focused && !state.isFocused) commit()
                    focused = state.isFocused
                },
        )
        if (title.canRestore) {
            IconButton(
                onClick = {
                    editing = false
                    onRestore()
                },
            ) {
                Icon(
                    imageVector = YoinSymbols.Sparkle,
                    contentDescription = title.restoreLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Matches AlbumMemoryTitleStore's cap. */
private const val ScrapTitleMaxLength = 80

@Composable
private fun Opening(
    piece: ScrapPiece.Opening,
    content: AlbumDetailUiState.Content,
    colors: ScrapbookColors,
    wide: Boolean,
    onEditReview: () -> Unit,
    onRenameTitle: (String) -> Unit,
    onRestoreTitle: () -> Unit,
    settled: Boolean,
    stamped: Boolean,
    onStamped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coverSide = if (wide) ScrapbookLayout.CoverSideWide else ScrapbookLayout.CoverSide
    val emblemSize = if (wide) ScrapbookLayout.EmblemSizeWide else ScrapbookLayout.EmblemSize
    val title = piece.title
    // A long review gets the full width under the cover instead of a narrow column beside it; on a
    // phone the column beside the cover belongs to the title when there is one.
    val reviewBeside = (wide || piece.review == null || weightedLength(piece.review) <= OpeningBesideBudget) &&
        (wide || title == null)
    val review: @Composable (Modifier) -> Unit = { m ->
        if (piece.review != null) {
            ReviewClipping(
                piece.review,
                piece.reviewTilt,
                colors,
                onEditReview,
                m,
                writtenAt = content.userReviewAt?.let { at ->
                    albumLastPlayLabels(at, LocalContext.current.resources).first
                },
            )
        } else {
            BlankReview(piece.reviewTilt, colors, onEditReview, m)
        }
    }
    val albumNote: (@Composable (Modifier) -> Unit)? = piece.albumNote?.let { text ->
        { m -> AlbumNoteSticky(text, piece.albumNoteTilt, colors, m) }
    }
    ScrapStack(mirror = false, modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.scrapSlot(cross = false, overlap = 0.dp).fillMaxWidth()) {
            CoverWithEmblem(
                content = content,
                coverSide = coverSide,
                emblemSize = emblemSize,
                tilt = piece.coverTilt,
                onEmblemClick = onEditReview,
                settled = settled,
                stamped = stamped,
                onStamped = onStamped,
            )
            if (title != null) {
                // the emblem's sideways overhang plus a little air
                Spacer(Modifier.width(emblemSize * ScrapbookLayout.EmblemOverhangX + 8.dp))
                Column(modifier = Modifier.weight(1f).padding(top = 4.dp)) {
                    OpeningTitle(title, wide, onRenameTitle, onRestoreTitle)
                    if (reviewBeside) {
                        // Wide: the review (and the sticky tucked under it) follows the title in its column.
                        Spacer(Modifier.height(16.dp))
                        ScrapStack(mirror = false, modifier = Modifier.fillMaxWidth()) {
                            review(Modifier.scrapSlot(cross = false, overlap = 0.dp).widthIn(max = 420.dp))
                            albumNote?.invoke(
                                Modifier
                                    .scrapSlot(cross = false, overlap = ScrapbookLayout.StickyTuck)
                                    .padding(start = ScrapbookLayout.StickyIndentWide)
                                    .stickyWidth(),
                            )
                        }
                    }
                }
            } else if (reviewBeside) {
                // the emblem's sideways overhang plus a little air
                Spacer(Modifier.width(emblemSize * ScrapbookLayout.EmblemOverhangX + 8.dp))
                if (wide && albumNote != null) {
                    // Wide: the sticky stays in the review's column, tucked under it and clear of the emblem.
                    ScrapStack(mirror = false, modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                        review(Modifier.scrapSlot(cross = false, overlap = 0.dp).widthIn(max = 420.dp))
                        albumNote(
                            Modifier
                                .scrapSlot(cross = false, overlap = ScrapbookLayout.StickyTuck)
                                .padding(start = ScrapbookLayout.StickyIndentWide)
                                .stickyWidth(),
                        )
                    }
                } else {
                    review(
                        Modifier
                            // fill = false: the clipping is as wide as its words, at most 420dp
                            .weight(1f, fill = false)
                            .padding(top = 8.dp)
                            .widthIn(max = 420.dp),
                    )
                }
            }
        }
        if (!reviewBeside) {
            review(Modifier.scrapSlot(cross = false, overlap = (-4).dp).fillMaxWidth())
        }
        if (albumNote != null && !wide) {
            // Phone: a row of its own on the far side. Under the cover it keeps a little air below the emblem
            // (which swells as it stamps); under a full-width review it tucks over the review's foot.
            albumNote(
                Modifier
                    .scrapSlot(
                        cross = true,
                        overlap = if (reviewBeside) -ScrapbookLayout.StickyEmblemGap else ScrapbookLayout.StickyTuck,
                    )
                    .stickyWidth(),
            )
        }
    }
}

/**
 * The album's one album note (D3's sticky): secondaryContainer paper with a folded bottom-right corner, in
 * the listener's own face. Told apart by paper and fold alone — no label, and no rail (the rail is the
 * track notes'). Read-only: nothing writes album notes any more.
 */
@Composable
private fun AlbumNoteSticky(
    text: String,
    tilt: Float,
    colors: ScrapbookColors,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.detail_scrap_cd_album_note, text)
    Text(
        text = text,
        style = userTextStyle().copy(fontSize = 14.5.sp),
        color = colors.onSticky,
        modifier = modifier
            .scrapLayer(tilt, depth = 0.45f)
            .seamFade()
            .clearAndSetSemantics { contentDescription = description }
            .clip(StickyShape)
            .background(colors.sticky)
            .drawBehind { drawPath(stickyFoldPath(size, StickyFold.toPx(), 4.dp.toPx()), colors.stickyFold) }
            .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 18.dp),
    )
}

/** D3's sticky width: 72% of the space it is given, at most 280dp (a fixed sheet, not as wide as its words). */
private fun Modifier.stickyWidth(): Modifier = layout { measurable, constraints ->
    val width = min((constraints.maxWidth * 0.72f).roundToInt(), ScrapbookLayout.StickyMaxWidth.roundToPx())
        .coerceIn(constraints.minWidth, constraints.maxWidth)
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** Weighted length (CJK 2) up to which the review sits beside the phone cover. */
private const val OpeningBesideBudget = 160

@Composable
private fun CoverWithEmblem(
    content: AlbumDetailUiState.Content,
    coverSide: Dp,
    emblemSize: Dp,
    tilt: Float,
    onEmblemClick: () -> Unit,
    settled: Boolean,
    stamped: Boolean,
    onStamped: () -> Unit,
) {
    val spec = content.emblemSpec()
    // The stamp (D3 §8): once per album page, the emblem's own award ceremony plays when the pager comes to
    // rest here; until then it waits uncut. Later score changes rebuild it at rest (no replay).
    val award = rememberAlbumScoreAward(spec, emblemSize, onCover = true)
    LaunchedEffect(award, settled, stamped) {
        if (stamped) return@LaunchedEffect
        if (settled) {
            award.play()
            onStamped()
        } else {
            award.setPending(true)
        }
    }
    // The box reserves the emblem's downward overhang; the sideways one rides into the gap.
    Box(modifier = Modifier.size(width = coverSide, height = coverSide + emblemSize * ScrapbookLayout.EmblemOverhangY)) {
        ExpressiveMediaArtwork(
            model = content.coverArtUrl,
            contentDescription = content.albumName,
            modifier = Modifier
                .size(coverSide)
                .scrapLayer(tilt, depth = 0.25f)
                .seamDissolve(),
            shape = YoinArtworkShapes.Hero,
            fallbackIcon = YoinSymbols.Album,
            border = null,
            shadowElevation = 0.dp,
            tonalElevation = 3.dp,
            requestSizePx = 640,
        )
        AlbumScoreMark(
            spec = spec,
            coverArtUrl = content.coverArtUrl,
            size = emblemSize,
            onCover = true,
            award = award,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = emblemSize * ScrapbookLayout.EmblemOverhangX)
                .scrapLayer(tilt = 0f, depth = 0.6f)
                .seamDissolve()
                .clip(CircleShape)
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.detail_scrap_cd_rate),
                    onClick = onEmblemClick,
                ),
        )
    }
}

/** The listener's review: told apart by its paper colour alone (no label, no rail — the rail is the notes'). */
@Composable
private fun ReviewClipping(
    review: String,
    tilt: Float,
    colors: ScrapbookColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    writtenAt: String? = null,
) {
    Column(
        modifier = modifier
            .scrapLayer(tilt, depth = 0.35f)
            .seamFade()
            .clip(YoinContainerShapes.Card)
            .background(colors.review)
            .clickable(onClickLabel = stringResource(R.string.detail_scrap_cd_edit_comment), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = review,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = GoogleSansFlex,
                fontSize = 15.sp,
                lineHeight = 1.55.em,
                letterSpacing = 0.sp,
            ),
            color = colors.onReview,
        )
        // When it was written, quietly at the foot (owner 2026-10-05).
        if (writtenAt != null) {
            Text(
                text = writtenAt,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onReview.copy(alpha = 0.6f),
            )
        }
    }
}

/**
 * No comment yet: just a pen in a dashed circle that opens the rate & comment
 * sheet — no "Write a review" title or instructions (owner 2026-10-05: keep
 * the page free of text blocks; "没写过就留一个笔的图标").
 */
@Composable
private fun BlankReview(
    tilt: Float,
    colors: ScrapbookColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The slot may be a full row (under the cover on a phone); the pen stays a 48dp circle at its start.
    val writeComment = stringResource(R.string.detail_scrap_cd_write_comment)
    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        Box(
            modifier = Modifier
                .scrapLayer(tilt, depth = 0.35f)
                .seamFade()
                .size(48.dp)
                .clip(CircleShape)
                .dashedOutline(CircleShape, color = { colors.outline }, width = 1.5.dp, gap = 5.dp)
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.detail_scrap_action_write_comment),
                    onClick = onClick,
                )
                .semantics { contentDescription = writeComment },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = YoinSymbols.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun TrackCluster(
    piece: ScrapPiece.TrackCluster,
    colors: ScrapbookColors,
    playing: Boolean,
    answerBudget: Int,
    onPlay: () -> Unit,
    onNote: (ScrapNoteLine) -> Unit,
    onMoreNotes: () -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScrapStack(mirror = piece.mirror, modifier = modifier.padding(top = 6.dp)) {
        // The album's best track needs no caption ("Best on the record" read as
        // AI slop, owner 2026-10-05): its ticket is the ink-filled, big-score one.
        Ticket(
            track = piece.track,
            colors = colors,
            best = piece.best,
            playing = playing,
            mini = false,
            tilt = piece.ticketTilt,
            onClick = onPlay,
            modifier = Modifier
                .scrapSlot(cross = false, overlap = 0.dp)
                .fillMaxWidth(0.66f)
                .widthIn(min = 200.dp, max = 280.dp),
        )
        if (piece.notes.isNotEmpty()) {
            NotesClipping(
                lines = piece.notes.take(ScrapbookRules.NotesShown),
                hidden = piece.hiddenNotes,
                tilt = piece.notesTilt,
                colors = colors,
                onNote = onNote,
                onMore = onMoreNotes,
                modifier = Modifier
                    .scrapSlot(cross = true)
                    .fillMaxWidth(0.78f)
                    .widthIn(max = 330.dp),
            )
        }
        if (piece.ask != null) {
            AskCard(
                ask = piece.ask,
                moreAsks = piece.moreAsks,
                budget = answerBudget,
                tilt = piece.cardTilt,
                colors = colors,
                trackTitle = piece.track.title,
                onClick = onAbout,
                modifier = Modifier
                    .scrapSlot(cross = false)
                    .fillMaxWidth(0.92f)
                    .widthIn(max = 380.dp),
            )
        } else if (piece.aboutLine != null) {
            AboutCard(
                line = piece.aboutLine,
                tilt = piece.cardTilt,
                colors = colors,
                trackTitle = piece.track.title,
                onClick = onAbout,
                modifier = Modifier
                    .scrapSlot(cross = false)
                    .fillMaxWidth(0.92f)
                    .widthIn(max = 380.dp),
            )
        }
        piece.tags.forEachIndexed { i, tag ->
            FactTag(
                tag = tag,
                colors = colors,
                extra = if (i == piece.tags.lastIndex) piece.hiddenTags else 0,
                onClick = onAbout,
                modifier = Modifier
                    .scrapSlot(cross = true, overlap = if (i == 0) ScrapbookLayout.TagOverlap else (-6).dp)
                    .padding(horizontal = 12.dp)
                    .widthIn(max = 340.dp),
            )
        }
    }
}

@Composable
private fun ContactSheet(
    piece: ScrapPiece.ContactSheet,
    colors: ScrapbookColors,
    currentTrackId: String?,
    onPlay: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (piece.tracks.size == 1) {
        val track = piece.tracks.single()
        ScrapStack(mirror = piece.mirror, modifier = modifier.padding(top = 6.dp)) {
            Ticket(
                track = track,
                colors = colors,
                best = false,
                playing = track.songId == currentTrackId,
                mini = true,
                tilt = piece.tilts.single(),
                onClick = { onPlay(track.songId) },
                modifier = Modifier
                    .fillMaxWidth(0.52f)
                    .widthIn(min = 168.dp, max = 220.dp),
            )
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            piece.tracks.forEachIndexed { i, track ->
                Ticket(
                    track = track,
                    colors = colors,
                    best = false,
                    playing = track.songId == currentTrackId,
                    mini = true,
                    tilt = piece.tilts[i],
                    onClick = { onPlay(track.songId) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = if (i % 2 == 1) 12.dp else 0.dp),
                )
            }
        }
    }
}

/**
 * A ticket stub: the number in a perforated stub, then title, duration, and plays, and the score in the
 * album ink (the Memories diary's way of writing a track score). The heart sticker marks a liked track.
 */
@Composable
private fun Ticket(
    track: ScrapTrack,
    colors: ScrapbookColors,
    best: Boolean,
    playing: Boolean,
    mini: Boolean,
    tilt: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        targetValue = when {
            best -> colors.ink
            playing -> colors.ticketNow
            else -> colors.ticket
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "scrapTicket",
    )
    val ink by animateColorAsState(
        targetValue = when {
            best -> colors.onInk
            playing -> colors.onTicketNow
            else -> colors.onTicket
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "scrapTicketInk",
    )
    val scoreInk = if (best || playing) ink else colors.ink
    val meta = scrapTicketMeta(track.durationSec, track.plays)
    val description = ticketContentDescription(track)
    val playingLabel = stringResource(R.string.detail_scrap_cd_playing)
    val playLabel = stringResource(R.string.detail_scrap_cd_play_track, track.title)
    val stub = TicketStub
    Box(
        modifier = modifier
            .scrapLayer(tilt, depth = 0.5f)
            .seamFade()
            .clearAndSetSemantics {
                contentDescription = description
                if (playing) stateDescription = playingLabel
                role = Role.Button
                onClick(label = playLabel) {
                    onClick()
                    true
                }
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (mini) 56.dp else 64.dp)
                .clip(TicketShape)
                .background(container)
                .clickable(onClick = onClick)
                .drawBehind {
                    // the perforation between stub and body
                    val x = stub.toPx()
                    drawLine(
                        color = ink.copy(alpha = 0.24f),
                        start = Offset(x, 8.dp.toPx()),
                        end = Offset(x, size.height - 8.dp.toPx()),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = track.number.toString().padStart(2, '0'),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFeatureSettings = "tnum",
                    fontWeight = FontWeight.Medium,
                    fontSize = if (mini) 16.sp else 18.sp,
                    letterSpacing = 0.sp,
                ),
                color = ink.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
                modifier = Modifier.width(stub),
            )
            val scoreText: (@Composable () -> Unit)? = track.score?.let { score ->
                {
                    Text(
                        text = scrapScoreText(score),
                        style = MaterialTheme.typography.titleMedium
                            .copy(fontWeight = FontWeight.SemiBold, fontSize = if (best) 22.sp else 16.sp)
                            .withTabularFigures(),
                        color = scoreInk,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 13.dp, top = 10.dp, bottom = 10.dp, end = if (mini) 12.dp else 8.dp),
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = if (mini) 15.sp else 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 1.25.em,
                    ),
                    color = ink,
                )
                // A small ticket gives the title the body's whole width: its score
                // trails the meta line instead of taking a column of its own.
                if (meta != null || (mini && scoreText != null)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (meta != null) {
                            ScrapTicketMetaLine(
                                meta = meta,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontSize = 12.sp,
                                    letterSpacing = 0.sp,
                                ),
                                color = ink.copy(alpha = 0.72f),
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        if (mini) scoreText?.invoke()
                    }
                }
            }
            if (!mini && scoreText != null) {
                Box(Modifier.padding(end = 14.dp)) { scoreText() }
            }
        }
        if (track.starred) {
            Sticker(
                shape = MaterialShapes.Heart.toShapeCompat(),
                size = 26.dp,
                color = colors.ink,
                tilt = track.heartTilt,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-9).dp, y = (-10).dp),
            )
        }
    }
}

/** The stub's width (the number column) and where the perforation and notches sit. */
private val TicketStub = 48.dp

/** A fact tag's corners: a full pill at one line, never an oval when the value wraps. */
private val FactTagShape = RoundedCornerShape(16.dp)

/**
 * A ticket's meta: [duration], then [plays]. [ScrapTicketMetaLine] drops the
 * plays when the row is too narrow and leaves the duration. [plays] is 0 when
 * there are none; [duration] is null when the track has no length.
 */
@Immutable
internal data class ScrapTicketMeta(val duration: String?, val plays: Int)

@Composable
private fun ticketContentDescription(track: ScrapTrack): String {
    val score = track.score?.let(::scrapScoreText)
    val liked = track.starred
    val plays = track.plays
    return when {
        score != null && liked && plays > 0 -> stringResource(
            R.string.detail_scrap_cd_track_rated_liked_plays,
            track.number,
            track.title,
            score,
            plays,
        )
        score != null && liked -> stringResource(
            R.string.detail_scrap_cd_track_rated_liked,
            track.number,
            track.title,
            score,
        )
        score != null && plays > 0 -> stringResource(
            R.string.detail_scrap_cd_track_rated_plays,
            track.number,
            track.title,
            score,
            plays,
        )
        liked && plays > 0 -> stringResource(
            R.string.detail_scrap_cd_track_liked_plays,
            track.number,
            track.title,
            plays,
        )
        score != null -> stringResource(R.string.detail_scrap_cd_track_rated, track.number, track.title, score)
        liked -> stringResource(R.string.detail_scrap_cd_track_liked, track.number, track.title)
        plays > 0 -> stringResource(R.string.detail_scrap_cd_track_plays, track.number, track.title, plays)
        else -> stringResource(R.string.detail_scrap_cd_track, track.number, track.title)
    }
}

/** [ScrapTicketMeta] for a track's [durationSec] and [plays]; null when it has neither. */
internal fun scrapTicketMeta(durationSec: Int?, plays: Int): ScrapTicketMeta? {
    val duration = durationSec?.let(::formatTrackDuration)
    val playCount = plays.takeIf { it > 0 } ?: 0
    if (duration == null && playCount == 0) return null
    return ScrapTicketMeta(duration = duration, plays = playCount)
}

/**
 * Receipt glyphs stay the English [receiptLines] / [relativeDayLabel] already painted.
 * Matching resources are read so shrinking keeps them.
 */
@Composable
private fun receiptGlyph(text: String): String {
    val plays = stringResource(R.string.detail_scrap_receipt_plays)
    val daysSince = stringResource(R.string.detail_scrap_receipt_days_since)
    val lastPlayed = stringResource(R.string.detail_scrap_receipt_last_played)
    val rated = stringResource(R.string.detail_scrap_receipt_rated)
    val notes = stringResource(R.string.detail_scrap_receipt_notes)
    val asked = stringResource(R.string.detail_scrap_receipt_asked)
    val today = stringResource(R.string.detail_scrap_receipt_today)
    val yesterday = stringResource(R.string.detail_scrap_receipt_yesterday)
    val daysAgo = pluralStringResource(R.plurals.detail_scrap_receipt_days_ago, 3, 3)
    val date = stringResource(R.string.detail_scrap_receipt_date)
    val dateYear = stringResource(R.string.detail_scrap_receipt_date_year)
    return when (text) {
        plays -> plays
        daysSince -> daysSince
        lastPlayed -> lastPlayed
        rated -> rated
        notes -> notes
        asked -> asked
        today -> today
        yesterday -> yesterday
        daysAgo -> daysAgo
        date -> date
        dateYear -> dateYear
        else -> text
    }
}

@Composable
private fun scrapFactLabel(label: String): String = when (label) {
    "Created" -> stringResource(R.string.detail_scrap_fact_created)
    "Recorded in" -> stringResource(R.string.detail_scrap_fact_recorded)
    "Lyricist" -> stringResource(R.string.detail_scrap_fact_lyricist)
    "Composer" -> stringResource(R.string.detail_scrap_fact_composer)
    "Producer" -> stringResource(R.string.detail_scrap_fact_producer)
    "About" -> stringResource(R.string.detail_scrap_fact_about)
    else -> label
}

/**
 * Duration, then the play count, on one line when they fit. A narrow ticket
 * keeps both and grows instead: the plays move under the duration (2026-10-08
 * owner — the stub has a min height only, so a taller one is fine; dropping
 * either fact was not). Decided in the layout pass from the one-line width, so
 * a width change re-measures without recomposing.
 */
@Composable
internal fun ScrapTicketMetaLine(
    meta: ScrapTicketMeta,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val groups = buildList {
        meta.duration?.let { add(MetaGroup.Stat(it)) }
        if (meta.plays > 0) {
            add(
                MetaGroup.Stat(
                    meta.plays.toString(),
                    pluralStringResource(R.plurals.detail_unit_play, meta.plays),
                ),
            )
        }
    }
    Layout(
        content = {
            DetailMetaLine(groups = groups, style = style, color = color)
            Column {
                groups.forEach { group -> DetailMetaLine(groups = listOf(group), style = style, color = color) }
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val oneLine = measurables[0]
        val fits = oneLine.maxIntrinsicWidth(constraints.maxHeight) <= constraints.maxWidth
        val placeable = (if (fits) oneLine else measurables[1]).measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.constrainWidth(placeable.width), constraints.constrainHeight(placeable.height)) {
            placeable.placeRelative(0, 0)
        }
    }
}

@Composable
private fun NotesClipping(
    lines: List<ScrapNoteLine>,
    hidden: Int,
    tilt: Float,
    colors: ScrapbookColors,
    onNote: (ScrapNoteLine) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .scrapLayer(tilt, depth = 0.4f)
            .seamFade()
            .clip(YoinContainerShapes.Card)
            .background(colors.note)
            .journalRail(colors.ink)
            .padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
    ) {
        lines.forEach { line -> NoteLine(line, colors, onClick = { onNote(line) }) }
        if (hidden > 0) {
            Text(
                text = pluralStringResource(R.plurals.detail_scrap_hidden_notes, hidden, hidden),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
                color = colors.ink,
                modifier = Modifier
                    .clip(YoinContainerShapes.ListRow)
                    .clickable(role = Role.Button, onClick = onMore)
                    .padding(start = NoteStampColumn, top = 6.dp, bottom = 4.dp, end = 8.dp),
            )
        }
    }
}

private val NoteStampColumn = 42.dp

/** A note, lyric-plain: the time stamp (empty without an anchor) and the words on one baseline. */
@Composable
private fun NoteLine(line: ScrapNoteLine, colors: ScrapbookColors, onClick: () -> Unit) {
    val at = line.positionMs?.let(::formatNotePosition)
    val description = if (at != null) {
        stringResource(R.string.detail_scrap_cd_note_at, at, line.text)
    } else {
        stringResource(R.string.detail_scrap_cd_note, line.text)
    }
    val playLabel = if (at != null) {
        stringResource(R.string.detail_scrap_cd_play_from, at)
    } else {
        stringResource(R.string.detail_scrap_cd_play)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(YoinContainerShapes.ListRow)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(label = playLabel) {
                    onClick()
                    true
                }
            }
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = at.orEmpty(),
            style = MaterialTheme.typography.labelMedium.copy(
                fontFeatureSettings = "tnum",
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                letterSpacing = 0.sp,
            ),
            color = colors.ink,
            modifier = Modifier
                .width(NoteStampColumn)
                .alignByBaseline(),
        )
        Text(
            text = line.text,
            style = userTextStyle(),
            color = colors.onNote,
            modifier = Modifier
                .weight(1f)
                .alignByBaseline(),
        )
    }
}

@Composable
private fun userTextStyle() = MaterialTheme.typography.bodyLarge.copy(
    fontFamily = GoogleSansFlex,
    fontSize = 15.sp,
    lineHeight = 1.5.em,
    letterSpacing = 0.sp,
)

/** A question asked in Now Playing: the listener's words, Gemini's title and whole sentences of its answer. */
@Composable
private fun AskCard(
    ask: AlbumScrapbookAsk,
    moreAsks: Int,
    budget: Int,
    tilt: Float,
    colors: ScrapbookColors,
    trackTitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val excerpt = remember(ask.answer, budget) { answerExcerpt(ask.answer, budget) }
    val asked = stringResource(R.string.detail_scrap_cd_ask, trackTitle, ask.title ?: ask.question)
    val readAll = stringResource(R.string.detail_scrap_cd_read_all)
    Box(modifier = modifier.scrapLayer(tilt, depth = 0.35f).seamFade()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(YoinContainerShapes.Card)
                .background(colors.ask)
                .semantics(mergeDescendants = true) {
                    contentDescription = asked
                }
                .clickable(role = Role.Button, onClickLabel = readAll, onClick = onClick)
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
        ) {
            Text(
                text = ask.question,
                style = userTextStyle().copy(fontSize = 14.sp, lineHeight = 1.45.em),
                color = colors.onAsk.copy(alpha = 0.8f),
            )
            ask.title?.let { title ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, lineHeight = 1.3.em),
                    color = colors.onAsk,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Text(
                text = markdownBoldAnnotatedString(excerpt.text),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp, lineHeight = 1.55.em),
                color = colors.onAsk,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (excerpt.truncated || moreAsks > 0) {
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.detail_scrap_read_all),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.sp,
                        ),
                        color = colors.askMark,
                    )
                    if (moreAsks > 0) {
                        Text(
                            text = "+$moreAsks",
                            style = MaterialTheme.typography.labelSmall.withTabularFigures(),
                            color = colors.onAskMark,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(colors.askMark)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
        Sticker(
            shape = MaterialShapes.Clover4Leaf.toShapeCompat(),
            size = 28.dp,
            color = colors.askMark,
            tilt = tilt * 6f,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = (-11).dp, y = (-12).dp),
        ) {
            Text(
                text = stringResource(R.string.detail_scrap_q),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
                color = colors.onAskMark,
            )
        }
    }
}

/** The About paragraph's first sentence, for a track that has About but no question. */
@Composable
private fun AboutCard(
    line: String,
    tilt: Float,
    colors: ScrapbookColors,
    trackTitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.detail_scrap_cd_about, trackTitle, line)
    val readAll = stringResource(R.string.detail_scrap_cd_read_all)
    Column(
        modifier = modifier
            .scrapLayer(tilt, depth = 0.35f)
            .seamFade()
            .clip(YoinContainerShapes.Card)
            .background(colors.ask)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .clickable(role = Role.Button, onClickLabel = readAll, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        AlbumSectionLabel(text = stringResource(R.string.detail_scrap_about))
        Text(
            text = markdownBoldAnnotatedString(line),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp, lineHeight = 1.55.em),
            color = colors.onAsk,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun FactTag(
    tag: ScrapTag,
    colors: ScrapbookColors,
    extra: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .scrapLayer(tag.tilt, depth = 0.7f)
            .seamFade()
            // A capped radius, not CircleShape: a long About value wraps, and a
            // circle clip turned the tag into a fat oval that cut its text off
            // (owner's foldable, 2026-10-05). Single-line tags still read as pills.
            .clip(FactTagShape)
            .background(colors.tag)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = scrapFactLabel(tag.label),
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 11.5.sp,
                letterSpacing = 0.sp,
            ),
            color = colors.onTag.copy(alpha = 0.75f),
            modifier = Modifier.alignByBaseline(),
        )
        Text(
            text = if (extra > 0) {
                stringResource(R.string.detail_scrap_fact_more, tag.value, extra)
            } else {
                tag.value
            },
            style = MaterialTheme.typography.labelLarge.copy(lineHeight = 1.35.em, letterSpacing = 0.sp),
            color = colors.onTag,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

@Composable
private fun NotYet(
    @Suppress("UNUSED_PARAMETER") piece: ScrapPiece.NotYet,
    colors: ScrapbookColors,
    @Suppress("UNUSED_PARAMETER") onPlay: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // No label, no moulds, no instructions: one line, and the tracks stay on page 1.
    Text(
        text = stringResource(R.string.detail_scrap_not_yet),
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 1.5.em),
        color = colors.muted,
        modifier = modifier
            .widthIn(max = 420.dp)
            .seamFade(),
    )
}

@Composable
private fun Receipt(piece: ScrapPiece.Receipt, colors: ScrapbookColors, modifier: Modifier = Modifier) {
    val mono = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.5.sp,
        lineHeight = 1.9.em,
        letterSpacing = 0.sp,
    )
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 330.dp)
                .fillMaxWidth()
                .scrapLayer(piece.tilt, depth = 0.35f)
                .seamFade()
                .clip(ReceiptShape)
                .background(colors.receipt)
                .semantics(mergeDescendants = true) {}
                .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 24.dp),
        ) {
            Text(
                text = piece.header,
                style = mono.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
                color = colors.onReceipt.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 6.dp),
            )
            piece.lines.forEach { (label, value) ->
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(text = receiptGlyph(label), style = mono, color = colors.onReceipt)
                    Spacer(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp)
                            .height(12.dp)
                            .drawBehind {
                                drawLine(
                                    color = colors.onReceipt.copy(alpha = 0.3f),
                                    start = Offset(0f, size.height - 4.dp.toPx()),
                                    end = Offset(size.width, size.height - 4.dp.toPx()),
                                    strokeWidth = 1.5.dp.toPx(),
                                    cap = StrokeCap.Round,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, 4.dp.toPx())),
                                )
                            },
                    )
                    Text(text = receiptGlyph(value), style = mono, color = colors.onReceipt)
                }
            }
        }
    }
}

/** A flat MaterialShapes sticker; [content] sits upright-ish with it. */
@Composable
private fun Sticker(
    shape: Shape,
    size: Dp,
    color: Color,
    tilt: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    Box(
        modifier = modifier
            .size(size)
            // rides on its host piece's layer: only a little extra lag of its own
            .scrapLayer(tilt, depth = 0.1f)
            .clip(shape)
            .background(color)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** M3's own polygon → Shape (it remembers the conversion itself). */
@Composable
private fun androidx.graphics.shapes.RoundedPolygon.toShapeCompat(): Shape = toShape()

// ---------------------------------------------------------------------------
// Sheets (full text).
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScrapAboutSheetContent(sheet: ScrapAboutSheet, colors: ScrapbookColors, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    YoinModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            AlbumSectionLabel(text = "${sheet.trackNumber.toString().padStart(2, '0')}  ${sheet.trackTitle}")
            sheet.asks.forEachIndexed { i, ask ->
                Text(
                    text = ask.question,
                    style = userTextStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = if (i == 0) 14.dp else 28.dp),
                )
                ask.title?.let { title ->
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                Text(
                    text = markdownBoldAnnotatedString(ask.answer),
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 1.6.em),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            sheet.facts.forEach { fact ->
                Text(
                    text = scrapFactLabel(fact.label),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.ink,
                    modifier = Modifier.padding(top = 22.dp),
                )
                Text(
                    text = markdownBoldAnnotatedString(fact.value),
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 1.5.em),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScrapNotesSheet(
    cluster: ScrapPiece.TrackCluster,
    colors: ScrapbookColors,
    onNote: (ScrapNoteLine) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    YoinModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            AlbumSectionLabel(
                text = "${cluster.track.number.toString().padStart(2, '0')}  ${cluster.track.title}",
            )
            Column(
                modifier = Modifier
                    .padding(top = 14.dp)
                    .fillMaxWidth()
                    .clip(YoinContainerShapes.Card)
                    .background(colors.note)
                    .journalRail(colors.ink)
                    .padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            ) {
                cluster.notes.forEach { line -> NoteLine(line, colors, onClick = { onNote(line) }) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Paper, rail, shapes.
// ---------------------------------------------------------------------------

/** The dot grid (16dp pitch) as one repeated tile: a single draw call however long the page. */
private fun Modifier.dottedPaper(color: Color): Modifier = drawWithCache {
    val pitch = ScrapbookLayout.DotPitch.toPx().roundToInt().coerceAtLeast(2)
    val tile = ImageBitmap(pitch, pitch)
    Canvas(tile).drawCircle(
        center = Offset(pitch / 2f, pitch / 2f),
        radius = ScrapbookLayout.DotRadius.toPx(),
        paint = Paint().apply { this.color = color },
    )
    val brush = ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    onDrawBehind { drawRect(brush) }
}

/** The journal rail down a notes clipping's leading edge (the rail is the track notes' mark alone). */
private fun Modifier.journalRail(color: Color): Modifier = drawBehind {
    val inset = 14.dp.toPx()
    val width = 3.dp.toPx()
    if (size.height <= inset * 2) return@drawBehind
    drawRoundRect(
        color = color,
        topLeft = Offset(11.dp.toPx(), inset),
        size = Size(width, size.height - inset * 2),
        cornerRadius = CornerRadius(width / 2, width / 2),
    )
}

/** Card corners (16dp continuous) with a 7dp half-circle notch top and bottom at the stub's perforation. */
private val TicketShape: Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val card = YoinContainerShapes.Card.createOutline(size, layoutDirection, density)
        val body = Path().apply {
            when (card) {
                is Outline.Generic -> addPath(card.path)
                is Outline.Rounded -> addRoundRect(card.roundRect)
                is Outline.Rectangle -> addRect(card.rect)
            }
        }
        val r = with(density) { 7.dp.toPx() }
        val x = with(density) { TicketStub.toPx() }
        val notches = Path().apply {
            addOval(Rect(center = Offset(x, 0f), radius = r))
            addOval(Rect(center = Offset(x, size.height), radius = r))
        }
        return Outline.Generic(Path.combine(PathOperation.Difference, body, notches))
    }
}

/** The sticky's corner fold: legs of this length (D3: a 12px cut on the diagonal ≈ 17dp legs). */
private val StickyFold = 17.dp

/** How much of the secondary ink the fold carries over the sticky's paper (D3 prototype: 32%). */
private const val StickyFoldInk = 0.32f

/** Card corners (16dp continuous) with the bottom-right corner cut off on the diagonal where it folds over. */
private val StickyShape: Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val card = YoinContainerShapes.Card.createOutline(size, layoutDirection, density)
        val body = Path().apply {
            when (card) {
                is Outline.Generic -> addPath(card.path)
                is Outline.Rounded -> addRoundRect(card.roundRect)
                is Outline.Rectangle -> addRect(card.rect)
            }
        }
        val f = with(density) { StickyFold.toPx() }
        val w = size.width
        val h = size.height
        // everything past the fold line, with margin outside the box
        val corner = Path().apply {
            moveTo(w - f, h)
            lineTo(w, h - f)
            lineTo(w + f, h - f)
            lineTo(w + f, h + f)
            lineTo(w - f, h + f)
            close()
        }
        return Outline.Generic(Path.combine(PathOperation.Difference, body, corner))
    }
}

/** The folded-over flap: the triangle on the paper's side of the fold line, its inner corner rounded by [r]. */
private fun stickyFoldPath(size: Size, f: Float, r: Float): Path = Path().apply {
    val x = size.width - f
    val y = size.height - f
    moveTo(x, size.height)
    lineTo(x, y + r)
    quadraticTo(x, y, x + r, y)
    lineTo(size.width, y)
    close()
}

/** A Panel top (20dp corners) over a torn, zig-zag bottom edge (14dp teeth). */
private val ReceiptShape: Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val corner = with(density) { 20.dp.toPx() }.coerceAtMost(min(size.width, size.height) / 2)
        val tooth = with(density) { 14.dp.toPx() }
        val depth = with(density) { 8.dp.toPx() }
        val path = Path().apply {
            moveTo(0f, corner)
            arcTo(Rect(0f, 0f, corner * 2, corner * 2), 180f, 90f, false)
            lineTo(size.width - corner, 0f)
            arcTo(Rect(size.width - corner * 2, 0f, size.width, corner * 2), 270f, 90f, false)
            lineTo(size.width, size.height - depth)
            val teeth = max(1, (size.width / tooth).roundToInt())
            val step = size.width / teeth
            var x = size.width
            repeat(teeth) {
                lineTo(x - step / 2, size.height)
                x -= step
                lineTo(x, size.height - depth)
            }
            close()
        }
        return Outline.Generic(path)
    }
}

// ---------------------------------------------------------------------------
// Previews.
// ---------------------------------------------------------------------------

@Preview(name = "Scrapbook · phone", widthDp = 390, heightDp = 1500, showBackground = true)
@Composable
private fun AlbumScrapbookPagePreview() {
    YoinTheme { ScrapbookPreviewContent(rich = true) }
}

@Preview(name = "Scrapbook · empty", widthDp = 390, heightDp = 700, showBackground = true)
@Composable
private fun AlbumScrapbookEmptyPreview() {
    YoinTheme { ScrapbookPreviewContent(rich = false) }
}

@Preview(name = "Scrapbook · tablet", widthDp = 800, heightDp = 1280, showBackground = true)
@Composable
private fun AlbumScrapbookTabletPreview() {
    YoinTheme {
        ProvidePreviewWindow(widthDp = 800, heightDp = 1280) { ScrapbookPreviewContent(rich = true) }
    }
}

@Composable
private fun ScrapbookPreviewContent(rich: Boolean) {
    val content = scrapbookPreviewAlbum(rich)
    val data = if (rich) scrapbookPreviewData() else AlbumScrapbookData.Empty
    AlbumScrapbookPage(
        state = AlbumScrapbookUiState.Ready(buildAlbumScrapbook(content, data, nowMillis = PreviewNow)),
        content = content,
        colors = rememberScrapbookColors(MaterialTheme.colorScheme),
        currentTrackId = if (rich) "subsonic:t7" else null,
        pageOffset = { 0f },
        onSongClick = {},
        onNoteMomentClick = { _, _ -> },
        onEditReview = {},
    )
}

private const val PreviewNow = 1_791_000_000_000L

private fun scrapbookPreviewAlbum(rich: Boolean): AlbumDetailUiState.Content {
    val titles = listOf(
        "Nikes", "Ivy", "Pink + White", "Be Yourself", "Solo", "Skyline To", "Self Control", "Good Guy",
        "Nights", "Solo (Reprise)", "Pretty Sweet", "Facebook Story", "Close to You", "White Ferrari",
    )
    val rated = setOf(1, 2, 3, 5, 6, 7, 9, 14)
    return AlbumDetailUiState.Content(
        albumId = "subsonic:al-blonde",
        albumName = "Blonde",
        artistName = "Frank Ocean",
        artistId = null,
        coverArtId = null,
        coverArtUrl = null,
        year = 2016,
        songCount = titles.size,
        totalDuration = 3600,
        songs = titles.mapIndexed { i, title ->
            AlbumSong("subsonic:t${i + 1}", title, "Frank Ocean", i + 1, 180 + i * 7, isStarred = rich && i + 1 in setOf(2, 7))
        },
        userReview = if (rich) "夏天结束前一定会重听的一张。前半张像白天，Nights 之后突然天黑了。" else "",
        averageTrackRating = if (rich) 8.9f else null,
        ratedTrackCount = if (rich) rated.size else 0,
        ratedSongIds = if (rich) rated.mapTo(HashSet()) { "subsonic:t$it" } else emptySet(),
    )
}

private fun scrapbookPreviewData(): AlbumScrapbookData {
    fun id(n: Int) = MediaId(MediaId.PROVIDER_SUBSONIC, "t$n")
    return AlbumScrapbookData(
        ratings = mapOf(1 to 8.4f, 2 to 9.3f, 3 to 9.1f, 5 to 8.8f, 6 to 8.2f, 7 to 9.6f, 9 to 9.4f, 14 to 9.5f)
            .mapKeys { id(it.key) },
        notes = mapOf(
            id(2) to listOf(AlbumScrapbookNote("n1", "前奏那把吉他一进来，就想起高三的夏天", 52_000, 1)),
            id(7) to listOf(
                AlbumScrapbookNote("n2", "这里人声一层一层叠上来，每次都起鸡皮疙瘩", 161_000, 2),
                AlbumScrapbookNote("n3", "结尾的吉他 loop 可以单曲循环一整晚", 238_000, 3),
            ),
        ),
        about = mapOf(
            id(9) to AlbumScrapbookAbout(
                asks = listOf(
                    AlbumScrapbookAsk(
                        question = "为什么中间突然换了一个 beat？",
                        title = "中点的切换",
                        answer = "Nights 在 3:30 左右整体换了一套编曲，前后像两首歌。常见的说法是，这个切换点正好落在整张 Blonde 的时间中点。",
                    ),
                ),
            ),
            id(3) to AlbumScrapbookAbout(
                facts = listOf(AlbumScrapbookFact(SongAboutEntry.CANON_PRODUCER, "Pharrell Williams · Frank Ocean")),
            ),
        ),
        playCounts = mapOf(id(2) to 11, id(7) to 23, id(9) to 14, id(14) to 19),
        albumNote = AlbumScrapbookAlbumNote(
            id = "an1",
            content = "第一次完整听是在去机场的路上，耳机里只有这一张。",
            updatedAt = 1L,
        ),
        plays = AlbumScrapbookPlays(count = 64, firstPlayedAt = PreviewNow - 205L * 86_400_000L, lastPlayedAt = PreviewNow - 2L * 86_400_000L),
        memoryTitle = "从白天听到天黑",
    )
}
