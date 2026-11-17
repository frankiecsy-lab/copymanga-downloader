package com.mangacopy.app.download

import android.util.Log
import com.mangacopy.app.data.AppConfig
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
 *   renderChapter onPartial 一有新頁 URL 即時入 DB → 即刻開始逐頁落 .webp
 *   （首 firstN 頁通常幾秒內到齊 → 閱讀器即刻可以睇，其餘背景補落）。
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

    /** 預載設定：首幾頁即開讀（kv prewebp_n，默認 5，設置可改）。 */
    fun firstN(): Int = db.getMeta("prewebp_n", "5")?.toIntOrNull()?.coerceIn(1, 50) ?: 5

    /**
     * in-flight 防重：同一章只容許一條 active 下載循環
     * （閱讀器同時 collect 本章 + 後台預載下一章、跨章反覆進出，都唔會雙寫同一頁）。
     * 已 active → 變被動輪詢模式（只報進度）。
     */
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun preflow(slug: String, cid: String): Flow<Prog> = callbackFlow {
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
                // 被動模式：有人（另一條 preflow / 下載引擎）落緊圖 → 我只輪詢報進度
                if (!mine || DownloadEngine.isRunning) {
                    while (true) {
                        val all = db.allImages(slug, cid)
                        val exp = db.getChapter(slug, cid)?.expectedPages ?: 0
                        trySend(Prog(true, all.count { it.status == "done" },
                            maxOf(all.size, exp)))
                        val st = db.getChapter(slug, cid)?.status
                        if (st == "done" || st == "error") break
                        if (!DownloadEngine.isRunning &&
                            (!active.contains(key) || db.pendingImages(slug, cid).isEmpty())
                        ) break
                        delay(600)
                    }
                    val all = db.allImages(slug, cid)
                    trySend(Prog(false, all.count { it.status == "done" }, all.size))
                    return@launch
                }

                val comic = db.getComic(slug) ?: return@launch
                val comicDir = Storage.existingComicDir(db.firstImagePath(slug))
                    ?: Storage.newComicDir(comic).also { it.mkdirs() }
                val destDir: File = Storage.chapterDir(comicDir, ch.title)

                // 1) 冇頁 / 缺頁 → 渲染（onPartial 即時入 DB，下載循環即刻有嘢落）
                var imgs = db.allImages(slug, cid)
                val exp = db.getChapter(slug, cid)?.expectedPages ?: 0
                if (imgs.isEmpty() || (exp > 0 && imgs.size < exp)) {
                    trySend(Prog(true, imgs.count { it.status == "done" }, exp))
                    val rendered = withTimeoutOrNull(150_000L) {
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
                    if (rendered != null && rendered.urls.isNotEmpty()) {
                        db.addImages(slug, cid, rendered.urls)
                        rendered.expectedTotal?.let { db.setChapterExpected(slug, cid, it) }
                    }
                }
                imgs = db.allImages(slug, cid)
                if (imgs.isEmpty()) {
                    trySend(Prog(false, 0, 0, "呢章載入唔到（站點可能維護中）— 稍後重試"))
                    return@launch
                }

                // 2) 逐頁落圖直到齊頁（error 頁都重試多一次；連續 4 頁失敗 = 網絡問題早停）
                var consecFail = 0
                while (true) {
                    val img = imgs.firstOrNull { it.status != "done" && it.url != null }
                        ?: db.allImages(slug, cid).firstOrNull { it.status != "done" && it.url != null }
                        ?: break
                    try {
                        val r = ImageDownloader.downloadImage(img.url!!, destDir, img.pageIndex)
                        db.markImageDone(slug, cid, img.pageIndex, r.path.absolutePath, r.sha256, r.size)
                        consecFail = 0
                        Log.d("MCPRE", "p${img.pageIndex + 1} ✓ ${r.size}B")
                    } catch (e: Exception) {
                        db.markImageError(slug, cid, img.pageIndex)
                        Log.w("MCPRE", "p${img.pageIndex + 1} ✗ ${e.message}")
                        if (++consecFail >= 4) {
                            emitState("連續 $consecFail 頁下載失敗 — 網絡/站點問題"); break
                        }
                    }
                    imgs = db.allImages(slug, cid)
                    emitState(null)
                    delay(AppConfig.IMAGE_FETCH_DELAY_MS)
                }

                // 3) 齊頁 → 標 done + 補產物（同引擎收尾一致）
                val allImgs = db.allImages(slug, cid)
                val expected = db.getChapter(slug, cid)?.expectedPages ?: 0
                if (allImgs.isNotEmpty() && allImgs.all { it.status == "done" } &&
                    (expected == 0 || allImgs.size >= expected)
                ) {
                    db.setChapterStatus(slug, cid, "done", allImgs.size)
                    Outputs.ensureOutputs(db, slug, cid, comicDir)
                }
                // 收尾必須 emit rendering=false —— 閱讀器 chapterComplete /
                // 跨章自動接續全靠呢個最終態（漏咗會永遠卡「背景加載中」）
                trySend(Prog(false, allImgs.count { it.status == "done" },
                    maxOf(allImgs.size, expected)))
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
