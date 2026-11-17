package com.mangacopy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.mangacopy.app.data.AppConfig
import com.mangacopy.app.data.Db
import com.mangacopy.app.source.ComicMeta
import com.mangacopy.app.source.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 搜尋頁（用戶要求：發現主菜單改為搜尋）：
 *   模糊輸入書名 / 作者  → 站內 searchcl API（服務端模糊匹配，無限加載）
 *   冇關鍵字             → 全站人氣列表（狀態 filter 服務端有效）
 *   種類 filter          → 從結果帶返嘅 theme/parodies 標籤生成 chips，客戶端篩選
 *                          （站點 filter API 維護中、search 唔返回標籤時自動隱去）
 *   GRID / LIST 切換     → 默認 GRID（ListMode kv 全站持久化）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BrowseScreen(nav: NavHostController) {
    val db = remember { Db.get() }
    val source = remember { SourceRegistry.default }

    var query by remember { mutableStateOf("") }         // 輸入框實時內容
    var activeQ by remember { mutableStateOf("") }       // 已提交嘅搜索詞（空=浏览模式）
    var statusFilter by remember { mutableStateOf<Int?>(null) }
    var items by remember { mutableStateOf<List<ComicMeta>>(emptyList()) }
    var total by remember { mutableStateOf<Int?>(null) }
    var offset by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var grid by remember { mutableStateOf(ListMode.isGrid(db)) }
    var tagFilter by remember { mutableStateOf<String?>(null) }
    var shelfTick by remember { mutableStateOf(0) }   // 收藏變動 → 重算 inShelf（否則 + 撳咗冇反應）

    val loadPage: suspend () -> Unit = {
        if (!loading) {
            loading = true; error = null
            try {
                val page = if (activeQ.isEmpty()) {
                    val p = withContext(Dispatchers.IO) { source.browse(offset, statusFilter) }
                    offset += AppConfig.LIST_LIMIT
                    p.items to p.total
                } else {
                    val p = withContext(Dispatchers.IO) { source.search(activeQ, offset) }
                    offset += 20   // searchcl 每頁上限 20
                    p.items to p.total
                }
                items = (items + page.first).distinctBy { it.pathWord }
                total = page.second
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e   // 离开页面/重搜的正常取消 → 唔好显示成「载入失败」
            } catch (e: Exception) {
                error = "載入失敗：${e.message}"
            } finally {
                loading = false
            }
        }
    }

    // 全域緩存（用戶要求）：列表/搜尋結果入記憶體緩存 10 分鐘 —
    // 返嚟即display 舊結果，超時或撳「刷新」先再打站點
    var forceTick by remember { mutableStateOf(0) }
    val cacheKey = if (activeQ.isEmpty()) "browse:${statusFilter ?: "all"}" else "search:$activeQ"
    LaunchedEffect(activeQ, statusFilter, forceTick) {
        val fresh = forceTick == 0 && ListCache.key == cacheKey &&
            System.currentTimeMillis() - ListCache.ts < 10 * 60_000L &&
            ListCache.items.isNotEmpty()
        if (fresh) {
            items = ListCache.items; offset = ListCache.offset; total = ListCache.total
            tagFilter = null; error = null
            return@LaunchedEffect
        }
        items = emptyList(); offset = 0; tagFilter = null
        loadPage()
        if (items.isNotEmpty()) {
            ListCache.key = cacheKey; ListCache.items = items
            ListCache.offset = offset; ListCache.total = total
            ListCache.ts = System.currentTimeMillis()
        }
    }

    // 種類標籤池：累積結果出過嘅 tags（searchcl theme/parodies）
    val tagPool = remember(items) {
        items.flatMap { it.tags }.distinct().sorted()
    }
    val shown = remember(items, tagFilter) {
        if (tagFilter == null) items else items.filter { tagFilter in it.tags }
    }

    Scaffold(
        containerColor = tabBackground("browse"),   // 淡藍：tab 背景區分（用戶要求）
        topBar = {
            Column(Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp)) {
                // 品牌標題欄：LOGO＋KIECOMIC 靠左。
                // 用戶（2026-11-17）：「顯示有多少部漫畫放到右上角」— 部數徽章放品牌行右端
                Box(Modifier.fillMaxWidth()) {
                    BrandTitle()
                    total?.let {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.align(Alignment.CenterEnd),
                        ) {
                            Text("共 $it 部",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("搜尋漫畫名 / 作者…") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { forceTick++ }) {
                                Icon(Icons.Filled.Refresh, "刷新（clear 緩存重載）")
                            }
                            IconButton(onClick = {
                                grid = !grid; ListMode.set(db, grid)
                            }) {
                                Icon(
                                    if (grid) Icons.Filled.GridView else Icons.Filled.ViewList,
                                    if (grid) "轉列表顯示" else "轉格子顯示",
                                )
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { activeQ = query.trim() }),
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 狀態 filter 只喺浏览模式服務端有效；搜索模式隱去避免誤導
                    if (activeQ.isEmpty()) {
                        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                            listOf<Int?>(null, 0, 1, 2).forEachIndexed { i, s ->
                                SegmentedButton(
                                    selected = statusFilter == s,
                                    onClick = { statusFilter = s },
                                    shape = SegmentedButtonDefaults.itemShape(i, 4),
                                ) { Text(listOf("全部", "連載中", "已完結", "短篇")[i], maxLines = 1) }
                            }
                        }
                    } else {
                        Text(
                            "搜尋「$activeQ」",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { query = ""; activeQ = "" }) { Text("返去瀏覽") }
                    }
                    // 「N 部」已移去品牌行右上角（用戶 2026-11-17）
                }
                if (tagPool.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                        maxItemsInEachRow = 3,
                    ) {
                        FilterChip(
                            selected = tagFilter == null,
                            onClick = { tagFilter = null },
                            label = { Text("種類・全部", maxLines = 1) },
                        )
                        tagPool.take(12).forEach { t ->
                            FilterChip(
                                selected = tagFilter == t,
                                onClick = { tagFilter = if (tagFilter == t) null else t },
                                label = { Text(t, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
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
            if (grid) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    contentPadding = PaddingValues(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    gridItems(shown, key = { it.pathWord }) { meta ->
                        GridCell(meta = meta,
                            onClick = { openFromSearch(db, nav, meta); shelfTick++ },
                            onAdd = { toggleShelf(db, meta); shelfTick++ },
                            inShelf = remember(meta, shelfTick) { db.getComic(meta.pathWord)?.favorite == true },
                        )
                    }
                    // 頁腳要 span 橫跨全行 — LazyVerticalGrid 的 item 預設只佔一格，
                    // 「全部 N 部已載完」會挤咗第一格內 wrap（用戶：「最後一包唔係用全寬」）。
                    // ⚠️ span lambda 入面個 it 係 item index（單元素＝0）—
                    //    GridItemSpan(0) 直接 IllegalArgumentException 閃退；
                    //    要用 LazyGridItemSpanScope.maxLineSpan（範圍內自動係行內總格數）
                    gridItems(listOf(0), span = { GridItemSpan(maxLineSpan) }) {
                        LoadMoreFooter(loading, total, shown.size, error == null) { loadPage() }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.pathWord }) { meta ->
                        ListRow(meta = meta,
                            onClick = { openFromSearch(db, nav, meta); shelfTick++ },
                            onAdd = { toggleShelf(db, meta); shelfTick++ },
                            inShelf = remember(meta, shelfTick) { db.getComic(meta.pathWord)?.favorite == true },
                        )
                    }
                    item { LoadMoreFooter(loading, total, shown.size, error == null) { loadPage() } }
                }
            }
        }
    }
}

private fun openFromSearch(db: Db, nav: NavHostController, meta: ComicMeta) {
    db.upsertComic(meta.pathWord, meta.name, meta.author, meta.coverUrl,
        serialStatus = meta.statusLabel)
    db.setFavorite(meta.pathWord, true)
    nav.openDetail(meta.pathWord)
}

private fun toggleShelf(db: Db, meta: ComicMeta) {
    val inShelf = db.getComic(meta.pathWord)?.favorite == true
    db.upsertComic(meta.pathWord, meta.name, meta.author, meta.coverUrl,
        serialStatus = meta.statusLabel)
    db.setFavorite(meta.pathWord, !inShelf)
}

/**
 * GRID / LIST 共用：无限加载页脚（可見即攞下一頁）。
 * 防呆（2026-11-17 ADB 實測踩出）：上一頁失敗（error≠null）時絕對唔好自動重fetch —
 * 否则 loading 翻 false → LaunchedEffect 重触发 → 死循环重试，
 * 每次失败再 invalidateBase → 域名探测风暴（每 0.6 秒 3 个网络请求，烧网烧电）。
 */
@Composable
private fun LoadMoreFooter(loading: Boolean, total: Int?, shownSize: Int,
                           retryOk: Boolean, loadMore: suspend () -> Unit) {
    LaunchedEffect(shownSize, loading, retryOk) {
        if (!loading && retryOk && (total == null || shownSize < (total ?: 0))) loadMore()
    }
    val done = total != null && shownSize >= (total ?: 0)
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        if (loading) CircularProgressIndicator(Modifier.size(28.dp))
        else if (!retryOk) Text("加載更多失敗 — 撳上方「刷新」重試",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else if (done) Text("—— 全部 $total 部已載完 ——",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** GRID 格：封面 + 置中名 + 收藏角撳。 */
@Composable
private fun GridCell(meta: ComicMeta, inShelf: Boolean, onClick: () -> Unit, onAdd: () -> Unit) {
    Column(
        Modifier.padding(6.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.7f)) {
            AsyncImage(
                model = meta.coverUrl, contentDescription = meta.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Surface(
                shape = RoundedCornerShape(topStart = 0.dp, bottomEnd = 8.dp),
                color = if (inShelf) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.TopEnd)
                    .clickable(onClick = onAdd),
            ) {
                Icon(
                    if (inShelf) Icons.Filled.Check else Icons.Filled.Add,
                    contentDescription = if (inShelf) "已收藏" else "加入書架",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(3.dp).size(16.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            meta.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** LIST 行（原發現頁樣式）。 */
@Composable
private fun ListRow(meta: ComicMeta, inShelf: Boolean, onClick: () -> Unit, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AsyncImage(
            model = meta.coverUrl, contentDescription = meta.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(56.dp).height(76.dp).clip(RoundedCornerShape(6.dp)),
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
                SuggestionChip(onClick = {}, enabled = false,
                    label = { Text(it, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
        IconButton(onClick = onAdd) {
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

/**
 * 瀏覽/搜尋結果記憶體緩存（用戶要求「全域盡量緩存」）—
 * 以 query+filter 做 key，TTL 10 分鐘；App 進程生存期內有效，
 * 細頁導航返嚟即時 display，超時/手動刷新先再打站點。
 */
private object ListCache {
    @Volatile var key = ""
    @Volatile var ts = 0L
    @Volatile var items: List<ComicMeta> = emptyList()
    @Volatile var offset = 0
    @Volatile var total: Int? = null
}
