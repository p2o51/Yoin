package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.only

/**
 * Keyboard lift for a bottom element inside a frame that already cleared the
 * navigation bar WITHOUT consuming it (`padding(systemBars.asPaddingValues())`).
 * The IME inset spans the nav bar, so a plain `imePadding()` there counts the
 * bar twice and leaves a nav-bar-tall gap above the keyboard. This is the
 * keyboard minus the bar the frame already gave: 0 with the keyboard down.
 */
fun imeAboveNavigationBarInsets(ime: WindowInsets, navigationBars: WindowInsets): WindowInsets =
    ime.exclude(navigationBars).only(WindowInsetsSides.Bottom)
