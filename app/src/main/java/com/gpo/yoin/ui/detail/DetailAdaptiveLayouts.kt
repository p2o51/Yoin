package com.gpo.yoin.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.component.BarExtraAction
import com.gpo.yoin.ui.component.BarExtraActionMenuItems
import com.gpo.yoin.ui.component.PlaySplitButton
import com.gpo.yoin.ui.experience.LocalShellChromeInsets
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.rememberYoinHaptics

/*
 * Detail-page breakpoint helpers (断点交接 §5): the landscape band the page
 * leaves for the edge-split Button Group, and the hero actions a split-pane
 * detail shows instead of a bottom bar.
 */

/**
 * Landscape handsets: the page (header + body, never the full-bleed
 * background) starts past the 84dp capsule band and stops short of a right
 * cutout. A no-op everywhere else.
 */
@Composable
internal fun Modifier.detailChromeBand(): Modifier {
    if (!LocalYoinWindowInfo.current.isCompactHeight) return this
    val insets = LocalShellChromeInsets.current
    val direction = LocalLayoutDirection.current
    return padding(
        start = insets.calculateStartPadding(direction),
        end = insets.calculateEndPadding(direction),
    )
}

/**
 * What a detail pane shows in its hero when Activity Embedding holds it next
 * to the shell (TabletSplit): no bottom bar there — now playing lives once,
 * in the shell pane's bar — so [Share] [Play ▾] sit with the page identity.
 * Shuffle and the page's other actions stay in ▾.
 */
@Composable
internal fun DetailHeroActions(
    playContainer: Color,
    playContent: Color,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    menuActions: List<BarExtraAction> = emptyList(),
    menuItems: @Composable ColumnScope.(dismissMenu: () -> Unit) -> Unit = {},
) {
    val haptics = rememberYoinHaptics()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalIconButton(
            onClick = {
                haptics.performClick()
                onShare()
            },
            modifier = Modifier.size(DetailHeroActionHeight),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Icon(Icons.Rounded.IosShare, contentDescription = "Share", modifier = Modifier.size(22.dp))
        }
        PlaySplitButton(
            playContainer = playContainer,
            playContent = playContent,
            onPlay = onPlay,
            onShuffle = onShuffle,
            buttonHeight = DetailHeroActionHeight,
            fillPlay = true,
            compact = true,
            trailingMenuItems = { dismissMenu ->
                menuItems(dismissMenu)
                BarExtraActionMenuItems(actions = menuActions, dismissMenu = dismissMenu)
            },
            modifier = Modifier.width(DetailHeroSplitWidth),
        )
    }
}

private val DetailHeroActionHeight = 52.dp
private val DetailHeroSplitWidth = 184.dp

/** Default content padding for a landscape detail body. */
internal val DetailLandscapeBodyPadding = PaddingValues(start = 16.dp, end = 24.dp, top = 12.dp)
