package com.gpo.yoin.ui.experience

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

/**
 * The [YoinWindowInfo] a window of [widthDp] × [heightDp] reads — the same
 * pure rules as [rememberYoinWindowInfo], for previews and UI tests that pin
 * a breakpoint (`@Preview(widthDp, heightDp)` doesn't feed the adaptive info).
 */
fun previewYoinWindowInfo(widthDp: Int, heightDp: Int): YoinWindowInfo {
    val widthAtLeastMedium = widthDp >= 600
    val heightAtLeastMedium = heightDp >= 480
    val layoutMode = when {
        widthDp >= 840 -> LayoutMode.Wide
        widthAtLeastMedium -> LayoutMode.Medium
        else -> LayoutMode.Compact
    }
    val chromeForm = resolveShellChromeForm(
        isTabletop = false,
        widthAtLeastMedium = widthAtLeastMedium,
        heightAtLeastMedium = heightAtLeastMedium,
    )
    return YoinWindowInfo(
        layoutMode = layoutMode,
        isWidthAtLeastMedium = widthAtLeastMedium,
        isHeightAtLeastMedium = heightAtLeastMedium,
        hingeBounds = null,
        chromeForm = chromeForm,
        feedUnits = feedUnitsForContainer(widthDp.dp, layoutMode, chromeForm),
        feedCoverColumns = feedCoverColumnsForContainer(widthDp.dp, layoutMode, chromeForm),
    )
}

/** Wraps a preview in [previewYoinWindowInfo] (and the edge band for a short window). */
@Composable
fun ProvidePreviewWindow(widthDp: Int, heightDp: Int, content: @Composable () -> Unit) {
    val info = previewYoinWindowInfo(widthDp, heightDp)
    CompositionLocalProvider(
        LocalYoinWindowInfo provides info,
        LocalShellChromeInsets provides if (info.isCompactHeight) {
            PaddingValues(start = EdgeSplitContentStart)
        } else {
            PaddingValues(bottom = FloatingBarHeight + CenteredBarBottomMargin)
        },
        content = content,
    )
}
