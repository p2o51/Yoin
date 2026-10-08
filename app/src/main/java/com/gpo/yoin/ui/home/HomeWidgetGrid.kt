package com.gpo.yoin.ui.home

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.common.asString
import com.gpo.yoin.ui.component.ExpressiveBackdropColors
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.rememberPressMorphShape
import com.gpo.yoin.ui.component.elasticPress
import com.gpo.yoin.ui.component.noRippleClickable
import com.gpo.yoin.ui.component.rememberExpressiveBackdropColors
import com.gpo.yoin.ui.component.seamDissolve
import com.gpo.yoin.ui.component.seamFade
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.home.edit.HomeRowsBody
import com.gpo.yoin.ui.home.edit.LocalHomeEditCardScope
import com.gpo.yoin.ui.home.edit.homeEditCard
import com.gpo.yoin.ui.home.edit.homeRowsCard
import com.gpo.yoin.ui.home.edit.homeEditInteractive
import com.gpo.yoin.ui.home.edit.homeEditSectionTitle
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.theme.GoogleSansFlex
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinSerifTitle
import com.gpo.yoin.ui.theme.withTabularFigures
import java.util.Calendar

// The compact "1×1" cover is 100dp wide in the Figma; the backdrop shape fills
// it while the artwork sits at ~73/100 in the bottom-right so the shape peeks
// out around it. The wide "1×2" card reuses that same 100dp cover on the left.
// Panes of 3+ feed units size it off the column instead (HomeWidgetGridAdaptive.kt).
private val WidgetCoverSize = 100.dp
private const val WidgetArtworkFraction = 0.72f

// Column counts (jbiGridSpec): a phone keeps the Figma 3-column, 12-cell
// grid; panes of 3+ feed units seat a deeper shelf on a seeded template of
// phone-sized columns (HomeJbiTemplate.kt).

/**
 * Which backdrop shape sits behind a cover — the "题材" mapping recovered from
 * the original ExpressiveBackdrop: entity type → Material 3 expressive shape.
 */
internal enum class WidgetShapeKind {
    Album,
    Song,
    Playlist,
    Artist,
}

internal fun MemoryEntityType.toWidgetShapeKind(): WidgetShapeKind = when (this) {
    MemoryEntityType.ALBUM -> WidgetShapeKind.Album
    MemoryEntityType.SONG -> WidgetShapeKind.Song
    MemoryEntityType.PLAYLIST -> WidgetShapeKind.Playlist
}

/**
 * The design-language section heading shared by the home feed sections.
 * Inside a Home edit block it offers TalkBack "Edit Home" outside edit mode;
 * anywhere else it is plain text.
 */
@Composable
internal fun HomeSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.SemiBold,
            fontFamily = GoogleSansFlex,
            fontSize = 18.sp,
        ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .homeEditSectionTitle()
            .seamFade(),
    )
}

/**
 * The home widget grid (Figma node 405:361, "Memories" visual language). On a
 * phone: a masonry over a 3-column grid where a wide "1×2" card spans two
 * columns and shares its row with compact "1×1" covers; unpaired covers fill
 * full rows. On panes of 3+ feed units: a seeded template of phone-sized
 * columns mixing 2×2 features, standing / lying signal cards and covers
 * (HomeJbiTemplate.kt). Cards are plain taps — album/playlist push their
 * detail, songs play, memory cards push into the Memories deck. No
 * predictive-back choreography.
 */
@Composable
internal fun HomeWidgetGridSection(
    title: String,
    cards: List<HomeWidgetCard>,
    extractBackdropColors: Boolean,
    onCardClick: (HomeWidgetTarget) -> Unit,
    modifier: Modifier = Modifier,
    // The row preset (D1, HomeRowPresets.kt); L is the composition from before presets.
    rows: HomeRowPreset = HomeRowPreset.Default,
) {
    if (cards.isEmpty()) return
    // Columns and path follow the container (HomeFeedDensity: jbiGridSpec) —
    // not LayoutMode: panes of 3+ feed units get as many phone-sized cover
    // columns as fit. Phones (≤ 2 units) and a landscape handset keep the
    // Row + weights + 100dp path below, byte for byte. Legacy100 is the hint
    // trial's before shot.
    val windowInfo = LocalYoinWindowInfo.current
    val coverFit = LocalHomeHintVariant.current.coverFit
    val gridSpec = when {
        coverFit == JbiCoverFit.Legacy100 ->
            legacyJbiGridSpec(windowInfo.feedUnits, windowInfo.isCompactHeight).copy(followColumn = false)
        else -> jbiGridSpec(windowInfo.feedUnits, windowInfo.feedCoverColumns, windowInfo.isCompactHeight)
    }
    // Panes of 3+ feed units seat the shelf on a seeded template (HomeJbiTemplate.kt):
    // the same cards always land the same way. Null = too few cards for even two
    // rows — the plain column grid below takes them. This is the L template;
    // the other presets grow or trim it (jbiRowLadder).
    val layout = remember(cards, gridSpec) {
        if (gridSpec.templated) {
            jbiLayout(
                cards = cards,
                columns = gridSpec.columns,
                rows = gridSpec.rows,
                seed = jbiLayoutSeed(cards),
            )
        } else {
            null
        }
    }
    val content = JbiContent(gridSpec, cards, layout, rows)
    val reduced = LocalMotionProfile.current == MotionProfile.AdaptiveReduced
    val heightSpec = YoinMotion.spatialSpring<Float>()
    val cardScope = LocalHomeEditCardScope.current

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HomeSectionTitle(text = title)
        // A column-count, template or path change re-packs the grid: crossfade
        // it, with the section's height on a spatial spring — never a hard cut.
        // A row preset updates in place (edit mode's resize interpolates it).
        AnimatedContent(
            targetState = content,
            contentKey = { it.key },
            transitionSpec = {
                if (reduced) {
                    ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
                } else {
                    // No SizeTransform: its animated WIDTH lags a container
                    // that is shrinking under a column spring, and the parent
                    // centres the overflow. springHeight eases the height.
                    YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                        YoinMotion.fadeOut(role = YoinMotionRole.Standard) using null
                }
            },
            label = "jbiGrid",
            modifier = Modifier.springHeight(spec = heightSpec, key = content.key, enabled = !reduced),
        ) { state ->
            val columns = state.spec.columns
            val followColumn = state.spec.followColumn
            // The packed paths keep the phone's 3 × 4 shelf at L (rows × columns at
            // the other presets); the VM supplies a deeper list for the templates.
            val ladder = remember(state.spec, state.cards, state.layout) {
                jbiRowLadder(state.spec, state.cards, state.layout)
            }
            Box(Modifier.heightOfIncomingOnly { transition.targetState == EnterExitState.PostExit }) {
                HomeRowsBody(
                    track = cardScope?.rows,
                    engine = cardScope?.rowsEngine,
                    ladder = ladder,
                    preset = state.rows,
                    reduced = reduced,
                    owner = transition.targetState != EnterExitState.PostExit,
                ) { stop ->
                    when (val composition = stop.payload) {
                        is JbiPresetLayout.Template -> JbiTemplateGrid(
                            seated = composition.layout,
                            coverFit = coverFit,
                            extractBackdropColors = extractBackdropColors,
                            onCardClick = onCardClick,
                        )
                        is JbiPresetLayout.Packed -> JbiPackedGrid(
                            rows = composition.rows,
                            columns = columns,
                            followColumn = followColumn,
                            coverFit = coverFit,
                            extractBackdropColors = extractBackdropColors,
                            onCardClick = onCardClick,
                        )
                    }
                }
            }
        }
    }
}

/** What a Jump Back In card renders as, for the row resize's matching (a cover, or a lying / standing signal card). */
private enum class JbiCardLook { Cover, Wide, Tall }

/** A seeded template of phone-sized columns (HomeJbiTemplate.kt). */
@Composable
private fun JbiTemplateGrid(
    seated: JbiLayout,
    coverFit: JbiCoverFit,
    extractBackdropColors: Boolean,
    onCardClick: (HomeWidgetTarget) -> Unit,
) {
    // Phone-sized columns, phone-sized covers: the column's
    // rhythm, but never past 128dp (a very wide window gets more
    // room per column once the 10 columns run out).
    val templateFit = if (coverFit == JbiCoverFit.PhoneRhythm) JbiCoverFit.Capped128 else coverFit
    val coverRequestPx = with(LocalDensity.current) { JbiCoverMax.roundToPx() }
    // Edit-mode card order = the template's reading order.
    val editIndex = remember(seated) { seated.cells.editCardIndex { it.card } }
    JbiSpanGrid(layout = seated, fit = templateFit, modifier = Modifier.fillMaxWidth()) { cell ->
        val card = cell.card
        val look = when (cell.piece.kind) {
            JbiPieceKind.TallSignal -> JbiCardLook.Tall
            JbiPieceKind.WideSignal -> JbiCardLook.Wide
            JbiPieceKind.Cover -> JbiCardLook.Cover
        }
        val editCard = Modifier
            .homeRowsCard(card.stableId, look)
            .homeEditCard(editIndex[card.stableId] ?: 0)
        when (cell.piece.kind) {
            JbiPieceKind.TallSignal, JbiPieceKind.WideSignal -> WidgetCard12(
                card = card,
                extractBackdropColors = extractBackdropColors,
                onClick = { onCardClick(card.target) },
                modifier = editCard,
                followColumn = true,
                tall = cell.piece.kind == JbiPieceKind.TallSignal,
                coverFit = templateFit,
                coverRequestPx = coverRequestPx,
            )
            JbiPieceKind.Cover -> WidgetCoverBlock(
                card = card,
                extractBackdropColors = extractBackdropColors,
                onClick = { onCardClick(card.target) },
                modifier = editCard,
                artworkModifier = Modifier.jbiCoverSquare(templateFit),
                artworkRequestSizePx = coverRequestPx,
            )
        }
    }
}

/**
 * The packed shelf ([packWidgetRows]): the phone's Row + weights + 100dp
 * path, or (a pane too short on cards for its template, the pre-template
 * trial) the true-column grid with covers following the column.
 */
@Composable
private fun JbiPackedGrid(
    rows: List<List<HomeWidgetCard>>,
    columns: Int,
    followColumn: Boolean,
    coverFit: JbiCoverFit,
    extractBackdropColors: Boolean,
    onCardClick: (HomeWidgetTarget) -> Unit,
) {
    // Edit-mode card order on the packed paths: row-major.
    val rowEditIndex = remember(rows) { rows.flatten().editCardIndex { it } }
    fun Modifier.card(card: HomeWidgetCard): Modifier = this
        .homeRowsCard(card.stableId, if (card.expanded) JbiCardLook.Wide else JbiCardLook.Cover)
        .homeEditCard(rowEditIndex[card.stableId] ?: 0)
    if (followColumn) {
        // Decode at the largest cover the column can grow to.
        val coverRequestPx = with(LocalDensity.current) { JbiCoverMax.roundToPx() }
        JbiColumnGrid(
            rows = rows,
            columns = columns,
            modifier = Modifier.fillMaxWidth(),
        ) { card ->
            if (card.expanded) {
                WidgetCard12(
                    card = card,
                    extractBackdropColors = extractBackdropColors,
                    onClick = { onCardClick(card.target) },
                    modifier = Modifier.card(card),
                    followColumn = true,
                    coverRequestPx = coverRequestPx,
                )
            } else {
                WidgetCoverBlock(
                    card = card,
                    extractBackdropColors = extractBackdropColors,
                    onClick = { onCardClick(card.target) },
                    modifier = Modifier.card(card),
                    artworkModifier = Modifier.jbiCoverSquare(coverFit),
                    artworkRequestSizePx = coverRequestPx,
                )
            }
        }
    } else {
        // The phone path, as before (its rows in their own 16dp stack).
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    // A 1×2 is taller than the 1×1 beside it; top-align so the cover
                    // block hangs from the same line and the review copy runs below.
                    verticalAlignment = Alignment.Top,
                ) {
                    var units = 0
                    row.forEach { card ->
                        // Keyed so a preset's re-pack carries each card's state along.
                        key(card.stableId) {
                            if (card.expanded) {
                                units += 2
                                WidgetCard12(
                                    card = card,
                                    extractBackdropColors = extractBackdropColors,
                                    onClick = { onCardClick(card.target) },
                                    modifier = Modifier.card(card).weight(2f),
                                )
                            } else {
                                units += 1
                                WidgetCoverBlock(
                                    card = card,
                                    extractBackdropColors = extractBackdropColors,
                                    onClick = { onCardClick(card.target) },
                                    modifier = Modifier.card(card).weight(1f),
                                )
                            }
                        }
                    }
                    // Pad short rows so cards keep their column width instead of
                    // stretching across the leftover space.
                    repeat(columns - units) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * One grid composition with the cards it seats — the outgoing layer keeps its
 * own while it fades. [key] is what a crossfade answers to: the L template
 * when there is one (a re-seat on the same template updates in place), else
 * the packed path's spec — never the row preset, which updates in place.
 */
private data class JbiContent(
    val spec: JbiGridSpec,
    val cards: List<HomeWidgetCard>,
    val layout: JbiLayout?,
    val rows: HomeRowPreset,
) {
    val key: Any get() = layout?.template ?: spec
}

/** Each card's edit-mode index (wiggle parity, tap target) by stable id, in this list's order. */
private inline fun <T> List<T>.editCardIndex(card: (T) -> HomeWidgetCard): Map<String, Int> =
    withIndex().associate { (index, item) -> card(item).stableId to index }

/**
 * Pack the grid into rows of [columns] units — a 1×2 is two units, a 1×1 is
 * one. Each 1×2 shares its row with the next `columns - 2` 1×1 covers taken in
 * list order (alternating which side the wide card hugs so it doesn't always
 * cling to the same edge, mirroring the Figma masonry); leftover 1×1 covers
 * fill full rows. Order otherwise follows the incoming ranking, so the
 * strongest cards still lead.
 *
 * GOLDEN INVARIANT: at 3 columns this reproduces the shipped
 * phone packing exactly — one compact per wide row, same alternation — pinned
 * by `HomeWidgetGridPackTest`. Do not drift the 3-column output.
 */
internal fun packWidgetRows(
    cards: List<HomeWidgetCard>,
    columns: Int,
): List<List<HomeWidgetCard>> {
    val wide = cards.filter { it.expanded }.toMutableList()
    val compact = cards.filterNot { it.expanded }.toMutableList()
    val rows = mutableListOf<List<HomeWidgetCard>>()
    var pairIndex = 0
    while (wide.isNotEmpty()) {
        val big = wide.removeAt(0)
        val fill = mutableListOf<HomeWidgetCard>()
        while (fill.size < columns - 2 && compact.isNotEmpty()) {
            fill += compact.removeAt(0)
        }
        rows += when {
            fill.isEmpty() -> listOf(big)
            pairIndex % 2 == 0 -> listOf(big) + fill
            else -> fill + big
        }
        pairIndex++
    }
    compact.chunked(columns).forEach { chunk -> rows += chunk }
    return rows
}

/**
 * The wide "1×2" card: the 1×1 cover block on the left, and a column on the
 * right with the rating (tinted to the cover), what it's based on, and — when
 * present — the review/note copy in a serif face echoing the Figma.
 * [followColumn] (Medium+ panes) sizes the cover off the column instead of the
 * fixed 100dp — see [JbiWideCardLayout].
 */
@Composable
private fun WidgetCard12(
    card: HomeWidgetCard,
    extractBackdropColors: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    followColumn: Boolean = false,
    // Standing (templated grids): the cover block over its copy, one column
    // and two rows, instead of side by side.
    tall: Boolean = false,
    coverFit: JbiCoverFit = LocalHomeHintVariant.current.coverFit,
    coverRequestPx: Int? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val backdropColors = rememberExpressiveBackdropColors(
        model = card.coverArtUrl,
        fallbackBaseColor = MaterialTheme.colorScheme.secondary,
        fallbackAccentColor = MaterialTheme.colorScheme.tertiary,
        enabled = extractBackdropColors,
    )
    // No tap haptic: browsing taps stay silent (haptic-feedback.md §D); the
    // press and the page it opens are the answer.
    val cardModifier = modifier
        .noRippleClickable(
            interactionSource = interactionSource,
            enabled = homeEditInteractive(),
            onClick = onClick,
        )
    if (tall) {
        Column(modifier = cardModifier) {
            WidgetCoverBlock(
                card = card,
                extractBackdropColors = extractBackdropColors,
                interactionSource = interactionSource,
                artworkModifier = Modifier.jbiCoverSquare(coverFit),
                artworkRequestSizePx = coverRequestPx,
            )
            Spacer(modifier = Modifier.height(JbiTallCopyGap))
            WidgetRatingColumn(
                card = card,
                backdropColors = backdropColors,
                commentMaxLines = JbiTallCommentMaxLines,
            )
        }
        return
    }
    if (followColumn) {
        JbiWideCardLayout(
            fit = coverFit,
            modifier = cardModifier,
        ) {
            WidgetCoverBlock(
                card = card,
                extractBackdropColors = extractBackdropColors,
                interactionSource = interactionSource,
                artworkModifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                artworkRequestSizePx = coverRequestPx,
            )
            WidgetRatingColumn(card = card, backdropColors = backdropColors)
        }
        return
    }
    Row(
        modifier = cardModifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        // Hang the rating from the cover's top edge (Figma), not the row centre.
        verticalAlignment = Alignment.Top,
    ) {
        WidgetCoverBlock(
            card = card,
            extractBackdropColors = extractBackdropColors,
            interactionSource = interactionSource,
            modifier = Modifier.width(WidgetCoverSize),
        )
        WidgetRatingColumn(
            card = card,
            backdropColors = backdropColors,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The right half of a [WidgetCard12]: the tinted rating, its basis, and the copy. */
@Composable
private fun WidgetRatingColumn(
    card: HomeWidgetCard,
    backdropColors: ExpressiveBackdropColors,
    modifier: Modifier = Modifier,
    commentMaxLines: Int = 3,
) {
    Column(
        modifier = modifier.seamFade(),
        // The rating's own line box already carries generous leading —
        // 3dp keeps the comment visually attached to its score.
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        card.shownRating()?.let { rating ->
            // The palette's base tone (L* capped at 0.62) reads fine on a
            // light surface but sinks into a dark one — use the brighter
            // accent tone there, same hue family.
            val ratingColor = if (isSystemInDarkTheme()) {
                backdropColors.accentColor
            } else {
                backdropColors.baseColor
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = rating,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                    ).withTabularFigures(),
                    color = ratingColor,
                )
                card.shownBasis()?.let { basis ->
                    Text(
                        text = basis,
                        style = MaterialTheme.typography.labelSmall.withTabularFigures(),
                        color = ratingColor.copy(alpha = 0.85f),
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
        }
        card.comment?.let { comment ->
            // AI 拟题保留宋体；首页笔记正文继承主题的 Google Sans Flex。
            Text(
                text = comment,
                style = if (card.commentIsHeadline) {
                    MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = if (card.commentSerif) YoinSerifTitle else null,
                        fontSize = 17.sp,
                        lineHeight = 24.sp,
                    )
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = commentMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The 1×1 cover: an entity-type backdrop shape with the artwork nested inside
 * it, then the title and subtitle. Used standalone in the grid and as the left
 * half of a [WidgetCard12]. [artworkModifier] sizes the backdrop: the fixed
 * 100dp cover, or a column-following square on Medium+ panes.
 */
@Composable
private fun WidgetCoverBlock(
    card: HomeWidgetCard,
    extractBackdropColors: Boolean,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    onClick: (() -> Unit)? = null,
    artworkModifier: Modifier = Modifier.size(WidgetCoverSize),
    artworkRequestSizePx: Int? = null,
) {
    val ownInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    // Only a standalone block takes its own tap (no haptic, as WidgetCard12);
    // nested in a 1×2 the card's own clickable answers.
    val clickModifier = if (onClick != null) {
        Modifier.noRippleClickable(
            interactionSource = ownInteractionSource,
            enabled = homeEditInteractive(),
            onClick = onClick,
        )
    } else {
        Modifier
    }
    // Spacing rhythm: the title + subtitle sit flush (their line-height
    // leading alone separates them) so they read as ONE text cluster, with a
    // single deliberate gap between that cluster and the artwork. A uniform
    // spacedBy here made every gap equal and the whole card read as loose,
    // unrelated lines.
    Column(
        modifier = modifier.then(clickModifier),
    ) {
        WidgetBackdropArtwork(
            model = card.coverArtUrl,
            kind = card.entityType.toWidgetShapeKind(),
            contentDescription = card.title,
            extractBackdropColors = extractBackdropColors,
            interactionSource = ownInteractionSource,
            modifier = artworkModifier,
            requestSizePx = artworkRequestSizePx,
        )
        Spacer(modifier = Modifier.height(5.dp))
        WidgetCoverCaption(card = card)
    }
}

@Composable
private fun HomeWidgetCard.shownSubtitle(): String = subtitleText?.asString() ?: subtitle

@Composable
private fun HomeWidgetCard.shownRating(): String? {
    val text = ratingText ?: return null
    return if (ratingUnavailable) stringResource(R.string.home_widget_rating_na) else text
}

@Composable
private fun HomeWidgetCard.shownBasis(): String? {
    val date = ratingBasisDateMillis
    if (date != null) return formatHomeMemoryDate(date)
    val text = ratingBasisText
    if (text != null) return text.asString()
    return ratingBasis
}

@Composable
@ReadOnlyComposable
private fun formatHomeMemoryDate(epochMillis: Long): String {
    val locale = LocalContext.current.resources.configuration.locales[0]
    val pattern = DateFormat.getBestDateTimePattern(locale, "MMMd")
    val calendar = Calendar.getInstance().apply { timeInMillis = epochMillis }
    return DateFormat.format(pattern, calendar).toString()
}

/** A cover's title + subtitle, flush — one text cluster (see [WidgetCoverBlock]). */
@Composable
private fun WidgetCoverCaption(card: HomeWidgetCard) {
    Column {
        // 歌曲/专辑名加大一档（用户裁决）；字体维持 GSF —— 宋体只属于 AI 拟题。
        Text(
            text = card.title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.seamFade(),
        )
        val subtitle = card.shownSubtitle()
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.seamFade(),
            )
        }
    }
}

/**
 * Static entity-type backdrop, the lightweight revival of the old
 * `ExpressiveBackdrop`: a filled [MaterialShapes] blob tinted to the cover's
 * palette, with the artwork nested at [WidgetArtworkFraction] in the
 * bottom-right so the shape peeks out around it. No morph / scale / FFT pulse —
 * those were the parts that cost frames and got cut; only the shape stays.
 * Shared by the widget grid and the Activities bento.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun WidgetBackdropArtwork(
    model: String?,
    kind: WidgetShapeKind,
    contentDescription: String,
    extractBackdropColors: Boolean,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    // Fixed decode size for a cover whose size moves with its column, so it
    // never stays at the (smaller) size it first laid out at.
    requestSizePx: Int? = null,
) {
    // Artists render as a clean full circle — the app-wide portrait convention
    // (Library grid/list, Artist detail) — NOT the album/song/playlist
    // shape-peek. A rounded-rect artwork over a SoftBoom blob read as "square"
    // in the Activities bento; the card's own tinted container already carries
    // the palette wash, so the bare circle is enough (no backdrop tint needed).
    if (kind == WidgetShapeKind.Artist) {
        ExpressiveMediaArtwork(
            model = model,
            contentDescription = contentDescription,
            modifier = modifier.seamDissolve(),
            shape = CircleShape,
            fallbackIcon = widgetFallbackIcon(kind),
            interactionSource = interactionSource,
            tonalElevation = 1.dp,
            shadowElevation = 0.dp,
            requestSizePx = requestSizePx,
        )
        return
    }
    val backdropColors = rememberExpressiveBackdropColors(
        model = model,
        fallbackBaseColor = MaterialTheme.colorScheme.secondaryContainer,
        fallbackAccentColor = MaterialTheme.colorScheme.tertiaryContainer,
        enabled = extractBackdropColors,
    )
    val backdropPolygon = when (kind) {
        WidgetShapeKind.Album -> MaterialShapes.Bun
        WidgetShapeKind.Song -> MaterialShapes.Circle
        WidgetShapeKind.Playlist -> MaterialShapes.Ghostish
        // Unreached — the artist branch returns above; kept for exhaustiveness.
        WidgetShapeKind.Artist -> MaterialShapes.Circle
    }
    // Pressable content tweens its backdrop to the M3 Triangle token while
    // held (seamless RoundedPolygon morph), springing back on release.
    val backdropShape: Shape = if (interactionSource != null) {
        rememberPressMorphShape(backdropPolygon, interactionSource)
    } else {
        backdropPolygon.toShape()
    }
    // Backdrop shape and cover break up as one print (a card around it, if
    // any, takes over as the print).
    Box(modifier = modifier.seamDissolve()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.TopStart)
                .clip(backdropShape)
                .background(backdropColors.baseColor),
        )
        ExpressiveMediaArtwork(
            model = model,
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize(WidgetArtworkFraction)
                .align(Alignment.BottomEnd)
                .then(
                    if (interactionSource != null) {
                        Modifier.elasticPress(interactionSource)
                    } else {
                        Modifier
                    },
                ),
            // Covers stay square-ish rounded rects like the Figma; only the
            // backdrop shape behind them varies by entity type.
            shape = YoinArtworkShapes.Thumb,
            fallbackIcon = widgetFallbackIcon(kind),
            tonalElevation = 1.dp,
            shadowElevation = 0.dp,
            requestSizePx = requestSizePx,
        )
    }
}

private fun widgetFallbackIcon(kind: WidgetShapeKind): ImageVector = when (kind) {
    WidgetShapeKind.Playlist -> YoinSymbols.Playlist
    WidgetShapeKind.Song -> YoinSymbols.MusicNote
    WidgetShapeKind.Album -> YoinSymbols.Album
    WidgetShapeKind.Artist -> YoinSymbols.Artist
}
