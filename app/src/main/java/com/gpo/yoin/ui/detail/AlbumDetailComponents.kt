package com.gpo.yoin.ui.detail

import android.content.res.Resources
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.gpo.yoin.R
import com.gpo.yoin.data.integration.neodb.NeoDbShortCommentMax
import com.gpo.yoin.data.integration.neodb.isNeoDbShortComment
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.symbols.rememberEqualizerSymbolPainter
import com.gpo.yoin.ui.component.FavoriteGlyph
import com.gpo.yoin.ui.component.FavoriteGlyphIcon
import com.gpo.yoin.ui.component.MetaGroup
import com.gpo.yoin.ui.component.MetaLine
import com.gpo.yoin.ui.component.RatingSlider
import com.gpo.yoin.ui.component.ScoreEmblemAwardMode
import com.gpo.yoin.ui.component.ScoreEmblemSurface
import com.gpo.yoin.ui.component.TrackLibraryButton
import com.gpo.yoin.ui.component.UnavailableTrackAlpha
import com.gpo.yoin.ui.component.UnavailableTrackBadge
import com.gpo.yoin.ui.component.UnavailableTrackReason
import com.gpo.yoin.ui.component.YoinArmTransform
import com.gpo.yoin.ui.component.YoinMark
import com.gpo.yoin.ui.component.YoinModalBottomSheet
import com.gpo.yoin.ui.component.draftFieldValue
import com.gpo.yoin.ui.component.formatTrackDuration
import com.gpo.yoin.ui.component.minimumTouchTarget
import com.gpo.yoin.ui.component.ratingBloom
import com.gpo.yoin.ui.component.rememberScoreEmblemAwardState
import com.gpo.yoin.ui.component.verticalEdgeFadeOnScroll
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.rememberCoverColorScheme
import com.gpo.yoin.ui.theme.withTabularFigures
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged

// ---------------------------------------------------------------------------
// Arrow-mark background — the two color blocks hugging the cover.
// ---------------------------------------------------------------------------

// The Compact full-bleed backdrop (two blocks bled off the edges) is drawn by
// the album's spectrum bar layer now (AlbumSpectrumBar.kt), so it can fly into
// the header; only the hugging form below stays a plain composable (Wide).

/**
 * Landscape handsets (断点交接 §5): the same two blocks as closed shapes
 * hugging the cover — drawn whole inside [modifier]'s box, never scaled past
 * it, so nothing is cut straight and nothing reaches under the Button Group.
 */
@Composable
internal fun AlbumArrowBackdropHugging(
    primaryBlock: Color,
    secondaryBlock: Color,
    lineColor: Color,
    modifier: Modifier = Modifier,
) {
    YoinMark(
        transforms = AlbumArrowArmTransforms,
        colors = listOf(primaryBlock, Color.Transparent, secondaryBlock),
        lineColor = lineColor,
        groupScaleX = AlbumArrowHuggingSquashX,
        groupScaleY = 1f,
        modifier = modifier,
    )
}

private const val AlbumArrowHuggingSquashX = 1f

// arm order [0 lower-left, 1 upper, 2 lower-right]; hide the upper arm.
private val AlbumArrowArmTransforms = listOf(
    YoinArmTransform(alpha = 1f),
    YoinArmTransform(alpha = 0f),
    YoinArmTransform(alpha = 1f),
)

// ---------------------------------------------------------------------------
// Two-page indicator dots (overview  ·  scrapbook). Tappable: a dot pages to it.
// ---------------------------------------------------------------------------

/**
 * The page dots. [activeFraction] (pager position, 0…count-1) is read only while drawing, so a swipe never
 * recomposes the header; [selectedPage] (the settled page) only feeds the accessibility state.
 */
@Composable
internal fun AlbumPageDots(
    activeFraction: () -> Float,
    selectedPage: Int,
    activeColor: Color,
    inactiveColor: Color,
    onPageClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    count: Int = 2,
    // Drawn only (the album bar's white twin): no tabs, no semantics.
    visualOnly: Boolean = false,
) {
    Row(
        modifier = if (visualOnly) modifier else modifier.selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val overview = stringResource(R.string.detail_album_page_overview)
        val scrapbook = stringResource(R.string.detail_album_page_scrapbook)
        val labels = mutableListOf<String>()
        for (i in 0 until count) {
            labels += when (i) {
                0 -> overview
                1 -> scrapbook
                else -> stringResource(R.string.detail_album_page_n, i + 1)
            }
        }
        repeat(count) { i ->
            val label = labels[i]
            Box(
                modifier = Modifier
                    // 24 × 20 cells (the dots stay 6dp apart as before); Compose
                    // widens the touch bounds of a target this small on its own.
                    .size(width = 24.dp, height = 20.dp)
                    .then(
                        if (visualOnly) {
                            Modifier
                        } else {
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .selectable(
                                    selected = i == selectedPage,
                                    role = Role.Tab,
                                    onClick = { onPageClick(i) },
                                )
                                .semantics { contentDescription = label }
                        },
                    )
                    .drawBehind {
                        val distance = (i - activeFraction()).absoluteValue.coerceIn(0f, 1f)
                        drawCircle(
                            color = lerp(activeColor, inactiveColor, distance),
                            radius = 4.dp.toPx(),
                        )
                    },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Score: the Memories groove emblem (replaced the "Avg." Bun, owner 2026-10-05).
// ---------------------------------------------------------------------------

/** Page 1's emblem: drawn natively at this size (6 rings; ≥ 64 shows the unrated word). */
internal val AlbumPageEmblemSize = 64.dp

/**
 * The album score on page 1: the groove emblem (album rating, else the track average, else the empty
 * mould) and, unless the album itself is rated, "Based on X/N". Tapping it opens the rate & comment sheet
 * (the album page's rating entry; the emblem itself is the Memories exhibit, so no press morph).
 */
@Composable
internal fun AlbumScoreEmblem(
    spec: AlbumEmblemSpec,
    coverArtUrl: String?,
    ratedCount: Int,
    total: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AlbumScoreMark(
            spec = spec,
            coverArtUrl = coverArtUrl,
            size = AlbumPageEmblemSize,
            onCover = false,
            modifier = Modifier
                // "Score bloom": pops only when a rating COMMIT lands — the
                // settle window inside keeps page-open resolves silent.
                .ratingBloom(spec.score)
                .clip(CircleShape)
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.detail_album_cd_rate),
                    onClick = onClick,
                ),
        )
        // Rule: only the computed-average / not-rated states show "Based on X/N";
        // a manual album rating stands alone.
        if (spec.score.kind == AlbumScoreKind.Average) {
            AlbumRatedCoverage(ratedCount = ratedCount, total = total)
        }
    }
}

/**
 * How many tracks a computed average stands on — one small line ("2/4 rated")
 * beside the emblem. Not shown for a manual album score or for "not rated"
 * (owner 2026-10-05: no "Rating"/"Based on" captions; the emblem speaks).
 */
@Composable
private fun AlbumRatedCoverage(ratedCount: Int, total: Int) {
    Text(
        text = stringResource(R.string.detail_album_rated, ratedCount, total),
        style = MaterialTheme.typography.labelSmall.withTabularFigures(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

// ---------------------------------------------------------------------------
// "Last Play" / "Comment" underlined section label (Google Sans Flex; the
// album receipt on page 2 is the only monospace left — owner 2026-10-05).
// ---------------------------------------------------------------------------

@Composable
internal fun AlbumSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(
                textDecoration = TextDecoration.Underline,
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (trailing != null) trailing()
    }
}

// ---------------------------------------------------------------------------
// Total track-count label — used in both the hero (above flowing titles)
// and the pulled-up list (below the docked band).
// ---------------------------------------------------------------------------

/**
 * [MetaLine] inside a slot that may be a tight width (fillMaxWidth, weight).
 * Trailing groups still drop; the slot keeps the width it was given.
 */
@Composable
internal fun DetailMetaLine(
    groups: List<MetaGroup>,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = Color.Unspecified,
) {
    Layout(
        modifier = modifier,
        content = { MetaLine(groups = groups, style = style, color = color) },
    ) { measurables, constraints ->
        val child = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(
            constraints.constrainWidth(child.width),
            constraints.constrainHeight(child.height),
        ) {
            child.placeRelative(0, 0)
        }
    }
}

@Composable
internal fun AlbumTrackCountLabel(
    count: Int,
    totalDurationSeconds: Int?,
    modifier: Modifier = Modifier,
) {
    val groups = buildList {
        add(
            MetaGroup.Stat(
                value = count.toString(),
                unit = pluralStringResource(R.plurals.detail_unit_track, count),
            ),
        )
        totalDurationSeconds?.takeIf { it > 0 }?.let { seconds ->
            addAll(totalDurationGroups(seconds))
        }
    }
    DetailMetaLine(
        groups = groups,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier,
    )
}

/** Hours and leftover minutes as separate stats. Zero leftover minutes are omitted. */
@Composable
private fun totalDurationGroups(seconds: Int): List<MetaGroup> {
    val totalMinutes = seconds / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val hourUnit = stringResource(R.string.detail_unit_hour)
    val minuteUnit = stringResource(R.string.detail_unit_minute)
    return buildList {
        if (hours > 0) {
            add(MetaGroup.Stat(hours.toString(), hourUnit))
            if (minutes > 0) add(MetaGroup.Stat(minutes.toString(), minuteUnit))
        } else {
            add(MetaGroup.Stat(minutes.toString(), minuteUnit))
        }
    }
}

// ---------------------------------------------------------------------------
// Flowing hero titles. Adjacent titles alternate tone. A space is the break.
// Feat credits stay attached to their title.
// ---------------------------------------------------------------------------

// Plain text normally (no link blue / underline — inherit the surrounding
// style); an underline appears while a title is pressed.
private val AlbumTitleLinkStyles = TextLinkStyles(
    style = SpanStyle(),
    pressedStyle = SpanStyle(textDecoration = TextDecoration.Underline),
)

/** Even indexes use the primary tone; odd indexes the variant. */
internal fun flowingTitleUsesPrimaryTone(index: Int): Boolean = index % 2 == 0

/** One breakable space between flowing titles. */
internal const val FlowingTitleGap = " "

/** Titles joined the way the hero paints them: a space, never a bullet. */
internal fun flowingTitleSequence(titles: List<String>): String =
    titles.joinToString(separator = FlowingTitleGap, transform = ::flowingTitle)

// Titles up to about 60% of a headlineMedium line (CJK counts double) are kept
// whole; longer ones stay breakable so they can never force an overflow.
private const val FlowingTitleGlueUnits = 16

/**
 * A title as it should sit in the flowing list: a short one wraps as ONE unit
 * ("Old Photographs", 「一个人的海」) instead of splitting mid-title —
 * spaces become NBSPs and wide (CJK) characters get WORD JOINERs between them.
 */
internal fun flowingTitle(title: String): String {
    var units = 0
    for (c in title) units += if (c.isWideGlyph()) 2 else 1
    if (units > FlowingTitleGlueUnits) return title
    return buildString(title.length * 2) {
        title.forEachIndexed { i, c ->
            if (i > 0 && (c.isWideGlyph() || title[i - 1].isWideGlyph())) append('\u2060')
            append(if (c == ' ') '\u00A0' else c)
        }
    }
}

private fun Char.isWideGlyph(): Boolean {
    val code = code
    return code in 0x1100..0x11FF || // Hangul Jamo
        code in 0x2E80..0x9FFF || // CJK radicals, kana, CJK punctuation, ideographs
        code in 0xAC00..0xD7AF || // Hangul syllables
        code in 0xF900..0xFAFF || // CJK compatibility ideographs
        code in 0xFF00..0xFFEF // full-width forms
}

@Composable
internal fun buildAlbumTrackTitles(
    songs: List<AlbumSong>,
    featColor: Color,
    onSongClick: ((String) -> Unit)?,
): AnnotatedString {
    val primary = MaterialTheme.colorScheme.onSurface
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    val credits = mutableListOf<String?>()
    for (song in songs) {
        credits += song.featArtist?.let { feat -> stringResource(R.string.detail_album_feat, feat) }
    }
    return buildAnnotatedString {
        fun appendTitle(index: Int, song: AlbumSong) {
            val tone = if (flowingTitleUsesPrimaryTone(index)) primary else variant
            withStyle(SpanStyle(color = tone)) {
                append(flowingTitle(song.title))
            }
            credits[index]?.let { credit ->
                withStyle(SpanStyle(fontSize = 0.6.em, color = featColor)) {
                    // NBSP: the small credit never wraps away from its title.
                    append(credit)
                }
            }
        }
        songs.forEachIndexed { index, song ->
            if (index > 0) append(FlowingTitleGap)
            if (onSongClick != null) {
                // Each title is its OWN clickable link → plays just that song,
                // exactly like tapping a row in a normal track list.
                withLink(
                    LinkAnnotation.Clickable(
                        tag = song.id,
                        styles = AlbumTitleLinkStyles,
                        linkInteractionListener = { onSongClick(song.id) },
                    ),
                ) { appendTitle(index, song) }
            } else {
                appendTitle(index, song)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Track row: number · title (+ note marker) · artist · duration · ♥ toggle.
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AlbumTrackRow(
    index: Int,
    song: AlbumSong,
    hasNote: Boolean,
    isNowPlaying: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleStar: () -> Unit,
    modifier: Modifier = Modifier,
    // False when the track's artist is just the album artist again — the
    // credit line then only repeats the header on every row, so the row
    // collapses to one 56dp line. Compilations / feat. credits keep it.
    showArtist: Boolean = true,
    // Drives the now-playing equalizer: bars jump while playing and sink
    // into dots on pause. Only read when [isNowPlaying].
    isPlaying: Boolean = false,
) {
    val haptics = rememberYoinHaptics()
    var showUnavailableReason by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(YoinContainerShapes.ListRow)
            .combinedClickable(
                onClick = if (song.isUnavailable) ({ showUnavailableReason = !showUnavailableReason }) else onClick,
                onLongClick = {
                    haptics.performLongPress()
                    onLongClick()
                },
            )
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .alpha(if (song.isUnavailable) UnavailableTrackAlpha else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (isNowPlaying) {
            // Same slot width as the index number, so swapping in the
            // indicator never shifts the row's columns.
            Box(
                modifier = Modifier.widthIn(min = 18.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    painter = rememberEqualizerSymbolPainter(playing = isPlaying),
                    contentDescription = stringResource(R.string.detail_album_cd_now_playing),
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            Text(
                text = (song.trackNumber ?: (index + 1)).toString(),
                style = MaterialTheme.typography.labelMedium.withTabularFigures(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 18.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isNowPlaying) accent else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (hasNote) {
                    Icon(
                        imageVector = YoinSymbols.Note,
                        contentDescription = stringResource(R.string.detail_album_cd_has_note),
                        tint = accent.copy(alpha = 0.8f),
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            if (showArtist) {
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        song.duration?.let { duration ->
            Text(
                text = formatTrackDuration(duration),
                style = MaterialTheme.typography.labelLarge.withTabularFigures(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val features = com.gpo.yoin.data.source.ServiceFeatureCatalog.forProvider(
            com.gpo.yoin.data.model.MediaId.parseOrNull(song.id)?.provider
        )
        when {
            song.isUnavailable -> UnavailableTrackBadge(
                onClick = { showUnavailableReason = !showUnavailableReason },
            )
            features.supportsFavorites -> AlbumCircleToggle(
                heart = FavoriteGlyph(song.isStarred, song.favoriteQuietFlips),
                accent = accent,
                onToggle = onToggleStar,
            )
            // Apple Music: the full catalog album with a check on the songs already in
            // the user's library; the same callback adds an unchecked song.
            features.supportsLibraryAdd -> TrackLibraryButton(
                membership = song.libraryMembership,
                isWorking = song.libraryActionInFlight,
                onClick = onToggleStar,
            )
        }
    }
    UnavailableTrackReason(
        visible = song.isUnavailable && showUnavailableReason,
        contentPadding = PaddingValues(start = 38.dp, end = 14.dp, bottom = 10.dp),
    )
    }
}

@Composable
private fun AlbumCircleToggle(
    // The row's heart; its quiet flips (a state nobody tapped) crossfade in without the beat.
    heart: FavoriteGlyph,
    accent: Color,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = heart.favorite
    val haptics = rememberYoinHaptics()
    val container by animateColorAsState(
        targetValue = if (active) accent else Color.Transparent,
        animationSpec = YoinMotion.effectsSpring(),
        label = "trackToggleContainer",
    )
    // The ring and the heart's ink change on the same effects spring as the
    // container, so a late confirmation is a pure colour-and-fill change.
    val ring by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.outline.copy(alpha = if (active) 0f else 0.5f),
        animationSpec = YoinMotion.effectsSpring(),
        label = "trackToggleRing",
    )
    val ink by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = YoinMotion.effectsSpring(),
        label = "trackToggleInk",
    )
    // Same heart pop as Now Playing's FavoriteButton (the two toggle the same
    // repository favorite): a short over-peak snap, then a spatial-spring
    // settle. Peak is higher on the fill moment so it reads as a heart pop.
    // Settle role pinned to Standard like NP's, not the page's Expressive.
    var tapPulse by remember { mutableIntStateOf(0) }
    val bounce = remember { Animatable(1f) }
    val bounceSpec = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Standard)
    LaunchedEffect(tapPulse) {
        if (tapPulse == 0) return@LaunchedEffect
        val peak = if (active) 1.25f else 1.15f
        bounce.animateTo(peak, tween(durationMillis = 90))
        bounce.animateTo(1f, bounceSpec)
    }
    IconButton(
        onClick = {
            tapPulse++
            if (active) haptics.performTick() else haptics.performConfirm()
            onToggle()
        },
        modifier = modifier
            .size(36.dp)
            .minimumTouchTarget(),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .graphicsLayer {
                    scaleX = bounce.value
                    scaleY = bounce.value
                }
                .clip(CircleShape)
                .background(container)
                .border(width = 1.5.dp, color = ring, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            FavoriteGlyphIcon(
                favorite = active,
                quietFlips = heart.quietFlips,
                contentDescription = stringResource(
                    if (active) R.string.detail_album_cd_remove_favorite else R.string.detail_album_cd_add_favorite,
                ),
                tint = ink,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Rate & comment (opened from the emblem / the pen): owner R1–R4, 2026-10-06.
// ---------------------------------------------------------------------------

/** The sheet's emblem: past 110dp, so every track gets its own ring. */
private val RateSheetEmblemSize = 120.dp

/**
 * The album's rate & comment sheet: "Rate" and the album's name at the top
 * left, the groove emblem beside them, Now Playing's rating bar under both —
 * the emblem lifts and leans while the bar is dragged and is stamped (the
 * award, with its beats) when the finger lifts, which is when the score is
 * saved. Then one text field that grows from a capsule into a card with its
 * words (never clipped inside it: the sheet scrolls instead), and NeoDB's
 * state as a quiet last line. The words are kept when the sheet closes
 * ([onDismiss]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlbumRateSheet(
    content: AlbumDetailUiState.Content,
    neoDb: AlbumNeoDbSync,
    onRatingCommit: (Float) -> Unit,
    onReviewDraftChange: (String) -> Unit,
    onNeoDbSignIn: () -> Unit,
    onNeoDbRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var live by remember { mutableFloatStateOf(content.userRating ?: 0f) }
    var dragging by remember { mutableStateOf(false) }
    var stamps by remember { mutableIntStateOf(0) }
    val trackRated = remember(content.songs, content.ratedSongIds) {
        content.songs.map { song -> song.id in content.ratedSongIds }
    }
    val spec = AlbumEmblemSpec(
        score = when {
            live > 0f -> AlbumScore(AlbumScoreKind.UserRating, live)
            else -> content.copy(userRating = null).albumScore()
        },
        trackRated = trackRated,
    )
    val award = rememberScoreEmblemAwardState(
        score = spec.emblemScore(),
        kind = spec.emblemKind(),
        trackRated = spec.trackRated,
        size = RateSheetEmblemSize,
        surface = ScoreEmblemSurface.Page,
        tag = "album-rate",
    )
    // Stamped once per release, on the controller built for the score it landed on.
    LaunchedEffect(award, stamps) {
        if (stamps > 0 && !dragging) award.play(ScoreEmblemAwardMode.Replay)
    }
    val lift by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = YoinMotion.spatialSpring(),
        label = "rateEmblemLift",
    )

    YoinModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // A long review makes the sheet scroll (the caret is kept in view), so the field never clips its words.
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                // Words scrolled past the sheet's edge dissolve instead of being cut.
                .verticalEdgeFadeOnScroll(scroll, top = RateSheetScrollFade, bottom = RateSheetScrollFade)
                .verticalScroll(scroll)
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = RateSheetBurstRoom, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Owner, 2026-10-06: the title at the top left, beside the emblem.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RateSheetTitle(albumName = content.albumName, modifier = Modifier.weight(1f))
                AlbumScoreMark(
                    spec = spec,
                    coverArtUrl = content.coverArtUrl,
                    size = RateSheetEmblemSize,
                    onCover = false,
                    award = award,
                    modifier = Modifier.graphicsLayer {
                        // Lifts and leans with the score while the bar is held.
                        val scale = 1f + RateSheetLiftScale * lift
                        scaleX = scale
                        scaleY = scale
                        rotationZ = (live - 5f) * RateSheetLeanPerPoint * lift
                    },
                )
            }
            // The bar wears the cover's colours, like the page's Play.
            MaterialTheme(colorScheme = rememberCoverColorScheme(content.coverArtUrl) ?: MaterialTheme.colorScheme) {
                RatingSlider(
                    rating = live,
                    onRatingChange = { live = it },
                    orientation = Orientation.Horizontal,
                    // The sheet scrolls and drags away: a swipe across the bar is the sheet's, not a score.
                    claimOnDown = false,
                    onInteractionChange = { active ->
                        dragging = active
                        if (!active) {
                            onRatingCommit(live)
                            stamps++
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AlbumReviewField(text = content.userReview, onTextChange = onReviewDraftChange)
            if (neoDb != AlbumNeoDbSync.Unknown &&
                neoDb != AlbumNeoDbSync.Idle &&
                neoDb != AlbumNeoDbSync.Pending
            ) {
                AlbumNeoDbLine(state = neoDb, onSignIn = onNeoDbSignIn, onRetry = onNeoDbRetry)
            }
        }
    }
}

/**
 * What the sheet is for (owner, 2026-10-06: the emblem alone felt empty): "Rate" and the album, at the top left
 * beside the emblem, the way the page's Last Play sits beside it.
 */
@Composable
private fun RateSheetTitle(albumName: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(top = RateSheetTitleTop)
            .semantics(mergeDescendants = true) { heading() },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.detail_album_rate),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = albumName,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Room over the emblem inside the scroll: the scroll (and its edge fade's layer) clip at the viewport's top, and
 * the 10.0 award's burst reaches ≈ 72dp from the 120dp emblem's centre, 12dp past its box.
 */
private val RateSheetBurstRoom = 14.dp

/** The fade at the sheet's scrolled edges. */
private val RateSheetScrollFade = 24.dp

/** The title's drop from the emblem's box edge to the ring's visual top. */
private val RateSheetTitleTop = 8.dp

/** How far the emblem grows while the rating bar is held. */
private const val RateSheetLiftScale = 0.06f

/** Degrees the held emblem leans per point away from 5. */
private const val RateSheetLeanPerPoint = 1.4f

/**
 * The album's one comment: a capsule at rest that grows into a card while
 * written (the note write bar's grammar). Once there are words, one small
 * line says where they go on NeoDB — a short comment up to
 * [NeoDbShortCommentMax] characters, a review beyond.
 */
@Composable
private fun AlbumReviewField(text: String, onTextChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // The field's full value (caret included), held so the caret can be followed; a new value starts at the end.
    var held by remember { mutableStateOf<TextFieldValue?>(null) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val caret = remember { BringIntoViewRequester() }
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    // The field grows with its words and the sheet scrolls; this keeps the caret (and the counter line under
    // it) above the keyboard on every edit, caret move and keyboard frame — the String field only did so once,
    // when it took focus.
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        snapshotFlow { Triple(held?.selection?.end, layout, ime.getBottom(density)) }
            .distinctUntilChanged()
            .collect { (end, measured, _) ->
                val result = measured ?: return@collect
                val length = result.layoutInput.text.length
                val cursor = result.getCursorRect((end ?: length).coerceIn(0, length))
                val above = with(density) { ReviewCaretClearanceAbove.toPx() }
                val below = with(density) { ReviewCaretClearanceBelow.toPx() }
                caret.bringIntoView(cursor.copy(top = cursor.top - above, bottom = cursor.bottom + below))
            }
    }
    val open = focused || text.isNotEmpty()
    val corner by animateDpAsState(
        targetValue = if (open) 20.dp else 28.dp,
        animationSpec = YoinMotion.defaultSpatialSpec(),
        label = "reviewFieldCorner",
    )
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corner))
            .background(scheme.surfaceContainerHigh)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicTextField(
            value = draftFieldValue(text, held),
            onValueChange = { value ->
                held = value
                // Caret / selection moves don't touch the words.
                if (value.text != text) onTextChange(value.text)
            },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(caret)
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { field ->
                Box {
                    if (text.isEmpty()) {
                        Text(
                            text = stringResource(R.string.detail_album_review_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    field()
                }
            },
        )
        AnimatedVisibility(
            visible = text.isNotEmpty(),
            enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard) + expandVertically(),
            exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard) + shrinkVertically(),
        ) {
            val count = text.codePointCount(0, text.length)
            val short = isNeoDbShortComment(text)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = stringResource(
                        if (short) R.string.detail_album_short_comment else R.string.detail_album_review,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    text = if (short) {
                        stringResource(R.string.detail_album_review_count, count, NeoDbShortCommentMax)
                    } else {
                        "$count"
                    },
                    style = MaterialTheme.typography.labelMedium.withTabularFigures(),
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Room kept above the caret when the sheet follows it (a line of context). */
private val ReviewCaretClearanceAbove = 24.dp

/** Room kept under the caret: the field's bottom padding and its counter line. */
private val ReviewCaretClearanceBelow = 48.dp

/** NeoDB's state for this album. Signed out, the line is the action; a failure gets a Retry button. */
@Composable
private fun AlbumNeoDbLine(state: AlbumNeoDbSync, onSignIn: () -> Unit, onRetry: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (state == AlbumNeoDbSync.SignedOut) {
                    Modifier.clickable(role = Role.Button, onClick = onSignIn)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.detail_neodb_name),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = scheme.onSurface,
        )
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                    YoinMotion.fadeOut(role = YoinMotionRole.Standard)
            },
            label = "neoDbLine",
        ) { shown ->
            if (shown == AlbumNeoDbSync.Failed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = albumNeoDbLabel(shown, LocalContext.current.resources),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.detail_retry))
                    }
                }
            } else {
                val label = albumNeoDbLabel(shown, LocalContext.current.resources)
                if (label.isNotEmpty()) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (shown == AlbumNeoDbSync.SignedOut) {
                            scheme.primary
                        } else {
                            scheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/** The words after "NeoDB" on the sheet's last line. Pending and idle say nothing. */
internal fun albumNeoDbLabel(state: AlbumNeoDbSync, resources: Resources? = null): String = when (state) {
    AlbumNeoDbSync.Unknown, AlbumNeoDbSync.Idle, AlbumNeoDbSync.Pending -> ""
    AlbumNeoDbSync.SignedOut -> resources?.getString(R.string.detail_neodb_sign_in)
        ?: "Sign in to sync" // i18n-allow: AlbumNeoDbSyncTest asserts this English
    AlbumNeoDbSync.Syncing -> resources?.getString(R.string.detail_neodb_syncing)
        ?: "Syncing…" // i18n-allow: AlbumNeoDbSyncTest asserts this English
    AlbumNeoDbSync.Synced -> resources?.getString(R.string.detail_neodb_synced)
        ?: "Synced" // i18n-allow: AlbumNeoDbSyncTest asserts this English
    AlbumNeoDbSync.Failed -> resources?.getString(R.string.detail_neodb_failed)
        ?: "Couldn't sync" // i18n-allow: AlbumNeoDbSyncTest asserts this English
}

// ---------------------------------------------------------------------------
// Formatting helpers.
// ---------------------------------------------------------------------------

/** Album / track 0–10 score rendered as "d.d" (e.g. 7 → "7.0", 8.5 → "8.5"). */
internal fun formatAlbumScore(rating: Float): String {
    val roundedTenths = (rating.coerceIn(0f, 10f) * 10f).roundToInt()
    if (roundedTenths >= 100) return "10"
    return "%d.%d".format(roundedTenths / 10, roundedTenths % 10)
}

/**
 * Album-level "last play" → (dayLabel, time), e.g. ("Yesterday", "16:04").
 * Pure local time; minSdk 26 so java.time is available without desugaring.
 */
internal fun albumLastPlayLabels(epochMillis: Long, resources: Resources): Pair<String, String> {
    val zone = ZoneId.systemDefault()
    val moment = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val date = moment.toLocalDate()
    val today = LocalDate.now(zone)
    val days = ChronoUnit.DAYS.between(date, today)
    val day = when {
        days <= 0L -> resources.getString(R.string.detail_relative_today)
        days == 1L -> resources.getString(R.string.detail_relative_yesterday)
        days < 7L -> resources.getQuantityString(R.plurals.detail_relative_days_ago, days.toInt(), days.toInt())
        else -> formatAlbumMonthDay(epochMillis, zone, resources)
    }
    val time = moment.format(DateTimeFormatter.ofPattern("HH:mm"))
    return day to time
}

private fun formatAlbumMonthDay(epochMillis: Long, zone: ZoneId, resources: Resources): String {
    val locale = resources.configuration.locales[0]
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd")
    val calendar = Calendar.getInstance(TimeZone.getTimeZone(zone.id)).apply {
        timeInMillis = epochMillis
    }
    return android.text.format.DateFormat.format(pattern, calendar).toString()
}
