package com.mangacopy.app.source

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mangacopy.app.data.AppConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * WebView 渲染器 — cm/browser.py（Playwright）嘅 Android 等價實現。
 *
 * 點解要 WebView：站點 WAF 會擋純 HTTP 詳情頁/章節頁，站內 JS 係 AES 混淆 —
 * 唯一可靠做法同桌面版一樣：放埋個真瀏覽器（Android WebView = Chromium）入去行完
 * 佢自己嘅 JS，再讀渲染後嘅結果。
 *
 * 關鍵等價關係：
 *   Playwright page.goto + wait_for_selector  →  onPageFinished + evaluateJavascript 輪詢
 *   Playwright 振盪滾動（ OSCILLATE ）        →  同一套 scrollBy(+500)/(-120) JS 循環
 *   .comicCount 徽章總頁數                    →  BADGE_JS（同一個 /N \/ M/ 正則）
 *
 * 單一 WebView 實例 + Mutex 串行使用（同桌面版單 page 一致）；WebView 必須喺主線程創建/操作。
 */
object WebViewRenderer {

    private lateinit var appContext: Context
    private var webView: WebView? = null
    private val mutex = Mutex()
    @Volatile private var pageFinished: CompletableDeferred<Unit>? = null

    fun init(context: Context) { appContext = context.applicationContext }

    /**
     * 喺主線程創建（或複用）WebView。
     * 必須 suspend + withContext(Main)：唔可以喺非主線程 busy-wait 等主線程
     * （LaunchedEffect 本身跑主線程，latch.await() 會自鎖死）。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensure(): WebView = withContext(Dispatchers.Main) {
        webView?.let { return@withContext it }
        val wv = WebView(appContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = AppConfig.USER_AGENT
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView?, u: String?, f: android.graphics.Bitmap?) {
                    pageFinished?.complete(Unit)
                    pageFinished = CompletableDeferred()
                }
                override fun onPageFinished(v: WebView?, u: String?) {
                    pageFinished?.complete(Unit)
                }
            }
            // 離屏 WebView 冇經 measure/layout 嘅話 viewport 會係 0×0，
            // lazy-load 嘅 IntersectionObserver 就永遠唔會觸發 — 手動畀佢一個屏幕尺寸
            val dm = resources.displayMetrics
            measure(
                View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.AT_MOST)
            )
            layout(0, 0, dm.widthPixels, dm.heightPixels)
        }
        webView = wv
        CookieManager.getInstance().setAcceptCookie(true)
        Log.i("MCWEB", "WebView created, engine=${WebView.getCurrentWebViewPackage()?.versionName}")
        wv
    }

    /** 主線程執行 JS 並取回結果（JSON 文本）；suspend 唔阻塞任何線程。 */
    private suspend fun js(script: String): String? = withContext(Dispatchers.Main) {
        val wv = webView ?: return@withContext null
        try {
            suspendCancellableCoroutine { cont ->
                wv.evaluateJavascript(script) { cont.resume(it) }
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun jsInt(script: String): Int? =
        js(script)?.takeIf { it != "null" && it != "undefined" }?.toDoubleOrNull()?.toInt()

    private suspend fun jsBool(script: String): Boolean = js(script) == "true"

    private suspend fun loadAndWait(url: String): Boolean {
        val wv = ensure()
        val done = CompletableDeferred<Unit>()
        pageFinished = done
        withContext(Dispatchers.Main) { wv.loadUrl(url) }
        return withTimeoutOrNull(AppConfig.NAV_TIMEOUT_MS) { done.await() } != null
    }

    // ------------------------------------------------------------------ 詳情頁
    private val DETAIL_JS = """
        (() => {
          const out = {chapters: [], synopsis: null, status: null};
          const seen = {};
          document.querySelectorAll('.upLoop a[href*="/chapter/"]').forEach(a => {
            const href = a.getAttribute('href') || '';
            const m = href.match(/\/comic\/[^/]+\/chapter\/([0-9a-fA-F-]+)/);
            if (!m || seen[m[1]]) return;
            seen[m[1]] = 1;
            out.chapters.push({
              cid: m[1],
              title: (a.textContent || '').trim(),
              url: href.startsWith('http') ? href : '__BASE__' + href
            });
          });
          const sp = document.querySelector('.comicParticulars-synopsis .theBoxModel-comic-detail-content');
          if (sp) { const t = (sp.textContent || '').trim(); if (t) out.synopsis = t; }
          for (const li of document.querySelectorAll('li')) {
            const first = li.querySelector('span');
            if (!first) continue;
            const label = (first.textContent || '').replace(/[:：\s]/g, '');
            if (label === '狀態') {
              const v = li.querySelector('.comicParticulars-right-txt');
              if (v) { const t = (v.textContent || '').trim(); if (t) out.status = t; break; }
            }
          }
          return out;
        })()
    """.trimIndent()

    /** 詳情頁渲染：等章節錨點出現（≤30s）→ 穩定 3s → 抽取章節清單/簡介/狀態。 */
    suspend fun renderDetail(slug: String, baseUrl: String): DetailResult = mutex.withLock {
        val url = "$baseUrl/comic/$slug"
        val loaded = loadAndWait(url)
        var tick = 0
        val found = withTimeoutOrNull(40_000L) {
            while (!jsBool("document.querySelectorAll('.upLoop a[href*=\"/chapter/\"]').length > 0")) {
                // 每 4 秒拍一次位置：追蹤 WAF 跳轉鏈（邊個域名都到唔到終點 page）
                if (tick++ % 8 == 0) Log.d("MCWEB", "detail poll t=${tick / 2}s js=${js("JSON.stringify([location.href, document.readyState, document.querySelectorAll('a').length])")}")
                delay(500)
            }
        } != null
        delay(AppConfig.RENDER_SETTLE_MS)
        val raw = js(DETAIL_JS.replace("__BASE__", baseUrl))
        CookieManager.getInstance().flush()
        val result = parseDetail(raw)
        // 診斷日志：出錯時一眼睇到 WebView 實際見到乜（WAF 頁？跳轉？選擇器變更？）
        if (result.chapters.isEmpty()) {
            val state = js("""JSON.stringify({href: location.href, title: document.title,
                len: document.body ? document.body.innerText.length : -1,
                head: document.body ? document.body.innerText.slice(0,300) : '',
                anchors: document.querySelectorAll('a[href*="chapter"]').length})""".trimIndent())
            Log.w("MCWEB", "renderDetail EMPTY url=$url loaded=$loaded found=$found state=$state")
        } else {
            Log.i("MCWEB", "renderDetail OK ${result.chapters.size} chapters url=$url loaded=$loaded found=$found")
        }
        result
    }

    private fun parseDetail(raw: String?): DetailResult {
        if (raw.isNullOrEmpty() || raw == "null") return DetailResult(null, emptyList(), null)
        return try {
            val o = JSONObject(raw)
            val arr = o.optJSONArray("chapters") ?: JSONArray()
            val chapters = (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                ChapterRef(
                    chapterId = c.optString("cid"),
                    title = c.optString("title"),
                    url = c.optString("url"),
                ).takeIf { it.chapterId.isNotEmpty() }
            }
            DetailResult(
                synopsis = o.optString("synopsis").takeIf { it.isNotEmpty() && it != "null" },
                chapters = chapters,
                serialStatus = o.optString("status").takeIf { it.isNotEmpty() && it != "null" },
            )
        } catch (e: Exception) {
            DetailResult(null, emptyList(), null)
        }
    }

    // ------------------------------------------------------------------ 章節頁
    private val BADGE_JS = """
        (() => {
          const re = /^\s*(\d+)\s*\/\s*(\d+)\s*$/;
          for (const e of document.querySelectorAll('div,span,p')) {
            const mm = (e.textContent || '').trim().match(re);
            if (mm) return parseInt(mm[2], 10);
          }
          return null;
        })()
    """.trimIndent()

    private val LI_COUNT_JS = "document.querySelectorAll('.comicContent-list li').length"

    private val AT_BOTTOM_JS =
        "window.scrollY + window.innerHeight >= document.body.scrollHeight - 6"

    private val IMAGES_JS = """
        (() => {
          const out = [];
          for (const im of document.querySelectorAll('.comicContent-list img')) {
            let u = im.getAttribute('data-src') || im.currentSrc || im.src || '';
            u = u.split('?')[0];
            if (!u) continue;
            if (/loading\.jpg${'$'}|\/static\//.test(u)) continue;
            out.push(u);
          }
          return out;
        })()
    """.trimIndent()

    private fun parseImagesJson(raw: String?): List<String> = try {
        val arr = JSONArray(raw ?: "[]")
        (0 until arr.length()).map { arr.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }.distinct()

    /**
     * 章節頁渲染 — 完整移植 render_chapter 嘅振盪滾動策略：
     * 內容係逐頁 append，單向滾動到底會「卡死」喺底部（IntersectionObserver 唔再觸發）；
     * 所以要落 500 / 返上 120 來回振盪，直至每頁 <li> 都入 DOM（或 badge 總數齊 / 45 次無進展）。
     * 圖片真 URL 直接讀 data-src（DOM 順序 = 閱讀順序），唔使靠網絡時序。
     *
     * onPartial：每當 DOM 出現新圖片即時回調（當前 URL 清單 + badge 總數）—
     * 閱讀器「即時預下載」靠佢：第一頁一入 DOM 就可以開始落圖，唔使等成章渲染完。
     */
    suspend fun renderChapter(
        url: String,
        onPartial: (List<String>, Int?) -> Unit = { _, _ -> },
    ): ChapterPages = mutex.withLock {
        loadAndWait(url)
        delay(1_500)   // networkidle 等價：畀初始批次嘅請求行完

        val total = jsInt(BADGE_JS)
        var last = -1
        var noProgress = 0
        var step = 0
        var lastEmit = 0
        val cap = maxOf((total ?: 60) * 3 + 40, 200)

        // 第一時間畀一次現有 DOM（通常首 2-5 頁已經喺度）
        parseImagesJson(js(IMAGES_JS)).takeIf { it.isNotEmpty() }?.let {
            lastEmit = it.size
            onPartial(it, total)
        }
        while (step++ < cap) {
            js("window.scrollBy(0, 500)")
            delay(AppConfig.CHAPTER_SCROLL_DELAY_MS)
            js("window.scrollBy(0, -120)")
            val cur = jsInt(LI_COUNT_JS) ?: 0
            // 有新頁入 DOM → 增量回調（閱讀器即刻開落）
            if (cur > lastEmit) {
                parseImagesJson(js(IMAGES_JS)).takeIf { it.size > lastEmit }?.let {
                    lastEmit = it.size
                    onPartial(it, total)
                }
            }
            if (total != null && cur >= total) break
            noProgress = if (cur == last) noProgress + 1 else 0
            if (jsBool(AT_BOTTOM_JS) && noProgress >= 45) break
            last = cur
        }

        delay(AppConfig.RENDER_SETTLE_MS)
        val urls = parseImagesJson(js(IMAGES_JS))
        CookieManager.getInstance().flush()
        if (urls.isEmpty()) {
            val state = js("""JSON.stringify({href: location.href, title: document.title,
                len: document.body ? document.body.innerText.length : -1,
                head: document.body ? document.body.innerText.slice(0,300) : '',
                imgs: document.querySelectorAll('img').length})""".trimIndent())
            Log.w("MCWEB", "renderChapter EMPTY url=$url total=$total state=$state")
        } else {
            Log.i("MCWEB", "renderChapter OK ${urls.size} pages (badge=$total) url=$url")
        }
        ChapterPages(urls, total)
    }
}
