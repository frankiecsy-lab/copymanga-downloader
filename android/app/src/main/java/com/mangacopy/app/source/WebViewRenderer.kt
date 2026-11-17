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
            // 2026-11-17 實測：append 嘅 <li> 會觸發 lazyload 真載圖 + webp 解码，
            // 霸佔 renderer 主線程 → scroll 事件/JS step 由 25/s 跌至 ~1.5/s（後期輪次減速根因）。
            // 我哋只需要 data-src URL 清單，圖片由 OkHttp 另落 → 呢個 WebView 永遠唔使載圖。
            settings.loadsImagesAutomatically = false
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
            // 通用擷取（2026-11-17 實測：站點改版後連結未必帶 /comic/{pw} 前綴、
            // cid 亦未必係 UUID）— 只要 /chapter/{id} 就收
            const m = href.match(/\/chapter\/([^\/?#]+)/);
            if (!m || seen[m[1]]) return;
            const t = (a.textContent || '').trim();
            seen[m[1]] = 1;
            out.chapters.push({
              cid: m[1],
              title: t,
              url: href.startsWith('http') ? href : '__BASE__' + href
            });
          });
          // 維護兜底（2026-11-17 實測）：站點清單 API 死時 upLoop=空，
          // 但詳情頁 HTML 仍帶「開始閱讀」真章節直 link → 抽取任何章節 <a> 做最小清單，
          // 起碼可以真閱讀／下載一章，再經章節頁「下一章」鏈繼續（比完全載入唔到強）。
          if (!out.chapters.length) {
            document.querySelectorAll('a[href*="/chapter/"]').forEach(a => {
              const href = a.getAttribute('href') || '';
              const m = href.match(/\/chapter\/([^\/?#]+)/);
              if (!m || seen[m[1]]) return;
              const t = (a.textContent || '').trim();
              seen[m[1]] = 1;
              out.chapters.push({
                cid: m[1],
                title: (t && !/閱讀|阅读/.test(t)) ? t : '開始閱讀 · 第1章（清單維護中）',
                url: href.startsWith('http') ? href : '__BASE__' + href
              });
            });
          }
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
                // 維護殼偵測（2026-11-17 實測）：12 秒無章節清單但頁內已有任何章節直 link
                // （開始閱讀按鈕喺 HTML 就喺度）→ 判定清單 API 死，即刻用兜底，
                // 唔好傻等 40 秒 ×2 次重試（用戶_feedback「基本載入好多分鐘」根因之一）
                if (tick >= 24 &&
                    jsBool("document.querySelectorAll('a[href*=\"/chapter/\"]').length > 0")
                ) {
                    Log.i("MCWEB", "detail SHELL-FALLBACK t=${tick / 2}s url=$url")
                    break
                }
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
        var noProgress = 0
        var step = 0
        var lastEmit = 0
        val cap = maxOf((total ?: 60) * 2 + 80, 160)

        // 第一時間畀一次現有 DOM（通常首 2-5 頁已經喺度）
        parseImagesJson(js(IMAGES_JS)).takeIf { it.isNotEmpty() }?.let {
            lastEmit = it.size
            onPartial(it, total)
        }
        // 2026-11-17 站點源碼拆包實錘（comic_content_pass202508141534.js，去混淆）：
        //   window.onscroll = ... if (ul.offsetTop / 16 > scrollY) appendOne()
        // —— **append 只喺 page 頂部附近先發生**（scrollY < offsetTop/16 ≈ 幾十 px），
        // 一個合資格 scroll 事件吐一張 <li>；單向滾去底 = 條件永遠唔成立 = 死喺 3 頁
        // （初載 for 循環只吐 3 張，同實測 li=3 吻合）。
        // 修正：__mcStep 喺頂部 0↔5px 微振盪 —— 每次調用都改變 scrollY（保證出事件）
        // 又永遠留喺最低閾值內；配合同頁禁載圖（loadsImagesAutomatically=false）
        // 解放主線程，先至跑到設計速度。
        // 實測再進化：真 scroll 要 scroll room、平滑滾動、事件節流等变量（每輪 append
        // Rate 唔穩）。其實 handler 只需要「一個 scroll 事件 + scrollY 喺低位」—
        // dispatchEvent 合成事件直接觸發 window.onscroll，scrollY 保持 0（永遠喺閾值內），
        // 完全唔使郁 page → 每次調用 =  guaranteed +1 頁。
        js("""
            window.__mcStep = function () {
              window.dispatchEvent(new Event('scroll'));
              return document.querySelectorAll('.comicContent-list li').length;
            }; void 0
        """.trimIndent())
        while (step++ < cap) {
            val cur = jsInt("window.__mcStep()") ?: 0
            if (cur > lastEmit) {
                parseImagesJson(js(IMAGES_JS)).takeIf { it.size > lastEmit }?.let {
                    lastEmit = it.size
                    onPartial(it, total)
                }
                noProgress = 0
            } else if (++noProgress >= 70) {
                break   // ~2.5 秒連派事件都冇新頁 = 站點真係吐晒，攞住有嘅收工
            }
            if (total != null && cur >= total) break
            delay(30)
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
