package com.mangacopy.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.mangacopy.app.data.Db
import com.mangacopy.app.download.DownloadEngine
import com.mangacopy.app.source.SourceRegistry

/**
 * 漫畫詳情 — 章節 GRID 按鈕 + 開始/繼續閱讀 + 檢查更新。
 * 章節排序：FlowRow chips 可揀（**默認「章節倒序」**= 最新喺頂，用戶要求）；CBZ vol 序永遠用 title 序不受影響。
 * 章節 GRID 按鈕 = 開始閱讀呢章（未下載由 ChapterPreloader 即時預載）；
 * 長按 / 頂欄「選擇」icon = 批量下載模式（用戶要求：刪除逐章下載按鈕，閱讀優先）。
 * 頂部主按鈕：未睇過 →「開始閱讀 · 第一章」；睇過 →「繼續閱讀 · <上次嗰章>」（kv last_read）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(nav: NavHostController, slug: String) {
    val db = remember { Db.get() }
    var comic by remember { mutableStateOf(db.getComic(slug)) }
    // 排序（用戶 2026-11-17：「消除流式顯示及未下載優先，保留正序及倒序」）：
    // 得返 title / title_desc（默認倒序）；舊 kv 存嘅 flow/pending_first 自動歸正為默認值
    var sort by remember { mutableStateOf(
        if (db.getMeta("chapter_sort", "title_desc") == "title") "title" else "title_desc") }
    var sortMenu by remember { mutableStateOf(false) }   // 頂欄排序 icon 嘅 DropdownMenu
    // 上次阅读进度（kv last_read:<slug> = cid，ReaderScreen 持續更新）
    var lastCid by remember { mutableStateOf(db.getMeta("last_read:$slug", null)) }
    val orderOf: (String) -> String = { if (it == "title") "title" else "title_desc" }
    var chapters by remember { mutableStateOf(db.allChapters(slug, orderOf(sort))) }
    var showSynopsis by remember { mutableStateOf(false) }
    val engineState by DownloadEngine.state.collectAsState()
    var loadingChapters by remember { mutableStateOf(false) }
    var loadErr by remember { mutableStateOf<String?>(null) }
    var retryTick by remember { mutableStateOf(0) }
    // 手動更新章節清單（用戶要求）：renderDetail diff 新章節，唔落圖唔入隊
    var refreshTick by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshMsg by remember { mutableStateOf<String?>(null) }
    // 章節批次揀選模式（長按開啟）
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }

    // 瀏覽紀錄（入頁即記，重訪更新時間）
    LaunchedEffect(slug) { db.recordHistory(slug) }

    // 緩存優先 ＋ 背景更新（用戶要求）：DB 有清單即顯示離線紀錄；
    // 距上次渲染超過 15 分鐘（或手動「更新章節」/重試）先再 renderDetail diff 新章節。
    // 連結（章節 url／圖片 url）本身就持久喺 DB = 下次下載唔使重新搵連結。
    LaunchedEffect(slug, retryTick, refreshTick) {
        if (DownloadEngine.state.value.running) return@LaunchedEffect
        val lastTs = db.getMeta("chlist_ts:$slug", "0")?.toLongOrNull() ?: 0L
        val stale = System.currentTimeMillis() - lastTs > 15 * 60_000L
        if (chapters.isNotEmpty() && !stale && refreshTick == 0 && retryTick == 0) return@LaunchedEffect
        loadingChapters = chapters.isEmpty()   // 有舊清單就唔好全屏幕「載入中」阻閱讀
        refreshing = chapters.isNotEmpty()
        loadErr = null
        try {
            val d = SourceRegistry.default.detail(slug)
            db.upsertComic(slug, synopsis = d.synopsis, serialStatus = d.serialStatus)
            val before = db.allChapters(slug, "flow").size
            db.addChapters(slug, d.chapters.map { Triple(it.chapterId, it.title, it.url) })
            val added = db.allChapters(slug, "flow").size - before
            db.setMeta("chlist_ts:$slug", System.currentTimeMillis().toString())
            if (added > 0) refreshMsg = "新增 $added 章"
            else if (refreshTick > 0) refreshMsg = "已經係最新清單"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e   // 正常取消（離開頁面）→ 唔好寫入「更新失敗」假訊息
        } catch (e: Exception) {
            // 有舊清單就靜默失敗（唔阻離線閱讀）；空清單先報錯俾重試
            if (chapters.isEmpty()) loadErr = e.message
            else if (refreshTick > 0) refreshMsg = "更新失敗：${e.message}"
        }
        comic = db.getComic(slug)
        chapters = db.allChapters(slug, orderOf(sort))
        loadingChapters = false
        refreshing = false
    }

    LifecycleResumeEffect(slug) {
        comic = db.getComic(slug)
        chapters = db.allChapters(slug, orderOf(sort))
        lastCid = db.getMeta("last_read:$slug", null)   // 由閱讀器返嚟 → 刷新「繼續閱讀」目標
        onPauseOrDispose { }
    }

    LaunchedEffect(sort) {
        db.setMeta("chapter_sort", sort)
        chapters = db.allChapters(slug, orderOf(sort))
    }

    Scaffold(
        topBar = {
            if (selectMode) {
                TopAppBar(
                    title = { Text("已揀 ${selected.size} 章") },
                    navigationIcon = {
                        IconButton(onClick = { selectMode = false; selected = emptySet() }) {
                            Icon(Icons.Filled.Close, "離開選擇")
                        }
                    },
                    actions = {
                        TextButton(onClick = { selected = chapters.map { it.chapterId }.toSet() }) {
                            Text("全選(${chapters.size})")
                        }
                        TextButton(onClick = { selected = emptySet() }) { Text("清単") }
                    },
                )
            } else TopAppBar(
                title = { Text(comic?.displayName ?: "", maxLines = 1) },
                navigationIcon = {
                    // 返回箭嘴加圓形底（用戶：「要加圓型底」＋「少 10%」＋「再少 20%」— 48→43→39→31dp）。
                    // 31dp 細圈用唔著 IconButton（M3 呢版冇 contentPadding 參數、預設 12dp 會迫爆），
                    // 直接 Surface＋clickable＋置中 18dp 箭嘴
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.size(31.dp).clip(CircleShape)
                            .clickable { nav.popBackStack() },
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(18.dp))
                        }
                    }
                },
                actions = {
                    // 用戶（2026-11-17）：「批量選擇左邊用 icon；更新章節、排序都移去右上 icon」—
                    // 頂欄一次過：〔批量選擇〕〔刷新=更新章節〕〔排序 menu〕〔收藏〕
                    IconButton(
                        enabled = chapters.isNotEmpty(),
                        onClick = { selectMode = true },
                    ) {
                        Icon(Icons.Filled.SelectAll, "批量下載模式")
                    }
                    IconButton(
                        enabled = !refreshing,
                        onClick = { refreshTick++ },
                    ) {
                        if (refreshing)
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Filled.Refresh, "更新章節")
                    }
                    Box {
                        IconButton(
                            enabled = chapters.isNotEmpty(),
                            onClick = { sortMenu = true },
                        ) { Icon(Icons.Filled.Sort, "章節排序") }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            listOf("title" to "章節正序", "title_desc" to "章節倒序")
                                .forEach { (v, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        trailingIcon = { if (sort == v) Icon(Icons.Filled.Check, null) },
                                        onClick = { sort = v; sortMenu = false },
                                    )
                                }
                        }
                    }
                    IconButton(
                        onClick = {
                            db.setFavorite(slug, !(comic?.favorite ?: false))
                            comic = db.getComic(slug)
                        },
                    ) {
                        Icon(
                            if (comic?.favorite == true) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = "收藏",
                            tint = if (comic?.favorite == true) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (selectMode) BottomBar(
                count = selected.size,
                enabled = selected.isNotEmpty() && !engineState.running,
                onDownload = {
                    DownloadEngine.start(listOf(slug), onlyCids = selected)
                    selectMode = false; selected = emptySet()
                    nav.navigate("downloads") { launchSingleTop = true }
                },
            )
        },
    ) { pad ->
        val c = comic
        if (c == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("搵唔到呢部漫畫")
            }
            return@Scaffold
        }
        Column(Modifier.padding(pad)) {
            Row(Modifier.padding(horizontal = 16.dp)) {
                AsyncImage(
                    model = c.coverUrl, contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(92.dp).height(126.dp).clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    if (c.author?.isNotBlank() == true) Text(c.author, style = MaterialTheme.typography.bodyMedium)
                    c.serialStatus?.takeIf { it.isNotBlank() }?.let {
                        SuggestionChip(onClick = {}, enabled = false,
                            label = { Text(it, style = MaterialTheme.typography.labelSmall) })
                    }
                    Spacer(Modifier.height(6.dp))
                    val prog = db.comicProgress(slug)
                    Text(
                        when {
                            loadingChapters -> "載入章節清單中…"
                            prog.chTotal > 0 -> "章節 ${prog.chDone}/${prog.chTotal}" +
                                    if (prog.chErr > 0) "（${prog.chErr} 章出錯）" else ""
                            else -> "未有章節資料"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (prog.chErr > 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    // 主按鈕（用戶要求）：未睇過 = 第一章開始；睇過 = 繼續閱讀上次嗰章
                    val seenCid = lastCid?.takeIf { id -> chapters.any { it.chapterId == id } }
                    val firstCid = remember(chapters) {
                        // 數字升序第一粒 = 「第01话」（2026-11-17 真站實測：flow 首位可能
                        // 係釘頂嘅「22卷增页」最新附加章，唔係第一章）
                        db.allChapters(slug, "title").firstOrNull()?.chapterId
                    }
                    val readCid = seenCid ?: firstCid
                    val readTitle = chapters.firstOrNull { it.chapterId == readCid }?.title
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = readCid != null && chapters.isNotEmpty(),
                            onClick = { readCid?.let { nav.navigate("reader/$slug/$it") } },
                        ) {
                            Text(
                                if (seenCid != null) "繼續閱讀 · ${readTitle ?: ""}"
                                else "開始閱讀 · ${readTitle ?: "第一章"}",
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 210.dp),
                            )
                        }
                        // 「更新章節」已移去頂欄 Refresh icon（用戶 2026-11-17）
                        // 2026-11-17 用戶要求：「從頭睇」— 永遠跳數字第一話（第01話/第01卷）
                        if (seenCid != null) {
                            OutlinedButton(
                                enabled = firstCid != null,
                                onClick = { firstCid?.let { nav.navigate("reader/$slug/$it") } },
                            ) { Text("從頭睇") }
                        }
                    }
                    refreshMsg?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            c.synopsis?.takeIf { it.isNotBlank() }?.let { syn ->
                TextButton(onClick = { showSynopsis = !showSynopsis }) {
                    Text(if (showSynopsis) "收起簡介 ▲" else "查看簡介 ▼",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (showSynopsis) Text(
                    syn, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            HorizontalDivider(Modifier.padding(top = 4.dp))
            // 章節排序 chips 已移去頂欄 Sort icon 嘅 DropdownMenu（用戶 2026-11-17）

            if (loadingChapters) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("載入章節清單中（站點渲染需時，通常 10 秒內）…",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            loadErr?.let { msg ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("載入章節清單失敗：$msg",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { retryTick++ }) { Text("重試") }
                }
            }

            // 章節 GRID 按鈕（用戶要求）：tap = 即時閱讀呢章；長按 / 頂欄選擇 = 批量下載模式
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(chapters, key = { it.chapterId }) { ch ->
                    val isSel = ch.chapterId in selected
                    ChapterGridButton(
                        title = ch.title ?: "章節",
                        done = ch.status == "done",
                        err = ch.status == "error",
                        current = ch.chapterId == lastCid,
                        selectMode = selectMode,
                        isSel = isSel,
                        onClick = {
                            if (selectMode)
                                selected = if (isSel) selected - ch.chapterId else selected + ch.chapterId
                            else nav.navigate("reader/$slug/${ch.chapterId}")
                        },
                        onLongClick = {
                            selectMode = true
                            selected = if (isSel) selected - ch.chapterId else selected + ch.chapterId
                        },
                    )
                }
            }
        }
    }
}

/**
 * 章節 GRID 按鈕 — **純色系零符號**（用戶 2026-11-17：「唔使打 tick，用唔同顏色表示
 * 目前在讀／有下載／冇下載」）：
 *   冇下載 ＝ surface 預設；已下載 ＝ secondaryContainer；
 *   閱讀中 ＝ primaryContainer ＋ primary 描邊；揀中 ＝ tertiaryContainer；出錯 ＝ 紅框。
 * 標題永遠顯示（select mode 都睇到章節名）。
 */
@Composable
private fun ChapterGridButton(
    title: String, done: Boolean, err: Boolean, current: Boolean,
    selectMode: Boolean, isSel: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val bg = when {
        isSel -> MaterialTheme.colorScheme.tertiaryContainer
        current -> MaterialTheme.colorScheme.primaryContainer
        done -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    Surface(
        color = bg,
        shape = shape,
        border = BorderStroke(if (current) 1.5.dp else 1.dp, when {
            err -> MaterialTheme.colorScheme.error
            current -> MaterialTheme.colorScheme.primary   // 閱讀中：primary 描邊格外突出
            bg != MaterialTheme.colorScheme.surface -> Color.Transparent
            else -> MaterialTheme.colorScheme.outlineVariant
        }),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        // 標題獨留全格（零 ✓／error 符號 — 狀態一律靠底色／紅框表達）。
        // 用戶（2026-11-17）：「幾多話章節嘅字要大一點、垂直置中」—
        // labelMedium(12sp)→bodyMedium(16sp)，Box contentAlignment=Center 做真·垂直置中
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

/** 選擇模式底部操作欄 — 「下載所選 N 章」。 */
@Composable
private fun BottomBar(count: Int, enabled: Boolean, onDownload: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("已揀 $count 章", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f))
            Button(enabled = enabled, onClick = onDownload) { Text("下載所選章節") }
        }
    }
}
