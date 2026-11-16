package com.mangacopy.app.data

import android.content.Context
import java.io.File

/**
 * 磁碟佈局 — 對應桌面版 config.DOWNLOADS_DIR（桌面版用 EXE 旁邊嘅 downloads/，
 * Android 用 app 專屬外部目錄：/sdcard/Android/data/com.mangacopy.app/files/downloads/
 * — 唔使任何運行時權限，卸載即自動清理，文件管理器亦可见）。
 *
 * 目錄結構同桌面版一致：<downloads>/<狀態標籤>/<漫畫名 - 作者>/<章節標題>/NNN.webp
 * 每卷 CBZ：<downloads>/<狀態>/<漫畫 - 作者>/<名>_<作者>_vol_NN.cbz
 */
object Storage {
    lateinit var root: File private set        // getExternalFilesDir(null)
    lateinit var downloadsDir: File private set

    fun init(context: Context) {
        root = context.getExternalFilesDir(null) ?: context.filesDir
        downloadsDir = File(root, "downloads").apply { mkdirs() }
        File(root, "covers").apply { mkdirs() }   // 封面縮圖 cache
    }

    /** 同桌面版 _safe()：Windows 禁用字元一律換底線（DB 同桌面互通，保持同一套規則）。 */
    fun safe(name: String?): String {
        val n = (name ?: "").trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").trim(' ', '.')
        return n.take(80).ifEmpty { "untitled" }
    }

    fun safeOrEmpty(s: String?): String {
        val n = (s ?: "").trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").trim(' ', '.')
        return n.take(80)
    }

    /** 漫畫根目錄（新下載）：<downloads>/<狀態|未分類>/<名 - 作者>。 */
    fun newComicDir(comic: Db.Comic): File {
        val label = comic.serialStatus?.takeIf { it.isNotBlank() } ?: AppConfig.STATUS_UNKNOWN
        val folder = comic.firstAuthor.let { fa -> if (fa.isNotEmpty()) "${comic.displayName} - $fa" else comic.displayName }
        return File(File(downloadsDir, safe(label)), safeOrEmpty(folder).ifEmpty { comic.pathWord })
    }

    /** 已有檔案嘅漫畫沿用現有目錄（同桌面版：以第一張已下載圖片嘅位置為準，避免孤兒檔）。 */
    fun existingComicDir(firstImagePath: String?): File? =
        firstImagePath?.let { File(it).parentFile?.parentFile }?.takeIf { it.exists() }

    fun chapterDir(comicDir: File, chapterTitle: String?): File =
        File(comicDir, safe(chapterTitle))
}
