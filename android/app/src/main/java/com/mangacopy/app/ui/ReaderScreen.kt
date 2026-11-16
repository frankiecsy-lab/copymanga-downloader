package com.mangacopy.app.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import com.mangacopy.app.data.Db
import kotlinx.coroutines.launch
import java.io.File

/**
 * 閱讀器 — 黑底沉浸式，支持用家要求嘅全部模式：
 *   翻頁（單頁 / 雙頁）｜流水（垂直連讀）
 *   閱讀方向：漫畫式 右→左（RTL）／ 一般 左→右
 * 頁面來源 = DB done 頁面檔案（NNN.webp）；章節可前後跳。
 * 全屏隱系統欄 + 可選保持螢幕開啟；點擊左右 1/3 翻頁、中間出工具欄。
 */
@Composable
fun ReaderScreen(slug: String, cid: String, onBack: () -> Unit) {
    val db = remember { Db.get() }
    val comic = remember(slug) { db.getComic(slug) }
    val chapters = remember(slug) { db.allChapters(slug) }

    var currentCid by remember { mutableStateOf(cid) }
    val chapterIndex = chapters.indexOfFirst { it.chapterId == currentCid }.coerceAtLeast(0)

    var mode by remember { mutableStateOf(ReaderPrefs.mode(db)) }          // paged | scroll
    var rtl by remember { mutableStateOf(ReaderPrefs.rtl(db)) }
    var spread by remember { mutableStateOf(ReaderPrefs.spread(db)) }
    var keepOn by remember { mutableStateOf(ReaderPrefs.keepScreenOn(db)) }
    var showBar by remember { mutableStateOf(true) }
    var showSheet by remember { mutableStateOf(false) }

    val pages = remember(currentCid) {
        db.allImages(slug, currentCid)
            .filter { it.status == "done" && it.path != null }
            .map { File(it.path!!) }
    }

    val view = LocalView.current
    val window = (view.context as? Activity)?.window

    // 全屏 + 保持螢幕開啟（離開閱讀器全部還原）
    DisposableEffect(Unit) {
        if (window != null) {
            val controller = WindowInsetsControllerCompat(window, view)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        if (keepOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (window != null) {
                WindowInsetsControllerCompat(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    val isWide = LocalConfiguration.current.screenWidthDp >= 600
    val useSpread = spread && mode == "paged"
    // 顯示單元：單頁 = 逐頁；雙頁 = 兩頁一組（由第一頁配對，同桌面書本習慣）
    val units: List<List<File>> = remember(pages, useSpread) {
        if (!useSpread) pages.map { listOf(it) }
        else pages.chunked(2)
    }

    val pagerState = rememberPagerState(pageCount = { units.size })
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // 章節切換 → 翻返第一頁
    LaunchedEffect(currentCid) {
        if (mode == "paged") pagerState.animateScrollToPage(0) else listState.animateScrollToItem(0)
        showBar = true
    }

    val goNext: () -> Unit = {
        if (mode == "paged") {
            if (pagerState.currentPage < units.size - 1)
                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
        } else {
            scope.launch { listState.animateScrollToItem((listState.firstVisibleItemIndex + 1).coerceAtMost(pages.size - 1)) }
        }
    }
    val goPrev: () -> Unit = {
        if (mode == "paged") {
            if (pagerState.currentPage > 0)
                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
        } else {
            scope.launch { listState.animateScrollToItem((listState.firstVisibleItemIndex - 1).coerceAtLeast(0)) }
        }
    }

    BackHandler { onBack() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (pages.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("呢章未下載／未有完成頁面", color = Color.White)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onBack) { Text("返回", color = Color.White) }
            }
        } else if (mode == "scroll") {
            // 流水（垂直連讀）
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val h = size.height
                            when {
                                offset.y < h * 0.2f -> showBar = !showBar
                                offset.y < h * 0.55f -> goPrev()
                                else -> goNext()
                            }
                        }
                    },
            ) {
                items(pages.size) { i ->
                    AsyncImage(
                        model = pages[i], contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            // 翻頁（單頁/雙頁），RTL = reverseLayout
            HorizontalPager(
                state = pagerState,
                reverseLayout = rtl,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(),
            ) { page ->
                val unit = units[page]
                Row(
                    Modifier.fillMaxSize()
                        .pointerInput(rtl) {
                            detectTapGestures { offset ->
                                val w = size.width
                                when {
                                    offset.x < w / 3f -> if (rtl) goNext() else goPrev()
                                    offset.x > w * 2 / 3f -> if (rtl) goPrev() else goNext()
                                    else -> showBar = !showBar
                                }
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    unit.forEachIndexed { i, f ->
                        AsyncImage(
                            model = f, contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        if (i == 0 && unit.size == 2) {
                            Spacer(Modifier.width(1.dp).fillMaxHeight().background(Color.DarkGray))
                        }
                    }
                }
            }
        }

        // 頁碼指示（翻頁模式）
        if (mode == "paged" && units.isNotEmpty() && !showBar) {
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
            ) {
                Text(
                    "${pagerState.currentPage + 1} / ${units.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        // 工具欄（點擊中間出/收）
        AnimatedVisibility(visible = showBar, enter = fadeIn(), exit = fadeOut()) {
            Column(Modifier.fillMaxSize()) {
                Surface(color = Color.Black.copy(alpha = 0.82f)) {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                comic?.displayName ?: "",
                                color = Color.White, style = MaterialTheme.typography.titleSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                chapters.getOrNull(chapterIndex)?.title ?: "",
                                color = Color.LightGray, style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            enabled = chapterIndex > 0,
                            onClick = { currentCid = chapters[chapterIndex - 1].chapterId },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上一章",
                                tint = if (chapterIndex > 0) Color.White else Color.Gray)
                        }
                        IconButton(
                            enabled = chapterIndex < chapters.size - 1,
                            onClick = { currentCid = chapters[chapterIndex + 1].chapterId },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下一章",
                                tint = if (chapterIndex < chapters.size - 1) Color.White else Color.Gray)
                        }
                        IconButton(onClick = { showSheet = true }) {
                            Icon(Icons.Filled.Tune, "閱讀設定", tint = Color.White)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }

    // 閱讀設定 Sheet
    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("閱讀模式", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    SegmentedButton(
                        selected = mode == "paged",
                        onClick = { mode = "paged"; db.setMeta(ReaderPrefs.MODE, "paged") },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("翻頁") }
                    SegmentedButton(
                        selected = mode == "scroll",
                        onClick = { mode = "scroll"; db.setMeta(ReaderPrefs.MODE, "scroll") },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("流水") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("方向（右→左）", Modifier.weight(1f))
                    Switch(checked = rtl, enabled = mode == "paged", onCheckedChange = {
                        rtl = it; db.setMeta(ReaderPrefs.RTL, if (it) "true" else "false")
                    })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("雙頁")
                        if (rtl && useSpread) Text("橫屏先至似書本翻頁效果",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = spread, enabled = mode == "paged", onCheckedChange = {
                        spread = it; db.setMeta(ReaderPrefs.SPREAD, if (it) "true" else "false")
                    })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("保持螢幕開啟", Modifier.weight(1f))
                    Switch(checked = keepOn, onCheckedChange = {
                        keepOn = it
                        db.setMeta(ReaderPrefs.KEEP_SCREEN_ON, if (it) "true" else "false")
                        if (window != null) {
                            if (it) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    })
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
