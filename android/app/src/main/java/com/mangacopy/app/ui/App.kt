package com.mangacopy.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

/**
 * App 外殼 — 手機/平板自適應導航（穩定 API 手寫版）：
 *   寬度 < 840dp（手機）      → 底部 NavigationBar
 *   寬度 ≥ 840dp（平板/橫屏） → 左側 NavigationRail
 */
private data class Dest(val route: String, val label: String, val icon: ImageVector)

private val DESTS = listOf(
    Dest("bookshelf", "書架", Icons.Filled.Bookmarks),
    Dest("browse", "發現", Icons.Filled.Explore),
    Dest("downloads", "下載", Icons.Filled.Download),
    Dest("settings", "設置", Icons.Filled.Settings),
)

/** 屏幕寬度（dp）— 各畫面用它做手機/平板適配。 */
val LocalScreenWidthDp = staticCompositionLocalOf { 360 }

@Composable
fun App() {
    val widthDp = LocalConfiguration.current.screenWidthDp
    val useRail = widthDp >= 840
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val navigate: (String) -> Unit = { route ->
        nav.navigate(route) {
            launchSingleTop = true
            // 頂層四頁之間切換時清返堆疊，避免書架→發現→書架…越堆越深
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            restoreState = true
        }
    }

    val navContent: @Composable () -> Unit = {
        NavHost(
            navController = nav,
            startDestination = "bookshelf",
            modifier = Modifier.fillMaxSize(),
        ) {
            composable("bookshelf") { BookshelfScreen(nav) }
            composable("browse") { BrowseScreen(nav) }
            composable("downloads") { DownloadsScreen() }
            composable("settings") { SettingsScreen() }
            composable("detail/{slug}") { entry ->
                DetailScreen(nav, entry.arguments?.getString("slug") ?: "")
            }
            composable("reader/{slug}/{cid}") { entry ->
                ReaderScreen(
                    slug = entry.arguments?.getString("slug") ?: "",
                    cid = entry.arguments?.getString("cid") ?: "",
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }

    CompositionLocalProvider(LocalScreenWidthDp provides widthDp) {
        if (useRail) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail {
                    DESTS.forEach { d ->
                        NavigationRailItem(
                            selected = currentRoute == d.route,
                            onClick = { navigate(d.route) },
                            icon = { Icon(d.icon, contentDescription = d.label) },
                            label = { Text(d.label) },
                        )
                    }
                }
                androidx.compose.material3.HorizontalDivider()
                navContent()
            }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        DESTS.forEach { d ->
                            NavigationBarItem(
                                selected = currentRoute == d.route,
                                onClick = { navigate(d.route) },
                                icon = { Icon(d.icon, contentDescription = d.label) },
                                label = { Text(d.label) },
                            )
                        }
                    }
                },
            ) { pad ->
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(pad)) {
                    navContent()
                }
            }
        }
    }
}

/** 跳去詳情頁。 */
fun NavHostController.openDetail(slug: String) = navigate("detail/$slug")
