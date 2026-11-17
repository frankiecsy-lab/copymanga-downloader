package com.mangacopy.app.download

import android.util.Log
import com.mangacopy.app.data.AppConfig
import com.mangacopy.app.data.Db
import com.mangacopy.app.data.Storage
import com.mangacopy.app.source.CopyMangaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 下載編排器 — 1:1 移植 cm/engine.py 狀態機：
 *
 *   detail（章節清單）→ 逐章：渲染圖片 URL → 逐頁下載 → 核對頁數 → 產物（webp/cbz）
 *
 * 每個工作單元完成即寫入 SQLite；停止旗標喺每章、每頁之間都檢查。
 * 停止只係令循環停下 — 下次啟動會重讀 DB、跳過所有 done，零丟失零重複。
 *
 * v2（用戶要求改造）：
 *   - 隊列式 UiState：每部漫畫一行（名 + done/總章 + 當前章 + 頁進度），UI 一行 + bar 顯示
 *   - 日誌流廢除（改 Logcat tag MCDL）
 *   - 章節渲染 2.5 分鐘硬超時；**連續 3 章渲染失敗 = 熔斷**（站點維護/被封時唔會
 *     逐章空轉幾個鐘頭，用戶睇落「永远下载中」就係呢個）
 *   - start(slugs=null) = 按 queue_order 自動取隊列（下載 tab「開始下載」= 全部續傳）
 */
object DownloadEngine {

    data class QueueRow(
        val slug: String, val name: String,
        val chDone: Int, val chTotal: Int,
        val curChapter: String = "", val pageDone: Int = 0, val pageTotal: Int = 0,
        val active: Boolean = false,
    )

    data class UiState(
        val running: Boolean = false,
        val queue: List<QueueRow> = emptyList(),
        val lastError: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    @Volatile private var stopRequested = false
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db get() = Db.get()

    val isRunning: Boolean get() = _state.value.running

    private fun log(msg: String) = Log.d("MCDL", msg)

    /**
     * @param slugs null = 自動隊列（有 pending/error 嘅漫畫，按 queue_order）
     * @param onlyCids 非空 = 只下載呢啲章節（批次揀選）；空 = 全部 pending。
     */
    fun start(slugs: List<String>? = null, checkUpdates: Boolean = false, onlyCids: Set<String> = emptySet()) {
        if (job?.isActive == true) return
        val list = slugs ?: db.comicsNeedingWork()
        if (list.isEmpty()) return
        stopRequested = false
        _state.update {
            it.copy(
                running = true, lastError = null,
                queue = list.map { s ->
                    val p = db.comicProgress(s)
                    QueueRow(s, db.getComic(s)?.displayName ?: s, p.chDone, p.chTotal)
                },
            )
        }
        job = scope.launch { run(list, checkUpdates, onlyCids) }
    }

    fun requestStop() { stopRequested = true }

    private fun patchRow(slug: String, f: (QueueRow) -> QueueRow) =
        _state.update { s -> s.copy(queue = s.queue.map { if (it.slug == slug) f(it) else it }) }

    private fun refreshRow(slug: String) = patchRow(slug) {
        val p = db.comicProgress(slug)
        it.copy(chDone = p.chDone, chTotal = p.chTotal)
    }

    private suspend fun run(slugs: List<String>, checkUpdates: Boolean, onlyCids: Set<String>) {
        try {
            if (onlyCids.isNotEmpty()) log("指定下載 ${onlyCids.size} 章")
            for (slug in slugs) {
                if (stopRequested) break
                val comic = db.getComic(slug) ?: continue
                patchRow(slug) { it.copy(active = true) }
                try {
                    processComic(slug, comic.displayName, comic.serialStatus ?: "", checkUpdates, onlyCids)
                } catch (e: Exception) {
                    db.setComicStatus(slug, "error")
                    log("$slug 出錯：${e.message}")
                    _state.update { it.copy(lastError = "${comic.displayName}：${e.message}") }
                } finally {
                    refreshRow(slug)
                    patchRow(slug) { it.copy(active = false, curChapter = "", pageDone = 0, pageTotal = 0) }
                }
            }
        } finally {
            _state.update { it.copy(running = false, queue = it.queue.map { r -> r.copy(active = false, curChapter = "", pageDone = 0, pageTotal = 0) }) }
        }
    }

    /** 連續渲染失敗熔断閾值（站點維護/封波時唔好空轉成日）。 */
    private const val RENDER_FAIL_LIMIT = 3

    private suspend fun processComic(
        slug: String, name: String, serialStatus: String, checkUpdates: Boolean,
        onlyCids: Set<String> = emptySet(),
    ) {
        // 目錄：有檔案就沿用現有目錄（避免孤兒檔）；全新下載用 <狀態>/<名 - 作者>
        val comicDir = Storage.existingComicDir(db.firstImagePath(slug))
            ?: Storage.newComicDir(db.getComic(slug)!!).also { it.mkdirs() }

        // 0) 上次 error 嘅章節重設為 pending（唔使重新渲染）
        db.resetErrorChapters(slug)

        // 1) 章節清單：首次必定渲染；檢查更新模式永遠重新渲染（addChapters 冪等，
        //    舊 done 章節狀態唔動，只有真新章節以 pending 入隊）
        val existing = db.allChapters(slug)
        if (existing.isEmpty() || checkUpdates) {
            log(if (checkUpdates) "檢查新章節…" else "載入章節列表…")
            val detail = CopyMangaSource.detail(slug)
            db.upsertComic(slug, synopsis = detail.synopsis, serialStatus = detail.serialStatus)
            val n = db.addChapters(slug, detail.chapters.map { Triple(it.chapterId, it.title, it.url) })
            if (existing.isEmpty()) db.setComicStatus(slug, "chapters_done")
            log("$slug 共 $n 章" + if (checkUpdates) "（新增 ${n - existing.size}）" else "")
            refreshRow(slug)
        }

        // 2) 循環清 pending 章節；連續 RENDER_FAIL_LIMIT 章渲染失敗 → 熔斷（留低嘅
        //    pending 下次再試，唔會永遠卡住）
        if (onlyCids.isNotEmpty()) db.resetChaptersToPending(slug, onlyCids)
        var renderFails = 0
        while (true) {
            if (stopRequested) return
            val ch = db.pendingChapters(slug).firstOrNull {
                onlyCids.isEmpty() || it.chapterId in onlyCids
            } ?: break
            log("· ${ch.title}")
            patchRow(slug) { it.copy(curChapter = ch.title ?: "", pageDone = 0, pageTotal = 0) }
            val outcome = try {
                processChapter(slug, comicDir, ch)
            } catch (e: Exception) {
                db.setChapterStatus(slug, ch.chapterId, "error")
                log("  章節出錯：${e.message}")
                "fail"
            }
            refreshRow(slug)
            if (outcome == "fail") {
                renderFails++
                if (renderFails >= RENDER_FAIL_LIMIT) {
                    _state.update {
                        it.copy(lastError = "$name：連續 $renderFails 章載入失敗（站點可能維護中）— 已自動暫停，稍後再撳「開始下載」重試")
                    }
                    log("$name 連續 $renderFails 章渲染失敗 — 熔斷暫停")
                    return
                }
            } else renderFails = 0
        }

        // 3) 全部章節 done → 漫畫完成
        val remaining = db.allChapters(slug).count { it.status != "done" }
        if (remaining == 0) {
            db.setComicStatus(slug, "done")
            log("$name 完成")
        }

        // 3b) 確保每個 done 章節嘅所選產物（cbz/webp）存在（冪等；切換輸出模式後先後來都得）
        for (ch in db.allChapters(slug)) {
            if (ch.status != "done") continue
            Outputs.ensureOutputs(db, slug, ch.chapterId, comicDir).forEach { log(it) }
        }
    }

    /** @return "ok" 或者 "fail"（渲染/核對失敗 — 供熔斷計數） */
    private suspend fun processChapter(slug: String, comicDir: File, ch: Db.Chapter): String {
        val cid = ch.chapterId
        // 冇頁面資料、或上次渲染少咗（缺頁）→ 重新渲染追補；齊 → 直接補下載
        val imgs = db.allImages(slug, cid)
        val expNow = db.getChapter(slug, cid)?.expectedPages ?: 0
        if (imgs.isEmpty() || (expNow > 0 && imgs.size < expNow)) {
            // 渲染硬超時 2.5 分鐘：WAF 跳轉鏈/死頁唔可以拖死成條隊列
            val chapterUrl = ch.url ?: return "fail"
            val pages = withTimeoutOrNull(150_000L) {
                runCatching { CopyMangaSource.chapterImageUrls(chapterUrl) }.getOrNull()
            }
            val urls = pages?.urls ?: emptyList()
            if (urls.isEmpty()) {
                // 空渲染/超時 — 通常網站維護中；標 error（唔係 done！）等之後重試
                db.setChapterStatus(slug, cid, "error")
                log("  章節渲染為空或超時 — 稍後重試")
                return "fail"
            }
            val n = db.addImages(slug, cid, urls)
            pages!!.expectedTotal?.let { db.setChapterExpected(slug, cid, it) }
            val expected = pages.expectedTotal ?: 0
            if (expected > 0 && urls.size < expected)
                log("  抓到 ${urls.size} / 預期 $expected 頁（可能未載完）")
            else log("  $n 頁")
            delay(AppConfig.CHAPTER_RENDER_DELAY_MS)
        }

        // 逐頁下載到 <漫畫dir>/<章節dir>/NNN.webp（每頁之間檢查停止旗標）
        val destDir = Storage.chapterDir(comicDir, ch.title)
        while (true) {
            if (stopRequested) return "ok"
            val img = db.pendingImages(slug, cid).firstOrNull() ?: break
            try {
                val r = ImageDownloader.downloadImage(img.url!!, destDir, img.pageIndex)
                db.markImageDone(slug, cid, img.pageIndex, r.path.absolutePath, r.sha256, r.size)
            } catch (e: Exception) {
                db.markImageError(slug, cid, img.pageIndex)
                log("  第${img.pageIndex + 1}頁：${e.message}")
            }
            val all = db.allImages(slug, cid)
            patchRow(slug) {
                it.copy(
                    pageDone = all.count { i -> i.status == "done" },
                    pageTotal = all.size,
                )
            }
            delay(AppConfig.IMAGE_FETCH_DELAY_MS)
        }

        // 收尾：核對頁數 — 缺頁當 error 等重試，唔會靜默「完成」（同桌面版行為一致）
        val allImgs = db.allImages(slug, cid)
        val failed = allImgs.count { it.status != "done" }
        val expected = db.getChapter(slug, cid)?.expectedPages ?: 0
        return when {
            failed == 0 && expected > 0 && allImgs.size < expected -> {
                db.setChapterStatus(slug, cid, "error", allImgs.size)
                log("  核對不符：${allImgs.size} / $expected 頁")
                "fail"
            }
            failed == 0 -> {
                db.setChapterStatus(slug, cid, "done", allImgs.size)
                Outputs.ensureOutputs(db, slug, cid, comicDir).forEach { log(it) }
                "ok"
            }
            else -> {
                db.setChapterStatus(slug, cid, "error", allImgs.size)
                log("  缺 $failed 頁")
                "fail"
            }
        }
    }
}
