package com.gpo.yoin.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
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
import com.gpo.yoin.ui.component.elasticPress
import com.gpo.yoin.ui.component.noRippleClickable
import com.gpo.yoin.ui.component.rememberExpressiveBackdropColors
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.withTabularFigures

// The compact "1×1" cover is 100dp wide in the Figma; the backdrop shape fills
// it while the artwork sits at ~73/100 in the bottom-right so the shape peeks
// out around it. The wide "1×2" card reuses that same 100dp cover on the left.
private val MemoryCoverSize = 100.dp
private const val MemoryArtworkFraction = 0.72f
private const val MemoriesGridColumns = 3

/**
 * The home "Memories" shelf (Figma node 405:361). A masonry over a 3-column
 * grid: a wide "1×2" card (cover + rating + review copy) spans two columns and
 * shares its row with a compact "1×1" cover; unpaired covers fill rows of three.
 * Every card is a plain tap that pushes into the Memories deck stopped on that
 * album — no shared-element or predictive-back choreography, just a unified open.
 */
@Composable
internal fun HomeMemoriesSection(
    memories: List<HomeMemoryWidget>,
    extractBackdropColors: Boolean,
    onOpenMemory: (sessionId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (memories.isEmpty()) return
    val rows = remember(memories) { packMemoryRows(memories) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Memories",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                // A 1×2 is taller than the 1×1 beside it; top-align so the cover
                // block hangs from the same line and the review copy runs below.
                verticalAlignment = Alignment.Top,
            ) {
                var units = 0
                row.forEach { memory ->
                    if (memory.expanded) {
                        units += 2
                        MemoryWidget12(
                            memory = memory,
                            extractBackdropColors = extractBackdropColors,
                            onClick = { onOpenMemory(memory.sessionId) },
                            modifier = Modifier.weight(2f),
                        )
                    } else {
                        units += 1
                        MemoryCoverBlock(
                            memory = memory,
                            extractBackdropColors = extractBackdropColors,
                            onClick = { onOpenMemory(memory.sessionId) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                // Pad short rows so cards keep their column width instead of
                // stretching across the leftover space.
                repeat(MemoriesGridColumns - units) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Pack the shelf into rows of [MemoriesGridColumns] units — a 1×2 is two units,
 * a 1×1 is one. Each 1×2 is paired with the next 1×1 (alternating sides so the
 * wide card doesn't always hug the same edge, mirroring the Figma masonry);
 * leftover 1×1 covers fill full rows of three. Order otherwise follows memory
 * rank, so the strongest memories still lead.
 */
private fun packMemoryRows(
    memories: List<HomeMemoryWidget>,
): List<List<HomeMemoryWidget>> {
    val wide = memories.filter { it.expanded }.toMutableList()
    val compact = memories.filterNot { it.expanded }.toMutableList()
    val rows = mutableListOf<List<HomeMemoryWidget>>()
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
    compact.chunked(MemoriesGridColumns).forEach { chunk -> rows += chunk }
    return rows
}

/**
 * The wide "1×2" card: the 1×1 cover block on the left, and a column on the
 * right with the rating (tinted to the cover), what it's based on, and — when
 * present — the review copy in a serif face echoing the Figma.
 */
@Composable
private fun MemoryWidget12(
    memory: HomeMemoryWidget,
    extractBackdropColors: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val backdropColors = rememberExpressiveBackdropColors(
        model = memory.coverArtUrl,
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
        MemoryCoverBlock(
            memory = memory,
            extractBackdropColors = extractBackdropColors,
            interactionSource = interactionSource,
            modifier = Modifier.width(MemoryCoverSize),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = memory.ratingText,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                    ).withTabularFigures(),
                    color = backdropColors.baseColor,
                )
                memory.ratingBasis?.let { basis ->
                    Text(
                        text = basis,
                        style = MaterialTheme.typography.labelSmall.withTabularFigures(),
                        color = backdropColors.baseColor.copy(alpha = 0.85f),
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
            memory.comment?.let { comment ->
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
 * half of a [MemoryWidget12].
 */
@Composable
private fun MemoryCoverBlock(
    memory: HomeMemoryWidget,
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
    Column(
        modifier = modifier.then(clickModifier),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MemoryBackdropArtwork(
            model = memory.coverArtUrl,
            entityType = memory.entityType,
            contentDescription = memory.title,
            extractBackdropColors = extractBackdropColors,
            interactionSource = ownInteractionSource,
            modifier = Modifier.size(MemoryCoverSize),
        )
        Text(
            text = memory.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = memory.subtitle,
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
 * palette, with the artwork nested at [MemoryArtworkFraction] in the
 * bottom-right so the shape peeks out around it. No morph / scale / FFT pulse —
 * those were the parts that cost frames and got cut; only the shape stays.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MemoryBackdropArtwork(
    model: String?,
    entityType: MemoryEntityType,
    contentDescription: String,
    extractBackdropColors: Boolean,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
) {
    val backdropColors = rememberExpressiveBackdropColors(
        model = model,
        fallbackBaseColor = MaterialTheme.colorScheme.secondaryContainer,
        fallbackAccentColor = MaterialTheme.colorScheme.tertiaryContainer,
        enabled = extractBackdropColors,
    )
    val backdropShape: Shape = when (entityType) {
        MemoryEntityType.ALBUM -> MaterialShapes.Bun.toShape()
        MemoryEntityType.SONG -> MaterialShapes.Circle.toShape()
        MemoryEntityType.PLAYLIST -> MaterialShapes.Ghostish.toShape()
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
                .fillMaxSize(MemoryArtworkFraction)
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
            shape = YoinShapeTokens.Small,
            fallbackIcon = memoryFallbackIcon(entityType),
            tonalElevation = 1.dp,
            shadowElevation = 0.dp,
        )
    }
}

private fun memoryFallbackIcon(entityType: MemoryEntityType): ImageVector = when (entityType) {
    MemoryEntityType.PLAYLIST -> Icons.AutoMirrored.Filled.QueueMusic
    MemoryEntityType.SONG -> Icons.Filled.MusicNote
    MemoryEntityType.ALBUM -> Icons.Filled.LibraryMusic
}
