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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.mangacopy.app.data.Db

/**
 * 瀏覽紀錄 — 入過詳情頁嘅漫畫都會自動記錄（含名稱／作者／簡介／封面／狀態）。
 * tap = 返詳情頁；長按 = 單筆刪除；右上 = 清空全部。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(nav: NavHostController) {
    val db = remember { Db.get() }
    var entries by remember { mutableStateOf(db.historyEntries()) }
    var confirmClear by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(ListMode.isGrid(db)) }   // 默認 GRID（全站一致）

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("瀏覽紀錄") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { grid = !grid; ListMode.set(db, grid) }) {
                        Icon(if (grid) Icons.Filled.GridView else Icons.Filled.ViewList,
                            if (grid) "轉列表顯示" else "轉格子顯示")
                    }
                    if (entries.isNotEmpty()) IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Filled.DeleteSweep, "清空瀏覽紀錄")
                    }
                },
            )
        },
    ) { pad ->
        if (entries.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(pad),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("未有瀏覽紀錄", style = MaterialTheme.typography.titleMedium)
                Text("開過嘅漫畫詳情頁會自動喺度出現",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (grid) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                contentPadding = PaddingValues(12.dp),
                modifier = Modifier.fillMaxSize().padding(pad),
            ) {
                gridItems(entries, key = { it.pathWord }) { c ->
                    Column(
                        Modifier.padding(6.dp)
                            .combinedClickable(
                                onClick = { nav.openDetail(c.pathWord) },
                                onLongClick = {
                                    db.removeHistory(c.pathWord)
                                    entries = db.historyEntries()
                                },
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(0.7f)) {
                            AsyncImage(
                                model = c.coverUrl, contentDescription = c.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(c.displayName, style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center)
                    }
                }
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            items(entries, key = { it.pathWord }) { c ->
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(
                            onClick = { nav.openDetail(c.pathWord) },
                            onLongClick = {
                                db.removeHistory(c.pathWord)
                                entries = db.historyEntries()
                            },
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    AsyncImage(
                        model = c.coverUrl, contentDescription = c.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(56.dp).height(76.dp).clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.displayName, style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        c.author?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        c.serialStatus?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary)
                        }
                        c.synopsis?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(2.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                HorizontalDivider()
            }
            item {
                Text("長按紀錄可個別刪除　·　右上掃描icon清空",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("清空瀏覽紀錄？") },
        text = { Text("只會刪除瀏覽紀錄，書架同已下載檔案唔受影響。") },
        confirmButton = {
            TextButton(onClick = {
                db.clearHistory(); entries = emptyList(); confirmClear = false
            }) { Text("清空", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
    )
}
