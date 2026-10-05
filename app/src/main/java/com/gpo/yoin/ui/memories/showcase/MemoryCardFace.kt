package com.gpo.yoin.ui.memories.showcase

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.YoinPageWidths
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryEntry
import com.gpo.yoin.ui.memories.MemoryScoreKind
import com.gpo.yoin.ui.memories.MemoryWriting
import com.gpo.yoin.ui.memories.copy.MemoryExcerptCandidate
import com.gpo.yoin.ui.memories.copy.MemoryExcerptSize
import com.gpo.yoin.ui.memories.copy.MemoryTitleKind
import com.gpo.yoin.ui.theme.GoogleSansFlex
import com.gpo.yoin.ui.theme.LocalYoinMotionRole
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionSpeed
import com.gpo.yoin.ui.theme.YoinSerifTitle
import com.gpo.yoin.ui.theme.YoinTheme
import kotlin.math.roundToInt

/*
 * The card state of one memory (twostate4 `.ts-a`): two clusters and the air between them.
 *
 *  · The exhibit sits at a fixed height — air1, the cover with the groove emblem pinned to its lower-right
 *    corner, the title, the album row — so every card's cover top is the same.
 *  · The teaser anchors to the bottom — the excerpt, [Diary · N notes | Go to album]. No "Swipe up for Home"
 *    cue (owner, 2026-10-05: the bar's Home pill already says it); the buttons keep [MemoryCardTokens.TeaserBottom]
 *    of air under them.
 *  · An album name takes at most two lines and runs the rest as a marquee, never an ellipsis
 *    ([TwoLineMarqueeText]).
 *  · air2 takes the slack. The excerpt is the first candidate (whole sentences, best first) whose block fits
 *    the slot between the album row (+24) and the buttons (−16); the last candidate is the attribution line
 *    alone, so nothing is ever cut or ellipsized.
 *
 * Flat: no shadow, no cover border, no "Written by Yoin" (Yoin's lines are unsigned), Go to album has no
 * arrow. Colours are the album palette's direct lerp ([MemoryPaletteTones]).
 */

/**
 * Card face geometry (prototype `.ts-a` + `.ts-S` + `.ts-T-medium`). Layout constants, not motion tokens;
 * the exhibit's sizes come from the container budget ([memoriesLayoutFor]).
 */
internal object MemoryCardTokens {
    /** Medium: the card sits in a 480 column. */
    val Column: Dp = YoinPageWidths.Card
    val SidePadding: Dp = 28.dp

    /** The emblem hangs off the cover's lower-right corner by these fractions of its own size. */
    const val SealOverhangRight = 0.3f
    const val SealOverhangBottom = 0.24f

    /** The excerpt slot: 24 clear of the album row, 16 above the buttons. */
    val ExcerptClear: Dp = 24.dp
    val ExcerptGap: Dp = 16.dp

    val ButtonHeight: Dp = 48.dp
    val ButtonGap: Dp = 10.dp

    /**
     * Air under the button row (the nav bar's inset wins when it is taller). The swipe cue's 48dp band is gone:
     * about a quarter of it comes back here, so the row doesn't sink onto the edge, and the rest goes to air2.
     */
    val TeaserBottom: Dp = 40.dp
    val TeaserBottomShort: Dp = 28.dp

    /** The Diary button's tonal ground: the album ink at 14%. */
    const val DiaryTonalAlpha = 0.14f

    /** Flat Material: no tonal lift, no shadow, anywhere on the card (owner, v4). */
    val FlatElevation: Dp = 0.dp
}

@Immutable
internal data class MemoryCardMetrics(
    val cover: Dp,
    val seal: Dp,
    val short: Boolean,
    val air1: Dp,
    /** The container is Medium: the card sits in a 480 column and takes the capped teaser excerpt. */
    val wide: Boolean,
    /** Under the teaser (Medium: plus half the slack over the 120dp air cap, [balanceMedium]). */
    val bottomPadding: Dp = if (short) MemoryCardTokens.TeaserBottomShort else MemoryCardTokens.TeaserBottom,
) {
    val titleTop: Dp get() = if (short) 22.dp else 30.dp
    val albumTop: Dp get() = if (short) 16.dp else 20.dp
    val titleMaxWidth: Dp get() = if (wide) 424.dp else 340.dp
    val excerptMaxWidth: Dp get() = if (wide) 400.dp else 324.dp
}

/** The card for a container of [width] × [height] (the page's own size, never the device's). */
internal fun memoryCardMetrics(width: Dp, height: Dp): MemoryCardMetrics =
    memoryCardMetrics(memoriesLayoutFor(width, height))

/** The card for a container budget, before the Medium balance. */
internal fun memoryCardMetrics(layout: MemoriesLayout): MemoryCardMetrics = MemoryCardMetrics(
    cover = layout.cover,
    seal = layout.seal,
    short = layout.short,
    air1 = layout.air1,
    wide = layout.tier != MemoriesTier.Phone,
)

/**
 * The card's metrics for the deck in its container. On a Medium the gap between the exhibit and the teaser
 * (air2) is capped at 120dp: every card is measured with TextMeasurer (its title, album row, the excerpt the
 * slot picks and the teaser — adaptive principle 6) and the slack over the cap, from the card with the least,
 * goes 1:1 to the top air and the bottom ([balanceMedium]), so every card keeps the same cover top and the
 * same button row. Phone: the budget's metrics as they are.
 */
@Composable
internal fun rememberCardMetrics(
    memories: List<MemoryEntry>,
    layout: MemoriesLayout,
    width: Dp,
    height: Dp,
    statusTop: Dp,
    navBottom: Dp,
): MemoryCardMetrics {
    val base = memoryCardMetrics(layout)
    if (layout.tier != MemoriesTier.Medium) return base
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val density = LocalDensity.current
    val titleStyles = MemoryTitleKind.entries.associateWith { cardTitleStyle(it) }
    val albumStyle = cardAlbumStyle()
    val artistStyle = cardArtistStyle()
    val excerptStyles = MemoryExcerptSize.entries.associateWith { excerptStyle(it) }
    val attribution = attributionStyle()
    return remember(memories, base, width, height, statusTop, navBottom, density, titleStyles, excerptStyles) {
        with(density) {
            val inner = (minOf(width, MemoryCardTokens.Column) - MemoryCardTokens.SidePadding * 2).toPx()
            val titleWidth = minOf(inner, base.titleMaxWidth.toPx()).roundToInt().coerceAtLeast(1)
            val excerptWidth = minOf(inner, base.excerptMaxWidth.toPx()).roundToInt().coerceAtLeast(1)
            fun h(text: String, style: TextStyle, w: Int, maxLines: Int = Int.MAX_VALUE): Float {
                val measured = measurer.measure(
                    text = text,
                    style = style,
                    maxLines = maxLines,
                    constraints = Constraints(maxWidth = w),
                )
                return measured.size.height.toDp().value
            }
            val bottom = maxOf(base.bottomPadding, navBottom)
            val teaser = MemoryCardTokens.ButtonHeight.value
            val avail = (height - statusTop - MemoriesTopBarTokens.Height - bottom).value
            val gap = MemoryCardTokens.ExcerptGap.value
            val clear = MemoryCardTokens.ExcerptClear.value
            val slacks = memories.map { memory ->
                val kind = memory.cardTitleKind()
                val title = memory.memoryTitle?.takeIf(String::isNotBlank) ?: memory.title
                val artist = h(memory.supportingText, artistStyle, titleWidth)
                val row = if (kind == MemoryTitleKind.ALBUM) {
                    6f + artist
                } else {
                    base.albumTop.value + h(memory.title, albumStyle, titleWidth, maxLines = 2) + 2f + artist
                }
                // the album name as the title takes at most two lines too (the rest runs as a marquee)
                val titleLines = if (kind == MemoryTitleKind.ALBUM) 2 else Int.MAX_VALUE
                val exhibit = base.air1.value + base.cover.value +
                    base.seal.value * MemoryCardTokens.SealOverhangBottom +
                    base.titleTop.value + h(title, titleStyles.getValue(kind), titleWidth, titleLines) + row
                val candidates = memory.excerptCandidatesMedium
                val heights = HashMap<Int, Float>()
                fun block(i: Int): Float = heights.getOrPut(i) {
                    val c = candidates[i]
                    val quote = c.text?.let { text ->
                        h("“$text”", excerptStyles.getValue(c.size), excerptWidth) + ExcerptAttributionGap.value
                    } ?: 0f
                    quote + h(c.attribution, attribution, excerptWidth)
                }
                val slot = avail - teaser - gap - exhibit - clear
                val excerpt = pickExcerpt(candidates, slot.roundToInt()) { block(it).roundToInt() }?.let(::block) ?: 0f
                avail - exhibit - excerpt - gap - teaser
            }
            val (air1, balancedBottom) = balanceMedium(base.air1.value, bottom.value, slacks)
            base.copy(air1 = air1.dp, bottomPadding = balancedBottom.dp)
        }
    }
}

/** The emblem's offset inside the cover's box (right −0.3s, bottom −0.24s). */
internal fun sealOffset(cover: Dp, seal: Dp): Pair<Dp, Dp> {
    val x = cover - seal * (1f - MemoryCardTokens.SealOverhangRight)
    val y = cover - seal * (1f - MemoryCardTokens.SealOverhangBottom)
    return x to y
}

/** The notes a Diary button counts: album and song notes (the review is not a note). */
internal fun MemoryEntry.diaryNoteCount(): Int = writings.count { it.kind != MemoryWriting.Kind.REVIEW }

/** "Diary · 4 notes" / "Diary · 1 note" / "Diary". */
internal fun diaryNotesSuffix(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> " · 1 note"
    else -> " · $count notes"
}

/**
 * The index of the excerpt to show: the first candidate whose block height fits [slotPx], else the
 * attribution line alone (always the last candidate). Never a cut sentence. [heightOf] measures the block
 * of the candidate at an index (by index: two candidates may carry the same words).
 */
internal fun pickExcerpt(candidates: List<MemoryExcerptCandidate>, slotPx: Int, heightOf: (Int) -> Int): Int? {
    candidates.forEachIndexed { index, candidate ->
        if (candidate.attributionOnly || heightOf(index) <= slotPx) return index
    }
    return candidates.indices.lastOrNull()
}

/**
 * [morph] moves the card's pieces through the card ⇄ diary morph (null: a still card, e.g. previews); it
 * also measures the anchors the morph flies between. [interactive] = false (the diary is open) takes the
 * buttons out of hit testing altogether, so the hidden card never shadows the diary beneath it.
 * [titleEditing]: the title can be tapped to edit ([MemoryTitleSlot]) while [titleEnabled] (the settled page,
 * the card state); while it is open the card rises over the keyboard ([MemoryTitleImeLift]).
 */
@Composable
internal fun MemoryCardFace(
    memory: MemoryEntry,
    tones: MemoryPaletteTones,
    metrics: MemoryCardMetrics,
    onOpenDiary: () -> Unit,
    onOpenAlbum: () -> Unit,
    cover: @Composable (Modifier) -> Unit,
    emblem: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    morph: MemoryPageMorph? = null,
    interactive: Boolean = true,
    marqueeRunning: Boolean = true,
    titleEditing: MemoryTitleEditing? = null,
    titleEnabled: Boolean = interactive,
) {
    val excerpts = if (metrics.wide) memory.excerptCandidatesMedium else memory.excerptCandidates
    // The keyboard lift rides the face's own layer, between the morph's outer and inner anchors, so the
    // morph never measures it; read per frame in the layer only.
    val lift = remember { MemoryTitleImeLift() }
    val ime = WindowInsets.ime
    val editor = titleEditing?.editor
    val titleKey = MemoryTitleSurface.Card.keyFor(memory)
    val face = Modifier
        .then(if (morph != null) Modifier.onPlaced(morph::onCardOuter) else Modifier)
        .onPlaced(lift::onHostOuter)
        .graphicsLayer {
            if (morph != null) with(morph) { cardFace() }
            val editing = editor?.isEditing(titleKey) == true
            translationY -= lift.liftPx(ime.getBottom(this), editing, this)
        }
        .then(if (morph != null) Modifier.onPlaced(morph::onCardInner) else Modifier)
        .onPlaced(lift::onHostInner)
    // the diary is open: the card is behind it and carries no semantics (TalkBack reads the diary)
    val inert = if (interactive) Modifier else Modifier.clearAndSetSemantics { }
    SubcomposeLayout(modifier = modifier.then(inert).then(face).fillMaxSize()) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val inner = Constraints(maxWidth = (width - MemoryCardTokens.SidePadding.roundToPx() * 2).coerceAtLeast(0))
        val top = subcompose(CardSlot.Exhibit) {
            CardExhibit(
                memory = memory,
                tones = tones,
                metrics = metrics,
                cover = cover,
                emblem = emblem,
                morph = morph,
                marqueeRunning = marqueeRunning,
                titleEditing = titleEditing,
                titleEnabled = titleEnabled,
                lift = lift,
            )
        }.map { it.measure(inner) }
        // the button row may use the side padding on a narrow phone: it never wraps
        val bottom = subcompose(CardSlot.Teaser) {
            CardTeaser(
                memory = memory,
                tones = tones,
                metrics = metrics,
                onOpenDiary = onOpenDiary,
                onOpenAlbum = onOpenAlbum,
                interactive = interactive,
                modifier = morph?.let { m -> Modifier.graphicsLayer { with(m) { teaser() } } } ?: Modifier,
            )
        }.map { it.measure(Constraints(maxWidth = width)) }
        val topHeight = top.maxOfOrNull { it.height } ?: 0
        val bottomHeight = bottom.maxOfOrNull { it.height } ?: 0
        val gap = MemoryCardTokens.ExcerptGap.roundToPx()
        val slot = height - bottomHeight - gap - topHeight - MemoryCardTokens.ExcerptClear.roundToPx()
        val measured = HashMap<Int, Placeable>()
        val measure = { index: Int ->
            measured.getOrPut(index) {
                subcompose(CardSlot.Excerpt(index)) {
                    CardExcerpt(
                        candidate = excerpts[index],
                        maxWidth = metrics.excerptMaxWidth,
                        modifier = morph?.let { m -> Modifier.graphicsLayer { with(m) { excerpt() } } } ?: Modifier,
                    )
                }.first().measure(inner)
            }
        }
        val excerpt = pickExcerpt(excerpts, slot) { index -> measure(index).height }?.let(measure)
        layout(width, height) {
            top.forEach { it.placeRelative((width - it.width) / 2, 0) }
            excerpt?.placeRelative((width - excerpt.width) / 2, height - bottomHeight - gap - excerpt.height)
            bottom.forEach { it.placeRelative((width - it.width) / 2, height - bottomHeight) }
        }
    }
}

private sealed interface CardSlot {
    data object Exhibit : CardSlot

    data object Teaser : CardSlot

    data class Excerpt(val index: Int) : CardSlot
}

@Composable
private fun CardExhibit(
    memory: MemoryEntry,
    tones: MemoryPaletteTones,
    metrics: MemoryCardMetrics,
    cover: @Composable (Modifier) -> Unit,
    emblem: @Composable (Modifier) -> Unit,
    morph: MemoryPageMorph?,
    marqueeRunning: Boolean,
    titleEditing: MemoryTitleEditing?,
    titleEnabled: Boolean,
    lift: MemoryTitleImeLift,
) {
    val titleKind = memory.cardTitleKind()
    // each morphing piece: its anchor measured before its own layer, so its flight never feeds back
    val coverLayer = morph?.let { m -> Modifier.onPlaced(m::onCover).graphicsLayer { with(m) { cardCover() } } }
    val sealLayer = morph?.let { m -> Modifier.onPlaced(m::onSeal).graphicsLayer { with(m) { cardSeal() } } }
    val titleLayer = morph?.let { m -> Modifier.onPlaced(m::onCardTitle).graphicsLayer { with(m) { cardTitle() } } }
    val rowLayer = morph?.let { m -> Modifier.graphicsLayer { with(m) { albumRow() } } } ?: Modifier
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(metrics.air1))
        MemoryExhibitShow(
            cover = metrics.cover,
            seal = metrics.seal,
            coverContent = { m -> cover(m.then(coverLayer ?: Modifier.clip(YoinArtworkShapes.Hero))) },
            emblem = { m -> emblem(m.then(sealLayer ?: Modifier)) },
        )
        Spacer(Modifier.height(metrics.titleTop))
        // the title and, while it is being edited, its row: the block the keyboard lift keeps in view
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.onPlaced(lift::onTarget),
        ) {
            MemoryTitleSlot(
                memory = memory,
                surface = MemoryTitleSurface.Card,
                editing = titleEditing,
                tones = tones,
                style = { kind -> cardTitleStyle(kind) },
                enabled = titleEnabled,
                centred = true,
                modifier = Modifier
                    .widthIn(max = metrics.titleMaxWidth)
                    .then(titleLayer ?: Modifier),
            ) { text, kind, tap ->
                val heading = tap.semantics(mergeDescendants = true) { heading() }
                if (kind == MemoryTitleKind.ALBUM) {
                    // fallback A: the title is the album name, two lines at most
                    TwoLineMarqueeText(
                        text = text,
                        style = cardTitleStyle(kind),
                        color = MaterialTheme.colorScheme.onSurface,
                        running = marqueeRunning,
                        modifier = heading,
                    )
                } else {
                    Text(
                        text = text,
                        style = cardTitleStyle(kind),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = heading,
                    )
                }
            }
            MemoryTitleEditRow(
                memory = memory,
                surface = MemoryTitleSurface.Card,
                editing = titleEditing,
                tones = tones,
                centred = true,
                keepAboveIme = false,
                modifier = Modifier.widthIn(max = metrics.titleMaxWidth),
            )
        }
        // the album row reads as one line: "album, artist · year"
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.semantics(mergeDescendants = true) { },
        ) {
            // fallback A: the title already is the album name, so the row keeps only the artist line; a title
            // given (or taken off) brings the album name in (or out) on the spatial spring
            Spacer(Modifier.height(AlbumRowArtistOnlyTop))
            AnimatedVisibility(
                visible = titleKind != MemoryTitleKind.ALBUM,
                enter = expandVertically(YoinMotion.defaultSpatialSpec()) +
                    YoinMotion.fadeIn(LocalYoinMotionRole.current, YoinMotionSpeed.Fast, MaterialTheme.motionScheme),
                exit = shrinkVertically(YoinMotion.defaultSpatialSpec()) +
                    YoinMotion.fadeOut(LocalYoinMotionRole.current, YoinMotionSpeed.Fast, MaterialTheme.motionScheme),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(metrics.albumTop - AlbumRowArtistOnlyTop))
                    TwoLineMarqueeText(
                        text = memory.title,
                        style = cardAlbumStyle(),
                        color = MaterialTheme.colorScheme.onSurface,
                        running = marqueeRunning,
                        modifier = Modifier.widthIn(max = metrics.titleMaxWidth).then(rowLayer),
                    )
                    Spacer(Modifier.height(2.dp))
                }
            }
            Text(
                text = memory.supportingText,
                style = cardArtistStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = metrics.titleMaxWidth).then(rowLayer),
            )
        }
    }
}

/**
 * The exhibit: the cover with the groove emblem pinned to its lower-right corner (right −0.3s, bottom −0.24s;
 * the overhang below is the box's own height). Bare art — the Hero corner, no border, no shadow. Shared by the
 * card and the spread's left page.
 */
@Composable
internal fun MemoryExhibitShow(
    cover: Dp,
    seal: Dp,
    coverContent: @Composable (Modifier) -> Unit,
    emblem: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (sealX, sealY) = sealOffset(cover, seal)
    Box(modifier.size(width = cover, height = cover + seal * MemoryCardTokens.SealOverhangBottom)) {
        coverContent(Modifier.size(cover))
        emblem(Modifier.offset(x = sealX, y = sealY).size(seal))
    }
}

@Composable
private fun CardExcerpt(candidate: MemoryExcerptCandidate, maxWidth: Dp, modifier: Modifier = Modifier) {
    Column(
        // the quote and its signature are one reading
        modifier = modifier.widthIn(max = maxWidth).semantics(mergeDescendants = true) { },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val text = candidate.text
        if (text != null) {
            Text(
                // Yoin quoting you: quotes on the card, your signature under it
                text = "“$text”",
                style = excerptStyle(candidate.size),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(ExcerptAttributionGap))
        }
        Text(
            text = candidate.attribution,
            style = attributionStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CardTeaser(
    memory: MemoryEntry,
    tones: MemoryPaletteTones,
    metrics: MemoryCardMetrics,
    onOpenDiary: () -> Unit,
    onOpenAlbum: () -> Unit,
    interactive: Boolean,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    val pill = RoundedCornerShape(percent = 50)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(MemoryCardTokens.ButtonGap)) {
            Row(
                modifier = Modifier
                    .height(MemoryCardTokens.ButtonHeight)
                    .clip(pill)
                    .background(tones.ink.copy(alpha = MemoryCardTokens.DiaryTonalAlpha))
                    .then(
                        if (interactive) {
                            Modifier.clickable(role = Role.Button, onClickLabel = "Open the diary") {
                                haptics.performClick()
                                onOpenDiary()
                            }
                        } else {
                            Modifier
                        },
                    )
                    .padding(start = 16.dp, end = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = YoinSymbols.Note,
                    contentDescription = null,
                    tint = tones.ink,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                val suffix = diaryNotesSuffix(memory.diaryNoteCount())
                Text(
                    text = buildAnnotatedString {
                        append("Diary")
                        if (suffix != null) {
                            val quiet = SpanStyle(fontWeight = FontWeight.Medium, color = tones.ink.copy(alpha = 0.78f))
                            withStyle(quiet) {
                                append(suffix)
                            }
                        }
                    },
                    style = cardText(GoogleSansFlex, FontWeight.SemiBold, 15.sp, 1f),
                    color = tones.ink,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            if (memory.entityType == MemoryEntityType.ALBUM) {
                Box(
                    modifier = Modifier
                        .height(MemoryCardTokens.ButtonHeight)
                        .clip(pill)
                        .background(tones.button)
                        .then(
                            if (interactive) {
                                Modifier.clickable(role = Role.Button) {
                                    haptics.performClick()
                                    onOpenAlbum()
                                }
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // no arrow (owner, v4)
                    Text(
                        text = "Go to album",
                        style = cardText(GoogleSansFlex, FontWeight.SemiBold, 15.sp, 1f),
                        color = tones.onButton,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** The card title's kind: the album name when there is no Yoin title. */
internal fun MemoryEntry.cardTitleKind(): MemoryTitleKind =
    if (memoryTitle.isNullOrBlank()) MemoryTitleKind.ALBUM else memoryTitleKind

/** Above the artist line when the album name is the title (fallback A); the album row's own top otherwise. */
private val AlbumRowArtistOnlyTop: Dp = 6.dp

/** The card title's font size (the morph scales it to the diary title by this ratio). */
internal fun cardTitleSize(kind: MemoryTitleKind, type: MemoriesTypeScale): TextUnit = when (kind) {
    MemoryTitleKind.AI, MemoryTitleKind.USER -> type.cardTitleAi
    MemoryTitleKind.MOTIF, MemoryTitleKind.ALBUM -> type.cardTitleMotif
}

/**
 * The title: a written title — the AI's, or the user's own — in the serif (the only serif on the card), the
 * motif in GSF ROND 60. Sizes from the tier ([LocalMemoriesType]: 26 / 24 phone, 24 short, 30 / 27 on a
 * Medium that isn't short).
 */
@Composable
internal fun cardTitleStyle(kind: MemoryTitleKind): TextStyle {
    val size = cardTitleSize(kind, LocalMemoriesType.current)
    return when (kind) {
        MemoryTitleKind.AI, MemoryTitleKind.USER ->
            cardText(YoinSerifTitle, FontWeight.SemiBold, size, 1.36f, heading = true)
        MemoryTitleKind.MOTIF -> cardText(ShowcaseType.rounded(600), FontWeight.SemiBold, size, 1.3f, heading = true)
        MemoryTitleKind.ALBUM -> cardText(GoogleSansFlex, FontWeight.SemiBold, size, 1.3f, heading = true)
    }
}

/** The album row: the album name (17 / 19 Medium) over the artist line (13 / 14). */
@Composable
internal fun cardAlbumStyle(): TextStyle =
    cardText(GoogleSansFlex, FontWeight.SemiBold, LocalMemoriesType.current.cardAlbum, 1.3f)

@Composable
internal fun cardArtistStyle(): TextStyle =
    cardText(GoogleSansFlex, FontWeight.Medium, LocalMemoriesType.current.cardArtist, 1.35f)

/** The signature under a quote ("Your review · Jul 26"). */
@Composable
internal fun attributionStyle(): TextStyle =
    cardText(GoogleSansFlex, FontWeight.Medium, 12.sp, 1.3f).copy(letterSpacing = 0.1.sp)

/** Between a quote and its signature. */
internal val ExcerptAttributionGap: Dp = 8.dp

/** The user's own words, in the system face (never the serif): three sizes by length. */
@Composable
internal fun excerptStyle(size: MemoryExcerptSize): TextStyle = when (size) {
    MemoryExcerptSize.SHORT -> cardText(FontFamily.Default, FontWeight.Medium, 22.sp, 1.4f, paragraph = true)
    MemoryExcerptSize.MEDIUM -> cardText(FontFamily.Default, FontWeight.Normal, 17.sp, 1.65f, paragraph = true)
    MemoryExcerptSize.LONG -> cardText(FontFamily.Default, FontWeight.Normal, 16.sp, 1.7f, paragraph = true)
}

/** A centred card text style with a CSS line box (half-leading, no font padding). */
@Composable
internal fun cardText(
    family: FontFamily,
    weight: FontWeight,
    size: TextUnit,
    lineHeight: Float,
    heading: Boolean = false,
    paragraph: Boolean = false,
): TextStyle = MaterialTheme.typography.bodyLarge.copy(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size,
    lineHeight = size * lineHeight,
    letterSpacing = 0.sp,
    textAlign = TextAlign.Center,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineBreak = when {
        heading -> LineBreak.Heading
        paragraph -> LineBreak.Paragraph
        else -> LineBreak.Simple
    },
)

/** Google Sans Flex instances the showcase needs beyond Type.kt (ROND 60: the motif title, Yoin's prose). */
internal object ShowcaseType {
    private val cache = HashMap<Pair<Int, Float>, FontFamily>()

    /** GSF at [weight] with ROND [rond] (60: the motif title and Yoin's prose; 40: the diary's numerals). */
    @OptIn(ExperimentalTextApi::class)
    fun rounded(weight: Int, rond: Float = 60f): FontFamily = synchronized(cache) {
        cache.getOrPut(weight to rond) {
            FontFamily(
                Font(
                    R.font.google_sans_flex_variable,
                    weight = FontWeight(weight),
                    variationSettings = FontVariation.Settings(
                        FontVariation.weight(weight),
                        FontVariation.Setting("ROND", rond),
                    ),
                ),
            )
        }
    }
}

@Preview(name = "Memory card face · m1", heightDp = 851, widthDp = 412)
@Composable
private fun MemoryCardFacePreview() {
    YoinTheme(darkTheme = false) {
        Box(Modifier.background(MaterialTheme.colorScheme.background)) {
            MemoryCardFace(
                memory = MemoryEntry(
                    stableId = "preview",
                    sourceActivityId = 0L,
                    entityType = MemoryEntityType.ALBUM,
                    entityId = "a",
                    entityProvider = "subsonic",
                    title = "夜行列车与未寄出的信",
                    supportingText = "椎名林檎 & 东京事变 · 2019",
                    metaText = null,
                    coverArtUrl = null,
                    timestamp = 0L,
                    scoreText = "9.5",
                    scoreKind = MemoryScoreKind.ALBUM_RATING,
                    scoreSupportingText = null,
                    footerText = null,
                    memoryTitle = "写给自己的、不寄出的信",
                    excerptCandidates = listOf(
                        MemoryExcerptCandidate("第一次听这张是在去机场的夜班巴士上。", "Your review · Jul 26"),
                        MemoryExcerptCandidate(null, "Your review · Jul 26 · in Diary"),
                    ),
                    playbackSongs = emptyList(),
                    tracks = emptyList(),
                ),
                tones = MemoryPaletteSamples.M1.tones(dark = false),
                metrics = memoryCardMetrics(412.dp, 915.dp),
                onOpenDiary = {},
                onOpenAlbum = {},
                cover = { Box(it.clip(RoundedCornerShape(8.dp)).background(MemoryPaletteSamples.M1.base)) },
                emblem = { Box(it.clip(RoundedCornerShape(50)).background(Color.White)) },
                modifier = Modifier.padding(top = 96.dp, bottom = 30.dp),
            )
        }
    }
}
