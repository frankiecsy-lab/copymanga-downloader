package com.mangacopy.app.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.mangacopy.app.data.Db
import com.mangacopy.app.download.DownloadEngine
import com.mangacopy.app.source.SourceRegistry

/**
 * 漫畫詳情 — 章節清單（狀態徽章）+ 下載/檢查更新/閱讀。
 * 章節排序：FlowRow chips 可揀（默認「流式」= 站點原始序）；CBZ vol 序永遠用 title 序不受影響。
 * 點擊章節 = 即時閱讀（未下載章節由 ChapterPreloader 即時預載前 N 張 webp）；
 * 章節右邊「下載」icon = 只排呢章；頂欄「選擇」icon = 批量下載模式。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(nav: NavHostController, slug: String) {
    val db = remember { Db.get() }
    var comic by remember { mutableStateOf(db.getComic(slug)) }
    // 排序：flow（默認，站點原序）/ title / title_desc / pending_first
    var sort by remember { mutableStateOf(db.getMeta("chapter_sort", "flow") ?: "flow") }
    val orderOf: (String) -> String = {
        when (it) { "title" -> "title"; "title_desc" -> "title_desc";
                    "pending_first" -> "pending_first"; else -> "flow" }
    }
    var chapters by remember { mutableStateOf(db.allChapters(slug, orderOf(sort))) }
    var showSynopsis by remember { mutableStateOf(false) }
    val engineState by DownloadEngine.state.collectAsState()
    var loadingChapters by remember { mutableStateOf(false) }
    var loadErr by remember { mutableStateOf<String?>(null) }
    var retryTick by remember { mutableStateOf(0) }
    // 章節批次揀選模式（長按開啟）
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }

    // 瀏覽紀錄（入頁即記，重訪更新時間）
    LaunchedEffect(slug) { db.recordHistory(slug) }

    // 入頁即自動載入章節清單（用戶要求）：DB 冇先至 render，引擎跑緊就等
    LaunchedEffect(slug, chapters.isEmpty(), retryTick) {
        if (chapters.isEmpty() && !DownloadEngine.state.value.running) {
            loadingChapters = true
            loadErr = null
            try {
                val d = SourceRegistry.default.detail(slug)
                db.upsertComic(slug, synopsis = d.synopsis, serialStatus = d.serialStatus)
                db.addChapters(slug, d.chapters.map { Triple(it.chapterId, it.title, it.url) })
            } catch (e: Exception) {
                loadErr = e.message
            }
            comic = db.getComic(slug)
            chapters = db.allChapters(slug, orderOf(sort))
            loadingChapters = false
        }
    }

    LifecycleResumeEffect(slug) {
        comic = db.getComic(slug)
        chapters = db.allChapters(slug, orderOf(sort))
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
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    // 批量下載模式入口（用戶要求：點擊下載先出現批量模式）
                    IconButton(
                        enabled = chapters.isNotEmpty(),
                        onClick = { selectMode = true },
                    ) {
                        Icon(Icons.Filled.SelectAll, "批量下載模式")
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !engineState.running,
                            onClick = {
                                DownloadEngine.start(listOf(slug))
                                nav.navigate("downloads") { launchSingleTop = true }
                            },
                        ) { Text(if (prog.chDone > 0) "繼續下載" else "開始下載") }
                        OutlinedButton(
                            enabled = !engineState.running && chapters.isNotEmpty(),
                            onClick = {
                                DownloadEngine.start(listOf(slug), checkUpdates = true)
                                nav.navigate("downloads") { launchSingleTop = true }
                            },
                        ) { Text("檢查更新") }
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

            // 章節排序（流式按鍵，默認「流式」= 站點原序）
            if (chapters.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("flow" to "流式", "title" to "章節正序", "title_desc" to "章節倒序",
                           "pending_first" to "未下載優先").forEach { (v, label) ->
                        FilterChip(
                            selected = sort == v,
                            onClick = { sort = v },
                            label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
            }

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

            LazyColumn(Modifier.fillMaxSize()) {
                items(chapters, key = { it.chapterId }) { ch ->
                    val done = ch.status == "done"
                    val err = ch.status == "error"
                    val isSel = ch.chapterId in selected
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (selectMode) {
                                        selected = if (isSel) selected - ch.chapterId
                                                    else selected + ch.chapterId
                                    } else {
                                        // tap = 即時閱讀模式：未下載章節開卷即即時預載（用戶要求）
                                        nav.navigate("reader/$slug/${ch.chapterId}")
                                    }
                                },
                                onLongClick = {
                                    selectMode = true
                                    selected = if (isSel) selected - ch.chapterId else selected + ch.chapterId
                                },
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selectMode) {
                            Checkbox(checked = isSel, onCheckedChange = {
                                selected = if (it) selected + ch.chapterId else selected - ch.chapterId
                            })
                            Spacer(Modifier.width(6.dp))
                        } else Icon(
                            when {
                                done -> Icons.Filled.CheckCircle
                                err -> Icons.Filled.ErrorOutline
                                else -> Icons.Filled.RadioButtonUnchecked
                            },
                            contentDescription = null,
                            tint = when {
                                done -> MaterialTheme.colorScheme.primary
                                err -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.outline
                            },
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            ch.title ?: "章節",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (done) FontWeight.Normal else FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        if (done) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, "閱讀",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                        }
                        // 單章下載 icon（tap 章節本身 = 閱讀，下載要明確撳呢度）
                        if (!selectMode) IconButton(
                            enabled = !done && !engineState.running,
                            onClick = {
                                DownloadEngine.start(listOf(slug), onlyCids = setOf(ch.chapterId))
                                nav.navigate("downloads") { launchSingleTop = true }
                            },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(Icons.Filled.FileDownload, "下載呢章",
                                modifier = Modifier.size(18.dp),
                                tint = if (done) MaterialTheme.colorScheme.outline
                                       else MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider()
                }
            }
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
