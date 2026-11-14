package com.gpo.yoin.ui.home

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import com.gpo.yoin.ui.component.horizontalEdgeFadeOnScroll
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.YoinWindowInfo
import com.gpo.yoin.ui.experience.windowChromeInfo

/**
 * Whether Home's horizontal shelves (Recently Played, Your Playlists, On Your
 * Other Accounts, Rediscover) fade at a scrolled edge.
 *
 * On a phone they don't (owner 2026-11-14: "右边这个不要淡入，直接漏到右边"):
 * the cards run full-bleed and the screen edge simply cuts them, the first card
 * still on the page margin. That is a phone window — Compact width, or a
 * landscape handset (height < 480). Wider windows keep the scroll-aware fade
 * for now; the owner wants a different answer there later ("大屏另想办法").
 *
 * Judged on the WINDOW ([windowChromeInfo]), not the column: a tablet's shell
 * column narrowed by a detail pane is still a large screen.
 */
internal fun homeShelvesFadeAtEdges(window: YoinWindowInfo): Boolean =
    window.layoutMode != LayoutMode.Compact && !window.isCompactHeight

/** [horizontalEdgeFadeOnScroll] where [homeShelvesFadeAtEdges] says so; nothing on a phone. */
@Composable
@ReadOnlyComposable
internal fun homeShelfEdgeFade(state: ScrollableState): Modifier =
    if (homeShelvesFadeAtEdges(windowChromeInfo)) {
        Modifier.horizontalEdgeFadeOnScroll(state)
    } else {
        Modifier
    }
