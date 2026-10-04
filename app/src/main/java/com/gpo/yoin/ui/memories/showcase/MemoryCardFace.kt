package com.gpo.yoin.ui.memories.showcase

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinSerifTitle
import com.gpo.yoin.ui.theme.YoinTheme

/*
 * The card state of one memory (twostate4 `.ts-a`): two clusters and the air between them.
 *
 *  · The exhibit sits at a fixed height — air1, the cover with the groove emblem pinned to its lower-right
 *    corner, the title, the album row — so every card's cover top is the same.
 *  · The teaser anchors to the bottom — the excerpt, [Diary · N notes | Go to album], "Swipe up for Home".
 *  · air2 takes the slack. The excerpt is the first candidate (whole sentences, best first) whose block fits
 *    the slot between the album row (+24) and the buttons (−16); the last candidate is the attribution line
 *    alone, so nothing is ever cut or ellipsized.
 *
 * Flat: no shadow, no cover border, no "Written by Yoin" (Yoin's lines are unsigned), Go to album has no
 * arrow. Colours are the album palette's direct lerp ([MemoryPaletteTones]).
 */

/** Card face geometry (prototype layoutFor + `.ts-S`). Layout constants, not motion tokens. */
internal object MemoryCardTokens {
    /** Below this container height the 16:9 rhythm applies (smaller exhibit, tighter spacing). */
    val ShortBelow: Dp = 760.dp

    /** From this container width the card sits in a 480 column (P6 brings the real Medium layout). */
    val ColumnFrom: Dp = 600.dp
    val Column: Dp = YoinPageWidths.Card

    val Cover: Dp = 256.dp
    val CoverShort: Dp = 168.dp
    val Seal: Dp = 96.dp
    val SealShort: Dp = 72.dp
    val Air1: Dp = 59.dp
    val Air1Short: Dp = 24.dp
    val SidePadding: Dp = 28.dp

    /** The emblem hangs off the cover's lower-right corner by these fractions of its own size. */
    const val SealOverhangRight = 0.3f
    const val SealOverhangBottom = 0.24f

    /** The excerpt slot: 24 clear of the album row, 16 above the buttons. */
    val ExcerptClear: Dp = 24.dp
    val ExcerptGap: Dp = 16.dp

    val ButtonHeight: Dp = 48.dp
    val ButtonGap: Dp = 10.dp

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
    /** The container is Medium-wide: the card sits in a 480 column and takes the capped teaser excerpt. */
    val wide: Boolean,
) {
    val titleTop: Dp get() = if (short) 22.dp else 30.dp
    val albumTop: Dp get() = if (short) 16.dp else 20.dp
    val swipeTop: Dp get() = if (short) 8.dp else 14.dp
    val bottomPadding: Dp get() = if (short) 22.dp else 30.dp
    val titleMaxWidth: Dp get() = if (wide) 424.dp else 340.dp
    val excerptMaxWidth: Dp get() = if (wide) 400.dp else 324.dp
}

/** The card for a container of [width] × [height] (the page's own size, never the device's). */
internal fun memoryCardMetrics(width: Dp, height: Dp): MemoryCardMetrics {
    val short = height < MemoryCardTokens.ShortBelow
    return MemoryCardMetrics(
        cover = if (short) MemoryCardTokens.CoverShort else MemoryCardTokens.Cover,
        seal = if (short) MemoryCardTokens.SealShort else MemoryCardTokens.Seal,
        short = short,
        air1 = if (short) MemoryCardTokens.Air1Short else MemoryCardTokens.Air1,
        wide = width >= MemoryCardTokens.ColumnFrom,
    )
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
) {
    val excerpts = if (metrics.wide) memory.excerptCandidatesMedium else memory.excerptCandidates
    SubcomposeLayout(modifier = modifier.fillMaxSize()) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val inner = Constraints(maxWidth = (width - MemoryCardTokens.SidePadding.roundToPx() * 2).coerceAtLeast(0))
        val top = subcompose(CardSlot.Exhibit) {
            CardExhibit(memory = memory, metrics = metrics, cover = cover, emblem = emblem)
        }.map { it.measure(inner) }
        // the button row may use the side padding on a narrow phone: it never wraps
        val bottom = subcompose(CardSlot.Teaser) {
            CardTeaser(
                memory = memory,
                tones = tones,
                metrics = metrics,
                onOpenDiary = onOpenDiary,
                onOpenAlbum = onOpenAlbum,
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
                    CardExcerpt(candidate = excerpts[index], maxWidth = metrics.excerptMaxWidth)
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
    metrics: MemoryCardMetrics,
    cover: @Composable (Modifier) -> Unit,
    emblem: @Composable (Modifier) -> Unit,
) {
    val titleKind = if (memory.memoryTitle.isNullOrBlank()) MemoryTitleKind.ALBUM else memory.memoryTitleKind
    val title = memory.memoryTitle?.takeIf(String::isNotBlank) ?: memory.title
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(metrics.air1))
        val (sealX, sealY) = sealOffset(metrics.cover, metrics.seal)
        val exhibitHeight = metrics.cover + metrics.seal * MemoryCardTokens.SealOverhangBottom
        Box(Modifier.size(width = metrics.cover, height = exhibitHeight)) {
            // bare art: the Hero corner, no border, no shadow
            cover(Modifier.size(metrics.cover).clip(YoinArtworkShapes.Hero))
            emblem(Modifier.offset(x = sealX, y = sealY).size(metrics.seal))
        }
        Spacer(Modifier.height(metrics.titleTop))
        Text(
            text = title,
            style = cardTitleStyle(titleKind, metrics.short),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = metrics.titleMaxWidth),
        )
        if (titleKind == MemoryTitleKind.ALBUM) {
            // fallback A: the title already is the album name; the row keeps only the artist line
            Spacer(Modifier.height(6.dp))
        } else {
            Spacer(Modifier.height(metrics.albumTop))
            Text(
                text = memory.title,
                style = cardText(GoogleSansFlex, FontWeight.SemiBold, 17.sp, 1.3f),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = metrics.titleMaxWidth),
            )
            Spacer(Modifier.height(2.dp))
        }
        Text(
            text = memory.supportingText,
            style = cardText(GoogleSansFlex, FontWeight.Medium, 13.sp, 1.35f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = metrics.titleMaxWidth),
        )
    }
}

@Composable
private fun CardExcerpt(candidate: MemoryExcerptCandidate, maxWidth: Dp) {
    Column(
        modifier = Modifier.widthIn(max = maxWidth),
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
            Spacer(Modifier.height(8.dp))
        }
        Text(
            text = candidate.attribution,
            style = cardText(GoogleSansFlex, FontWeight.Medium, 12.sp, 1.3f).copy(letterSpacing = 0.1.sp),
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
) {
    val haptics = rememberYoinHaptics()
    val pill = RoundedCornerShape(percent = 50)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(MemoryCardTokens.ButtonGap)) {
            Row(
                modifier = Modifier
                    .height(MemoryCardTokens.ButtonHeight)
                    .clip(pill)
                    .background(tones.ink.copy(alpha = MemoryCardTokens.DiaryTonalAlpha))
                    .clickable(role = Role.Button, onClickLabel = "Open the diary") {
                        haptics.performClick()
                        onOpenDiary()
                    }
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
                        .clickable(role = Role.Button) {
                            haptics.performClick()
                            onOpenAlbum()
                        }
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
        Spacer(Modifier.height(metrics.swipeTop))
        // the cue only: a swipe up anywhere goes Home (the gesture router), the words are not a control
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clearAndSetSemantics { },
        ) {
            Icon(
                imageVector = YoinSymbols.ChevronUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            if (!metrics.short) {
                Text(
                    text = "Swipe up for Home",
                    style = cardText(GoogleSansFlex, FontWeight.Medium, 11.5.sp, 1.2f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The title: the AI title in the serif (the only serif on the card), the motif in GSF ROND 60. */
@Composable
private fun cardTitleStyle(kind: MemoryTitleKind, short: Boolean): TextStyle = when (kind) {
    MemoryTitleKind.AI ->
        cardText(YoinSerifTitle, FontWeight.SemiBold, if (short) 24.sp else 26.sp, 1.36f, heading = true)
    MemoryTitleKind.MOTIF -> cardText(ShowcaseType.rounded(600), FontWeight.SemiBold, 24.sp, 1.3f, heading = true)
    MemoryTitleKind.ALBUM -> cardText(GoogleSansFlex, FontWeight.SemiBold, 24.sp, 1.3f, heading = true)
}

/** The user's own words, in the system face (never the serif): three sizes by length. */
@Composable
private fun excerptStyle(size: MemoryExcerptSize): TextStyle = when (size) {
    MemoryExcerptSize.SHORT -> cardText(FontFamily.Default, FontWeight.Medium, 22.sp, 1.4f, paragraph = true)
    MemoryExcerptSize.MEDIUM -> cardText(FontFamily.Default, FontWeight.Normal, 17.sp, 1.65f, paragraph = true)
    MemoryExcerptSize.LONG -> cardText(FontFamily.Default, FontWeight.Normal, 16.sp, 1.7f, paragraph = true)
}

/** A centred card text style with a CSS line box (half-leading, no font padding). */
@Composable
private fun cardText(
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
    private val cache = HashMap<Int, FontFamily>()

    @OptIn(ExperimentalTextApi::class)
    fun rounded(weight: Int): FontFamily = synchronized(cache) {
        cache.getOrPut(weight) {
            FontFamily(
                Font(
                    R.font.google_sans_flex_variable,
                    weight = FontWeight(weight),
                    variationSettings = FontVariation.Settings(
                        FontVariation.weight(weight),
                        FontVariation.Setting("ROND", 60f),
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
