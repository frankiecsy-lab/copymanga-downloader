package com.mangacopy.app.source

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

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override suspend fun browse(offset: Int, status: Int?): BrowsePage = withContext(Dispatchers.IO) {
        // 同桌面版一樣有禮貌限速：翻下一頁前等 2 秒（robots 提 10 秒，用戶手動翻頁已天然限速）
        if (offset > 0) delay(AppConfig.LIST_DELAY_MS)
        val url = buildString {
            append(AppConfig.BASE_URL).append("/comics?ordering=")
            .append(AppConfig.LIST_ORDERING)
            .append("&offset=").append(offset).append("&limit=").append(AppConfig.LIST_LIMIT)
            if (status != null) append("&status=").append(status)
        }
        val request = Request.Builder().url(url)
            .header("User-Agent", AppConfig.USER_AGENT)
            .header("Accept-Language", "zh-TW,zh;q=0.9")
            .build()
        http.newCall(request).execute().use { resp ->
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

    override suspend fun detail(slug: String): DetailResult {
        val d = WebViewRenderer.renderDetail(slug)
        if (d.chapters.isEmpty())
            throw IOException("詳情頁渲染為空（站點可能維護中/WAF 升級）— 稍後重試")
        return d
    }

    override suspend fun chapterImageUrls(url: String): ChapterPages {
        val p = WebViewRenderer.renderChapter(url)
        if (p.urls.isEmpty())
            throw IOException("章節渲染為空（站點可能維護中）— 稍後重試")
        return p
    }
}
