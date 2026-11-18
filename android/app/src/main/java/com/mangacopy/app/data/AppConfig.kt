package com.mangacopy.app.data

/**
 * 全域設定 — 1:1 移植自桌面版 cm/config.py。
 * 所有數值同桌面版一致，確保站點行為（WAF、lazy-load、限速）嘅應對策略完全相同。
 */
object AppConfig {
    /** App 版本 — title bar「KIECOMIC vX.Y.Z」顯示用；改版本時同 build.gradle.kts 一起改。 */
    const val VERSION_NAME = "0.3.6"
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

    /**
     * CopyManga 站點題材分類（用戶 2026-11-18：「加一個題材分類，COPYMANGA 本來就有」）。
     * 2026-11-18 PC 實測：清單由 /comics 頁導航欄抽出（`?theme=<slug>` 服務端過濾有效，
     * 實測 theme=maoxian → total 7014）；站上限 return 50 條/頁，同 LIST_LIMIT 一致。
     */
    val TOPICS: List<Pair<String, String>> = listOf(
        "aiqing" to "愛情", "huanlexiang" to "歡樂向", "maoxian" to "冒險", "qihuan" to "奇幻",
        "baihe" to "百合", "xiaoyuan" to "校園", "kehuan" to "科幻", "dongfang" to "東方",
        "danmei" to "耽美", "gedou" to "格鬥", "teenslove" to "TL", "shenghuo" to "生活",
        "qingxiaoshuo" to "輕小說", "qita" to "其他", "xuanyi" to "懸疑", "mengxi" to "萌系",
        "shengui" to "神鬼", "zhichang" to "職場", "zhiyu" to "治癒", "jiecao" to "節操",
        "sige" to "四格", "gaoxiao" to "搞笑", "changtiao" to "長條", "jingji" to "競技",
        "mohuan" to "魔幻", "jianniang" to "艦娘", "weiniang" to "偽娘", "rexue" to "熱血",
        "jingsong" to "驚悚", "meishi" to "美食", "xingzhuanhuan" to "性轉換", "lizhi" to "勵志",
        "hougong" to "後宮", "yinyuewudao" to "音樂舞蹈", "zhentan" to "偵探", "aa" to "AA",
        "yishijie" to "異世界", "lishi" to "歷史", "chuanyue" to "穿越", "jizhan" to "機戰",
        "zhanzheng" to "戰爭", "dushi" to "都市", "chongsheng" to "重生", "kongbu" to "恐怖",
        "shengcun" to "生存", "wuxia" to "武俠", "zhaixi" to "宅系", "zhuansheng" to "轉生",
        "fate" to "FATE", "xianxia" to "仙俠", "love" to "LoveLive",
        "comiket95" to "C95", "comiket96" to "C96", "comiket97" to "C97", "comiket99" to "C99",
        "comiket100" to "C100", "comiket101" to "C101", "comiket102" to "C102",
        "comiket103" to "C103", "comiket104" to "C104", "comiket105" to "C105",
        "comiket106" to "C106", "comiket107" to "C107", "comiket108" to "C108",
        "zazhifuzengxiezhenji" to "雜誌附贈寫真集",
    )

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
