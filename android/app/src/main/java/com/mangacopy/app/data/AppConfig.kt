package com.mangacopy.app.data

/**
 * 全域設定 — 1:1 移植自桌面版 cm/config.py。
 * 所有數值同桌面版一致，確保站點行為（WAF、lazy-load、限速）嘅應對策略完全相同。
 */
object AppConfig {
    /** App 版本 — title bar「KIECOMIC vX.Y.Z」顯示用；改版本時同 build.gradle.kts 一起改。 */
    const val VERSION_NAME = "0.3.3"
    const val BASE_URL = "https://www.mangacopy.com"

    /**
     * 域名候選（failover 順序）— 站點家族域名多個，但邊個通視乎地區網絡：
     * 2026-11-17 實測中國大陸網絡 mangacopy.com 連唔上（DNS 污染），copymanga.site 正常。
     * 運行時由 CopyMangaSource.resolveBase() 逐個探測，揀第一個返回有效列表頁嘅。
     */
    val DOMAIN_CANDIDATES = listOf(
        "https://www.copymanga.site",
        "https://www.mangacopy.com",
        "https://www.copymanga.fun",
        "https://www.copymanga.io",
    )

    // 同桌面版共用同一個瀏覽器 UA：站點 WAF 認呢個 UA（Playwright 都係用佢）
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    // --- 列表爬取（純 HTTP，無 WAF）---
    const val LIST_LIMIT = 50
    const val LIST_ORDERING = "-popular"
    const val LIST_DELAY_MS = 2_000L

    // --- WebView 渲染 ---
    const val NAV_TIMEOUT_MS = 60_000L
    const val RENDER_SETTLE_MS = 3_000L
    const val CHAPTER_SCROLL_DELAY_MS = 400L
    const val CHAPTER_RENDER_DELAY_MS = 1_500L

    // --- 圖片下載（圖片域名無 WAF）---
    const val IMAGE_SIZE_SUFFIX = ".c1500x.webp"
    const val DOWNLOAD_RETRIES = 3
    const val IMAGE_FETCH_DELAY_MS = 200L

    // 站點 status int -> 標籤（同桌面版實測一致：0=連載中 1=已完結 2=短篇）
    val STATUS_LABELS = mapOf(0 to "連載中", 1 to "已完結", 2 to "短篇")
    const val STATUS_UNKNOWN = "未分類"

    fun statusLabel(status: Int?): String? = STATUS_LABELS[status]
}
