package com.gpo.yoin.ui.experience

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.captionBarPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.ui.Modifier

/**
 * What a page's top must clear: the status bar, and in a desktop window the
 * caption bar (the title bar with the app name and the window buttons).
 *
 * A desktop window has no status bar of its own, and its caption bar is drawn
 * over the top of the page, transparent ([com.gpo.yoin.DesktopCaptionSync]),
 * so the page's own surface runs up behind the window buttons. Everywhere
 * else the caption bar is zero and this is the status bar alone.
 */
val WindowInsets.Companion.topBars: WindowInsets
    @Composable
    @NonRestartableComposable
    get() = statusBars.union(captionBar)

/**
 * [Modifier.statusBarsPadding] that also clears a desktop window's caption bar
 * (see [topBars]). The caption padding only adds what the status bar padding
 * did not already cover.
 */
fun Modifier.topBarsPadding(): Modifier = statusBarsPadding().captionBarPadding()
