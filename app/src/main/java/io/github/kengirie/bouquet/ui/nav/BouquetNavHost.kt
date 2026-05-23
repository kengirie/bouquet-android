package io.github.kengirie.bouquet.ui.nav

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.kengirie.bouquet.R
import io.github.kengirie.bouquet.ui.home.HomeScreen
import io.github.kengirie.bouquet.ui.timeline.TimelineScreen

/**
 * Top-level navigation graph: a two-tab Material3 bottom-nav between the
 * existing input/resolve home screen and the nsite discovery timeline.
 *
 * NavController lives at the activity root and survives configuration
 * changes; per-tab state (e.g. timeline scroll position) survives via
 * `rememberSaveable` inside each destination.
 *
 * Re-selecting the active tab is a no-op: `launchSingleTop = true` plus
 * a `popUpTo(startDestination)` keeps the back stack a single entry
 * deep, matching the standard Material bottom-nav pattern.
 */
@Composable
fun BouquetNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                BouquetDestination.values().forEach { dest ->
                    val selected = backStackEntry?.destination
                        ?.hierarchy
                        ?.any { it.route == dest.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (currentRoute != dest.route) {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        // Using a Text glyph rather than the Material-Icons
                        // artifact keeps the APK lean (same rationale as
                        // HomeScreen's "→" / ">_" glyphs).
                        icon = {
                            Text(
                                text = dest.glyph,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        },
                        label = { Text(stringResource(dest.labelRes)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                                .copy(alpha = 0.3f),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        BouquetGraph(navController = navController, contentPadding = innerPadding)
    }
}

@Composable
private fun BouquetGraph(
    navController: androidx.navigation.NavHostController,
    contentPadding: PaddingValues,
) {
    NavHost(
        navController = navController,
        startDestination = BouquetDestination.Home.route,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable(BouquetDestination.Home.route) {
            HomeScreen(modifier = Modifier.padding(contentPadding))
        }
        composable(BouquetDestination.Timeline.route) {
            TimelineScreen(modifier = Modifier.padding(contentPadding))
        }
    }
}

private enum class BouquetDestination(
    val route: String,
    val labelRes: Int,
    val glyph: String,
) {
    Home(route = "home", labelRes = R.string.nav_home, glyph = "❀"),
    Timeline(route = "timeline", labelRes = R.string.nav_timeline, glyph = "≡"),
}
