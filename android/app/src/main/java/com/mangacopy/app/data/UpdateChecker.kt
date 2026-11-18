package com.mangacopy.app.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * GitHub Release 檢查更新（用戶 2026-11-18：「用 GITHUB，有更新開 App 時提醒一次」）。
 *
 * Release tag 慣例 `android-vX.Y.Z`（同發佈流程一致）→ 同 AppConfig.VERSION_NAME
 * 逐段數值比較。節流：12 小時最多一次網絡請求（GitHub 未授權 API 60 次/鐘/IP，
 * 呢個用量远远唔夠使佢封）；結果快取 kv「upd_latest=ver|url」，
 * 節流期內開 App 直接用快取決定提唔提醒。
 * 「提醒一次」= 每個新版本只彈一次 dialog（kv「upd_seen」記版本號，
 * 用戶撳任何按鈕即當確認；將來出更新版本先會再彈）。
 */
object UpdateChecker {
    private const val API =
        "https://api.github.com/repos/frankiecsy-lab/copymanga-downloader/releases?per_page=10"
    private const val KV_TS = "upd_ts"
    private const val KV_LATEST = "upd_latest"
    private const val KV_SEEN = "upd_seen"
    private const val INTERVAL = 12 * 3600_000L

    data class Available(val version: String, val url: String)

    private val _state = MutableStateFlow<Available?>(null)
    val state: StateFlow<Available?> = _state

    /** 設置頁手動檢查（用戶 2026-11-18：「設置中加一個更新 GITHUB 檢查」）：
     *  checking＝按钮 spinner 狀態；result＝如實回報文字（最新/新版/失敗）。 */
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking
    private val _result = MutableStateFlow<String?>(null)
    val result: StateFlow<String?> = _result

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db get() = Db.get()

    /** 打 GitHub API 攞最新 `android-vX.Y.Z` release；冇 / 失敗 → null。 */
    private fun fetchLatest(): Pair<String, String>? {
        val req = Request.Builder().url(API)
            .header("User-Agent", "KIECOMIC")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val arr = JSONArray(resp.body!!.string())
            var ver = ""; var url = ""
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optBoolean("draft") || o.optBoolean("prerelease")) continue
                val tag = o.optString("tag_name")
                if (!tag.startsWith("android-v")) continue
                val v = tag.substring("android-v".length)
                if (ver.isEmpty() || cmp(v, ver) > 0) { ver = v; url = o.optString("html_url") }
            }
            return if (ver.isEmpty()) null else ver to url
        }
    }

    /** App 啟動 effect 調用；background fire-and-forget，失敗靜默（唔滋擾用戶）。 */
    fun onAppStart() {
        val ts = db.getMeta(KV_TS, "0")?.toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - ts < INTERVAL) { restoreFromCache(); return }
        scope.launch {
            try {
                val (ver, url) = fetchLatest() ?: return@launch
                db.setMeta(KV_TS, System.currentTimeMillis().toString())
                db.setMeta(KV_LATEST, "$ver|$url")
                if (cmp(ver, AppConfig.VERSION_NAME) > 0 && db.getMeta(KV_SEEN, "") != ver)
                    _state.update { Available(ver, url) }
            } catch (e: Exception) {
                Log.w("MCUPD", "檢查更新失敗：${e.message}")
            }
        }
    }

    /** 設置頁「檢查」撳制：繞過 12 小時節流強制打 API，結果如實上報。
     *  發現新版 → 清 upd_seen 強制彈 dialog（用戶 명시檢查，理应即刻見到入口）。 */
    fun checkNow() {
        if (_checking.value) return
        _checking.value = true; _result.value = "連接 GitHub…"
        scope.launch {
            try {
                val (ver, url) = fetchLatest() ?: error("GitHub 冇 Android 發行版")
                db.setMeta(KV_TS, System.currentTimeMillis().toString())
                db.setMeta(KV_LATEST, "$ver|$url")
                if (cmp(ver, AppConfig.VERSION_NAME) > 0) {
                    db.setMeta(KV_SEEN, "")
                    _state.update { Available(ver, url) }
                    _result.update { "發現新版本 v$ver — 彈窗已附下載連結" }
                } else {
                    _result.update { "已是最新版本 v${AppConfig.VERSION_NAME} ✔" }
                }
            } catch (e: Exception) {
                Log.w("MCUPD", "手動檢查失敗：${e.message}")
                _result.update { "檢查失敗：${e.message ?: "網絡錯誤"}" }
            } finally {
                _checking.value = false
            }
        }
    }

    /** 節流期內開 App：唔打網，直接用快取嘅最新版本決定彈唔彈。 */
    private fun restoreFromCache() {
        val s = db.getMeta(KV_LATEST)?.split('|')?.takeIf { it.size >= 2 } ?: return
        if (cmp(s[0], AppConfig.VERSION_NAME) > 0 && db.getMeta(KV_SEEN, "") != s[0])
            _state.update { Available(s[0], s[1]) }
    }

    /** 用戶關閉 dialog（任何按鈕/點外面）= 本版本已確認，唔再骚扰。 */
    fun acknowledge() {
        _state.value?.let { db.setMeta(KV_SEEN, it.version) }
        _state.update { null }
    }

    /** semver 逐段數值比較："0.3.10" > "0.3.9"。 */
    fun cmp(a: String, b: String): Int {
        val pa = a.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrNull(i) ?: 0) - (pb.getOrNull(i) ?: 0)
            if (d != 0) return if (d > 0) 1 else -1
        }
        return 0
    }
}
