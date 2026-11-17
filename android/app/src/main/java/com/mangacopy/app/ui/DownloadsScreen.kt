package com.mangacopy.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavHostController
import com.mangacopy.app.data.Db
import com.mangacopy.app.download.DownloadEngine
import com.mangacopy.app.download.Outputs

/**
 * 下載管理（用戶要求改造）：
 *   兩區 tab：未下載（佇列）／ 已下載
 *   日誌流廢除 → 每部漫畫一行：名 + 已下/總章 + 當前章 + progress bar + ↑↓ 調佇列次序
 *   單獨/批量/全選：取消（移出書架）／刪除檔案／只刪 CBZ
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
    var delCbzOnly by remember { mutableStateOf(false) }

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
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("下載管理", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    if (selectMode) {
                        TextButton(onClick = {
                            selected = (if (tab == 0) queue else doneList.map { it.pathWord }).toSet()
                        }) { Text("全選") }
                        TextButton(onClick = { selected = emptySet() }) { Text("取消全選") }
                        TextButton(onClick = { selectMode = false; selected = emptySet() }) { Text("離開") }
                    } else if (state.running) {
                        Button(
                            onClick = { DownloadEngine.requestStop() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error),
                        ) { Text("停止") }
                    } else {
                        Button(
                            enabled = queue.isNotEmpty(),
                            onClick = { DownloadEngine.start() },   // 自動按 queue_order 取佇列
                        ) { Text("開始下載（${queue.size} 部）") }
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
                            OutlinedButton(onClick = {
                                selected.forEach { db.removeFromShelf(it) }
                                selected = emptySet(); selectMode = false; refresh()
                            }) { Text("取消（移出書架）") }
                        } else {
                            OutlinedButton(onClick = {
                                selected.forEach { s ->
                                    DownloadEngine.start(listOf(s))
                                }
                                selected = emptySet(); selectMode = false
                            }) { Text("繼續下載") }
                            OutlinedButton(onClick = {
                                delCbzOnly = true; confirmDel = selected.toList()
                            }) { Text("只刪CBZ") }
                        }
                        Button(
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error),
                            onClick = { delCbzOnly = false; confirmDel = selected.toList() },
                        ) { Text("刪除檔案") }
                    }
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            if (tab == 0) {
                items(queue, key = { "q$it" }) { slug ->
                    val comic = remember(slug) { db.getComic(slug) }
                    val prog = remember(slug, state) { db.comicProgress(slug) }
                    val er = engineRow(slug)
                    QueueRowUi(
                        name = comic?.displayName ?: slug,
                        chDone = prog.chDone, chTotal = prog.chTotal,
                        curChapter = er?.curChapter ?: "",
                        pageDone = er?.pageDone ?: 0, pageTotal = er?.pageTotal ?: 0,
                        active = er?.active == true,
                        selected = selectMode && slug in selected,
                        selectMode = selectMode,
                        onClick = {
                            if (selectMode) selected = if (slug in selected) selected - slug else selected + slug
                            else nav.openDetail(slug)
                        },
                        onLongClick = {
                            selectMode = true
                            selected = if (slug in selected) selected - slug else selected + slug
                        },
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
                        chDone = prog.chDone, chTotal = prog.chTotal,
                        curChapter = "", pageDone = 0, pageTotal = 0, active = false,
                        selected = selectMode && comic.pathWord in selected,
                        selectMode = selectMode,
                        onClick = {
                            if (selectMode) {
                                val s = comic.pathWord
                                selected = if (s in selected) selected - s else selected + s
                            } else nav.openDetail(comic.pathWord)
                        },
                        onLongClick = {
                            selectMode = true
                            val s = comic.pathWord
                            selected = if (s in selected) selected - s else selected + s
                        },
                        canMove = false, onUp = {}, onDown = {},
                    )
                }
                if (doneList.isEmpty()) item { EmptyHint("未有已下載嘅漫畫 — 去「未下載」tab 開始下載啦") }
            }
        }
    }

    confirmDel?.let { slugs ->
        AlertDialog(
            onDismissRequest = { confirmDel = null },
            title = { Text(if (delCbzOnly) "只刪除 CBZ？" else "刪除下載檔案？") },
            text = {
                Text(if (delCbzOnly)
                    "會刪除 $slugs.size 部漫畫嘅 CBZ 壓縮檔（webp 圖片保留，之後可再打包）。確定？"
                else "會刪除 $slugs.size 部漫畫已下載嘅全部圖片/CBZ 檔案（書架記錄保留，可重新下載）。確定？")
            },
            confirmButton = {
                TextButton(onClick = {
                    slugs.forEach { s ->
                        if (delCbzOnly) Outputs.deleteCbzFiles(db, s)
                        else { Outputs.deleteComicFiles(db, s); db.resetComicDownloads(s) }
                    }
                    confirmDel = null; selected = emptySet(); selectMode = false
                    refresh()
                }) { Text("刪除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDel = null }) { Text("取消") } },
        )
    }
}

/** 一行式佇列列：名 + 章數 + 當前章 + bar + ↑↓（用戶明確要求嘅顯示格式）。 */
@Composable
private fun QueueRowUi(
    name: String, chDone: Int, chTotal: Int,
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
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$chDone/$chTotal 章",
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
