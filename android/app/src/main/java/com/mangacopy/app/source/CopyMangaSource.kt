package com.mangacopy.app.source

import android.util.Log
import com.mangacopy.app.data.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * CopyManga（mangacopy.com）下載源。
 *
 * 混合策略（同桌面版完全一致）：
 *   browse — /comics 列表冇 WAF：純 OkHttp GET + ListingParser
 *   detail / chapterImageUrls — WAF + AES JS：交俾 WebViewRenderer（真 Chromium 渲染）
 */
object CopyMangaSource : MangaSource {
    override val id = "copymanga"
    override val displayName = "CopyManga"

    // 快fail超時（用戶 2026-11-18「endless loading」排查）：舊版 30s/60s 超時
    // ＋失敗重試 = 最長拖成三分幾鐘空轉 spinner。列表/搜索都係細 HTML/JSON，
    // 12/25/30s 綽綽有餘 — 真出事短時間如實報錯，等用戶撳「刷新」
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val probeHttp = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 當前可用域名（探測成功先寫入）；任何請求失敗即 invalidate 重新探測。 */
    @Volatile private var cachedBase: String? = null
    /** 防探測風暴（2026-11-17 ADB 實測）：記低上次成功探測嘅 base + 時間；
     *  invalidate 後 10 秒內唔再全線掃（舊 base 照用），等真正域名死清先再掃。 */
    @Volatile private var lastGoodBase: String? = null
    @Volatile private var lastProbeOkAt = 0L

    /** 逐個域名探測（短時限速），第一個返回有效列表頁 HTML 嘅就攞用。 */
    private fun probeBases(): String {
        var lastErr: Exception? = null
        for (base in AppConfig.DOMAIN_CANDIDATES) {
            try {
                val req = Request.Builder()
                    .url("$base/comics?ordering=${AppConfig.LIST_ORDERING}&offset=0&limit=1")
                    .header("User-Agent", AppConfig.USER_AGENT)
                    .header("Accept-Language", "zh-TW,zh;q=0.9")
                    .build()
                probeHttp.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body!!.string()
                        if (body.contains("exemptComic-box")) {
                            Log.i("MCWEB", "probe OK base=$base")
                            cachedBase = base
                            lastGoodBase = base
                            lastProbeOkAt = System.currentTimeMillis()
                            return base
                        } else Log.w("MCWEB", "probe SKIP base=$base (200 但冇列表marker)")
                    } else Log.w("MCWEB", "probe SKIP base=$base HTTP ${resp.code}")
                }
            } catch (e: Exception) {
                Log.w("MCWEB", "probe FAIL base=$base: ${e.message}")
                lastErr = e   // 連唔上/DNS 污染 → 試下一個
            }
        }
        throw IOException("所有域名都連唔上（網絡封鎖？最後錯誤：${lastErr?.message}）")
    }

    /** 攞當前域名；未探測過就探一次。剛探過 10 秒內被 invalidate → 返舊 base（防風暴）。 */
    suspend fun currentBase(): String {
        cachedBase?.let { return it }
        lastGoodBase?.let { good ->
            if (System.currentTimeMillis() - lastProbeOkAt < 10_000) return good
        }
        return withContext(Dispatchers.IO) { probeBases() }
    }

    /** 請求失敗時作廢，下次重新探測（域名隨時死，要自動切換）。 */
    fun invalidateBase() { cachedBase = null }

    override suspend fun browse(offset: Int, status: Int?, theme: String?): BrowsePage =
        withContext(Dispatchers.IO) {
            // 一次失敗（域名中途死）→ invalidate + 重新探測再嚟一次
            try { doBrowse(offset, status, theme) }
            catch (e: Exception) {
                invalidateBase()
                doBrowse(offset, status, theme)
            }
        }

    private suspend fun doBrowse(offset: Int, status: Int?, theme: String?): BrowsePage {
        // 同桌面版一樣有禮貌限速：翻下一頁前等 2 秒（robots 提 10 秒，用戶手動翻頁已天然限速）
        if (offset > 0) delay(AppConfig.LIST_DELAY_MS)
        val base = currentBase()
        val url = buildString {
            append(base).append("/comics?ordering=")
            .append(AppConfig.LIST_ORDERING)
            .append("&offset=").append(offset).append("&limit=").append(AppConfig.LIST_LIMIT)
            if (status != null) append("&status=").append(status)
            // 題材分類（用戶 2026-11-18：站點本來就有 — /comics 頁題材欄就係呢個參數）
            if (!theme.isNullOrEmpty()) append("&theme=").append(theme)
        }
        val request = Request.Builder().url(url)
            .header("User-Agent", AppConfig.USER_AGENT)
            .header("Accept-Language", "zh-TW,zh;q=0.9")
            .build()
        return http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("列表頁 HTTP ${resp.code}")
            val page = ListingParser.parse(resp.body!!.string())
            BrowsePage(
                items = page.items.map {
                    ComicMeta(
                        pathWord = it.pathWord,
                        name = it.name,
                        author = it.author,
                        coverUrl = it.coverUrl,
                        status = it.status,
                        statusLabel = AppConfig.statusLabel(it.status),
                    )
                },
                total = page.total,
            )
        }
    }

    /**
     * 站內模糊搜索（書名 / 別名 / 作者）— api/kb/web/searchcl/comics?q=（實測可用）。
     * 參數名必須係 q（key_word 會報「搜索關鍵詞不能為空」）；每頁上限 20；
     * status 參數無效（維護中嘅 filter API 先有）— 種類標籤 theme/parodies 有就帶返。
     */
    override suspend fun search(query: String, offset: Int, limit: Int): SearchPage =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext SearchPage(emptyList(), 0)
            try { doSearch(q, offset, limit) }
            catch (e: Exception) { invalidateBase(); doSearch(q, offset, limit) }
        }

    private suspend fun doSearch(query: String, offset: Int, limit: Int): SearchPage {
        val base = currentBase()
        val url = buildString {
            append(base).append("/api/kb/web/searchcl/comics?q=")
            append(java.net.URLEncoder.encode(query, "UTF-8"))
            append("&_offset=").append(offset).append("&_limit=").append(limit)
        }
        val request = Request.Builder().url(url)
            .header("User-Agent", AppConfig.USER_AGENT)
            .header("Accept-Language", "zh-TW,zh;q=0.9")
            .build()
        return http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("搜索 HTTP ${resp.code}")
            val root = org.json.JSONObject(resp.body!!.string())
            if (root.optInt("code") != 200)
                throw IOException(root.optString("detail", "搜索失敗").ifEmpty { "搜索失敗" })
            val res = root.optJSONObject("results")
            val arr = res?.optJSONArray("list") ?: org.json.JSONArray()
            val items = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val pw = o.optString("path_word")
                if (pw.isEmpty()) return@mapNotNull null
                val authors = o.optJSONArray("author")
                val authorNames = authors?.let { a ->
                    (0 until a.length()).mapNotNull { j -> a.optJSONObject(j)?.optString("name") }
                        .filter { it.isNotEmpty() }.joinToString(", ")
                } ?: ""
                val tags = mutableListOf<String>()
                for (k in arrayOf("theme", "parodies")) {
                    o.optJSONArray(k)?.let { t ->
                        for (j in 0 until t.length()) {
                            val s = t.optString(j)
                            if (s.isNotEmpty() && s != "null") tags.add(s)
                        }
                    }
                }
                ComicMeta(
                    pathWord = pw,
                    name = o.optString("name").ifEmpty { pw },
                    author = authorNames,
                    coverUrl = o.optString("cover").takeIf { it.isNotEmpty() && it != "null" },
                    status = null,
                    statusLabel = null,
                    tags = tags,
                )
            }
            SearchPage(items, res?.optInt("total"))
        }
    }

    override suspend fun detail(slug: String): DetailResult {
        // 域名隨時死/被 WAF 針對：試兩次（第二次 invalidateBase 重新探測揀另一個域名）
        var lastErr: Exception = IOException("詳情頁載入失敗")
        repeat(2) {
            try {
                val d = WebViewRenderer.renderDetail(slug, currentBase())
                if (d.chapters.isNotEmpty()) return d
                lastErr = IOException("詳情頁渲染為空（站點可能維護中/WAF 升級）")
            } catch (e: Exception) { lastErr = e }
            invalidateBase()
            delay(1_500)
        }
        throw lastErr
    }

    /**
     * 章節 URL 域名自癒（2026-11-17 實測）：DB chapters.url 存嘅係擷取當日嘅域名，
     * 而站域名日變＋ASUS 路由器 DNS 劫持係間歇性（知識庫 #11）— 舊章（如原作版159）
     * 存咗死域名就永遠「站點維護」。路徑（/comic/x/chapter/uuid）先係真正身份，
     * 域名照 currentBase() 探測結果換走即可。
     */
    fun rebaseChapterUrl(url: String, base: String): String = runCatching {
        val u = java.net.URI(url)
        val b = java.net.URI(base)
        // 必須両邊都有 host（「not-a-url」呢類相對字串 u.host=null → 唔好亂砌）
        if (u.host != null && u.host != b.host && u.path.isNotEmpty()) base + u.path else url
    }.getOrDefault(url)

    /** 渲染章節；舊域名自動 rebasing，中途域名死咗再 invalidate 重新探測重嚟一次。 */
    override suspend fun chapterImageUrls(
        url: String,
        onPartial: (List<String>, Int?) -> Unit,
    ): ChapterPages {
        var lastErr: Exception = IOException("章節渲染失敗")
        repeat(2) { attempt ->
            val fixed = try { rebaseChapterUrl(url, currentBase()) } catch (e: Exception) { url }
            try {
                val p = WebViewRenderer.renderChapter(fixed, onPartial)
                if (p.urls.isNotEmpty()) return p
                lastErr = IOException("章節渲染為空（站點可能維護中）— 稍後重試")
            } catch (e: Exception) { lastErr = e }
            if (attempt == 0) {   // 可能啱啱先被劫持/域名死 → 作廢緩存，第二次換域名再嚟
                invalidateBase()
                delay(1_500)
            }
        }
        throw lastErr
    }
}
