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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import com.mangacopy.app.data.Db
import com.mangacopy.app.download.ChapterPreloader
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/**
 * 閱讀器 — 黑底沉浸式，支持用家要求嘅全部模式：
 *   翻頁（單頁 / 雙頁）｜流水（垂直連讀）
 *   閱讀方向：漫畫式 右→左（RTL）／ 一般 左→右
 *
 * 即時閱讀（用戶要求「無感瀏覽」）：開卷即行 ChapterPreloader —
 *   渲染一有新頁 URL 就逐張落 webp，前 N 張（設置可改，默認 5）到齊即刻開讀，
 *   其餘背景補落；進度記憶（每章 readPos），退出重入自動返上次位置。
 * 跨章節無縫（用戶要求）：本章備齊前 N 頁即背景預載下一章（preflow 防重）；
 *   翻到本章尾 → 自動接下一章（readPos 恢復位置）；第一頁 ← 可无缝回上一章。
 * 全屏隱系統欄 + 可選保持螢幕開啟；點擊左右 1/3 翻頁、中間出工具欄。
 */
@Composable
fun ReaderScreen(slug: String, cid: String, onBack: () -> Unit) {
    val db = remember { Db.get() }
    val comic = remember(slug) { db.getComic(slug) }
    // 2026-11-17 實測：站序（flow）係最新排最前 → 一拳「第17卷」嘅 flow「下一章」
    // 竟然係第16卷（越睇越倒退）。閱讀器跨章必須用數字話/卷序（17→18）；
    // 清單九成以上標題冇數字先 fallback 站序。
    val chapters = remember(slug) {
        val flow = db.allChapters(slug, "flow")
        val numbered = flow.count { t -> t.title?.any { c -> c in '0'..'9' } == true }
        if (flow.isNotEmpty() && numbered * 10 >= flow.size * 9) db.allChapters(slug, "title")
        else flow
    }

    var currentCid by remember { mutableStateOf(cid) }
    val chapterIndex = chapters.indexOfFirst { it.chapterId == currentCid }.coerceAtLeast(0)

    var mode by remember { mutableStateOf(ReaderPrefs.mode(db)) }          // paged | scroll
    var rtl by remember { mutableStateOf(ReaderPrefs.rtl(db)) }
    var spread by remember { mutableStateOf(ReaderPrefs.spread(db)) }
    var keepOn by remember { mutableStateOf(ReaderPrefs.keepScreenOn(db)) }
    var showBar by remember { mutableStateOf(true) }
    var showSheet by remember { mutableStateOf(false) }

    // --- 即時預載：preflow 每次 emit 都 bump imgTick → pages 重查 DB 見新頁 ---
    var prog by remember { mutableStateOf(ChapterPreloader.Prog(true, 0, 0)) }
    var imgTick by remember { mutableStateOf(0) }
    LaunchedEffect(currentCid) {
        prog = ChapterPreloader.Prog(true, 0, 0)
        imgTick++
        ChapterPreloader.preflow(slug, currentCid).collect { p -> prog = p; imgTick++ }
    }

    val pages = remember(currentCid, imgTick) {
        db.allImages(slug, currentCid)
            .filter { it.status == "done" && it.path != null }
            .map { File(it.path!!) }
    }
    val totalPages = maxOf(prog.total, pages.size)

    // --- 跨章節無縫（用戶要求：無縫無感睇到完、能跨章節）---
    val nextCid = chapters.getOrNull(chapterIndex + 1)?.chapterId
    val prevCid = chapters.getOrNull(chapterIndex - 1)?.chapterId
    var nextProg by remember { mutableStateOf(ChapterPreloader.Prog(true, 0, 0)) }
    var nextStartedFor by remember { mutableStateOf("") }
    // 切章進行中 → 屏蔽尾卡自動接續（防止 pager 舊 page index clamp 誤觸發跳章）
    val switching = remember { mutableStateOf(false) }
    val chapterComplete = pages.isNotEmpty() && !prog.rendering
    // 成話爆落策略下冇「首 N 頁齊」概念：下一章有第 1 頁到手就可以無縫接
    val nextReady = nextProg.done > 0

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

    val tailCard = nextCid != null && pages.isNotEmpty()
    val displayCount = units.size + if (tailCard) 1 else 0
    // pageCount lambda 要讀「最新」值 → rememberUpdatedState 防 lambda 擷住舊 units
    val unitsCount by rememberUpdatedState(units.size)
    val tailCount by rememberUpdatedState(if (tailCard) 1 else 0)
    val pagerState = rememberPagerState(pageCount = { unitsCount + tailCount })
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // --- 進度記憶：入章恢復 readPos；閱讀中 300ms debounce 持續保存 ---
    // mode 入 key：翻頁↔流水切換都要重新行恢復邏輯 → 落返當前進度（唔會彈返第 1 頁）
    val pendingRestore = remember(currentCid, mode) { db.readPos(slug, currentCid) }
    var restoreDone by remember(currentCid, mode) { mutableStateOf(pendingRestore <= 0) }
    LaunchedEffect(currentCid, mode) {
        showBar = true
        if (pendingRestore <= 0) {
            // 先定位（壓住 clamp flash-frame），先至解除 switching 屏蔽
            if (mode == "paged") pagerState.scrollToPage(0) else listState.scrollToItem(0)
            restoreDone = true
            switching.value = false
        }
    }
    LaunchedEffect(pages.size, currentCid, prog.rendering, mode) {
        // 等 readPos 嗰頁真係備好先跳（即時預載初期頁數仲少）
        if (!restoreDone && pages.isNotEmpty()) {
            if (pages.size > pendingRestore) {
                val pageIdx = if (useSpread) pendingRestore / 2 else pendingRestore
                if (mode == "paged")
                    pagerState.scrollToPage(pageIdx.coerceIn(0, (units.size - 1).coerceAtLeast(0)))
                else
                    listState.scrollToItem(pendingRestore.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
                restoreDone = true
            } else if (!prog.rendering) {
                // readPos 超出呢章實際頁數（站點削頁/清單有變）→ 停落最尾一页
                if (mode == "paged")
                    pagerState.scrollToPage((units.size - 1).coerceAtLeast(0))
                else
                    listState.scrollToItem((pages.size - 1).coerceAtLeast(0))
                restoreDone = true
            }
        }
        // 只有恢復真正完成（企穩喺目標頁）先解除屏蔽；等頁期間保持 true 擋住誤觸發
        if (restoreDone) switching.value = false
    }

    val curPos = rememberUpdatedState(
        if (mode == "paged") pagerState.currentPage * (if (useSpread) 2 else 1)
        else listState.firstVisibleItemIndex
    )

    // --- 後台預載下一章（2026-11-17 用戶改指令）---
    // 舊版：本章淨係備齊首 N 頁就開火下一章 → 搶資源 + 「睇5頁就轉章」觀感。
    // 新版：要嘛本章真係備齊（預載器而家只喺齊頁/限速放棄先報 rendering=false），
    // 要嘛讀者已接近章尾（最後 4 頁）先悄悄預備 — 成話優先、跨章唔死等。
    LaunchedEffect(currentCid) {
        nextProg = ChapterPreloader.Prog(true, 0, 0)
        nextStartedFor = ""
    }
    LaunchedEffect(currentCid, pages.size, prog.rendering, curPos.value, totalPages) {
        val nc = nextCid ?: return@LaunchedEffect
        if (nextStartedFor == nc) return@LaunchedEffect          // 呢章已經開咗預載
        val nearEnd = totalPages > 0 && curPos.value >= totalPages - 4
        if (!((!prog.rendering && pages.isNotEmpty()) || (pages.isNotEmpty() && nearEnd)))
            return@LaunchedEffect
        nextStartedFor = nc
        scope.launch {
            ChapterPreloader.preflow(slug, nc).collect { p ->
                // nc 仲係「下一章」先更新進度；切章後舊收集器自動靜默、繼續背景落圖
                val cur = chapters.indexOfFirst { it.chapterId == currentCid }
                if (chapters.getOrNull(cur + 1)?.chapterId == nc) nextProg = p
            }
        }
    }
    LaunchedEffect(currentCid) {
        androidx.compose.runtime.snapshotFlow { curPos.value }
            .collect {
                kotlinx.coroutines.delay(300)
                // 切章進行中唔好存：pager 跨章瞬間會 clamp 去舊 index（flash-frame），
                // 誤存會污染新章 readPos → 開章即尾頁 → 自動接連跳（實測踩過）
                if (!switching.value) db.saveReadPos(slug, currentCid, curPos.value)
            }
    }

    // 記錄「呢部漫畫讀到邊章」（詳情頁「繼續閱讀」按鈕用）
    LaunchedEffect(slug, currentCid) { db.setMeta("last_read:$slug", currentCid) }

    // 切章統一入口：先存走而家嗰章嘅 readPos（跨章回來時無縫恢復位置）
    val gotoChapter: (String) -> Unit = { id ->
        runCatching { db.saveReadPos(slug, currentCid, curPos.value) }
        switching.value = true
        currentCid = id
    }
    val goNext: () -> Unit = {
        if (mode == "paged") {
            if (pagerState.currentPage < displayCount - 1)
                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
        } else {
            if (pages.isNotEmpty() && listState.firstVisibleItemIndex < pages.size - 1)
                scope.launch { listState.animateScrollToItem(listState.firstVisibleItemIndex + 1) }
            // 本章未備齊唔准跳：留喺尾頁畀預載器繼續補（用戶：睇晒成話先去下一章）
            else if (nextCid != null && chapterComplete) gotoChapter(nextCid!!)
        }
    }
    val goPrev: () -> Unit = {
        if (mode == "paged") {
            if (pagerState.currentPage > 0)
                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
            else if (prevCid != null) gotoChapter(prevCid!!)   // 第一頁← = 無縫回上一章（readPos 落返尾頁）
        } else {
            if (listState.firstVisibleItemIndex > 0)
                scope.launch { listState.animateScrollToItem(listState.firstVisibleItemIndex - 1) }
            else if (prevCid != null) gotoChapter(prevCid!!)
        }
    }

    // 自動接下一章（翻頁）：停落尾卡 + 本章齊頁 + 下一章備好 → 無縫切過去
    LaunchedEffect(pagerState.settledPage, mode, units.size, chapterComplete,
        nextProg.done, nextProg.error, switching.value) {
        if (mode == "paged" && !switching.value && tailCard && chapterComplete &&
            pagerState.settledPage == units.size && (nextReady || nextProg.error != null)
        ) gotoChapter(nextCid!!)
    }
    // 自動接下一章（流水）：footer 可見 + 本章齊頁 + 下一章備好
    LaunchedEffect(mode, currentCid, pages.size, chapterComplete,
        nextProg.done, nextProg.error, switching.value) {
        if (mode != "scroll" || switching.value || !chapterComplete || nextCid == null)
            return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }.collect { idx ->
            if (idx >= pages.size && (nextReady || nextProg.error != null))
                gotoChapter(nextCid!!)
        }
    }

    BackHandler { onBack() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (pages.isEmpty()) {
            // 即時預載中（或載入失敗）— 展示實時進度而非死頁
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (prog.error != null) {
                    Text(prog.error ?: "載入失敗", color = Color.White,
                        style = MaterialTheme.typography.bodyLarge)
                } else {
                    Text("即時載入中…", color = Color.White,
                        style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))
                    if (totalPages > 0) {
                        LinearProgressIndicator(
                            progress = { prog.done.toFloat() / totalPages },
                            color = Color.White, trackColor = Color.DarkGray,
                            modifier = Modifier.width(220.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("已備 ${prog.done}/$totalPages 頁（成話下載中，備好一頁即刻睇一頁）",
                            color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                    } else CircularProgressIndicator(color = Color.White)
                }
                Spacer(Modifier.height(16.dp))
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
                item {
                    if (nextCid != null) NextChapterCard(
                        title = chapters.getOrNull(chapterIndex + 1)?.title,
                        complete = chapterComplete,
                        done = if (chapterComplete) nextProg.done else prog.done,
                        total = if (chapterComplete) nextProg.total else totalPages,
                        error = if (chapterComplete) nextProg.error else prog.error,
                        fillHeight = false,
                        modifier = Modifier.fillMaxWidth()
                            .clickable { if (chapterComplete) gotoChapter(nextCid!!) },
                    )
                    else if (!chapterComplete)
                        Text("本章背景加載緊 ${prog.done}/$totalPages 頁…",
                            color = Color.LightGray, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            textAlign = TextAlign.Center)
                    else
                        Text("—— 最後一章 · 全書完 ——",
                            color = Color.LightGray, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            textAlign = TextAlign.Center)
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
                val unit = units.getOrNull(page)
                if (unit == null) {
                    // 合成尾卡：下一章入口（背景預載進度實時顯示）
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        NextChapterCard(
                            title = chapters.getOrNull(chapterIndex + 1)?.title,
                            complete = chapterComplete,
                            done = if (chapterComplete) nextProg.done else prog.done,
                            total = if (chapterComplete) nextProg.total else totalPages,
                            error = if (chapterComplete) nextProg.error else prog.error,
                            fillHeight = true,
                            // 未備齊呢話 = 尾卡只做進度板，唔係跳章出口（用戶：睇晒成話先轉）
                            modifier = Modifier.fillMaxSize()
                                .clickable {
                                    if (nextCid != null && chapterComplete) gotoChapter(nextCid!!)
                                },
                        )
                    }
                    return@HorizontalPager
                }
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

        // 底部進度條（用戶要求：見到進度 ＋ 拖控跳頁；翻頁/流水都支援）
        // 2026-11-17 用戶追加：「拖拉時要實時更新當時嘅畫面」→
        // onValueChange 期間即時 snapTo（唔使等放手指）；lastSeek 去重避免每像素都掃。
        // 2026-11-17 用戶追加：「滑杆刻度 = 當話實際總頁數（30 頁就 30），唔好用緩存決定」→
        // 量程改用 totalPages（站內 badge 總頁數），拖到未落盤嘅頁先 clamp 去已備尾頁，
        // 背景補落啲頁會繼續追前（拖尾位 = 章尾），唔再係「得幾多頁就拖幾長」
        // 滑杆拖曳狀態（頁碼藥丸共用：拖嘅時候即時顯示目標頁）
        var dragF by remember { mutableStateOf<Float?>(null) }
        var lastSeek by remember { mutableStateOf(-1) }
        if (totalPages > 1) {
            val scale = totalPages - 1
            val shown = dragF
                ?: (curPos.value.toFloat() / scale).coerceIn(0f, 1f)
            val seek: (Float) -> Unit = { f ->
                val target = (f * scale).roundToInt()
                    .coerceIn(0, (pages.size - 1).coerceAtLeast(0))   // 未落嘅頁 clamp 去已備尾
                val pi = if (mode == "paged" && useSpread) target / 2 else target
                if (pi != lastSeek) {
                    lastSeek = pi
                    scope.launch {
                        if (mode == "paged") pagerState.scrollToPage(pi)  // scrollToPage=即時跳（ animate 先有動畫）
                        else listState.scrollToItem(target)
                    }
                }
            }
            Surface(
                color = Color.Black.copy(alpha = 0.42f),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            ) {
                Slider(
                    value = shown,
                    onValueChange = { dragF = it; seek(it) },
                    onValueChangeFinished = {
                        dragF?.let { seek(it) }
                        dragF = null; lastSeek = -1
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White.copy(alpha = 0.9f),
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                    ),
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
            }
        }

        // 頁碼藥丸（用戶：「要顯示當前話的第幾頁」）：翻頁/流水都常駐，
        // 分母 = 呢話/卷實際總頁（唔係成部漫畫）；拖滑杆即時預覽目標頁。
        if (pages.isNotEmpty() && totalPages > 0) {
            val onTail = mode == "paged" && pagerState.currentPage >= units.size
            val shownPage = dragF?.let {
                ((it * (totalPages - 1)).roundToInt() + 1).coerceIn(1, totalPages)
            } ?: (curPos.value + 1).coerceIn(1, totalPages)
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp),
            ) {
                Text(
                    if (!onTail) "第 $shownPage / $totalPages 頁"
                    else "下一章 · ${chapters.getOrNull(chapterIndex + 1)?.title ?: ""}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        .widthIn(max = 260.dp),
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
                                buildString {
                                    append(chapters.getOrNull(chapterIndex)?.title ?: "")
                                    append("　·　第${curPos.value + 1}/${totalPages}頁")
                                    if (prog.rendering && prog.done < totalPages)
                                        append("　·　成話下載中 ${prog.done}/$totalPages")
                                    // 本地緩衝：而家呢頁之後已備好幾多頁（翻頁零等待實況）
                                    val ahead = (pages.size - 1 - curPos.value).coerceAtLeast(0)
                                    append("　·　後面已備 $ahead 頁")
                                    prog.error?.let { append("　·　$it") }
                                },
                                color = Color.LightGray, style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            enabled = chapterIndex > 0,
                            onClick = { if (chapterIndex > 0) gotoChapter(chapters[chapterIndex - 1].chapterId) },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上一章",
                                tint = if (chapterIndex > 0) Color.White else Color.Gray)
                        }
                        IconButton(
                            enabled = chapterIndex < chapters.size - 1,
                            onClick = { if (chapterIndex < chapters.size - 1) gotoChapter(chapters[chapterIndex + 1].chapterId) },
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

/**
 * 跨章節尾卡（翻頁合成頁 / 流水 footer）：實時顯示下一章背景預載進度，
 * 備齊即自動接上；本章未齊頁時顯示本章補頁狀態並提示。
 */
@Composable
private fun NextChapterCard(
    title: String?, complete: Boolean, done: Int, total: Int,
    error: String?, fillHeight: Boolean, modifier: Modifier = Modifier,
) {
    val box = if (fillHeight) modifier.fillMaxSize()
              else modifier.fillMaxWidth().height(170.dp)
    Column(
        box.padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!complete) {
            CircularProgressIndicator(
                color = Color.White, modifier = Modifier.size(26.dp), strokeWidth = 2.dp,
            )
            Spacer(Modifier.height(10.dp))
            Text("本章背景補頁中…", color = Color.LightGray,
                style = MaterialTheme.typography.bodySmall)
            if (total > 0) Text("已備 $done/$total 頁",
                color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            Text("備齊晒成話先至通去下一章",
                color = Color.Gray, style = MaterialTheme.typography.labelSmall)
        } else {
            Text("下 一 章", color = Color.Gray, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            Text(title ?: "下一話", color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            when {
                error != null -> Text("$error\n撳一下照去，入面會重試",
                    color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center)
                total > 0 && done < total -> {
                    LinearProgressIndicator(
                        progress = { done.toFloat() / total },
                        color = Color.White, trackColor = Color.DarkGray,
                        modifier = Modifier.width(180.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("下一章後台備緊 $done/$total 頁 · 備齊自動接上",
                        color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                }
                done > 0 -> Text("下一章已備 $done 頁 · 撳一下即刻繼續",
                    color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                else -> Text("下一章準備緊…", color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(14.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = Color.White, modifier = Modifier.size(34.dp))
        }
    }
}
