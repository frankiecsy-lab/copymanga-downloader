package com.mangacopy.app.source

/** 來源層共用資料模型（跨「下載源」抽象嘅通用格式）。 */

data class ComicMeta(
    val pathWord: String,
    val name: String,
    val author: String,
    val coverUrl: String?,
    val status: Int?,          // 站點原始 status int：0=連載中 1=已完結 2=短篇
    val statusLabel: String?,
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

/**
 * 「下載源」抽象 — 日後新增源只需實話呢個接口再喺 SourceRegistry 註冊。
 * 三個能力對應桌面版三個模組：browse（純 HTTP 列表）/ detail + chapterImageUrls（WebView 渲染）。
 */
interface MangaSource {
    val id: String
    val displayName: String
    suspend fun browse(offset: Int, status: Int?): BrowsePage
    suspend fun detail(slug: String): DetailResult
    suspend fun chapterImageUrls(url: String): ChapterPages
}

object SourceRegistry {
    val all: List<MangaSource> by lazy { listOf(CopyMangaSource) }
    val default: MangaSource get() = all.first()
    fun byId(id: String?): MangaSource = all.firstOrNull { it.id == id } ?: default
}
