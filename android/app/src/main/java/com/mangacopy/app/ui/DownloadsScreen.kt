package com.mangacopy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.mangacopy.app.data.Db
import com.mangacopy.app.download.DownloadEngine
import com.mangacopy.app.download.Outputs

/**
 * 下載管理（用戶要求改造）：
 *   兩區 tab：未下載（佇列）／ 已下載
 *   每部漫畫：封面圖 + 名 + 已下/總章 + 當前章 + progress bar + ↑↓ 調佇列次序（清單模式）
 *   GRID / LIST 顯示切換（全站 ListMode 持久化，用戶要求「下載管理中要有封面、有 grid list 選擇」）
 *   一鍵清除所有下載（未下載 tab：全部移出書架，已下載檔案保留）
 *   單獨/批量/全選：取消（移出書架）／刪除檔案（CBZ 選項已按用戶指令刪除 — 默認全部 WebP）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(nav: NavHostController) {
    val db = remember { Db.get() }
    val state by DownloadEngine.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    var tab by remember { mutableStateOf(0) }            // 0=未下載 1=已下載
    var queue by remember { mutableStateOf(db.comicsNeedingWork()) }
    var doneList by remember { mutableStateOf(db.doneComics()) }
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmDel by remember { mutableStateOf<List<String>?>(null) }   // 待刪 slugs
    var confirmClearAll by remember { mutableStateOf(false) }            // 一鍵清除所有下載
    var confirmDeleteAll by remember { mutableStateOf(false) }           // 已下載：一鍵刪除全部
    // GRID/LIST：下載管理用返自己嘅 kv（用戶：「下載管理用 GRID…默認列表」）— 默認 GRID
    var grid by remember { mutableStateOf(db.getMeta("dl_view", "grid") != "list") }

    val refresh = {
        queue = db.comicsNeedingWork()
        doneList = db.doneComics()
    }
    LaunchedEffect(state.running, tab) { refresh() }
    LifecycleResumeEffect(Unit) { refresh(); onPauseOrDispose { } }

    val engineRow: (String) -> DownloadEngine.QueueRow? = { slug ->
        state.queue.firstOrNull { it.slug == slug }
    }

    Scaffold(
        containerColor = tabBackground("download"), // 淡綠：tab 背景區分（用戶要求）
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp)) {
                // 品牌標題欄：LOGO＋KIECOMIC 靠左。
                // 用戶（2026-11-17）：「開始下載改為 icon」「清除全部都改 icon」—
                // 頂欄按鈕群一律 icon 化（文字掣喺 425dp 窄屏會同品牌重疊，icon 徹底解決）
                Box(Modifier.fillMaxWidth()) {
                    BrandTitle()
                    Row(Modifier.align(Alignment.TopEnd), verticalAlignment = Alignment.CenterVertically) {
                        val tint = MaterialTheme.colorScheme.onSurfaceVariant
                        if (!selectMode) {
                            // 一鍵清除所有下載（用戶要求）— 全部「取消下載」（paused），書架保留
                            if (tab == 0 && queue.isNotEmpty()) {
                                IconButton(enabled = !state.running,
                                    onClick = { confirmClearAll = true }) {
                                    Icon(Icons.Filled.DeleteSweep, "清除全部（全部取消下載）", tint = tint)
                                }
                            }
                            // 已下載：一鍵刪除全部檔案（用戶要求）
                            if (tab == 1 && doneList.isNotEmpty()) {
                                IconButton(enabled = !state.running,
                                    onClick = { confirmDeleteAll = true }) {
                                    Icon(Icons.Filled.Delete, "刪除全部檔案", tint = tint)
                                }
                            }
                            IconButton(onClick = {
                                grid = !grid; db.setMeta("dl_view", if (grid) "grid" else "list")
                            }) {
                                Icon(
                                    if (grid) Icons.Filled.ViewList else Icons.Filled.GridView,
                                    if (grid) "轉列表顯示" else "轉格子顯示",
                                    tint = tint,
                                )
                            }
                        }
                        if (selectMode) {
                            IconButton(onClick = {
                                selected = (if (tab == 0) queue else doneList.map { it.pathWord }).toSet()
                            }) { Icon(Icons.Filled.SelectAll, "全選", tint = tint) }
                            IconButton(onClick = { selected = emptySet() }) {
                                Icon(Icons.Filled.Clear, "取消全選", tint = tint)
                            }
                            IconButton(onClick = { selectMode = false; selected = emptySet() }) {
                                Icon(Icons.Filled.Close, "離開選擇模式", tint = tint)
                            }
                        } else if (state.running) {
                            IconButton(onClick = { DownloadEngine.requestStop() }) {
                                Icon(Icons.Filled.Stop, "停止下載",
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            IconButton(
                                enabled = queue.isNotEmpty(),
                                onClick = { DownloadEngine.start() },   // 自動按 queue_order 取佇列
                            // 用戶（2026-11-17）：「開始下載」icon 化 — 冇文字冇部數，tab 已顯示幾多部
                            ) {
                                Icon(Icons.Filled.PlayArrow, "開始下載",
                                    tint = if (queue.isEmpty()) tint
                                           else MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    listOf("未下載（${queue.size}）", "已下載（${doneList.size}）")
                        .forEachIndexed { i, label ->
                            SegmentedButton(
                                selected = tab == i,
                                onClick = { tab = i; selectMode = false; selected = emptySet() },
                                shape = SegmentedButtonDefaults.itemShape(i, 2),
                            ) { Text(label, maxLines = 1) }
                        }
                }
                state.lastError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error, maxLines = 2)
                }
            }
        },
        bottomBar = {
            if (selectMode && selected.isNotEmpty()) Surface(tonalElevation = 3.dp) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("已揀 ${selected.size} 部", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (tab == 0) {
                            // 用戶：「取消未下載的不要移出書架」— 只做 paused，書架保留
                            OutlinedButton(onClick = {
                                selected.forEach { db.setPaused(it, true) }
                                selected = emptySet(); selectMode = false; refresh()
                            }) { Text("取消下載（書架保留）") }
                        } else {
                            OutlinedButton(onClick = {
                                selected.forEach { s ->
                                    DownloadEngine.start(listOf(s))
                                }
                                selected = emptySet(); selectMode = false
                            }) { Text("繼續下載") }
                        }
                        Button(
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error),
                            onClick = { confirmDel = selected.toList() },
                        ) { Text("刪除檔案") }
                    }
                }
            }
        },
    ) { pad ->
        val clickFor: (String) -> Unit = { s ->
            if (selectMode) selected = if (s in selected) selected - s else selected + s
            else nav.openDetail(s)
        }
        val longFor: (String) -> Unit = { s ->
            selectMode = true
            selected = if (s in selected) selected - s else selected + s
        }
        val queueClick = { slug: String -> clickFor(slug) }
        if (grid) {
            // GRID：封面卡（用戶：「下載管理中要包括有封面，有 GRID LIST 顯示方式選擇」）
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                contentPadding = PaddingValues(12.dp),
                modifier = Modifier.fillMaxSize().padding(pad),
            ) {
                if (tab == 0) {
                    gridItems(queue, key = { "q$it" }) { slug ->
                        val comic = remember(slug) { db.getComic(slug) }
                        val prog = remember(slug, state) { db.comicProgress(slug) }
                        val er = engineRow(slug)
                        DlCard(
                            name = comic?.displayName ?: slug, coverUrl = comic?.coverUrl,
                            chDone = prog.chDone, chTotal = prog.chTotal,
                            note = if (er?.active == true) er.curChapter else "",
                            active = er?.active == true,
                            selected = selectMode && slug in selected, selectMode = selectMode,
                            onClick = { queueClick(slug) }, onLongClick = { longFor(slug) },
                        )
                    }
                    if (queue.isEmpty()) item {
                        EmptyHint("佇列係空嘅 — 去「搜尋」撳漫畫入書架，或喺詳情頁撳「開始下載」")
                    }
                } else {
                    gridItems(doneList, key = { "d${it.pathWord}" }) { comic ->
                        val prog = remember(comic, state) { db.comicProgress(comic.pathWord) }
                        DlCard(
                            name = comic.displayName, coverUrl = comic.coverUrl,
                            chDone = prog.chDone, chTotal = prog.chTotal,
                            note = "", active = false,
                            selected = selectMode && comic.pathWord in selected, selectMode = selectMode,
                            onClick = { clickFor(comic.pathWord) }, onLongClick = { longFor(comic.pathWord) },
                        )
                    }
                    if (doneList.isEmpty()) item { EmptyHint("未有已下載嘅漫畫 — 去「未下載」tab 開始下載啦") }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                if (tab == 0) {
                    items(queue, key = { "q$it" }) { slug ->
                        val comic = remember(slug) { db.getComic(slug) }
                        val prog = remember(slug, state) { db.comicProgress(slug) }
                        val er = engineRow(slug)
                        QueueRowUi(
                            name = comic?.displayName ?: slug,
                            coverUrl = comic?.coverUrl,
                            chDone = prog.chDone, chTotal = prog.chTotal,
                            curChapter = er?.curChapter ?: "",
                            pageDone = er?.pageDone ?: 0, pageTotal = er?.pageTotal ?: 0,
                            active = er?.active == true,
                            selected = selectMode && slug in selected,
                            selectMode = selectMode,
                            onClick = { queueClick(slug) },
                            onLongClick = { longFor(slug) },
                            canMove = !state.running,
                            onUp = {
                                val i = queue.indexOf(slug)
                                if (i > 0) {
                                    val l = queue.toMutableList()
                                    l[i] = l[i - 1]; l[i - 1] = slug
                                    queue = l; db.requeue(l)
                                }
                            },
                            onDown = {
                                val i = queue.indexOf(slug)
                                if (i >= 0 && i < queue.size - 1) {
                                    val l = queue.toMutableList()
                                    l[i] = l[i + 1]; l[i + 1] = slug
                                    queue = l; db.requeue(l)
                                }
                            },
                        )
                    }
                    if (queue.isEmpty()) item {
                        EmptyHint("佇列係空嘅 — 去「搜尋」撳漫畫入書架，或喺詳情頁撳「開始下載」")
                    }
                } else {
                    items(doneList, key = { "d${it.pathWord}" }) { comic ->
                        val prog = remember(comic, state) { db.comicProgress(comic.pathWord) }
                        QueueRowUi(
                            name = comic.displayName,
                            coverUrl = comic.coverUrl,
                            chDone = prog.chDone, chTotal = prog.chTotal,
                            curChapter = "", pageDone = 0, pageTotal = 0, active = false,
                            selected = selectMode && comic.pathWord in selected,
                            selectMode = selectMode,
                            onClick = { clickFor(comic.pathWord) },
                            onLongClick = { longFor(comic.pathWord) },
                            canMove = false, onUp = {}, onDown = {},
                        )
                    }
                    if (doneList.isEmpty()) item { EmptyHint("未有已下載嘅漫畫 — 去「未下載」tab 開始下載啦") }
                }
            }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("刪除全部已下載檔案？") },
            text = { Text("會刪除 ${doneList.size} 部已下載漫畫嘅全部圖片檔案（書架記錄保留，會返返落「未下載」佇列）。確定？") },
            confirmButton = {
                TextButton(onClick = {
                    doneList.forEach { s ->
                        Outputs.deleteComicFiles(db, s.pathWord); db.resetComicDownloads(s.pathWord)
                    }
                    confirmDeleteAll = false; selected = emptySet(); selectMode = false
                    refresh()
                }) { Text("全部刪除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("取消") } },
        )
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("清除所有下載？") },
            text = { Text("會取消佇列內 ${queue.size} 部漫畫嘅下載（書架保留，之後喺詳情頁或書架長按「繼續／重新下載」就返返嚟）。確定？") },
            confirmButton = {
                TextButton(onClick = {
                    if (state.running) DownloadEngine.requestStop()
                    queue.forEach { db.setPaused(it, true) }
                    confirmClearAll = false; selected = emptySet(); selectMode = false
                    refresh()
                }) { Text("全部取消下載", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClearAll = false }) { Text("取消") } },
        )
    }

    confirmDel?.let { slugs ->
        AlertDialog(
            onDismissRequest = { confirmDel = null },
            title = { Text("刪除下載檔案？") },
            text = { Text("會刪除 $slugs.size 部漫畫已下載嘅全部圖片檔案（書架記錄保留，可重新下載）。確定？") },
            confirmButton = {
                TextButton(onClick = {
                    slugs.forEach { s ->
                        Outputs.deleteComicFiles(db, s); db.resetComicDownloads(s)
                    }
                    confirmDel = null; selected = emptySet(); selectMode = false
                    refresh()
                }) { Text("刪除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDel = null }) { Text("取消") } },
        )
    }
}

/** GRID 卡（用戶：「封面下方要有進度顯示」）：封面 → 進度條 + x/y 章 → 名（+ 下載中當前章）。 */
@Composable
private fun DlCard(
    name: String, coverUrl: String?, chDone: Int, chTotal: Int,
    note: String, active: Boolean,
    selected: Boolean, selectMode: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit,
) {
    Column(
        Modifier.padding(6.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.7f)) {
            DlCover(coverUrl, name)
            if (selectMode) Checkbox(
                checked = selected, onCheckedChange = { onClick() },
                modifier = Modifier.align(Alignment.TopEnd),
            )
            if (note.isNotEmpty()) Text(
                "▸ $note", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart)
                    .padding(start = 4.dp, bottom = 2.dp).widthIn(max = 120.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        // 封面下方進度（用戶明確要求）
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            LinearProgressIndicator(
                progress = { if (chTotal > 0) chDone.toFloat() / chTotal else 0f },
                modifier = Modifier.weight(1f).height(4.dp),
            )
            Spacer(Modifier.width(6.dp))
            // 用戶：「進度不用顯示『章』就簡潔 1/23」
            Text("$chDone/$chTotal", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(2.dp))
        Text(name, style = MaterialTheme.typography.bodySmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.fillMaxWidth())
    }
}

/** 封面縮圖（清單行 + 格子卡共用）：null/未載時留 surfaceVariant 底。 */
@Composable
private fun DlCover(coverUrl: String?, desc: String, size: Dp? = null) {
    val mod = if (size != null) Modifier.size(size) else Modifier.fillMaxSize()
    AsyncImage(
        model = coverUrl, contentDescription = desc,
        contentScale = ContentScale.Crop,
        modifier = mod.clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/** 一行式佇列列：封面 + 名 + 章數 + 當前章 + bar + ↑↓（用戶明確要求嘅顯示格式）。 */
@Composable
private fun QueueRowUi(
    name: String, coverUrl: String?, chDone: Int, chTotal: Int,
    curChapter: String, pageDone: Int, pageTotal: Int, active: Boolean,
    selected: Boolean, selectMode: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit,
    canMove: Boolean, onUp: () -> Unit, onDown: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = if (selectMode) 4.dp else 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectMode) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        }
        DlCover(coverUrl, name, size = 46.dp)   // 用戶：「下載管理中要包括有封面」
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$chDone/$chTotal",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (curChapter.isNotEmpty()) {
                    Text("　${if (active) "▸ " else ""}$curChapter",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                }
                if (pageTotal > 0 && active) {
                    Text(" $pageDone/$pageTotal 頁",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(4.dp))
            if (active && pageTotal > 0) {
                LinearProgressIndicator(
                    progress = { pageDone.toFloat() / pageTotal },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            } else {
                LinearProgressIndicator(
                    progress = { if (chTotal > 0) chDone.toFloat() / chTotal else 0f },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            }
        }
        if (!selectMode && canMove) {
            IconButton(onClick = onUp, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Filled.ArrowUpward, "排前啲", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDown, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Filled.ArrowDownward, "排後啲", modifier = Modifier.size(18.dp))
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}
