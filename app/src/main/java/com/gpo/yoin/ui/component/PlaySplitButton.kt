package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.rememberYoinHaptics

/** Original detail floating-toolbar button height; the bottom bar uses 48dp. */
val PlaySplitButtonDefaultHeight = 60.dp

private val PlayMenuTextStyle
    @Composable get() = MaterialTheme.typography.titleMedium

private val PlayMenuItemPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)

/**
 * Play + ▾ split button: the real M3 leading/trailing split buttons (pressed
 * shape morphs intact) composed in a PLAIN Row instead of SplitButtonLayout.
 * This is deliberate, same story as the shell bar dropping M3 ButtonGroup:
 * the shell renders this inside SharedTransitionLayout, and custom
 * multi-child measure policies hang/crash under the lookahead pass when the
 * bar's shared elements are active. A plain Row is lookahead-safe, and also
 * lets the Play half stretch ([fillPlay]) to absorb the bar's slack width.
 *
 * "Shuffle play" is always the first menu item; page-specific items (Go to
 * artist, Open in Spotify, Share, …) slot in via [trailingMenuItems].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlaySplitButton(
    playContainer: Color,
    playContent: Color,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    buttonHeight: Dp = PlaySplitButtonDefaultHeight,
    fillPlay: Boolean = false,
    compact: Boolean = false,
    // False when the bar already shows Shuffle as its own button (CenteredBar
    // detail pose / a roomy EdgeSplit capsule): an action pulled out of the
    // menu never stays in it too (断点交接 §2.2).
    showShuffleInMenu: Boolean = true,
    trailingMenuItems: @Composable ColumnScope.(dismissMenu: () -> Unit) -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    val dismissMenu = { menuOpen = false }
    val haptics = rememberYoinHaptics()
    val buttonColors = ButtonDefaults.buttonColors(
        containerColor = playContainer,
        contentColor = playContent,
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(SplitButtonDefaults.Spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SplitButtonDefaults.LeadingButton(
            onClick = {
                haptics.performClick()
                onPlay()
            },
            colors = buttonColors,
            modifier = if (fillPlay) {
                Modifier
                    .height(buttonHeight)
                    .weight(1f)
            } else {
                Modifier.height(buttonHeight)
            },
            contentPadding = PaddingValues(horizontal = if (compact) 16.dp else 26.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize),
            )
            Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
            Text(
                "Play",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                softWrap = false,
            )
        }
        SplitButtonDefaults.TrailingButton(
            checked = menuOpen,
            onCheckedChange = { menuOpen = it },
            colors = buttonColors,
            modifier = Modifier.height(buttonHeight),
            contentPadding = PaddingValues(horizontal = if (compact) 14.dp else 20.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = "More play options",
                modifier = Modifier
                    .size(SplitButtonDefaults.TrailingIconSize)
                    .graphicsLayer { rotationZ = if (menuOpen) 180f else 0f },
            )
            YoinDropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                shadowElevation = 0.dp,
            ) {
                PlayMenuContent(
                    showShuffle = showShuffleInMenu,
                    onShuffle = onShuffle,
                    dismissMenu = dismissMenu,
                    trailingMenuItems = trailingMenuItems,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.PlayMenuContent(
    showShuffle: Boolean,
    onShuffle: () -> Unit,
    dismissMenu: () -> Unit,
    trailingMenuItems: @Composable ColumnScope.(dismissMenu: () -> Unit) -> Unit,
) {
    if (showShuffle) {
        YoinDropdownMenuItem(
            text = "Shuffle play",
            onClick = {
                dismissMenu()
                onShuffle()
            },
            leadingIcon = {
                Icon(
                    Icons.Filled.Shuffle,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            },
            textStyle = PlayMenuTextStyle,
            contentPadding = PlayMenuItemPadding,
        )
    }
    trailingMenuItems(dismissMenu)
}

/**
 * The same Play + ▾ split turned upright for the edge-split capsule
 * (ShellChromeForm.EdgeSplit, 断点交接 §2.2): Play 52 × [playHeight] with the
 * rounded end on top (26/26/8/8), ▾ 52 × 46 below it (8/8/26/26), 2dp apart.
 * M3's split button has exactly two parts; when the capsule has room for a
 * third action it is a separate segment ([showShuffleButton]), and Shuffle
 * then leaves the ▾ menu.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlaySplitButtonVertical(
    playContainer: Color,
    playContent: Color,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    playHeight: Dp,
    modifier: Modifier = Modifier,
    showShuffleButton: Boolean = false,
    trailingMenuItems: @Composable ColumnScope.(dismissMenu: () -> Unit) -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = rememberYoinHaptics()
    val colors = ButtonDefaults.buttonColors(
        containerColor = playContainer,
        contentColor = playContent,
    )
    Column(
        modifier = modifier.width(VerticalSplitWidth),
        verticalArrangement = Arrangement.spacedBy(SplitButtonDefaults.Spacing),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(
            onClick = {
                haptics.performClick()
                onPlay()
            },
            colors = colors,
            shape = RoundedCornerShape(
                topStart = VerticalSplitOuterRadius,
                topEnd = VerticalSplitOuterRadius,
                bottomStart = VerticalSplitInnerRadius,
                bottomEnd = VerticalSplitInnerRadius,
            ),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .width(VerticalSplitWidth)
                .height(playHeight),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Play",
                modifier = Modifier.size(26.dp),
            )
        }
        Box {
            Button(
                onClick = {
                    haptics.performContextClick()
                    menuOpen = !menuOpen
                },
                colors = colors,
                shape = RoundedCornerShape(
                    topStart = VerticalSplitInnerRadius,
                    topEnd = VerticalSplitInnerRadius,
                    bottomStart = VerticalSplitOuterRadius,
                    bottomEnd = VerticalSplitOuterRadius,
                ),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .width(VerticalSplitWidth)
                    .height(VerticalSplitMenuHeight),
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "More play options",
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer { rotationZ = if (menuOpen) 180f else 0f },
                )
            }
            YoinDropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                shadowElevation = 0.dp,
            ) {
                PlayMenuContent(
                    showShuffle = !showShuffleButton,
                    onShuffle = onShuffle,
                    dismissMenu = { menuOpen = false },
                    trailingMenuItems = trailingMenuItems,
                )
            }
        }
        if (showShuffleButton) {
            FilledTonalIconButton(
                onClick = {
                    haptics.performClick()
                    onShuffle()
                },
                shape = RoundedCornerShape(VerticalSplitShuffleHeight / 2),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier
                    .padding(top = SplitButtonDefaults.Spacing)
                    .width(VerticalSplitWidth)
                    .height(VerticalSplitShuffleHeight),
            ) {
                Icon(
                    imageVector = Icons.Filled.Shuffle,
                    contentDescription = "Shuffle play",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Width of every upright split segment (the capsule's 64 minus 6 + 6). */
val VerticalSplitWidth = 52.dp

/** The ▾ segment's height. */
val VerticalSplitMenuHeight = 46.dp

/** The optional Shuffle segment's height (a roomy capsule). */
val VerticalSplitShuffleHeight = 40.dp

private val VerticalSplitOuterRadius = 26.dp
private val VerticalSplitInnerRadius = 8.dp

/**
 * One page action that the bar may pull out of the ▾ menu into its own round
 * button when it has room (CenteredBar detail pose; Shuffle alone on a roomy
 * edge capsule). Where it stays in the menu it renders as a menu row.
 */
class BarExtraAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
)

/** Menu rows for the [BarExtraAction]s that stayed in the ▾ menu. */
@Composable
fun ColumnScope.BarExtraActionMenuItems(
    actions: List<BarExtraAction>,
    dismissMenu: () -> Unit,
) {
    actions.forEach { action ->
        YoinDropdownMenuItem(
            text = action.label,
            onClick = {
                dismissMenu()
                action.onClick()
            },
            leadingIcon = {
                Icon(
                    action.icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            },
            textStyle = PlayMenuTextStyle,
            contentPadding = PlayMenuItemPadding,
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(
    name = "Upright split · roomy",
    widthDp = 64,
    heightDp = 200,
    showBackground = true,
)
@Composable
private fun PlaySplitButtonVerticalPreview() {
    com.gpo.yoin.ui.theme.YoinTheme {
        PlaySplitButtonVertical(
            playContainer = MaterialTheme.colorScheme.primary,
            playContent = MaterialTheme.colorScheme.onPrimary,
            onPlay = {},
            onShuffle = {},
            playHeight = 94.dp,
            showShuffleButton = true,
            modifier = Modifier.padding(6.dp),
        )
    }
}
