package com.mangacopy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import androidx.compose.ui.text.style.TextAlign
import com.mangacopy.app.data.Db
import com.mangacopy.app.download.DownloadEngine
import com.mangacopy.app.download.ListUpdater
import com.mangacopy.app.download.Outputs
import kotlinx.coroutines.launch

/**
 * 書架 — 收藏管理（favorite=1 先入書架）+ 封面圖 + 下載進度徽章。
 * 長按彈功能選單：置頂 / 更新書單 / 重新下載 / 刪除檔案 / 移出書架。
 * 置頂（pinned）嘅漫畫排書架最前，封面左下角有 📌 徽章（用戶：「書架加一個置頂功能」）。
 * 顶部「全部更新書單」= 對所有收藏做 檢查更新（重新渲染詳情頁 → 新增章節自動入隊下載）。
 */
@Composable
fun BookshelfScreen(nav: NavHostController) {
    val db = remember { Db.get() }
    var comics by remember { mutableStateOf(db.listComics(favoriteOnly = true)) }
    var menuFor by remember { mutableStateOf<Db.Comic?>(null) }
    var confirmRemove by remember { mutableStateOf<Db.Comic?>(null) }
    var confirmDelete by remember { mutableStateOf<Db.Comic?>(null) }
    val engineState by DownloadEngine.state.collectAsState()
    val listState by ListUpdater.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    // 書架排序（用戶要求）：最近更新（默認）／書名／下載進度；置頂者永遠排最前。kv 持久。
    var sortKey by remember { mutableStateOf(db.getMeta("shelf_sort") ?: "updated") }
    var sortMenu by remember { mutableStateOf(false) }
    val shownComics = remember(comics, sortKey) { sortShelf(db, comics, sortKey) }

    // 由詳情頁/reader 返回時自動重新載入
    LifecycleResumeEffect(Unit) {
        comics = db.listComics(favoriteOnly = true)
        onPauseOrDispose { }
    }

    Scaffold(
        containerColor = tabBackground("shelf"),    // 暖褐：tab 背景區分（用戶要求）
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.padding(top = 8.dp, start = 16.dp, end = 8.dp)) {
                // 品牌標題欄：LOGO＋KIECOMIC 靠左、「書架」置中显眼、按鈕靠右
                Box(Modifier.fillMaxWidth()) {
                    BrandTitle()
                    // 按鈕對齊第 1 行品牌（BrandTitle 已分兩行）
                    Row(Modifier.align(Alignment.TopEnd), verticalAlignment = Alignment.CenterVertically) {
                        // 排序選單（用戶：「書架要有排序功能」）
                        Box {
                            IconButton(onClick = { sortMenu = true }) {
                                Icon(Icons.Filled.Sort, "排序（書架）",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                listOf(
                                    "updated" to "最近更新",
                                    "name" to "書名",
                                    "progress" to "下載進度",
                                ).forEach { (k, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        trailingIcon = { if (sortKey == k) Icon(Icons.Filled.Check, null) },
                                        onClick = {
                                            sortKey = k; sortMenu = false
                                            db.setMeta("shelf_sort", k)
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { nav.navigate("history") }) {
                            Icon(Icons.Filled.History, "瀏覽紀錄",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // 用戶（2026-11-17）：「右上更新書單改為一個簡單刷新 icon」—
                        // 文字掣變 Refresh icon；更新中轉圈仔當狀態提示
                        IconButton(
                            enabled = !listState.running && comics.isNotEmpty(),
                            onClick = { ListUpdater.start(db.favoritePathWords(), force = true) },
                        ) {
                            if (listState.running)
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Filled.Refresh, "全部更新書單",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                // 用戶（2026-11-17）：「書架上方唔使有列『收藏 6 部』」—
                // 呢行只喺有動態先出現（清單更新中／下載中／檢查有新章），閑置時完全唔佔位
                val subtitle: String? = when {
                    listState.running ->
                        "清單更新中 ${listState.done}/${listState.total}　·　${listState.current}" +
                            if (listState.newChapters > 0) "　·　新增 ${listState.newChapters} 章" else ""
                    engineState.running -> {
                        val act = engineState.queue.firstOrNull { it.active }
                        "下載中：${act?.name ?: "排緊隊"}"
                    }
                    listState.lastFinishAt > 0 && listState.newChapters > 0 ->
                        "最近檢查新增 ${listState.newChapters} 章（睇「+新章」徽章）"
                    else -> null
                }
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (listState.running) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (listState.running) {
                    LinearProgressIndicator(
                        progress = { if (listState.total > 0) listState.done.toFloat() / listState.total else 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        },
    ) { pad ->
        if (comics.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(pad),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("書架係空空嘅", style = MaterialTheme.typography.titleMedium)
                Text("去「搜尋」撳漫畫加入書架啦", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { nav.navigate("browse") }) { Text("去搜尋") }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                contentPadding = PaddingValues(12.dp),
                modifier = Modifier.padding(pad),
            ) {
                items(shownComics, key = { it.pathWord }) { comic ->
                    val prog = remember(comic, engineState) { db.comicProgress(comic.pathWord) }
                    // 「+N 新章」只計更新書單 diff 標記且仍未下載嘅章（用戶要求）
                    val newCount = remember(comic, engineState) { db.newChapterPending(comic.pathWord) }
                    ShelfCard(
                        comic = comic,
                        prog = prog,
                        newCount = newCount,
                        onClick = { nav.openDetail(comic.pathWord) },
                        onLongClick = { menuFor = comic },
                    )
                }
            }
        }
    }

    // 長按選單
    menuFor?.let { comic ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(comic.displayName) },
            text = {
                Column {
                    MenuRow("📖 打開 / 查看章節") { menuFor = null; nav.openDetail(comic.pathWord) }
                    MenuRow(if (comic.pinned) "📌 解除置頂" else "📌 置頂（排書架最前）") {
                        menuFor = null
                        db.setPinned(comic.pathWord, !comic.pinned)
                        comics = db.listComics(favoriteOnly = true)
                    }
                    MenuRow("🔄 更新書單（檢查新章節）") {
                        menuFor = null
                        ListUpdater.start(listOf(comic.pathWord), force = true)
                    }
                    MenuRow("⬇️ 繼續／重新下載") {
                        menuFor = null
                        DownloadEngine.start(listOf(comic.pathWord))
                        nav.navigate("downloads") { launchSingleTop = true }
                    }
                    MenuRow("🗑️ 刪除已下載檔案") { menuFor = null; confirmDelete = comic }
                    MenuRow("⭐ 移出書架（唔刪檔）") {
                        menuFor = null
                        db.removeFromShelf(comic.pathWord)
                        comics = db.listComics(favoriteOnly = true)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { menuFor = null }) { Text("關掉") } },
        )
    }

    confirmDelete?.let { comic ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("刪除檔案？") },
            text = { Text("會刪除「${comic.displayName}」已下載嘅全部圖片/CBZ 檔案，書架記錄保留。確定？") },
            confirmButton = {
                TextButton(onClick = {
                    Outputs.deleteComicFiles(db, comic.pathWord)
                    db.resetComicDownloads(comic.pathWord)
                    comics = db.listComics(favoriteOnly = true)
                    confirmDelete = null
                }) { Text("刪除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ColumnScope.MenuRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp),
    )
}

@Composable
private fun ShelfCard(
    comic: Db.Comic,
    prog: Db.ComicProgress,
    newCount: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        Modifier.padding(6.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.7f)) {
            AsyncImage(
                model = comic.coverUrl,
                contentDescription = comic.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            if (prog.chTotal > 0 && prog.chDone == prog.chTotal) {
                Icon(
                    Icons.Filled.CheckCircle, "已完成",
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp),
                )
            } else if (newCount > 0) {
                // 用戶（2026-11-17）：「有新章節喺右下角顯示 NEW」—
                // 「+N 新章」文字縮做 NEW 徽章；仍然只計更新書單 diff 且未落盤嘅章
                Surface(
                    color = MaterialTheme.colorScheme.tertiary,
                    shape = RoundedCornerShape(topStart = 8.dp),
                    modifier = Modifier.align(Alignment.BottomEnd),
                ) {
                    Text(
                        "NEW",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onTertiary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (comic.pinned) {
                // 置頂星（用戶：「置頂星星大 1.5 倍」— 11sp→17sp）；放左下不變
                Text(
                    "⭐",
                    fontSize = 17.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(3.dp),
                )
            }
            // 左上進度藥丸已刪（用戶 2026-11-17：「封面左上角唔使顯示下載咗幾多」—
            // 進度詳情喺下載頁/詳情頁都有，書架保持乾淨）
        }
        Spacer(Modifier.height(4.dp))
        Text(
            comic.displayName,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,      // 用戶要求：封面下漫畫名水平置中
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 書架排序（用戶：「書架要有排序功能」）— 置頂者永遠最前，其餘按所選鍵排：
 *   updated（默認）＝ DB 原序（pinned DESC, updated_at DESC）
 *   name            ＝ 書名（中文 Collator）
 *   progress        ＝ 下載進度百分比（高→低）
 */
private fun sortShelf(db: Db, list: List<Db.Comic>, sortKey: String): List<Db.Comic> {
    if (sortKey == "updated") return list
    val pinned = list.filter { it.pinned }
    val rest = list.filterNot { it.pinned }
    val sorted = when (sortKey) {
        "name" -> {
            val coll = java.text.Collator.getInstance(java.util.Locale.CHINA)
            rest.sortedWith(compareBy(coll) { it.displayName })
        }
        "progress" -> {
            val pct = rest.associateWith { c ->
                val p = db.comicProgress(c.pathWord)
                if (p.chTotal > 0) p.chDone.toFloat() / p.chTotal else 0f
            }
            rest.sortedByDescending { pct[it] }
        }
        else -> rest
    }
    return pinned + sorted
}
