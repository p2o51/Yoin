package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
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
                trailingMenuItems(dismissMenu)
            }
        }
    }
}
