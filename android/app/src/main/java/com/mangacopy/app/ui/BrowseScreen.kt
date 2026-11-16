package com.mangacopy.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.mangacopy.app.data.AppConfig
import com.mangacopy.app.data.Db
import com.mangacopy.app.source.BrowsePage
import com.mangacopy.app.source.ComicMeta
import com.mangacopy.app.source.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 發現（下載源瀏覽）— CopyManga 全站列表，人氣排序、狀態篩選、無限加載。
 * 純 HTTP（冇 WAF）→ OkHttp + ListingParser；點擊即加入書架（收藏）並入 DB。
 */
@Composable
fun BrowseScreen(nav: NavHostController) {
    val db = remember { Db.get() }
    val source = remember { SourceRegistry.default }

    var statusFilter by remember { mutableStateOf<Int?>(null) }
    var items by remember { mutableStateOf<List<ComicMeta>>(emptyList()) }
    var total by remember { mutableStateOf<Int?>(null) }
    var offset by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadedOnce by remember { mutableStateOf(false) }

    val loadPage: suspend () -> Unit = {
        if (!loading) {
            loading = true; error = null
            try {
                val page: BrowsePage = withContext(Dispatchers.IO) { source.browse(offset, statusFilter) }
                items = (items + page.items).distinctBy { it.pathWord }
                total = page.total
                offset += AppConfig.LIST_LIMIT
            } catch (e: Exception) {
                error = "載入失敗：${e.message}"
            } finally {
                loading = false; loadedOnce = true
            }
        }
    }

    // 切篩選 = 重來
    LaunchedEffect(statusFilter) {
        items = emptyList(); offset = 0; loadedOnce = false
        loadPage()
    }

    Scaffold(
        topBar = {
            Column(Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(source.displayName, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.weight(1f))
                    total?.let {
                        Text("$it 部", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf<Int?>(null, 0, 1, 2).forEachIndexed { i, s ->
                        SegmentedButton(
                            selected = statusFilter == s,
                            onClick = { statusFilter = s },
                            shape = SegmentedButtonDefaults.itemShape(i, 4),
                        ) { Text(listOf("全部", "連載中", "已完結", "短篇")[i], maxLines = 1) }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.pathWord }) { meta ->
                    val inShelf = remember(meta) { db.getComic(meta.pathWord)?.favorite == true }
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                db.upsertComic(
                                    meta.pathWord, meta.name, meta.author, meta.coverUrl,
                                    serialStatus = meta.statusLabel,
                                )
                                db.setFavorite(meta.pathWord, true)
                                nav.openDetail(meta.pathWord)
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        AsyncImage(
                            model = meta.coverUrl,
                            contentDescription = meta.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(56.dp).height(76.dp)
                                .clip(RoundedCornerShape(6.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(meta.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleSmall)
                            if (meta.author.isNotEmpty())
                                Text(meta.author, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            meta.statusLabel?.let {
                                SuggestionChip(
                                    onClick = {}, enabled = false,
                                    label = { Text(it, style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                        IconButton(onClick = {
                            db.upsertComic(meta.pathWord, meta.name, meta.author, meta.coverUrl,
                                serialStatus = meta.statusLabel)
                            db.setFavorite(meta.pathWord, !inShelf)
                        }) {
                            Icon(
                                if (inShelf) Icons.Filled.Check else Icons.Filled.Add,
                                contentDescription = if (inShelf) "已收藏" else "加入書架",
                                tint = if (inShelf) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                }
                item {
                    // 無限加載：尾頁可見即攞下一頁
                    LaunchedEffect(items.size, loading) {
                        if (!loading && (total == null || items.size < (total ?: 0))) loadPage()
                    }
                    val tot = total
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        if (loading) CircularProgressIndicator(Modifier.size(28.dp))
                        else if (tot != null && items.size >= tot)
                            Text("—— 全部 $tot 部已載完 ——",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
