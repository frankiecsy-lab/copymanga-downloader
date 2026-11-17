package com.mangacopy.app.source

/** 來源層共用資料模型（跨「下載源」抽象嘅通用格式）。 */

data class ComicMeta(
    val pathWord: String,
    val name: String,
    val author: String,
    val coverUrl: String?,
    val status: Int?,          // 站點原始 status int：0=連載中 1=已完結 2=短篇
    val statusLabel: String?,
    val tags: List<String> = emptyList(),   // 種類標籤（searchcl theme/parodies；站點維護時可能空）
)

data class ChapterRef(val chapterId: String, val title: String, val url: String)

data class DetailResult(
    val synopsis: String?,
    val chapters: List<ChapterRef>,
    val serialStatus: String?,
)

/** 一頁章節渲染結果：圖片 URL（閱讀順序）+ 浮動徽章嘅總頁數（可能冇）。 */
data class ChapterPages(val urls: List<String>, val expectedTotal: Int?)

data class BrowsePage(val items: List<ComicMeta>, val total: Int?)
data class SearchPage(val items: List<ComicMeta>, val total: Int?)

/**
 * 「下載源」抽象 — 日後新增源只需實話呢個接口再喺 SourceRegistry 註冊。
 * 能力對應：browse（純 HTTP 列表）/ search（站內搜索 API）/
 * detail + chapterImageUrls（WebView 渲染，後者可增量回調俾即時預載）。
 */
interface MangaSource {
    val id: String
    val displayName: String
    suspend fun browse(offset: Int, status: Int?): BrowsePage
    suspend fun search(query: String, offset: Int, limit: Int = 20): SearchPage
    suspend fun detail(slug: String): DetailResult
    /** onPartial：每當 DOM 出現新圖片即時回調（URL 清單 + 徽章總數）— 閱讀器即時預載用。 */
    suspend fun chapterImageUrls(
        url: String,
        onPartial: (List<String>, Int?) -> Unit = { _, _ -> },
    ): ChapterPages
}

object SourceRegistry {
    val all: List<MangaSource> by lazy { listOf(CopyMangaSource) }
    val default: MangaSource get() = all.first()
    fun byId(id: String?): MangaSource = all.firstOrNull { it.id == id } ?: default
}
