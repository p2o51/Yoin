package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Keyboard lift for a bottom element inside a frame that already cleared the
 * navigation bar WITHOUT consuming it (`padding(systemBars.asPaddingValues())`).
 * The IME inset spans the nav bar, so a plain `imePadding()` there counts the
 * bar twice and leaves a nav-bar-tall gap above the keyboard. This is the
 * keyboard minus the bar the frame already gave: 0 with the keyboard down.
 */
fun imeAboveNavigationBarInsets(ime: WindowInsets, navigationBars: WindowInsets): WindowInsets =
    ime.exclude(navigationBars).only(WindowInsetsSides.Bottom)

/**
 * For a form field in a scrolling page that pads for the keyboard: while the field (or anything in it) has
 * focus, keep the whole field — label and box, not only the text line a text field brings into view on its
 * own — in view on every keyboard frame, as the album review field does with its caret. Without it a field
 * low on a short window (a tablet in landscape) ends up partly under the keyboard.
 */
internal fun Modifier.keepInViewAboveKeyboard(): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        snapshotFlow { ime.getBottom(density) }
            .distinctUntilChanged()
            .collect { requester.bringIntoView() }
    }
    this
        .bringIntoViewRequester(requester)
        .onFocusChanged { focused = it.hasFocus }
}
