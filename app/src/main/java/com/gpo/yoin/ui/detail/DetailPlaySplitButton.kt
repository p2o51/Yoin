package com.gpo.yoin.ui.detail

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.component.YoinDropdownMenu
import com.gpo.yoin.ui.component.YoinDropdownMenuItem

internal val DetailToolbarButtonHeight = 60.dp

private val DetailPlayMenuTextStyle
    @Composable get() = MaterialTheme.typography.titleMedium

private val DetailPlayMenuItemPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)

/**
 * Shared Play + ▾ split button for detail pages. "Shuffle play" is always present;
 * page-specific items (Go to artist, Open in Spotify, Add to playlist, …) slot
 * in via [trailingMenuItems].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DetailPlaySplitButton(
    playContainer: Color,
    playContent: Color,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    trailingMenuItems: @Composable ColumnScope.(dismissMenu: () -> Unit) -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    val dismissMenu = { menuOpen = false }
    val buttonColors = ButtonDefaults.buttonColors(
        containerColor = playContainer,
        contentColor = playContent,
    )
    SplitButtonLayout(
        modifier = modifier,
        leadingButton = {
            SplitButtonDefaults.LeadingButton(
                onClick = onPlay,
                colors = buttonColors,
                modifier = Modifier.height(DetailToolbarButtonHeight),
                contentPadding = PaddingValues(horizontal = 26.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize),
                )
                Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                Text("Play", style = MaterialTheme.typography.titleMedium)
            }
        },
        trailingButton = {
            SplitButtonDefaults.TrailingButton(
                checked = menuOpen,
                onCheckedChange = { menuOpen = it },
                colors = buttonColors,
                modifier = Modifier.height(DetailToolbarButtonHeight),
                contentPadding = PaddingValues(horizontal = 20.dp),
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
                        textStyle = DetailPlayMenuTextStyle,
                        contentPadding = DetailPlayMenuItemPadding,
                    )
                    trailingMenuItems(dismissMenu)
                }
            }
        },
    )
}
