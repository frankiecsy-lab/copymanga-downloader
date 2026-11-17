package com.mangacopy.app.download

import android.util.Log
import com.mangacopy.app.data.AppConfig
import com.mangacopy.app.data.Db
import com.mangacopy.app.source.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 倉庫更新器（用戶 2026-11-18 定稿語義）：「更新倉庫＝只用來更新 CopyManga 全域
 * 有冇增減漫畫，唔包括章節 — 取一次全站清單，結果係增加了幾多套、減少幾多套」。
 *
 * 原理：本地 `repo` 表係全站 path_word 快照。每輪跑一個 runTs：
 *   ① /comics 分頁（50/頁）循環到 offset ≥ total；
 *   ② 每頁行蓋 runTs 印（repoUpsertRun，新行＝新增）；
 *   ③ 完整跑完先 prune：last_seen ≠ runTs 嘅行 = 站點已下架 → 移除數。
 * 中途中斷/停／error：kv「repo_run」記住 runTs|offset|added，下次撳制由斷點續跑
 * （唔 prune — 半輪快照 prune 會誤報天量「減少」）。
 */
object RepoUpdater {

    data class UiState(
        val running: Boolean = false,
        val fetched: Int = 0,          // 本輪已抓套數
        val total: Int = 0,            // 站點聲稱全站套數
        val added: Int = 0,            // 本輪累計新增
        val pagesDone: Int = 0,
        val lastError: String? = null,
        // 上次完成摘要（kv repo_summary 持久化，跨啟動重現）
        val lastFinishAt: Long = 0,
        val lastAdded: Int = 0,
        val lastRemoved: Int = 0,
        val lastTotal: Int = 0,
    )

    private const val KV_RUN = "repo_run"
    private const val KV_SUMMARY = "repo_summary"

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    val isRunning get() = _state.value.running

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db get() = Db.get()
    private var job: Job? = null

    /** 設置頁入面還原上次完成摘要（App 重啟後照顯示）。 */
    fun restoreSummary() {
        val s = db.getMeta(KV_SUMMARY)?.split('|')?.takeIf { it.size >= 4 } ?: return
        _state.update {
            it.copy(
                lastFinishAt = s[0].toLongOrNull() ?: 0,
                lastAdded = s[1].toIntOrNull() ?: 0,
                lastRemoved = s[2].toIntOrNull() ?: 0,
                lastTotal = s[3].toIntOrNull() ?: 0,
            )
        }
    }

    fun start() {
        if (job?.isActive == true) return
        // 斷點續跑：有未完成嘅 run 就跟返同一個 runTs 由舊 offset 繼續
        val resume = db.getMeta(KV_RUN)?.split('|')?.takeIf { it.size >= 3 }
        val runTs = resume?.get(0) ?: System.currentTimeMillis().toString()
        var offset = resume?.get(1)?.toIntOrNull() ?: 0
        var added = resume?.get(2)?.toIntOrNull() ?: 0
        var total = db.repoCount()
        _state.update {
            it.copy(running = true, fetched = offset, total = total, added = added,
                pagesDone = offset / AppConfig.LIST_LIMIT, lastError = null)
        }
        job = scope.launch {
            var completed = false
            try {
                while (true) {
                    val p = SourceRegistry.default.browse(offset, null)
                    added += db.repoUpsertRun(
                        p.items.map { it.pathWord to it.name }, runTs)
                    total = p.total ?: total
                    val before = offset
                    offset += AppConfig.LIST_LIMIT
                    _state.update { it.copy(fetched = offset.coerceAtMost(total),
                        total = total, added = added, pagesDone = it.pagesDone + 1) }
                    db.setMeta(KV_RUN, "$runTs|$offset|$added")
                    // 站點無端端返空頁（未夠 total）→ 當到尾收線，唔好死循环燒網
                    if (p.items.isEmpty() && before > 0) break
                    if (total > 0 && offset >= total) break
                    delay(AppConfig.LIST_DELAY_MS)
                }
                completed = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e   // stop()/殺程序：kv repo_run 保留，下次續跑
            } catch (e: Exception) {
                Log.w("MCREPO", "倉庫更新中途中斷：${e.message}")
                _state.update { it.copy(lastError = e.message ?: "網絡錯誤") }
            } finally {
                if (completed) {
                    val removed = db.repoPruneRun(runTs)
                    db.setMeta(KV_SUMMARY,
                        "${System.currentTimeMillis()}|$added|$removed|$total")
                    db.setMeta(KV_RUN, "")
                    Log.i("MCREPO", "倉庫更新完成：全站 $total 套，+$added / -$removed")
                    _state.update { it.copy(lastFinishAt = System.currentTimeMillis(),
                        lastAdded = added, lastRemoved = removed, lastTotal = total) }
                }
                _state.update { it.copy(running = false) }
            }
        }
    }

    fun stop() { job?.cancel() }
}
