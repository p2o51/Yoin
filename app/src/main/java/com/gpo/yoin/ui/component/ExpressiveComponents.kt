package com.gpo.yoin.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import com.gpo.yoin.R
import com.gpo.yoin.ui.theme.GoogleSansFlex
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinShapeTokens
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// One full cycle of the playing-state wash — intentionally slow so the page
// feels alive without competing with cover art or scrolling content.
private const val ExpressivePageBackgroundDriftMillis = 75_000

// The drift's step: one 0.5 s step moves the wobble by under a quarter of an 8-bit level.
private const val ExpressivePageBackgroundDriftTickMillis = 500L

/**
 * Pages that share a window with another column (the Wide shell's detail
 * column) draw the window's ONE neutral page wash — no cover accent, no
 * playing wobble — and so does the gutter between the columns. The wash is a
 * vertical gradient, so equal parameters meet edge to edge without a seam:
 * the columns read as one surface, with only the drag handle between them.
 */
internal val LocalSharedPageBackground = staticCompositionLocalOf { false }

@Composable
internal fun ExpressivePageBackground(
    accentColor: androidx.compose.ui.graphics.Color? = null,
    isPlaying: Boolean = false,
    playbackSignal: Float = 0f,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val shared = LocalSharedPageBackground.current
    @Suppress("NAME_SHADOWING")
    val accentColor = accentColor.takeUnless { shared }
    @Suppress("NAME_SHADOWING")
    val isPlaying = isPlaying && !shared
    val scheme = MaterialTheme.colorScheme
    // Animated because the upstream 380ms palette tween can't cover the
    // null→non-null accent gate (DetailPageColors): at the instant the palette
    // resolves the target steps, and this wash absorbs the residual snap.
    val baseTopColor by animateColorAsState(
        targetValue = accentColor?.let { accent ->
            lerp(scheme.surfaceContainer, accent, 0.18f)
        } ?: scheme.surfaceContainer,
        animationSpec = YoinMotion.effectsSpring(),
        label = "pageBackgroundTop",
    )

    // The drift moves the wash by well under one 8-bit level per second, so it
    // steps every [ExpressivePageBackgroundDriftTickMillis] instead of every
    // vsync (a per-frame Animatable recomposed this whole background at the
    // panel's full rate for as long as music played). Each step waits for a
    // frame, so it rests with the window's frame clock (stopped, or covered
    // by another Yoin window), and a long rest never jumps the phase. Paused,
    // the wobble is off (below) and the phase simply holds.
    var drift by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (isActive) {
            delay(ExpressivePageBackgroundDriftTickMillis)
            val now = withFrameMillis { it }
            val elapsed = (now - last).coerceAtMost(ExpressivePageBackgroundDriftTickMillis * 2)
            last = now
            drift = (drift + elapsed.toFloat() / ExpressivePageBackgroundDriftMillis) % 1f
        }
    }

    val phase = drift
    val playingWobble = if (isPlaying) {
        val wave = sin(phase * 2f * PI.toFloat()) * 0.5f + 0.5f
        wave * 0.08f + playbackSignal.coerceIn(0f, 1f) * 0.03f
    } else {
        0f
    }
    val topColor = if (accentColor != null && isPlaying) {
        lerp(baseTopColor, accentColor, playingWobble)
    } else {
        baseTopColor
    }
    val midColor = if (isPlaying) {
        lerp(scheme.background, scheme.surfaceContainerLow, playingWobble * 0.35f)
    } else {
        scheme.background
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        topColor,
                        midColor,
                        scheme.surfaceContainerLow,
                    ),
                ),
            ),
        content = content,
    )
}

/**
 * The page colours [ExpressivePageBackground] runs through (top → bottom),
 * for the seams' bottom field to ease its dots toward. The accent wash and
 * the playing wobble only tint the top stop, far from the bar.
 */
@Composable
internal fun expressivePageSeamBackground(): SeamBackground {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme.surfaceContainer, scheme.background, scheme.surfaceContainerLow) {
        SeamBackground(listOf(scheme.surfaceContainer, scheme.background, scheme.surfaceContainerLow))
    }
}

@Composable
internal fun ExpressiveSectionPanel(
    modifier: Modifier = Modifier,
    shape: Shape = YoinContainerShapes.Panel,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainerLow,
    tonalElevation: Dp = 1.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        border = border,
    ) {
        Column(content = content)
    }
}

/** The type icon [ExpressiveMediaArtwork] shows with no artwork, or under a failed one. */
internal const val ARTWORK_FALLBACK_TAG = "artworkFallback"

@Composable
internal fun ExpressiveMediaArtwork(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = YoinShapeTokens.Large,
    fallbackIcon: ImageVector,
    interactionSource: MutableInteractionSource? = null,
    contentScale: ContentScale = ContentScale.Crop,
    tonalElevation: Dp = 2.dp,
    shadowElevation: Dp = 0.dp,
    // No default hairline: cover art everywhere (home grid, cards, docks, NP)
    // reads cleaner naked — the user explicitly banned cover outlines.
    border: BorderStroke? = null,
    filterQuality: FilterQuality = FilterQuality.Low,
    // When set, the bitmap resolves at this fixed square size instead of the
    // live layout size — required when the artwork's size is being animated.
    requestSizePx: Int? = null,
    reveal: ArtworkReveal = ArtworkReveal.Crossfade,
    revealDirection: Int = 1,
) {
    val artworkModifier = if (interactionSource != null) {
        modifier.elasticPress(interactionSource)
    } else {
        modifier
    }

    // A blank url is no artwork, like null — not a request that can only fail.
    val artworkModel = model?.takeIf { it.isNotBlank() }
    // A failed load must render the same icon-in-box as a null url — an
    // `error =` painter of the launcher mark reads as fake artwork. A failed
    // artwork retries when the network comes back, when the app returns to
    // the foreground, and on a backoff for transient errors (ArtworkRetry.kt);
    // the icon stays under the retries until a recovered cover has revealed
    // over it. Artwork that has not failed never waits on the retry signal.
    var retry by remember(artworkModel) {
        mutableStateOf(ArtworkRetryState(requestSignal = ArtworkRetrySignal.generation.value))
    }

    Surface(
        modifier = artworkModifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        border = border,
    ) {
        if (LocalInspectionMode.current) {
            Image(
                painter = painterResource(id = R.drawable.ic_yoin_launcher_foreground),
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            if (artworkModel == null || retry.fallbackUnder) {
                Box(
                    modifier = Modifier.fillMaxSize().testTag(ARTWORK_FALLBACK_TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = fallbackIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            if (artworkModel != null && retry.requesting) {
                ArtworkSwap(
                    model = artworkModel,
                    contentDescription = contentDescription,
                    contentScale = contentScale,
                    filterQuality = filterQuality,
                    requestSizePx = requestSizePx,
                    reveal = reveal,
                    direction = revealDirection,
                    onError = { error -> retry = retry.failed(error) },
                    revealFirstLoad = retry.fallbackUnder,
                    onRevealed = { if (retry.fallbackUnder) retry = retry.revealed() },
                )
            } else if (artworkModel != null) {
                val failed = retry
                val context = LocalContext.current
                LaunchedEffect(failed) {
                    // Bytes that made no image must not be read back from the disk cache.
                    if (failed.failure == ArtworkFailureKind.Undecodable) {
                        forgetStoredArtwork(SingletonImageLoader.get(context), artworkModel)
                    }
                    retry = awaitArtworkRetry(failed, ArtworkRetrySignal.generation)
                }
            }
        }
    }
}

@Composable
internal fun ExpressiveTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val fieldInteractionSource = remember { MutableInteractionSource() }
    val isFocused by fieldInteractionSource.collectIsFocusedAsState()
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "expressiveFieldBorder",
    )
    val labelColor by animateColorAsState(
        targetValue = if (isFocused) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.76f)
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "expressiveFieldLabel",
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, borderColor),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                singleLine = true,
                interactionSource = fieldInteractionSource,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = GoogleSansFlex,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = visualTransformation,
                decorationBox = { innerTextField ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (leadingContent != null) {
                            Box(
                                modifier = Modifier.size(18.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                leadingContent()
                            }
                        }
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (value.isBlank()) {
                                Text(
                                    text = placeholder,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            innerTextField()
                        }
                        if (trailingContent != null) {
                            trailingContent()
                        }
                    }
                },
            )
        }
    }
}

@Composable
internal fun <T> ExpressiveSegmentedTabs(
    items: List<T>,
    selectedItem: T,
    label: (T) -> String,
    onSelectedChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    // Padding INSIDE the scrolling viewport, so chips scroll under the row's
    // edges (soft scroll-aware fade) instead of being chopped at them.
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalEdgeFadeOnScroll(scrollState)
            .horizontalScroll(scrollState)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            val interactionSource = remember { MutableInteractionSource() }
            val selected = item == selectedItem
            val containerColor by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                animationSpec = YoinMotion.effectsSpring(),
                label = "segmentContainer",
            )
            val contentColor by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = YoinMotion.effectsSpring(),
                label = "segmentContent",
            )
            FilledTonalButton(
                onClick = { onSelectedChange(item) },
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .elasticPress(interactionSource),
                interactionSource = interactionSource,
                shape = RoundedCornerShape(18.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 10.dp,
                ),
                colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                    containerColor = containerColor,
                    contentColor = contentColor,
                ),
            ) {
                Text(
                    text = label(item),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun ExpressiveMetaPill(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = YoinShapeTokens.Full,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
internal fun ExpressiveHeaderBlock(
    title: String,
    modifier: Modifier = Modifier,
    overline: String? = null,
    supporting: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (overline != null) {
            Text(
                text = overline,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailing != null) {
                Spacer(modifier = Modifier.width(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = trailing,
                )
            }
        }
        if (supporting != null) {
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
