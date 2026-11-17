package com.mangacopy.app.download

import android.util.Log
import com.mangacopy.app.data.Db
import com.mangacopy.app.source.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 書單清單更新器 — 同「下載」分開嘅獨立工序：
 *   只重新渲染各漫畫詳情頁 → addChapters（冪等，新章節以 pending 入隊、done 唔動），
 *   **唔會自動開落圖片**（要用戶去下載 tab 撳開始）。
 *
 * 觸發點：
 *   1) App 啟動 3 秒後自動跑（收藏 > 0 且引擎空闲）— 無感背景檢查新章節
 *   2) 設置頁「手動更新全部漫畫清單」按鈕
 * state 暴露進度俾書架 topBar / 設置頁顯示；書架卡「+N 新章」徽章
 * 直接讀 comicProgress（pending+error 數），唔使 extra state。
 */
object ListUpdater {

    data class UiState(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val current: String = "",
        val newChapters: Int = 0,     // 本次累計新增章節
        val lastError: String? = null,
        val lastFinishAt: Long = 0,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db get() = Db.get()
    private var job: Job? = null

    val isRunning get() = _state.value.running

    fun start(slugs: List<String>, force: Boolean = false) {
        if (job?.isActive == true || slugs.isEmpty()) return
        // TTL 節流（用戶要求「適當時間自動更新」+ 2026-11-17 ADB 實測：
        // 每次開 App 都全量重 render 死站詳情頁 = 燒 80 秒 WebView、仲同閱讀器搶鎖）：
        // 自動觸發跳過 15 分鐘內更新過嘅漫畫；手動按鈕 force=true 照跑。
        val targets = if (force) slugs else slugs.filter { slug ->
            val ts = db.getMeta("chlist_ts:$slug", "0")?.toLongOrNull() ?: 0L
            System.currentTimeMillis() - ts > 15 * 60_000L
        }
        if (targets.isEmpty()) {
            Log.d("MCLIST", "清單都係新（TTL 15分鐘內），跳過自動更新")
            return
        }
        _state.update { it.copy(running = true, done = 0, total = targets.size,
                                current = "", newChapters = 0, lastError = null) }
        job = scope.launch {
            try {
                for (slug in targets) {
                    _state.update { it.copy(current = db.getComic(slug)?.displayName ?: slug) }
                    try {
                        val n = updateOne(slug)
                        if (n > 0) _state.update { s -> s.copy(newChapters = s.newChapters + n) }
                        Log.i("MCLIST", "$slug 檢查完：新章 $n")
                    } catch (e: Exception) {
                        Log.w("MCLIST", "$slug 檢查失敗：${e.message}")
                        _state.update { it.copy(lastError = "${db.getComic(slug)?.displayName ?: slug}：${e.message}") }
                    }
                    _state.update { it.copy(done = it.done + 1) }
                }
            } finally {
                _state.update { it.copy(running = false, current = "", lastFinishAt = System.currentTimeMillis()) }
            }
        }
    }

    /** 重新渲染詳情頁 → 章節 upsert；返回新增章節數。 */
    private suspend fun updateOne(slug: String): Int {
        val existing = db.allChapters(slug).size
        val d = SourceRegistry.default.detail(slug)
        db.upsertComic(slug, synopsis = d.synopsis, serialStatus = d.serialStatus)
        val n = db.addChapters(slug, d.chapters.map { Triple(it.chapterId, it.title, it.url) })
        db.setMeta("chlist_ts:$slug", System.currentTimeMillis().toString())  // TTL 記時
        return (n - existing).coerceAtLeast(0)
    }
}
