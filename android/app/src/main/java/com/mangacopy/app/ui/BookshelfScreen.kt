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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
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
 * 長按彈功能選單：更新書單 / 重新下載 / 刪除檔案 / 移出書架。
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
    val scope = rememberCoroutineScope()

    // 由詳情頁/reader 返回時自動重新載入
    LifecycleResumeEffect(Unit) {
        comics = db.listComics(favoriteOnly = true)
        onPauseOrDispose { }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.padding(top = 8.dp, start = 16.dp, end = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("書架", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { nav.navigate("history") }) {
                        Icon(Icons.Filled.History, "瀏覽紀錄",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(
                        enabled = !listState.running && comics.isNotEmpty(),
                        onClick = { ListUpdater.start(db.favoritePathWords(), force = true) },
                    ) { Text(if (listState.running) "更新中…" else "全部更新書單") }
                }
                Text(
                    when {
                        listState.running ->
                            "清單更新中 ${listState.done}/${listState.total}　·　${listState.current}" +
                                if (listState.newChapters > 0) "　·　新增 ${listState.newChapters} 章" else ""
                        engineState.running -> {
                            val act = engineState.queue.firstOrNull { it.active }
                            "下載中：${act?.name ?: "排緊隊"}"
                        }
                        listState.lastFinishAt > 0 && listState.newChapters > 0 ->
                            "收藏 ${comics.size} 部　·　最近檢查新增 ${listState.newChapters} 章（睇「+新章」徽章）"
                        else -> "收藏 ${comics.size} 部"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (listState.running) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
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
                items(comics, key = { it.pathWord }) { comic ->
                    val prog = remember(comic, engineState) { db.comicProgress(comic.pathWord) }
                    ShelfCard(
                        comic = comic,
                        prog = prog,
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
                    MenuRow("🔄 更新書單（檢查新章節）") {
                        menuFor = null
                        ListUpdater.start(listOf(comic.pathWord), force = true)
                    }
                    MenuRow("⬇️ 繼續／重新下載") {
                        menuFor = null
                        DownloadEngine.start(listOf(comic.pathWord))
                        nav.navigate("downloads") { launchSingleTop = true }
                    }
                    MenuRow("📦 只刪除 CBZ（保留圖片）") {
                        menuFor = null
                        val n = Outputs.deleteCbzFiles(db, comic.pathWord)
                        scope.launch { snackbar.showSnackbar("已刪除 $n 個 CBZ") }
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
            val pendingNew = prog.chTotal - prog.chDone - prog.chErr
            if (prog.chTotal > 0 && prog.chDone == prog.chTotal) {
                Icon(
                    Icons.Filled.CheckCircle, "已完成",
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp),
                )
            } else if (prog.chDone > 0 && pendingNew > 0) {
                // 清單檢查發現新章節（未下过）→ 明確顯示「+N 新章」（用戶要求）
                Surface(
                    color = MaterialTheme.colorScheme.tertiary,
                    shape = RoundedCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Text(
                        "+$pendingNew 新章",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiary,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
            if (prog.chTotal > 0 && prog.chDone < prog.chTotal) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(bottomEnd = 8.dp),
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    Text(
                        "${prog.chDone}/${prog.chTotal}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
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
