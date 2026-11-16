package com.mangacopy.app.source

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mangacopy.app.data.AppConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
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
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var pageFinished: CompletableDeferred<Unit>? = null

    fun init(context: Context) { appContext = context.applicationContext }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensure(): WebView {
        webView?.let { return it }
        var created: WebView? = null
        val latch = CountDownLatch(1)
        main.post {
            created = WebView(appContext).apply {
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
            latch.countDown()
        }
        latch.await()
        CookieManager.getInstance().setAcceptCookie(true)
        return created!!
    }

    /** 主線程執行 JS 並取回結果（JSON 文本）。 */
    private suspend fun js(script: String): String? = suspendCancellableCoroutine { cont ->
        val wv = webView
        if (wv == null) { cont.resume(null); return@suspendCancellableCoroutine }
        main.post {
            try {
                wv.evaluateJavascript(script) { v -> cont.resume(v) }
            } catch (e: Exception) {
                cont.resume(null)
            }
        }
    }

    private suspend fun jsInt(script: String): Int? =
        js(script)?.takeIf { it != "null" && it != "undefined" }?.toDoubleOrNull()?.toInt()

    private suspend fun jsBool(script: String): Boolean = js(script) == "true"

    private suspend fun loadAndWait(url: String): Boolean {
        val wv = ensure()
        val done = CompletableDeferred<Unit>()
        pageFinished = done
        main.post { wv.loadUrl(url) }
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
    suspend fun renderDetail(slug: String): DetailResult = mutex.withLock {
        val url = "${AppConfig.BASE_URL}/comic/$slug"
        loadAndWait(url)
        withTimeoutOrNull(30_000L) {
            while (!jsBool("document.querySelectorAll('.upLoop a[href*=\"/chapter/\"]').length > 0")) delay(500)
        }
        delay(AppConfig.RENDER_SETTLE_MS)
        val raw = js(DETAIL_JS.replace("__BASE__", AppConfig.BASE_URL))
        CookieManager.getInstance().flush()
        parseDetail(raw)
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

    /**
     * 章節頁渲染 — 完整移植 render_chapter 嘅振盪滾動策略：
     * 內容係逐頁 append，單向滾動到底會「卡死」喺底部（IntersectionObserver 唔再觸發）；
     * 所以要落 500 / 返上 120 來回振盪，直至每頁 <li> 都入 DOM（或 badge 總數齊 / 到底且 45 次無進展）。
     * 圖片真 URL 直接讀 data-src（DOM 順序 = 閱讀順序），唔使靠網絡時序。
     */
    suspend fun renderChapter(url: String): ChapterPages = mutex.withLock {
        loadAndWait(url)
        delay(1_500)   // networkidle 等價：畀初始批次嘅請求行完

        val total = jsInt(BADGE_JS)
        var last = -1
        var noProgress = 0
        var step = 0
        val cap = maxOf((total ?: 60) * 3 + 40, 200)
        while (step++ < cap) {
            js("window.scrollBy(0, 500)")
            delay(AppConfig.CHAPTER_SCROLL_DELAY_MS)
            js("window.scrollBy(0, -120)")
            val cur = jsInt(LI_COUNT_JS) ?: 0
            if (total != null && cur >= total) break
            noProgress = if (cur == last) noProgress + 1 else 0
            if (jsBool(AT_BOTTOM_JS) && noProgress >= 45) break
            last = cur
        }

        delay(AppConfig.RENDER_SETTLE_MS)
        val raw = js(IMAGES_JS)
        CookieManager.getInstance().flush()
        val urls = try {
            val arr = JSONArray(raw ?: "[]")
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }.distinct()
        ChapterPages(urls, total)
    }
}
