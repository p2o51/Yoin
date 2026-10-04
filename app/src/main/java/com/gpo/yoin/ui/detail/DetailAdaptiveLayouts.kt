package com.gpo.yoin.ui.detail

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.LocalShellChromeInsets
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo

/*
 * Detail-page breakpoint helpers (断点交接 §5): the landscape band the page
 * leaves for the edge-split Button Group.
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

/** Default content padding for a landscape detail body. */
internal val DetailLandscapeBodyPadding = PaddingValues(start = 16.dp, end = 24.dp, top = 12.dp)
