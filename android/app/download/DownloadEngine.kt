package com.mangacopy.app.download

import com.mangacopy.app.data.AppConfig
import com.mangacopy.app.data.Db
import com.mangacopy.app.data.Storage
import com.mangacopy.app.source.CopyMangaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * 下載編排器 — 1:1 移植 cm/engine.py 狀態機：
 *
 *   detail（章節清單）→ 逐章：渲染圖片 URL → 逐頁下載 → 核對頁數 → 產物（webp/cbz）
 *
 * 每個工作單元完成即寫入 SQLite；停止旗標喺每章、每頁之間都檢查。
 * 停止只係令循環停下 — 下次啟動會重讀 DB、跳過所有 done，零丟失零重複。
 * checkUpdates 模式：對每部漫畫都重新渲染詳情頁，新增章節以 pending 併入再落。
 */
object DownloadEngine {

    data class UiState(
        val running: Boolean = false,
        val comicName: String = "",
        val chapterTitle: String = "",
        val pageDone: Int = 0,
        val pageTotal: Int = 0,
        val log: List<String> = emptyList(),
        val lastError: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    @Volatile private var stopRequested = false
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db get() = Db.get()

    val isRunning: Boolean get() = _state.value.running

    fun start(slugs: List<String>, checkUpdates: Boolean = false) {
        if (job?.isActive == true) return
        stopRequested = false
        _state.update { it.copy(running = true, lastError = null) }
        job = scope.launch { run(slugs, checkUpdates) }
    }

    fun requestStop() { stopRequested = true }

    private fun log(msg: String) {
        _state.update { it.copy(log = (it.log + msg).takeLast(300)) }
    }

    private suspend fun run(slugs: List<String>, checkUpdates: Boolean) {
        try {
            for (slug in slugs) {
                if (stopRequested) break
                val comic = db.getComic(slug) ?: continue
                val name = comic.displayName
                log("▶ $name")
                _state.update { it.copy(comicName = name, chapterTitle = "", pageDone = 0, pageTotal = 0) }
                try {
                    processComic(slug, name, comic.serialStatus ?: "", checkUpdates)
                } catch (e: Exception) {
                    db.setComicStatus(slug, "error")
                    log("  ✗ $name 出錯：${e.message}")
                    _state.update { it.copy(lastError = "$name：${e.message}") }
                }
            }
        } finally {
            _state.update { it.copy(running = false, comicName = "", chapterTitle = "") }
        }
    }

    private suspend fun processComic(slug: String, name: String, serialStatus: String, checkUpdates: Boolean) {
        // 目錄：有檔案就沿用現有目錄（避免孤兒檔）；全新下載用 <狀態>/<名 - 作者>
        val comicDir = Storage.existingComicDir(db.firstImagePath(slug))
            ?: Storage.newComicDir(db.getComic(slug)!!).also { it.mkdirs() }

        // 0) 上次 error 嘅章節重設為 pending（唔使重新渲染）
        db.resetErrorChapters(slug)

        // 1) 章節清單：首次必定渲染；檢查更新模式永遠重新渲染（addChapters 冪等，
        //    舊 done 章節狀態唔動，只有真新章節以 pending 入隊）
        val existing = db.allChapters(slug)
        if (existing.isEmpty() || checkUpdates) {
            log("  " + if (checkUpdates) "檢查新章節…" else "載入章節列表…")
            val detail = CopyMangaSource.detail(slug)
            db.upsertComic(slug, synopsis = detail.synopsis, serialStatus = detail.serialStatus)
            val n = db.addChapters(slug, detail.chapters.map { Triple(it.chapterId, it.title, it.url) })
            if (existing.isEmpty()) db.setComicStatus(slug, "chapters_done")
            val added = (n - existing.size).coerceAtLeast(0)
            log("  共 $n 章" + if (checkUpdates) "（新增 $added）" else "")
        }

        // 2) 循環清 pending 章節（每章都会去 done/error，唔會無限循環）
        while (true) {
            if (stopRequested) return
            val ch = db.pendingChapters(slug).firstOrNull() ?: break
            log("  · ${ch.title}")
            _state.update { it.copy(chapterTitle = ch.title ?: "", pageDone = 0, pageTotal = 0) }
            try {
                processChapter(slug, comicDir, ch)
            } catch (e: Exception) {
                db.setChapterStatus(slug, ch.chapterId, "error")
                log("    ✗ 章節出錯：${e.message}")
            }
        }

        // 3) 全部章節 done → 漫畫完成
        val remaining = db.allChapters(slug).count { it.status != "done" }
        if (remaining == 0) {
            db.setComicStatus(slug, "done")
            log("  ✓ $name 完成")
        }

        // 3b) 確保每個 done 章節嘅所選產物（cbz/webp）存在（冪等；切換輸出模式後先後來都得）
        for (ch in db.allChapters(slug)) {
            if (ch.status != "done") continue
            Outputs.ensureOutputs(db, slug, ch.chapterId, comicDir).forEach { log(it) }
        }
    }

    private suspend fun processChapter(slug: String, comicDir: File, ch: Db.Chapter) {
        val cid = ch.chapterId
        // 冇頁面資料、或上次渲染少咗（缺頁）→ 重新渲染追補；齊 → 直接補下載
        var imgs = db.allImages(slug, cid)
        val expNow = db.getChapter(slug, cid)?.expectedPages ?: 0
        if (imgs.isEmpty() || (expNow > 0 && imgs.size < expNow)) {
            val pages = CopyMangaSource.chapterImageUrls(ch.url)
            val urls = pages.urls
            if (urls.isEmpty()) {
                // 空渲染 — 通常網站維護中；標 error（唔係 done！）等之後重試
                db.setChapterStatus(slug, cid, "error")
                log("    ✗ 章節渲染為空（網站可能維護中）— 稍後重試")
                return
            }
            val n = db.addImages(slug, cid, urls)
            pages.expectedTotal?.let { db.setChapterExpected(slug, cid, it) }
            val expected = pages.expectedTotal ?: 0
            if (expected > 0 && urls.size < expected)
                log("    ⚠ 抓到 ${urls.size} / 預期 $expected 頁（可能未載完）")
            else log("    $n 頁")
            kotlinx.coroutines.delay(AppConfig.CHAPTER_RENDER_DELAY_MS)
        }

        // 逐頁下載到 <漫畫dir>/<章節dir>/NNN.webp（每頁之間檢查停止旗標）
        val destDir = Storage.chapterDir(comicDir, ch.title)
        while (true) {
            if (stopRequested) return
            val img = db.pendingImages(slug, cid).firstOrNull() ?: break
            val total = db.allImages(slug, cid).size
            _state.update { it.copy(pageDone = total - db.pendingImages(slug, cid).size, pageTotal = total) }
            try {
                val r = ImageDownloader.downloadImage(img.url!!, destDir, img.pageIndex)
                db.markImageDone(slug, cid, img.pageIndex, r.path.absolutePath, r.sha256, r.size)
            } catch (e: Exception) {
                db.markImageError(slug, cid, img.pageIndex)
                log("    ✗ 第${img.pageIndex + 1}頁：${e.message}")
            }
            _state.update {
                val all = db.allImages(slug, cid)
                it.copy(
                    pageDone = all.count { i -> i.status == "done" },
                    pageTotal = all.size,
                )
            }
            kotlinx.coroutines.delay(AppConfig.IMAGE_FETCH_DELAY_MS)
        }

        // 收尾：核對頁數 — 缺頁當 error 等重試，唔會靜默「完成」（同桌面版行為一致）
        val allImgs = db.allImages(slug, cid)
        val failed = allImgs.count { it.status != "done" }
        val expected = db.getChapter(slug, cid)?.expectedPages ?: 0
        when {
            failed == 0 && expected > 0 && allImgs.size < expected -> {
                db.setChapterStatus(slug, cid, "error", allImgs.size)
                log("    ⚠ 核對不符：${allImgs.size} / $expected 頁 — 再按「開始下載」可重試")
            }
            failed == 0 -> {
                db.setChapterStatus(slug, cid, "done", allImgs.size)
                if (expected > 0) log("    ✓ 核對 ${allImgs.size} / $expected 頁")
                Outputs.ensureOutputs(db, slug, cid, comicDir).forEach { log(it) }
            }
            else -> {
                db.setChapterStatus(slug, cid, "error", allImgs.size)
                log("    ⚠ 缺 $failed 頁 — 再按「開始下載」可重試")
            }
        }
    }
}
