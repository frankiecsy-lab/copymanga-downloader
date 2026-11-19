package com.mangacopy.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
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
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * GitHub Release 檢查更新（用戶 2026-11-18：「用 GITHUB，有更新開 App 時提醒一次」）。
 *
 * Release tag 慣例 `android-vX.Y.Z`（同發佈流程一致）→ 同 AppConfig.VERSION_NAME
 * 逐段數值比較。節流：12 小時最多一次網絡請求（GitHub 未授權 API 60 次/鐘/IP）；
 * 結果快取 kv「upd_latest=ver|頁url|APK直鏈」，節流期內開 App 直接用快取決定提唔提醒
 * （舊兩段式快取冇直鏈 → 自動補一次後台刷新）。
 * 「提醒一次」= 每個新版本只彈一次 dialog（kv「upd_seen」記版本號）。
 *
 * **v0.3.6 起：更新唔再跳瀏覽器**（用戶：「更新不想跳轉，想它直接下載APK自動打開」）—
 * 彈窗「直接下載安裝」→ OkHttp 落 APK 去 app 專屬外部目錄 `updates/`（冇 storage 權限問題）
 * → 完成即自動用 FileProvider content URI 開系統安裝界面（ACTION_VIEW +
 * application/vnd.android.package-archive）。Android 8+ 要「安裝未知應用程式」授權：
 * 未授權唔會白嘔 12MB — 檔照落盤，狀態轉 NeedPerm 帶去授權頁，返嚟撳多一次即裝。
 */
object UpdateChecker {
    private const val API =
        "https://api.github.com/repos/frankiecsy-lab/copymanga-downloader/releases?per_page=10"
    private const val KV_DAY = "upd_day"        // 日曆日閘（用戶：「每次打開APP到前景都要CHECK UPDATE，每日一次」）
    private const val KV_LATEST = "upd_latest"
    private const val KV_SEEN = "upd_seen"

    data class Available(val version: String, val url: String, val apkUrl: String)

    /** 彈窗「直接下載安裝」狀態機。 */
    sealed interface Download {
        data object Idle : Download
        data class Running(val read: Long, val total: Long) : Download   // total<=0 = 未知大小
        data class Ready(val apk: File) : Download                        // 下載完成，安裝界面已開/可重開
        data class NeedPerm(val apk: File) : Download                     // 檔已落盤，欠安裝授權
        data class Fail(val msg: String) : Download
    }

    private val _state = MutableStateFlow<Available?>(null)
    val state: StateFlow<Available?> = _state

    private val _download = MutableStateFlow<Download>(Download.Idle)
    val download: StateFlow<Download> = _download

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
    /** APK 下載專用：~10MB 檔案唔可以食 API 嗰份 20 秒 callTimeout —
     *  只限 connect/read，唔設 callTimeout（慢網都要畀時間佢落完）。 */
    private val httpDl = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db get() = Db.get()

    private data class Latest(val ver: String, val pageUrl: String, val apkUrl: String)

    /** 打 GitHub API 攞最新 `android-vX.Y.Z` release；冇 / 失敗 → null。 */
    private fun fetchLatest(): Latest? {
        val req = Request.Builder().url(API)
            .header("User-Agent", "KIECOMIC")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val arr = JSONArray(resp.body!!.string())
            var best: Latest? = null
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optBoolean("draft") || o.optBoolean("prerelease")) continue
                val tag = o.optString("tag_name")
                if (!tag.startsWith("android-v")) continue
                val v = tag.substring("android-v".length)
                if (best != null && cmp(v, best!!.ver) <= 0) continue
                // APK 直鏈：assets 入面搵 .apk 檔（browser_download_url）—
                // 有就首选含 debug 嗰個；冇 apk 嘅 release 唔接受（下載彈窗會死喺度）
                val assets = o.optJSONArray("assets")
                var apk = ""
                if (assets != null) for (j in 0 until assets.length()) {
                    val u = assets.optJSONObject(j)?.optString("browser_download_url").orEmpty()
                    if (u.endsWith(".apk") && (apk.isEmpty() || u.contains("debug"))) apk = u
                }
                if (apk.isEmpty()) continue
                best = Latest(v, o.optString("html_url"), apk)
            }
            return best
        }
    }

    /** 打 API → 寫快取 → 有新未睇版本就摆 state。onAppStart（未節流路徑）＋
     *  舊格式快取補刷共用。 */
    private fun fetchAndCache(): Latest? {
        val l = fetchLatest() ?: return null
        db.setMeta(KV_LATEST, "${l.ver}|${l.pageUrl}|${l.apkUrl}")
        if (cmp(l.ver, AppConfig.VERSION_NAME) > 0 && db.getMeta(KV_SEEN, "") != l.ver)
            _state.update { Available(l.ver, l.pageUrl, l.apkUrl) }
        return l
    }

    /** 每次 App 回到前景（Activity ON_RESUME）調用（用戶：「每次打開APP到前景都要
     *  CHECK UPDATE，每日一次」）：日曆日閘 — 今日未check過先打 API；日_marker 先寫，
     *  API 失敗当日都唔重複刷網絡（手動檢查仍可随时繞過）。今日已 check →
     *  restoreFromCache 用快取決定提唔提醒（upd_seen 防重複彈）。 */
    fun onAppStart() {
        val today = java.time.LocalDate.now().toString()
        if (db.getMeta(KV_DAY, "") == today) { restoreFromCache(); return }
        db.setMeta(KV_DAY, today)
        scope.launch {
            try { fetchAndCache() }
            catch (e: Exception) { Log.w("MCUPD", "檢查更新失敗：${e.message}") }
        }
    }

    /** 設置頁「檢查」撳制：繞過 12 小時節流強制打 API，結果如實上報。
     *  發現新版 → 清 upd_seen 強制彈 dialog（用戶 명시檢查，理应即刻見到入口）。 */
    fun checkNow() {
        if (_checking.value) return
        _checking.value = true; _result.value = "連接 GitHub…"
        scope.launch {
            try {
                val l = fetchLatest() ?: error("GitHub 冇帶 APK 嘅 Android 發行版")
                db.setMeta(KV_DAY, java.time.LocalDate.now().toString())  // 手動 check 亦計做「今日已 check」
                db.setMeta(KV_LATEST, "${l.ver}|${l.pageUrl}|${l.apkUrl}")
                if (cmp(l.ver, AppConfig.VERSION_NAME) > 0) {
                    db.setMeta(KV_SEEN, "")
                    _download.value = Download.Idle
                    _state.update { Available(l.ver, l.pageUrl, l.apkUrl) }
                    _result.update { "發現新版本 v${l.ver} — 彈窗可直接下載安裝" }
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
        val s = db.getMeta(KV_LATEST)?.split('|')?.takeIf { it.isNotEmpty() } ?: return
        val ver = s[0]
        if (cmp(ver, AppConfig.VERSION_NAME) <= 0 || db.getMeta(KV_SEEN, "") == ver) return
        val apk = s.getOrNull(2).orEmpty()
        if (apk.isEmpty()) {   // 舊兩段式快取（v0.3.5 之前冇直鏈）→ 補一次後台刷新
            scope.launch { runCatching { fetchAndCache() } }
            return
        }
        _state.update { Available(ver, s.getOrNull(1).orEmpty(), apk) }
    }

    // ================= 直接下載安裝（v0.3.6，用戶：「唔想跳轉」） =================

    /** 彈窗「直接下載安裝」：App 內落 APK（跳過瀏覽器）。完成即自動開系統安裝界面；
     *  欠「安裝未知應用」授權 → NeedPerm（檔已落盤，授權返嚟撳多一次即裝）。 */
    fun startDownload(context: Context) {
        val avail = _state.value ?: return
        if (_download.value is Download.Running) return
        if (avail.apkUrl.isEmpty()) {
            _download.value = Download.Fail("下載連結失效 — 去設置「檢查」重新整理一次")
            return
        }
        val app = context.applicationContext
        _download.value = Download.Running(0, 0)
        scope.launch {
            try {
                // app 專屬外部目錄：唔使 storage 權限、卸載即清、FileProvider 直接暴露
                val dir = File(app.getExternalFilesDir(null), "updates").apply { mkdirs() }
                val out = File(dir, "KIECOMIC-android-v${avail.version}.apk")
                val tmp = File(dir, "download.part")
                out.delete()
                val req = Request.Builder().url(avail.apkUrl)
                    .header("User-Agent", "KIECOMIC").build()
                var read = 0L; var lastEmit = 0L; var total = -1L
                httpDl.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    total = resp.body!!.contentLength()
                    tmp.outputStream().use { os ->
                        val ins = resp.body!!.byteStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n); read += n
                            // 節流 emit：每 256KB / 完檔先刷 UI，唔好逐 block 重組
                            if (total <= 0 || read - lastEmit >= 256_000 || read == total) {
                                lastEmit = read
                                _download.update { Download.Running(read, total) }
                            }
                        }
                    }
                }
                if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
                if (canInstall(app)) {
                    _download.value = Download.Ready(out)
                    launchInstall(app, out)          // 自動彈安裝界面（用戶要的「自動打開」）
                } else {
                    _download.value = Download.NeedPerm(out)
                }
            } catch (e: Exception) {
                Log.w("MCUPD", "APK 下載失敗：${e.message}")
                _download.value = Download.Fail(e.message ?: "網絡錯誤")
            }
        }
    }

    /** Ready／NeedPerm 狀態下彈窗按鈕：有權限 → 重開安裝界面；
     *  欠授權 → 帶去「安裝未知應用」授權頁，允許後返嚟再撳一次即裝。 */
    fun retryInstall(context: Context) {
        val app = context.applicationContext
        when (val d = _download.value) {
            is Download.NeedPerm ->
                if (canInstall(app)) { _download.value = Download.Ready(d.apk); launchInstall(app, d.apk) }
                else openPermSettings(app)
            is Download.Ready -> launchInstall(app, d.apk)
            else -> {}
        }
    }

    private fun canInstall(context: Context) =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    private fun launchInstall(context: Context, apk: File) {
        runCatching {
            // Android 7+ 嚴禁 file:// content URI 跨進程 — 必須 FileProvider + 臨時讀權限
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", apk)
            context.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            Log.w("MCUPD", "開啟安裝界面失敗：${it.message}")
            _download.value = Download.Fail("開啟安裝界面失敗：${it.message}")
        }
    }

    private fun openPermSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** 用戶關閉 dialog（任何按鈕/點外面）= 本版本已確認，唔再骚扰；清下載狀態。 */
    fun acknowledge() {
        _state.value?.let { db.setMeta(KV_SEEN, it.version) }
        _state.update { null }
        _download.value = Download.Idle
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
