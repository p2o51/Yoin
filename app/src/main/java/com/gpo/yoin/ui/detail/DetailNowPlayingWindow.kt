package com.gpo.yoin.ui.detail

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import com.gpo.yoin.ui.nowplaying.NowPlayingFrame
import com.gpo.yoin.ui.nowplaying.morphsFromPill

/**
 * The shared-transition scope a detail window's bar hands its pill to, so the
 * pill grows into Now Playing's cover, title and artist exactly as the shell's
 * does (and shrinks back on close). Null where this window's player does not
 * take the morph ([morphsFromPill]) and outside a detail window — a page in
 * the shell's detail column draws no bar of its own.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalDetailNowPlayingMorph = compositionLocalOf<SharedTransitionScope?> { null }

/**
 * A detail Activity's root: its page and the Now Playing it hosts in one
 * [SharedTransitionLayout], the shell's arrangement, so a pill tap on a
 * secondary page opens the player with the same pill → cover morph as from
 * Home. [content] gets the scope to hand to NowPlayingOverlayHost, which picks
 * the presentations that take the morph itself (as the shell's does); the
 * page's bar reads [LocalDetailNowPlayingMorph], gated on the live [frame].
 *
 * Always in the tree, in every presentation: wrapping the page only while the
 * morph applies would re-create it on a fold or resize, resetting its scroll
 * and re-registering its back callback above Now Playing's.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun DetailNowPlayingWindow(
    frame: NowPlayingFrame,
    content: @Composable BoxScope.(nowPlayingMorph: SharedTransitionScope) -> Unit,
) {
    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
        val scope = this
        CompositionLocalProvider(
            LocalDetailNowPlayingMorph provides scope.takeIf { frame.presentation.morphsFromPill },
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                content(scope)
            }
        }
    }
}
