package com.gpo.yoin.ui.component

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SearchBarColors
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.gpo.yoin.ui.detail.findActivityOrNull

/**
 * Wrap an M3 `ExpandedFullScreenSearchBar` in this. The expanded search is a dialog window of its
 * own, and M3 picks that window's bar icons from [LocalContentColor] where the search bar is
 * called — not from the surface it draws. Outside a Surface that is the default black, so the
 * icons came out dark on the dark search surface in dark theme. This provides the surface's own
 * content colour, so the icons match what is drawn behind them and follow a light / dark switch.
 */
@Composable
fun ProvideFullScreenSearchBarIcons(
    colors: SearchBarColors = SearchBarDefaults.colors(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalContentColor provides contentColorFor(colors.containerColor),
        content = content,
    )
}

/**
 * Sets the window's bar icons for a page that draws its own fixed background behind the bars
 * (light icons = `false`), and gives the theme's own icons back when the page leaves. Follows a
 * light / dark switch while the page shows.
 */
@Composable
fun SystemBarIconsOverride(lightStatusBars: Boolean, lightNavigationBars: Boolean) {
    val view = LocalView.current
    val window = view.context.findActivityOrNull()?.window ?: return
    val darkTheme = isSystemInDarkTheme()
    DisposableEffect(window, view, darkTheme, lightStatusBars, lightNavigationBars) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = lightStatusBars
        controller.isAppearanceLightNavigationBars = lightNavigationBars
        onDispose {
            // The theme's icons, as enableYoinEdgeToEdge sets them: dark in light theme.
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
}
