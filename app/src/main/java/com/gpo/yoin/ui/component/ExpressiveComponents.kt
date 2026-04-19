package com.gpo.yoin.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.gpo.yoin.R
import com.gpo.yoin.ui.theme.GoogleSansFlex
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinShapeTokens

@Composable
internal fun ExpressivePageBackground(
    accentColor: androidx.compose.ui.graphics.Color? = null,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val topColor = accentColor?.let { accent ->
        androidx.compose.ui.graphics.lerp(
            start = MaterialTheme.colorScheme.surfaceContainer,
            stop = accent,
            fraction = 0.18f,
        )
    } ?: MaterialTheme.colorScheme.surfaceContainer
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        topColor,
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ),
            ),
        content = content,
    )
}

@Composable
internal fun ExpressiveSectionPanel(
    modifier: Modifier = Modifier,
    shape: Shape = YoinShapeTokens.ExtraLarge,
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
) {
    val artworkModifier = if (interactionSource != null) {
        modifier.elasticPress(interactionSource)
    } else {
        modifier
    }

    Surface(
        modifier = artworkModifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f),
        ),
    ) {
        when {
            !LocalInspectionMode.current && model != null -> {
                val context = LocalContext.current
                val request = remember(model, context) {
                    ImageRequest.Builder(context)
                        .data(model)
                        .crossfade(220)
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = contentDescription,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                    error = painterResource(R.drawable.ic_launcher_foreground),
                )
            }

            LocalInspectionMode.current -> {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = contentDescription,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
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
    val borderColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f),
        animationSpec = YoinMotion.effectsSpring(),
        label = "expressiveFieldBorder",
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.76f),
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
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
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
