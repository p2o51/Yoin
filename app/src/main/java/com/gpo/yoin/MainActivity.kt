package com.gpo.yoin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gpo.yoin.ui.component.YoinButtonGroup
import com.gpo.yoin.ui.navigation.YoinNavHost
import com.gpo.yoin.ui.navigation.YoinRoute
import com.gpo.yoin.ui.navigation.YoinSection
import com.gpo.yoin.ui.theme.YoinTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            YoinTheme {
                YoinApp()
            }
        }
    }
}

@Composable
fun YoinApp(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()

    val currentRoute = navBackStackEntry?.destination?.route.orEmpty()
    val selectedSection = when {
        currentRoute.contains(YoinRoute.Library::class.qualifiedName.orEmpty()) ->
            YoinSection.LIBRARY
        else -> YoinSection.HOME
    }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            YoinButtonGroup(
                selectedSection = selectedSection,
                currentTrackTitle = null,
                currentTrackArtist = null,
                onHomeClick = {
                    navController.navigate(YoinRoute.Home) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onNowPlayingClick = {
                    navController.navigate(YoinRoute.NowPlaying) {
                        launchSingleTop = true
                    }
                },
                onLibraryClick = {
                    navController.navigate(YoinRoute.Library) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        },
    ) { paddingValues ->
        YoinNavHost(
            navController = navController,
            modifier = Modifier.padding(paddingValues),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun YoinAppPreview() {
    YoinTheme {
        YoinApp()
    }
}

