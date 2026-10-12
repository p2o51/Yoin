@file:Suppress("ktlint:standard:function-naming") // Composables are PascalCase

package com.gpo.yoin.ui.library

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect

/**
 * The key of the first row a list last showed. Hoisted with the list's own
 * state, above the views' AnimatedContent, so a row that lands at the head
 * while another view is on is still seen as new when the list shows again.
 * Plain, not state: only [KeepListTopForANewHead] reads it.
 */
internal class LibraryListHead {
    var key: Any? = null
}

/**
 * A list resting at its very top stays there when a new row lands at its
 * head: Spotify's Songs files a new like first, from an album page, Now
 * Playing or the notification. A lazy list keeps its place on the key of its
 * first visible row, so it would move down with the old first row and leave
 * the new one just above the viewport, out of sight. The new row takes the
 * top instead, fading in while the others move down on their item springs.
 * Once the user has scrolled, the list keeps their place.
 */
@Composable
internal fun KeepListTopForANewHead(state: LazyListState, headKey: Any?, last: LibraryListHead) {
    // Composition's results are applied by now and the list is yet to
    // measure them: the request is for that measure, and the position the
    // list reports is still the one it rested at.
    SideEffect {
        val previous = last.key
        if (headKey == previous) return@SideEffect
        last.key = headKey
        val atTop = state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0
        if (previous != null && headKey != null && atTop) state.requestScrollToItem(0)
    }
}
