package com.mangacopy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mangacopy.app.data.AppConfig
import com.mangacopy.app.data.Db
import com.mangacopy.app.data.UpdateChecker
import com.mangacopy.app.download.DownloadEngine
import com.mangacopy.app.download.LibraryCleaner
import com.mangacopy.app.download.ListUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * App 外殼 — 手機/平板自適應導航（穩定 API 手寫版）：
 *   寬度 < 840dp（手機）      → 底部 NavigationBar
 *   寬度 ≥ 840dp（平板/橫屏） → 左側 NavigationRail
 */
private data class Dest(val route: String, val label: String, val icon: ImageVector)

private val DESTS = listOf(
    Dest("bookshelf", "書架", Icons.Filled.Bookmarks),
    Dest("browse", "搜尋", Icons.Filled.Search),
    Dest("downloads", "下載", Icons.Filled.Download),
    Dest("settings", "設置", Icons.Filled.Settings),
)

/** 漫畫列表顯示模式（全站默認 GRID，可切 LIST；kv 持久化）。 */
object ListMode {
    const val KEY = "list_mode"
    fun isGrid(db: Db) = db.getMeta(KEY, "grid") != "list"
    fun set(db: Db, grid: Boolean) = db.setMeta(KEY, if (grid) "grid" else "list")
}

/** 屏幕寬度（dp）— 各畫面用它做手機/平板適配。 */
val LocalScreenWidthDp = staticCompositionLocalOf { 360 }

@Composable
fun App() {
    val widthDp = LocalConfiguration.current.screenWidthDp
    val useRail = widthDp >= 840
    val nav = rememberNavController()

    // 用戶要求：每次打開 App 背景自動保養 —
    // 啟動 3 秒後（等 UI 先渲染好）：
    // ① 清理「已落圖但冇放書架」嘅內容（用戶 2026-11-18：閱讀過唔算收藏）
    // ② 再跑 ListUpdater 檢查書架新章節（只渲染詳情頁、唔會開落圖片）
    LaunchedEffect(Unit) {
        delay(3_000)
        val db = Db.get()
        withContext(Dispatchers.IO) {
            runCatching { LibraryCleaner.run(db) }
        }
        val favs = db.favoritePathWords()
        if (favs.isNotEmpty() && !DownloadEngine.isRunning && !ListUpdater.isRunning) {
            ListUpdater.start(favs)
        }
        UpdateChecker.onAppStart()   // GitHub 檢查更新（12 小時節流；有新版先彈一次）
    }
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val navigate: (String) -> Unit = { route ->
        // 用戶要求：撳下方任一 tab（書架／搜尋／下載／設置）都要落返嗰個 tab 嘅「根板」清單，
        // 唔准逗留喺詳情頁／章節清單／閱讀器。
        // 旧版用官方 bottom-nav 模板嘅 saveState/restoreState — 但 detail/reader/history 係
        // push 喺同一個 NavHost、掛喺書架之上嘅兄弟路由：離開時 popUpTo(bookshelf){saveState=true}
        // 會將連同 detail 嘅整條堆疊 snapshot 存低，再撳返書架就 restoreState 連 detail 一齊还原
        // → 人就「逗留喺章節」、返唔到根清單（實測踩中）。
        // 正解：tab 切換唔 save/restore state，只用純 popUpTo + launchSingleTop ——
        // 每次切換都係乾淨地回到目標 tab 根清單；launchSingleTop 同時避免書架→搜尋→書架…越堆越深。
        // 代價：切 tab 唔保留各清單嘅捲軸位置（「必落根板」優先於捲軸記憶）。
        nav.navigate(route) {
            launchSingleTop = true
            popUpTo(nav.graph.findStartDestination().id)
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
            composable("downloads") { DownloadsScreen(nav) }
            composable("settings") { SettingsScreen() }
            composable("history") { HistoryScreen(nav) }
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

    // 閱讀器 = 全屏沉浸式：隱底欄/側欄（用戶要求無感睇漫）
    val inReader = currentRoute?.startsWith("reader") == true

    val context = LocalContext.current
    val update by UpdateChecker.state.collectAsState()

    Box(Modifier.fillMaxSize()) {
    CompositionLocalProvider(LocalScreenWidthDp provides widthDp) {
        if (inReader) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) { navContent() }
        } else if (useRail) {
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
                // 【橫屏白屏元兇】Material3 HorizontalDivider = fillMaxWidth 子節點，
                // 放喺橫向 Row 會食晒所有剩餘寬 → NavHost 分到 0 寬 → 內容全空白
                // （2026-11-17 用戶：「橫屏會白屏」）。正解：手寫豎線＋weight 保障內容寬度。
                Box(
                    Modifier.fillMaxHeight().width(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                Box(Modifier.weight(1f).fillMaxHeight()) { navContent() }
            }
        } else {
            Scaffold(
                bottomBar = {
                    // 用戶迭代終態：80→52→44→40（太矮顯示唔全）→56（再高少少）→ **60dp 定稿**（用戶指定）
                    NavigationBar(Modifier.height(60.dp)) {
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

    // GitHub 新版本 dialog（用戶 2026-11-18：「有更新要在打開APP時提醒一次」）—
    // acknowledge() 記低已確認版本，同一版號只彈一次。
    // v0.3.6 起「直接下載安裝」（用戶：「更新不想跳轉，想它直接下載APK自動打開」）：
    // App 內 OkHttp 落 APK → 完成自動開系統安裝界面；欠授權走 NeedPerm 授權流程。
    val dl by UpdateChecker.download.collectAsState()
    update?.let { u ->
        val running = dl is UpdateChecker.Download.Running
        AlertDialog(
            onDismissRequest = { if (!running) UpdateChecker.acknowledge() },
            title = { Text("發現新版本 v${u.version}") },
            text = {
                Text(when (val d = dl) {
                    UpdateChecker.Download.Idle ->
                        "當前 v${AppConfig.VERSION_NAME} → 最新 v${u.version}。\n" +
                        "撳「直接下載安裝」即刻喺 App 內下載新 APK，完成後自動開啟安裝界面。"
                    is UpdateChecker.Download.Running ->
                        if (d.total > 0)
                            "下載中 ${"%.1f".format(d.read / 1e6)} / ${"%.1f".format(d.total / 1e6)} MB" +
                                "（${(d.read * 100 / d.total)}%）"
                        else "下載中 ${"%.1f".format(d.read / 1e6)} MB…"
                    is UpdateChecker.Download.Ready ->
                        "下載完成 ✅ 系統安裝界面已開啟 — 撳「安裝」完成升級，App 會自動重啟。"
                    is UpdateChecker.Download.NeedPerm ->
                        "APK 已下載完成。安裝需要授權 KIECOMIC「安裝未知應用程式」— " +
                        "撳「去授權」允許後，返嚟再撳一次即裝。"
                    is UpdateChecker.Download.Fail ->
                        "下載失敗：${d.msg}\n撳「重試下載」再試一次。"
                })
            },
            confirmButton = {
                TextButton(
                    enabled = !running,
                    onClick = {
                        when (dl) {
                            is UpdateChecker.Download.NeedPerm,
                            is UpdateChecker.Download.Ready -> UpdateChecker.retryInstall(context)
                            else -> UpdateChecker.startDownload(context)
                        }
                    },
                ) { Text(when (dl) {
                    is UpdateChecker.Download.Running -> "下載中…"
                    is UpdateChecker.Download.NeedPerm -> "去授權"
                    is UpdateChecker.Download.Ready -> "立即安裝"
                    is UpdateChecker.Download.Fail -> "重試下載"
                    else -> "直接下載安裝"
                }) }
            },
            dismissButton = {
                TextButton(enabled = !running,
                    onClick = { UpdateChecker.acknowledge() }) { Text("知道") }
            },
        )
    }
    }   // Box（content + dialog overlay）
}

/** 跳去詳情頁。 */
fun NavHostController.openDetail(slug: String) = navigate("detail/$slug")
