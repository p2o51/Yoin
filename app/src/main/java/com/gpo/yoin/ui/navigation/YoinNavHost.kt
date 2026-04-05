package com.gpo.yoin.ui.navigation

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.ui.settings.SettingsScreen
import com.gpo.yoin.ui.settings.SettingsViewModel
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinTheme

@Composable
fun YoinNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = YoinRoute.Home,
        modifier = modifier,
    ) {
        // Home — crossfade (Effects Spring)
        composable<YoinRoute.Home>(
            enterTransition = {
                fadeIn(spring(stiffness = Spring.StiffnessLow))
            },
            exitTransition = {
                fadeOut(spring(stiffness = Spring.StiffnessLow))
            },
            popEnterTransition = {
                fadeIn(spring(stiffness = Spring.StiffnessLow))
            },
            popExitTransition = {
                fadeOut(spring(stiffness = Spring.StiffnessLow))
            },
        ) {
            HomeScreenPlaceholder()
        }

        // Library — crossfade (Effects Spring)
        composable<YoinRoute.Library>(
            enterTransition = {
                fadeIn(spring(stiffness = Spring.StiffnessLow))
            },
            exitTransition = {
                fadeOut(spring(stiffness = Spring.StiffnessLow))
            },
            popEnterTransition = {
                fadeIn(spring(stiffness = Spring.StiffnessLow))
            },
            popExitTransition = {
                fadeOut(spring(stiffness = Spring.StiffnessLow))
            },
        ) {
            LibraryScreenPlaceholder()
        }

        // Now Playing — overlay transition (Phase 10 will build out)
        composable<YoinRoute.NowPlaying>(
            enterTransition = { YoinMotion.navEnterOverlay },
            exitTransition = { YoinMotion.navExitOverlay },
            popEnterTransition = { YoinMotion.navEnterOverlay },
            popExitTransition = { YoinMotion.navExitOverlay },
        ) {
            NowPlayingScreenPlaceholder()
        }

        // Settings — forward sub-route
        composable<YoinRoute.Settings>(
            enterTransition = { YoinMotion.navEnterForward },
            exitTransition = { YoinMotion.navExitForward },
            popEnterTransition = { YoinMotion.navEnterBack },
            popExitTransition = { YoinMotion.navExitBack },
        ) {
            val app = LocalContext.current.applicationContext as YoinApplication
            val viewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModel.Factory(app.container),
            )
            SettingsScreen(viewModel = viewModel)
        }
    }
}

// ── Placeholder screens (will be replaced in later phases) ─────────────

@Composable
fun HomeScreenPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Home",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
fun LibraryScreenPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Library",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
fun NowPlayingScreenPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Now Playing",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

// ── Previews ────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun YoinNavHostPreview() {
    YoinTheme {
        YoinNavHost(navController = rememberNavController())
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun HomeScreenPlaceholderPreview() {
    YoinTheme {
        HomeScreenPlaceholder()
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun LibraryScreenPlaceholderPreview() {
    YoinTheme {
        LibraryScreenPlaceholder()
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun NowPlayingScreenPlaceholderPreview() {
    YoinTheme {
        NowPlayingScreenPlaceholder()
    }
}
