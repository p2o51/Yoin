package com.gpo.yoin.ui.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.gpo.yoin.ui.component.YoinArmTransform
import com.gpo.yoin.ui.component.YoinMark
import com.gpo.yoin.ui.component.elasticPress
import com.gpo.yoin.ui.component.formatTotalDuration
import com.gpo.yoin.ui.component.formatTrackDuration
import com.gpo.yoin.ui.component.minimumTouchTarget
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.withTabularFigures
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Arrow-mark background — the two enlarged, off-edge-bled color blocks.
// ---------------------------------------------------------------------------

// Tune-on-device knobs for how the mark reads as two abstract color blocks.
// SquashX > 1 stretches the mark horizontally about the hub, pushing the
// left block further left and the right block further right (off the edges).
private const val AlbumArrowScale = 1.8f
private const val AlbumArrowOffsetYFraction = -0.1f
private const val AlbumArrowGroupSquashX = 1.8f

/**
 * Reuses the real Yoin three-arrow mark ([YoinMark]) as the album backdrop:
 * the lower-left arm is filled with the cover's [primaryBlock] color, the
 * lower-right arm with [secondaryBlock], and the upper arm is hidden — so it
 * reads as the "two blocks" of the icon, enlarged and bled off the edges.
 * The white centre line on the visible arms is kept as [lineColor].
 */
@Composable
internal fun AlbumArrowBackground(
    primaryBlock: Color,
    secondaryBlock: Color,
    lineColor: Color,
    markHeight: Dp,
    modifier: Modifier = Modifier,
) {
    // `modifier` is the WHOLE page, so the mark is clipped only at the screen
    // edges (never truncated to a band). The mark is sized to `markHeight` and
    // top-anchored so it sits over the cover area, then scaled up to bleed out.
    Box(modifier = modifier.clipToBounds()) {
        YoinMark(
            transforms = AlbumArrowArmTransforms,
            colors = listOf(primaryBlock, Color.Transparent, secondaryBlock),
            lineColor = lineColor,
            groupScaleX = AlbumArrowGroupSquashX,
            groupScaleY = 1f,
            modifier = Modifier
                .fillMaxWidth()
                .height(markHeight)
                .align(Alignment.TopCenter)
                .graphicsLayer {
                    scaleX = AlbumArrowScale
                    scaleY = AlbumArrowScale
                    translationY = size.height * AlbumArrowOffsetYFraction
                },
        )
    }
}

// arm order [0 lower-left, 1 upper, 2 lower-right]; hide the upper arm.
private val AlbumArrowArmTransforms = listOf(
    YoinArmTransform(alpha = 1f),
    YoinArmTransform(alpha = 0f),
    YoinArmTransform(alpha = 1f),
)

/**
 * Pin this preview in Android Studio and edit the `AlbumArrow*` constants above
 * (and `AlbumArrowArmTransforms`) — with "Live Edit of literals" on, the numbers
 * update the render instantly, no device needed. (Colors here are stand-ins; the
 * real ones are cover-derived at runtime, so use this for shape/size/position.)
 */
@Preview(showBackground = true, widthDp = 412, heightDp = 360, backgroundColor = 0xFFFDFBFF)
@Composable
private fun AlbumArrowBackgroundPreview() {
    AlbumArrowBackground(
        primaryBlock = Color(0xFF7A4E86),
        secondaryBlock = Color(0xFF7A5A2A),
        lineColor = Color.White.copy(alpha = 0.7f),
        markHeight = 320.dp,
        modifier = Modifier.fillMaxSize(),
    )
}

// ---------------------------------------------------------------------------
// Two-page indicator dots (this page  ·  scores/About page).
// ---------------------------------------------------------------------------

@Composable
internal fun AlbumPageDots(
    activeFraction: Float,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
    count: Int = 2,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val distance = (i - activeFraction).absoluteValue.coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(lerp(activeColor, inactiveColor, distance)),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Docked-cover "wavy band" — the thin rectangle the hero cover morphs into when
// the track list is pulled up (replaces the old right-docked capsule).
// ---------------------------------------------------------------------------

/**
 * A rectangle whose top & bottom edges are sine waves run HALF a wavelength out
 * of phase ([bottomPhase]) — so the two edges stagger (错落) and the band pinches
 * & bulges instead of undulating as a constant-thickness ribbon. [expand] gates
 * the wave amplitude in from 0 → full and relaxes the corner radius, so the album
 * cover morphs from a plain rounded square (expand 0, hero) into a thin wavy band
 * (expand 1, docked) along the reshape — one continuous [Shape], no crossfade.
 */
internal class WavyBandShape(
    private val expand: Float,
    private val amplitude: Dp = 3.5.dp,
    private val waveLength: Dp = 48.dp,
    private val heroCorner: Dp = 8.dp,
    private val bottomPhase: Float = PI.toFloat(),
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val e = expand.coerceIn(0f, 1f)
        val amp = with(density) { amplitude.toPx() } * e
        // Near the hero end the waves are sub-pixel — fall back to a rounded rect
        // whose corner relaxes toward 0 as it docks.
        if (amp < 0.75f) {
            val r = with(density) { heroCorner.toPx() } * (1f - e)
            return Outline.Rounded(
                RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r)),
            )
        }
        val w = size.width
        val h = size.height
        val wl = with(density) { waveLength.toPx() }.coerceAtLeast(1f)
        val k = (2f * PI.toFloat()) / wl
        val segments = (w / 3f).toInt().coerceIn(24, 400)
        val path = Path()
        // Top edge L→R, baseline at `amp`, oscillating in [0, 2·amp].
        path.moveTo(0f, amp)
        for (i in 1..segments) {
            val x = w * i / segments
            path.lineTo(x, amp + amp * sin(k * x))
        }
        // Bottom edge R→L, run out of phase with the top (bottomPhase) so the two
        // edges stagger — the band pinches & bulges rather than moving in parallel.
        for (i in segments downTo 0) {
            val x = w * i / segments
            path.lineTo(x, (h - amp) + amp * sin(k * x + bottomPhase))
        }
        path.close()
        return Outline.Generic(path)
    }
}

// ---------------------------------------------------------------------------
// "Avg." Bun score chip (MaterialShapes.Bun).
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AlbumScoreBun(
    score: AlbumScore,
    ratedCount: Int,
    total: Int,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .size(width = 56.dp, height = 58.dp)
                .elasticPress(interaction),
            enabled = enabled,
            interactionSource = interaction,
            shape = MaterialShapes.Bun.toShape(),
            color = containerColor,
            contentColor = contentColor,
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (score.kind == AlbumScoreKind.None) {
                        "N/A"
                    } else {
                        formatAlbumScore(score.value)
                    },
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    color = contentColor,
                    maxLines = 1,
                )
            }
        }
        // Rule: only the computed-average / not-rated states show "Based on X/N";
        // a manual album rating stands alone.
        if (score.kind != AlbumScoreKind.UserRating) {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = "Based on",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "$ratedCount/$total",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// "Last Play" / "Avg." / "Comment" underlined section label.
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
                fontFamily = FontFamily.Monospace,
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
// and the pulled-up list (below the wavy band).
// ---------------------------------------------------------------------------

@Composable
internal fun AlbumTrackCountLabel(
    count: Int,
    totalDurationSeconds: Int?,
    modifier: Modifier = Modifier,
) {
    val tracks = if (count == 1) "1 track" else "$count tracks"
    Text(
        text = buildString {
            append(tracks)
            totalDurationSeconds?.takeIf { it > 0 }?.let {
                append("  ·  ${formatTotalDuration(it)}")
            }
        },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------
// Flowing "Describe • Gimme Time • … • Tell Me (feat. X)" hero title text.
// ---------------------------------------------------------------------------

// Plain text normally (no link blue / underline — inherit the surrounding
// style); an underline appears while a title is pressed.
private val AlbumTitleLinkStyles = TextLinkStyles(
    style = SpanStyle(),
    pressedStyle = SpanStyle(textDecoration = TextDecoration.Underline),
)

internal fun buildAlbumTrackTitles(
    songs: List<AlbumSong>,
    separatorColor: Color,
    featColor: Color,
    onSongClick: ((String) -> Unit)?,
) = buildAnnotatedString {
    fun appendTitle(song: AlbumSong) {
        append(song.title)
        song.featArtist?.let { feat ->
            withStyle(SpanStyle(fontSize = 0.6.em, color = featColor)) {
                append(" (feat. $feat)")
            }
        }
    }
    songs.forEachIndexed { index, song ->
        if (index > 0) {
            withStyle(SpanStyle(color = separatorColor)) { append("  •  ") }
        }
        if (onSongClick != null) {
            // Each title is its OWN clickable link → plays just that song,
            // exactly like tapping a row in a normal track list.
            withLink(
                LinkAnnotation.Clickable(
                    tag = song.id,
                    styles = AlbumTitleLinkStyles,
                    linkInteractionListener = { onSongClick(song.id) },
                ),
            ) { appendTitle(song) }
        } else {
            appendTitle(song)
        }
    }
}

// ---------------------------------------------------------------------------
// Track row: number · title (+ note marker) · artist · duration · ⊕/✓ toggle.
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AlbumTrackRow(
    index: Int,
    song: AlbumSong,
    hasNote: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleStar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(YoinContainerShapes.ListRow)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performLongPress()
                    onLongClick()
                },
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = (song.trackNumber ?: (index + 1)).toString(),
            style = MaterialTheme.typography.labelMedium.withTabularFigures(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 18.dp),
        )
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
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (hasNote) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.StickyNote2,
                        contentDescription = "Has note",
                        tint = accent.copy(alpha = 0.8f),
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        song.duration?.let { duration ->
            Text(
                text = formatTrackDuration(duration),
                style = MaterialTheme.typography.labelLarge.withTabularFigures(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AlbumCircleToggle(
            active = song.isStarred,
            accent = accent,
            onToggle = onToggleStar,
        )
    }
}

@Composable
private fun AlbumCircleToggle(
    active: Boolean,
    accent: Color,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    val container by animateColorAsState(
        targetValue = if (active) accent else Color.Transparent,
        animationSpec = YoinMotion.effectsSpring(),
        label = "trackToggleContainer",
    )
    IconButton(
        onClick = {
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
                .clip(CircleShape)
                .background(container)
                .then(
                    if (!active) {
                        Modifier.border(
                            width = 1.5.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            shape = CircleShape,
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (active) Icons.Filled.Check else Icons.Filled.Add,
                contentDescription = if (active) "Saved" else "Save",
                tint = if (active) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Rating + comment editor (opened from the comment-row pencil / the Bun).
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlbumRatingReviewSheet(
    userRating: Float?,
    userReview: String,
    reviewHasUnsavedEdits: Boolean,
    onRatingCommit: (Float) -> Unit,
    onReviewDraftChange: (String) -> Unit,
    onSaveReview: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptics = rememberYoinHaptics()
    var sliderValue by remember(userRating) { mutableStateOf(userRating ?: 0f) }
    LaunchedEffect(userRating) { sliderValue = userRating ?: 0f }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Rate & comment",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "My rating",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (sliderValue > 0f) {
                        "${formatAlbumScore(sliderValue)} / 10"
                    } else {
                        "Not rated"
                    },
                    style = MaterialTheme.typography.titleMedium.withTabularFigures(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = {
                    haptics.performTick()
                    onRatingCommit(sliderValue)
                },
                valueRange = 0f..10f,
                steps = 9,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Comment",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Markdown supported. Saved locally; pushed to NeoDB from the Memory card.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = userReview,
                onValueChange = onReviewDraftChange,
                placeholder = {
                    Text(
                        text = "Write what stuck with you…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                minLines = 4,
            )
            Button(
                onClick = {
                    haptics.performConfirm()
                    onSaveReview()
                },
                enabled = reviewHasUnsavedEdits,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (reviewHasUnsavedEdits) "Save comment" else "Comment saved")
            }
        }
    }
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
internal fun albumLastPlayLabels(epochMillis: Long): Pair<String, String> {
    val zone = ZoneId.systemDefault()
    val moment = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val date = moment.toLocalDate()
    val today = LocalDate.now(zone)
    val days = ChronoUnit.DAYS.between(date, today)
    val day = when {
        days <= 0L -> "Today"
        days == 1L -> "Yesterday"
        days < 7L -> "$days days ago"
        else -> date.format(DateTimeFormatter.ofPattern("MMM d"))
    }
    val time = moment.format(DateTimeFormatter.ofPattern("HH:mm"))
    return day to time
}
