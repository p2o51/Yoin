package com.gpo.yoin.ui.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier

/*
 * One rule for every sheet that scrolls (owner 2026-11-06): what names the sheet (a track's number and
 * title, a menu's heading) stays put above the scroll, and the scroll's top edge is the tide line, the
 * same water as a page's top under fixed chrome. Content sinks under two waves instead of being cut
 * flat under the handle. At rest (nothing scrolled) there is no line at all.
 *
 * Always the tide, whatever the user picked for pages in Settings › Motion: the dots and the Cookie
 * wave need every piece of content marked, and a sheet's rows are plain text.
 */

/** Put on the sheet's scrolling container, after any fixed header and before `verticalScroll`. */
internal fun Modifier.sheetScrollEdge(state: ScrollState): Modifier =
    seamDissolveViewport(style = SeamTopStyle.Tide) { state.value.toFloat() }

/** Put on the sheet's LazyColumn. */
internal fun Modifier.sheetScrollEdge(state: LazyListState): Modifier =
    seamDissolveViewport(style = SeamTopStyle.Tide) { state.seamScrolledPx() }
