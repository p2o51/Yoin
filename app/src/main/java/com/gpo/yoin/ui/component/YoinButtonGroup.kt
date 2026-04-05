package com.gpo.yoin.ui.component

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gpo.yoin.ui.navigation.YoinSection

/**
 * Floating navigation/playback group built on the official Material 3 Expressive
 * `ButtonGroup` API. The container itself is custom, but item interaction, pressed
 * width animation and selection affordances stay inside the official MD3 system.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun YoinButtonGroup(
    selectedSection: YoinSection,
    currentTrackTitle: String?,
    currentTrackArtist: String?,
    currentTrackCoverArtUrl: String?,
    isPlaybackReady: Boolean,
    connectionErrorMessage: String?,
    playbackProgress: Float = 0f,
    onHomeClick: () -> Unit,
    onNowPlayingClick: () -> Unit,
    onLibraryClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
    ) {
        ButtonGroup(
            overflowIndicator = { _ -> },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            expandedRatio = ButtonGroupDefaults.ExpandedRatio,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            customItem(
                buttonGroupContent = {
                    val interactionSource = rememberButtonGroupInteractionSource()
                    FilledIconButton(
                        onClick = onHomeClick,
                        modifier = Modifier.animateWidth(interactionSource),
                        interactionSource = interactionSource,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (selectedSection == YoinSection.HOME) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            contentColor = if (selectedSection == YoinSection.HOME) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Home,
                            contentDescription = "Home",
                        )
                    }
                },
                menuContent = { _ ->
                    Icon(
                        imageVector = Icons.Filled.Home,
                        contentDescription = "Home",
                    )
                },
            )

            customItem(
                buttonGroupContent = {
                    val interactionSource = rememberButtonGroupInteractionSource()
                    val clampedProgress = playbackProgress.coerceIn(0f, 1f)
                    val progressFill = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)

                    FilledTonalButton(
                        onClick = onNowPlayingClick,
                        modifier = Modifier
                            .weight(1.65f)
                            .animateWidth(interactionSource),
                        interactionSource = interactionSource,
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            if (currentTrackTitle != null && clampedProgress > 0f) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .clip(MaterialTheme.shapes.extraLarge)
                                        .background(
                                            Brush.horizontalGradient(
                                                colorStops = arrayOf(
                                                    0f to progressFill,
                                                    clampedProgress to progressFill,
                                                    (clampedProgress + 0.005f)
                                                        .coerceAtMost(1f) to Color.Transparent,
                                                    1f to Color.Transparent,
                                                ),
                                            ),
                                        ),
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                NowPlayingArtwork(
                                    currentTrackCoverArtUrl = currentTrackCoverArtUrl,
                                    currentTrackTitle = currentTrackTitle,
                                    sharedTransitionScope = sharedTransitionScope,
                                    animatedVisibilityScope = animatedVisibilityScope,
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    val titleText = currentTrackTitle ?: when {
                                        connectionErrorMessage != null -> "Playback unavailable"
                                        isPlaybackReady -> "Now Playing"
                                        else -> "Connecting"
                                    }
                                    val artistText = currentTrackArtist ?: when {
                                        connectionErrorMessage != null -> connectionErrorMessage
                                        isPlaybackReady -> "Open player"
                                        else -> "Preparing audio"
                                    }

                                    val titleMod = if (
                                        sharedTransitionScope != null &&
                                        animatedVisibilityScope != null &&
                                        currentTrackTitle != null
                                    ) {
                                        with(sharedTransitionScope) {
                                            Modifier.sharedBounds(
                                                sharedContentState = rememberSharedContentState(
                                                    key = "np_title",
                                                ),
                                                animatedVisibilityScope = animatedVisibilityScope,
                                                boundsTransform = { _, _ ->
                                                    spring(stiffness = Spring.StiffnessMediumLow)
                                                },
                                            )
                                        }
                                    } else {
                                        Modifier
                                    }
                                    Text(
                                        text = titleText,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = titleMod,
                                    )

                                    val artistMod = if (
                                        sharedTransitionScope != null &&
                                        animatedVisibilityScope != null &&
                                        currentTrackArtist != null
                                    ) {
                                        with(sharedTransitionScope) {
                                            Modifier.sharedBounds(
                                                sharedContentState = rememberSharedContentState(
                                                    key = "np_artist",
                                                ),
                                                animatedVisibilityScope = animatedVisibilityScope,
                                                boundsTransform = { _, _ ->
                                                    spring(stiffness = Spring.StiffnessMediumLow)
                                                },
                                            )
                                        }
                                    } else {
                                        Modifier
                                    }
                                    Text(
                                        text = artistText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(
                                            alpha = 0.72f,
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = artistMod,
                                    )
                                }
                            }
                        }
                    }
                },
                menuContent = { _ -> },
            )

            customItem(
                buttonGroupContent = {
                    val interactionSource = rememberButtonGroupInteractionSource()
                    FilledIconButton(
                        onClick = onLibraryClick,
                        modifier = Modifier.animateWidth(interactionSource),
                        interactionSource = interactionSource,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (selectedSection == YoinSection.LIBRARY) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            contentColor = if (selectedSection == YoinSection.LIBRARY) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LibraryMusic,
                            contentDescription = "Library",
                        )
                    }
                },
                menuContent = { _ ->
                    Icon(
                        imageVector = Icons.Filled.LibraryMusic,
                        contentDescription = "Library",
                    )
                },
            )
        }
    }
}

@Composable
private fun rememberButtonGroupInteractionSource() =
    remember { MutableInteractionSource() }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun NowPlayingArtwork(
    currentTrackCoverArtUrl: String?,
    currentTrackTitle: String?,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    val baseModifier = modifier.size(34.dp)
    val finalModifier = if (
        sharedTransitionScope != null &&
        animatedVisibilityScope != null &&
        currentTrackCoverArtUrl != null
    ) {
        with(sharedTransitionScope) {
            baseModifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = "np_cover"),
                animatedVisibilityScope = animatedVisibilityScope,
                boundsTransform = { _, _ ->
                    spring(stiffness = Spring.StiffnessMediumLow)
                },
            )
        }
    } else {
        baseModifier
    }

    Box(
        modifier = finalModifier.clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (currentTrackCoverArtUrl != null) {
            AsyncImage(
                model = currentTrackCoverArtUrl,
                contentDescription = currentTrackTitle ?: "Current track",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}
