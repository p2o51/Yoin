package com.gpo.yoin.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.rememberPressMorphShape
import com.gpo.yoin.ui.component.elasticPress
import com.gpo.yoin.ui.component.noRippleClickable
import com.gpo.yoin.ui.component.rememberExpressiveBackdropColors
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.theme.GoogleSansFlex
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.withTabularFigures

// The compact "1×1" cover is 100dp wide in the Figma; the backdrop shape fills
// it while the artwork sits at ~73/100 in the bottom-right so the shape peeks
// out around it. The wide "1×2" card reuses that same 100dp cover on the left.
private val WidgetCoverSize = 100.dp
private const val WidgetArtworkFraction = 0.72f
private const val WidgetGridColumns = 3

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

/** The design-language section heading shared by the home feed sections. */
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
        modifier = modifier,
    )
}

/**
 * The home widget grid (Figma node 405:361, "Memories" visual language): a
 * masonry over a 3-column grid where a wide "1×2" card spans two columns and
 * shares its row with a compact "1×1" cover; unpaired covers fill rows of
 * three. Cards are plain taps — album/playlist push their detail, songs play,
 * memory cards push into the Memories deck. No predictive-back choreography.
 */
@Composable
internal fun HomeWidgetGridSection(
    title: String,
    cards: List<HomeWidgetCard>,
    extractBackdropColors: Boolean,
    onCardClick: (HomeWidgetTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (cards.isEmpty()) return
    val rows = remember(cards) { packWidgetRows(cards) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HomeSectionTitle(text = title)
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
                    if (card.expanded) {
                        units += 2
                        WidgetCard12(
                            card = card,
                            extractBackdropColors = extractBackdropColors,
                            onClick = { onCardClick(card.target) },
                            modifier = Modifier.weight(2f),
                        )
                    } else {
                        units += 1
                        WidgetCoverBlock(
                            card = card,
                            extractBackdropColors = extractBackdropColors,
                            onClick = { onCardClick(card.target) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                // Pad short rows so cards keep their column width instead of
                // stretching across the leftover space.
                repeat(WidgetGridColumns - units) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Pack the grid into rows of [WidgetGridColumns] units — a 1×2 is two units,
 * a 1×1 is one. Each 1×2 is paired with the next 1×1 (alternating sides so the
 * wide card doesn't always hug the same edge, mirroring the Figma masonry);
 * leftover 1×1 covers fill full rows of three. Order otherwise follows the
 * incoming ranking, so the strongest cards still lead.
 */
private fun packWidgetRows(
    cards: List<HomeWidgetCard>,
): List<List<HomeWidgetCard>> {
    val wide = cards.filter { it.expanded }.toMutableList()
    val compact = cards.filterNot { it.expanded }.toMutableList()
    val rows = mutableListOf<List<HomeWidgetCard>>()
    var pairIndex = 0
    while (wide.isNotEmpty()) {
        val big = wide.removeAt(0)
        val small = if (compact.isNotEmpty()) compact.removeAt(0) else null
        rows += when {
            small == null -> listOf(big)
            pairIndex % 2 == 0 -> listOf(big, small)
            else -> listOf(small, big)
        }
        pairIndex++
    }
    compact.chunked(WidgetGridColumns).forEach { chunk -> rows += chunk }
    return rows
}

/**
 * The wide "1×2" card: the 1×1 cover block on the left, and a column on the
 * right with the rating (tinted to the cover), what it's based on, and — when
 * present — the review/note copy in a serif face echoing the Figma.
 */
@Composable
private fun WidgetCard12(
    card: HomeWidgetCard,
    extractBackdropColors: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val backdropColors = rememberExpressiveBackdropColors(
        model = card.coverArtUrl,
        fallbackBaseColor = MaterialTheme.colorScheme.secondary,
        fallbackAccentColor = MaterialTheme.colorScheme.tertiary,
        enabled = extractBackdropColors,
    )
    val haptics = rememberYoinHaptics()
    Row(
        modifier = modifier
            .noRippleClickable(interactionSource = interactionSource) {
                haptics.performContextClick()
                onClick()
            },
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
        Column(
            modifier = Modifier.weight(1f),
            // The rating's own line box already carries generous leading —
            // 3dp keeps the comment visually attached to its score.
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            card.ratingText?.let { rating ->
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
                    card.ratingBasis?.let { basis ->
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
                Text(
                    text = comment,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The 1×1 cover: an entity-type backdrop shape with the artwork nested inside
 * it, then the title and subtitle. Used standalone in the grid and as the left
 * half of a [WidgetCard12].
 */
@Composable
private fun WidgetCoverBlock(
    card: HomeWidgetCard,
    extractBackdropColors: Boolean,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    onClick: (() -> Unit)? = null,
) {
    val ownInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val haptics = rememberYoinHaptics()
    val clickModifier = if (onClick != null) {
        Modifier.noRippleClickable(interactionSource = ownInteractionSource) {
            haptics.performContextClick()
            onClick()
        }
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
            modifier = Modifier.size(WidgetCoverSize),
        )
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = card.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = card.subtitle,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
            modifier = modifier,
            shape = CircleShape,
            fallbackIcon = widgetFallbackIcon(kind),
            interactionSource = interactionSource,
            tonalElevation = 1.dp,
            shadowElevation = 0.dp,
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
    Box(modifier = modifier) {
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
        )
    }
}

private fun widgetFallbackIcon(kind: WidgetShapeKind): ImageVector = when (kind) {
    WidgetShapeKind.Playlist -> Icons.AutoMirrored.Filled.QueueMusic
    WidgetShapeKind.Song -> Icons.Filled.MusicNote
    WidgetShapeKind.Album -> Icons.Filled.LibraryMusic
    WidgetShapeKind.Artist -> Icons.Filled.Person
}
