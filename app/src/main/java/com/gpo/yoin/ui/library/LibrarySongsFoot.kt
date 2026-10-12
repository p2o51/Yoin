@file:Suppress("ktlint:standard:function-naming") // Composables are PascalCase

package com.gpo.yoin.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.minimumTouchTarget
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.flow.first

private const val LIBRARY_SONGS_FOOT_KEY = "library-songs-foot"

/**
 * Songs' foot while more can follow ([LibrarySongsMore]): the loading
 * indicator, or a retry button once a read failed. Nothing at the end.
 */
internal fun LazyListScope.librarySongsFoot(more: LibrarySongsMore, onRetry: () -> Unit) {
    if (more == LibrarySongsMore.None) return
    item(key = LIBRARY_SONGS_FOOT_KEY, contentType = LIBRARY_SONGS_FOOT_KEY) {
        LibrarySongsFoot(
            failed = more == LibrarySongsMore.Failed,
            onRetry = onRetry,
            modifier = Modifier.animateItem(
                fadeInSpec = YoinMotion.effectsSpring(),
                placementSpec = YoinMotion.spatialSpring(),
                fadeOutSpec = YoinMotion.effectsSpring()
            )
        )
    }
}

/**
 * Asks for Songs' next page once the list is within about a screen of its
 * end: as many rows left below as it shows. Asks again after each page that
 * keeps it there, and never after a failed read (the foot's retry does).
 */
@Composable
internal fun LoadMoreSongsEffect(
    listState: LazyListState,
    more: LibrarySongsMore,
    songCount: Int,
    onLoadMore: () -> Unit
) {
    val loadMore by rememberUpdatedState(onLoadMore)
    if (more != LibrarySongsMore.Available) return
    LaunchedEffect(listState, songCount) {
        snapshotFlow { listState.isWithinAScreenOfEnd() }.first { it }
        loadMore()
    }
}

private fun LazyListState.isWithinAScreenOfEnd(): Boolean {
    val info = layoutInfo
    val shown = info.visibleItemsInfo
    val last = shown.lastOrNull() ?: return false
    return info.totalItemsCount - 1 - last.index <= shown.size
}

@Composable
private fun LibrarySongsFoot(failed: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberYoinHaptics()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = failed,
            transitionSpec = {
                YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                    YoinMotion.fadeOut(role = YoinMotionRole.Standard)
            },
            contentAlignment = Alignment.Center,
            label = "songsFoot"
        ) { showsRetry ->
            if (showsRetry) {
                IconButton(
                    onClick = {
                        haptics.performTick()
                        onRetry()
                    },
                    modifier = Modifier.minimumTouchTarget()
                ) {
                    Icon(
                        imageVector = YoinSymbols.Refresh,
                        contentDescription = stringResource(R.string.library_error_retry),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                YoinLoadingIndicator()
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LibrarySongsFootLoadingPreview() {
    YoinTheme {
        LibrarySongsFoot(failed = false, onRetry = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun LibrarySongsFootFailedPreview() {
    YoinTheme {
        LibrarySongsFoot(failed = true, onRetry = {})
    }
}
