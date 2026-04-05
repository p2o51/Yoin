package com.gpo.yoin.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.pill
import androidx.graphics.shapes.rectangle
import com.gpo.yoin.ui.navigation.YoinSection
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * Bottom Button Group — the sole navigation element in Yoin.
 *
 * Three buttons:
 *  - Home (left)   — section toggle
 *  - Now Playing (center) — opens fullscreen overlay
 *  - Library (right) — section toggle
 *
 * Selected section button expands via Spatial Spring with Shape Morph;
 * colors transition via Effects Spring.
 */
@Composable
fun YoinButtonGroup(
    selectedSection: YoinSection,
    currentTrackTitle: String?,
    currentTrackArtist: String?,
    onHomeClick: () -> Unit,
    onNowPlayingClick: () -> Unit,
    onLibraryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pillPoly = remember { RoundedPolygon.pill() }
    val roundedRectPoly = remember {
        RoundedPolygon.rectangle(rounding = CornerRounding(0.4f))
    }
    val morph = remember { Morph(pillPoly, roundedRectPoly) }

    val homeWeight by animateFloatAsState(
        targetValue = if (selectedSection == YoinSection.HOME) 1.5f else 1f,
        animationSpec = YoinMotion.spatialSpring(),
        label = "homeWeight",
    )
    val libraryWeight by animateFloatAsState(
        targetValue = if (selectedSection == YoinSection.LIBRARY) 1.5f else 1f,
        animationSpec = YoinMotion.spatialSpring(),
        label = "libraryWeight",
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SectionButton(
                isSelected = selectedSection == YoinSection.HOME,
                onClick = onHomeClick,
                morph = morph,
                modifier = Modifier.weight(homeWeight),
            ) {
                Icon(
                    imageVector = Icons.Filled.Home,
                    contentDescription = "Home",
                    modifier = Modifier.size(24.dp),
                )
            }

            NowPlayingButton(
                trackTitle = currentTrackTitle,
                trackArtist = currentTrackArtist,
                onClick = onNowPlayingClick,
                modifier = Modifier.weight(2f),
            )

            SectionButton(
                isSelected = selectedSection == YoinSection.LIBRARY,
                onClick = onLibraryClick,
                morph = morph,
                modifier = Modifier.weight(libraryWeight),
            ) {
                Icon(
                    imageVector = Icons.Filled.LibraryMusic,
                    contentDescription = "Library",
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

// ── Internal components ─────────────────────────────────────────────────

@Composable
private fun SectionButton(
    isSelected: Boolean,
    onClick: () -> Unit,
    morph: Morph,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val morphProgress by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = YoinMotion.spatialSpring(),
        label = "morphProgress",
    )

    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "sectionBgColor",
    )

    val contentColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "sectionContentColor",
    )

    val shape = remember(morphProgress) { morph.toComposeShape(morphProgress) }

    Box(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(backgroundColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

@Composable
private fun NowPlayingButton(
    trackTitle: String?,
    trackArtist: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasTrack = trackTitle != null

    Box(
        modifier = modifier
            .height(48.dp)
            .clip(YoinShapeTokens.Full)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            if (hasTrack) {
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = trackTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = trackArtist.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(
                            alpha = 0.7f,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    text = "Not Playing",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

// ── Shape morph utility ─────────────────────────────────────────────────

/**
 * Converts a [Morph] at the given [progress] (0 → start shape, 1 → end shape)
 * into a Compose [Shape] that can be used with `Modifier.clip()` / `.background()`.
 */
private fun Morph.toComposeShape(progress: Float): Shape = object : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val androidPath = morphToPath(progress)
        val matrix = android.graphics.Matrix()
        // RoundedPolygon lives in normalised [-1, 1] space; scale + translate to layout size
        matrix.postScale(size.width / 2f, size.height / 2f)
        matrix.postTranslate(size.width / 2f, size.height / 2f)
        androidPath.transform(matrix)
        return Outline.Generic(androidPath.asComposePath())
    }
}

/** Build an [android.graphics.Path] from a [Morph] at the given [progress]. */
private fun Morph.morphToPath(progress: Float): android.graphics.Path {
    val path = android.graphics.Path()
    val cubics = asCubics(progress)
    if (cubics.isNotEmpty()) {
        path.moveTo(cubics[0].anchor0X, cubics[0].anchor0Y)
        for (cubic in cubics) {
            path.cubicTo(
                cubic.control0X, cubic.control0Y,
                cubic.control1X, cubic.control1Y,
                cubic.anchor1X, cubic.anchor1Y,
            )
        }
        path.close()
    }
    return path
}

// ── Previews ────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun YoinButtonGroupHomeSelectedPreview() {
    YoinTheme {
        YoinButtonGroup(
            selectedSection = YoinSection.HOME,
            currentTrackTitle = null,
            currentTrackArtist = null,
            onHomeClick = {},
            onNowPlayingClick = {},
            onLibraryClick = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun YoinButtonGroupLibrarySelectedPreview() {
    YoinTheme {
        YoinButtonGroup(
            selectedSection = YoinSection.LIBRARY,
            currentTrackTitle = "Starlight",
            currentTrackArtist = "Muse",
            onHomeClick = {},
            onNowPlayingClick = {},
            onLibraryClick = {},
        )
    }
}
