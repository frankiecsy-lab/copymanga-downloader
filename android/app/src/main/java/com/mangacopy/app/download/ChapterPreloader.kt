package com.mangacopy.app.download

import android.util.Log
import com.mangacopy.app.data.Db
import com.mangacopy.app.data.Storage
import com.mangacopy.app.source.WebViewRenderer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 章節即時預載 —「點擊章節 = 即時觀看」嘅核心（用戶要求：無感瀏覽）：
 *
 *   renderChapter onPartial 一有新頁 URL 即時入 DB → 即刻 4 lane 爆落成話晒圖
 *   （2026-11-17 用戶最終指令：唔搞首 N 頁預讀檔，一開波就落齊成話；
 *   第 1 頁好 readers 即刻可以睇，其後頁陸續即到）。
 *
 * 頁面直接 markImageDone 入同一套 DB/images 狀態機 = 同 DownloadEngine 完全互通：
 * 預載過嘅頁下次唔會重落；chapters done 之後 CBZ/WebP 產物照樣 ensure。
 * 下載引擎忙住（引擎 running）時唔搶資源 — 變做被動輪詢顯示進度。
 *
 * Flow 每有新進度 emit 一次 Prog；閱讀器 collect → pages 重查 DB 即見新頁。
 * collect 取消（退出閱讀器）→ job cancel → 渲染/落圖循環即時停。
 */
object ChapterPreloader {

    data class Prog(
        val rendering: Boolean,   // 仲渲染/補落中（未齊頁）
        val done: Int, val total: Int,
        val error: String? = null,
    )

    private val db get() = Db.get()


    /**
     * in-flight 防重：同一章只容許一條 active 下載循環
     * （閱讀器同時 collect 本章 + 後台預載下一章、跨章反覆進出，都唔會雙寫同一頁）。
     * 已 active → 變被動輪詢模式（只報進度）。
     */
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * priorityPage：閱讀器「降落頁」優先落盤（2026-11-17 用戶：「上咗尾頁，
     * 先讀尾頁出來先至自動下載其他頁數」— 跨章回上一章／readPos 喺尾段時，
     * 入章即刻見到的應該係降落嗰頁，唔係由第 1 頁排起、降落頁長期佔位）。
     * 只影響 pending 出隊次序（降落頁排最前，其餘照升序），唔改下載範圍。
     */
    fun preflow(slug: String, cid: String, priorityPage: Int = -1): Flow<Prog> = callbackFlow {
        // 必須 Dispatchers.IO：collect 者（閱讀器 LaunchedEffect）喺主線程，
        // 預設 inherit 會令 OkHttp 同步下載堵死主線程（實測踩過）
        val job = launch(kotlinx.coroutines.Dispatchers.IO) {
            val ch = db.getChapter(slug, cid)
            if (ch == null || ch.url.isNullOrEmpty()) {
                trySend(Prog(false, 0, 0, "呢章仲未載入清單 — 返回再入一次")); return@launch
            }
            val emitState = { err: String? ->
                val all = db.allImages(slug, cid)
                val exp = db.getChapter(slug, cid)?.expectedPages ?: 0
                trySend(Prog(true, all.count { it.status == "done" },
                    maxOf(all.size, exp), err))
            }
            val key = "$slug:$cid"
            val mine = active.add(key)
            Log.d("MCPRE", "preflow $cid（mine=$mine engineRunning=${DownloadEngine.isRunning}）")
            try {
                // 被動模式：有人（另一條 preflow / 下載引擎）落緊圖 → 我只輪詢報進度。
                // 2026-11-17 實測 bug 修正：舊收尾用「pending 空」做完結條件 — 但站點
                // 逐頁滴圖期間未落盤嘅頁都仲未入 DB（pending 都係空）→ 誤報
                // rendering=false，閱讀器誤判本章完成（實測 89 頁瞬間變返 3 頁）。
                // 正解：只認持有者標 done/error、或引擎完仔、或齊頁（done>=expected）。
                if (!mine || DownloadEngine.isRunning) {
                    var idle = 0
                    var lastCount = -1
                    while (true) {
                        val all = db.allImages(slug, cid)
                        val exp = db.getChapter(slug, cid)?.expectedPages ?: 0
                        val doneN = all.count { it.status == "done" }
                        trySend(Prog(true, doneN, maxOf(all.size, exp)))
                        val st = db.getChapter(slug, cid)?.status
                        if (st == "done" || st == "error") break
                        if (!DownloadEngine.isRunning && !active.contains(key) &&
                            (doneN > 0 && (exp == 0 || doneN >= exp))
                        ) break
                        idle = if (all.size > 0 && all.size == lastCount) idle + 1 else 0
                        if (DownloadEngine.isRunning && !active.contains(key) &&
                            st != "done" && st != "error" && idle >= 200
                        ) break   // 引擎亦冇緊呢章（>2 分鐘零進展）→ 如實收工，唔好死等
                        lastCount = all.size
                        delay(600)
                    }
                    val all = db.allImages(slug, cid)
                    val exp = db.getChapter(slug, cid)?.expectedPages ?: 0
                    trySend(Prog(false, all.count { it.status == "done" }, maxOf(all.size, exp)))
                    return@launch
                }

                val comic = db.getComic(slug) ?: return@launch
                val comicDir = Storage.existingComicDir(db.firstImagePath(slug))
                    ?: Storage.newComicDir(comic).also { it.mkdirs() }
                val destDir: File = Storage.chapterDir(comicDir, ch.title)

                suspend fun fetch(url: String, index: Int): Boolean = try {
                    val r = ImageDownloader.downloadImage(url, destDir, index)
                    db.markImageDone(slug, cid, index, r.path.absolutePath, r.sha256, r.size)
                    Log.d("MCPRE", "p${index + 1} ✓ ${r.size}B"); true
                } catch (e: Exception) {
                    db.markImageError(slug, cid, index)
                    Log.w("MCPRE", "p${index + 1} ✗ ${e.message}"); false
                }

                // 2026-11-17 用戶改指令：「先緩存首 N 頁即開讀，之後一直睇一直補，
                // 直到睇晒成話」— 舊版單次 render＋補落就收工：站點逐頁滴圖、render
                // 早停得 5 頁都照報 rendering=false，閱讀器誤判「本章完成」→
                // 睇完頭幾頁即俾尾卡掟去下一章。改外層補頁循環：
                // 重渲染攞新 URL → 落圖 → 复查，未齊頁而閱讀器仍 collect 就一直補。
                var round = 0
                while (true) {
                    // (1) 冇頁 / 缺頁 → 渲染（onPartial 即時入 DB，下載循環即刻有嘢落）
                    var imgs = db.allImages(slug, cid)
                    val exp = db.getChapter(slug, cid)?.expectedPages ?: 0
                    if (imgs.isEmpty() || (exp > 0 && imgs.size < exp)) {
                        trySend(Prog(true, imgs.count { it.status == "done" }, maxOf(imgs.size, exp)))
                        // 2026-11-17 用戶：「下載咗第一頁就顯示，唔好落咗幾十頁先更新」—
                        // 舊版要 render（成話清單，可達幾十秒）完晒先開始落圖，首頁見得太慢。
                        // 新版：drainer 與 render **並行** — onPartial 一有 URL 入 DB，
                        // drainer 即刻 4 lane 開落；第 1 頁由 URL 到手到好盤 <1 秒，
                        // 閱讀器（占位制）即刻見真圖。render 完 drainer 自己啃晒尾數先收工。
                        val renderDone = java.util.concurrent.atomic.AtomicBoolean(false)
                        val rendered = kotlinx.coroutines.coroutineScope {
                            val drainer = launch {
                                while (true) {
                                    val pend = db.pendingImages(slug, cid)
                                        .filter { it.url != null }.sortedBy { it.pageIndex }
                                        // 降落頁優先出隊（穩定排序：priority 抽去最前，其餘保升序）
                                        .sortedBy { if (it.pageIndex == priorityPage) 0 else 1 }
                                    if (pend.isEmpty()) {
                                        if (renderDone.get()) break
                                        delay(150); continue
                                    }
                                    kotlinx.coroutines.coroutineScope {
                                        pend.take(4).map { p -> launch { fetch(p.url!!, p.pageIndex) } }
                                    }
                                    emitState(null)
                                }
                            }
                            val r = withTimeoutOrNull(150_000L) {
                                runCatching {
                                    WebViewRenderer.renderChapter(ch.url) { urls, total ->
                                        // onPartial 喺 IO 協程被同步回調：DB 寫入快、trySend 非阻塞
                                        try {
                                            db.addImages(slug, cid, urls)
                                            total?.let { db.setChapterExpected(slug, cid, it) }
                                            emitState(null)
                                        } catch (e: Exception) { Log.w("MCPRE", "addImages: ${e.message}") }
                                    }
                                }.getOrNull()
                            }
                            renderDone.set(true)
                            drainer.join()
                            r
                        }
                        if (rendered != null && rendered.urls.isNotEmpty()) {
                            db.addImages(slug, cid, rendered.urls)
                            rendered.expectedTotal?.let { db.setChapterExpected(slug, cid, it) }
                        }
                    }
                    imgs = db.allImages(slug, cid)
                    if (imgs.isEmpty()) {          // 站點完全無回應 → _retry 幾輪先放棄
                        if (++round >= 20) {
                            trySend(Prog(false, 0, 0, "呢章載入唔到（站點可能維護中）— 稍後重試"))
                            return@launch
                        }
                        delay(4_000); continue
                    }

                    // (2) 成話爆落（2026-11-17 用戶最終指令：「唔使預讀 3/5/10/15，
                    //     一開波就不斷下載到成話完」）：4 lane 逐頁升序落，1 頁好即刻畀
                    //     閱讀器睇到；error 頁排除喺 pending 外唔會死圈，下一輪
                    //     re-render 會將 error 重置 pending 再試。stall 熔斷 3 連零進展 =
                    //     網絡真死 → rendering=false + error（閱讀器尾卡開逃生門）。
                    run {
                        var stall = 0
                        while (true) {
                            val pend = db.pendingImages(slug, cid)
                                .filter { it.url != null }.sortedBy { it.pageIndex }
                                .sortedBy { if (it.pageIndex == priorityPage) 0 else 1 }
                            if (pend.isEmpty()) break
                            val beforeDone = db.allImages(slug, cid).count { it.status == "done" }
                            kotlinx.coroutines.coroutineScope {
                                pend.take(4).map { p -> launch { fetch(p.url!!, p.pageIndex) } }
                            }
                            emitState(null)
                            stall = if (db.allImages(slug, cid).count { it.status == "done" }
                                        == beforeDone) stall + 1 else 0
                            if (stall >= 3) {
                                val all = db.allImages(slug, cid)
                                trySend(Prog(false, all.count { it.status == "done" },
                                    maxOf(all.size, db.getChapter(slug, cid)?.expectedPages ?: 0),
                                    "連續下載失敗 — 網絡/站點問題，去到尾可手動接下一章"))
                                return@launch
                            }
                        }
                    }

                    // (4) 齊頁 → 標 done + 補產物（同引擎收尾一致）；未齊 → 等站點滴多啲頁再一輪
                    val allImgs = db.allImages(slug, cid)
                    val expected = db.getChapter(slug, cid)?.expectedPages ?: 0
                    val doneN = allImgs.count { it.status == "done" }
                    if (allImgs.isNotEmpty() && allImgs.all { it.status == "done" } &&
                        (expected == 0 || allImgs.size >= expected)
                    ) {
                        db.setChapterStatus(slug, cid, "done", allImgs.size)
                        Outputs.ensureOutputs(db, slug, cid, comicDir)
                        // rendering=false 只喺「成話真係齊頁」呢一刻先 emit —
                        // 閱讀器 chapterComplete／跨章自動接續全靠呢個最終態
                        trySend(Prog(false, doneN, maxOf(allImgs.size, expected)))
                        break
                    }
                    // 2026-11-17 實測修復：呢條路徑（有 pending 但 drainer 冇嘢可落，例如
                    // url 缺失嘅殘行）由頭到尾冇 emit → 閱讀器 prog 永遠停喺初始 0/0，
                    // 副標題誤顯示「成話下載中 0/已備頁數」、量程缺 → readPos 等唔切恢復。
                    // 每輪結尾如實 emit 進度，閱讀器先可以攞到真 total 做恢復定位。
                    trySend(Prog(true, doneN, maxOf(allImgs.size, expected)))
                    if (++round >= 20) {
                        // 站點限速補唔齊 → 如實報錯（閱讀器容許手動逃去下一章），唔准扮完成
                        trySend(Prog(false, doneN, maxOf(allImgs.size, expected),
                            "站點限速，呢章暫時備到 $doneN 頁 — 去到尾可手動接下一章"))
                        break
                    }
                    delay(4_000)   // 站點滴圖中，抖吓再重渲染攞多啲頁
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e   // 退出閱讀器 = 正常取消（2026-11-17 實測「載入中」假象源頭之一）
            } catch (e: Exception) {
                Log.w("MCPRE", "preflow 異常：${e.message}")
                trySend(Prog(false, 0, 0, e.message))
            } finally {
                if (mine) active.remove(key)
            }
        }
        awaitClose { job.cancel() }
    }
}
