package com.mangacopy.app.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.mangacopy.app.data.Db
import com.mangacopy.app.data.Storage
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 產物生成 — 1:1 移植 cm/outputs.py：
 *   WEBP ：非 webp 頁面就地上轉 webp（桌面用 Pillow，Android 用 BitmapFactory.compress）
 *   CBZ  ：打 zip 打包（2026-11-17 用戶：「刪除 CBZ 嘅選擇，默認全部 WEBP」—
 *          ensureOutputs 已唔再自動打包；函數保留俾日後需要／清理舊檔用）
 */
object Outputs {

    /** <名>_<第一位作者>_vol_NN.cbz — NN = 章節按 title 排序嘅 1-based 位置（同桌面版）。 */
    fun cbzFilename(db: Db, slug: String, cid: String): String {
        val comic = db.getComic(slug)
        val name = Storage.safeOrEmpty(comic?.name).ifEmpty { slug }
        val author = Storage.safeOrEmpty(comic?.firstAuthor)
        val chapters = db.allChapters(slug)
        val idx = chapters.indexOfFirst { it.chapterId == cid }.takeIf { it >= 0 }?.plus(1)
        val vol = idx?.let { "vol_%02d".format(it) } ?: "vol_00"
        return listOf(name, author, vol).filter { it.isNotEmpty() }.joinToString("_") + ".cbz"
    }

    private fun donePages(db: Db, slug: String, cid: String): List<Pair<Int, File>> =
        db.allImages(slug, cid)
            .filter { it.status == "done" && it.path != null }
            .mapNotNull { pi -> File(pi.path!!).takeIf { it.exists() }?.let { pi.pageIndex to it } }

    /** 一張圖轉 webp（Android 版 Pillow）：解碼 → WEBP_LOSSY 85 → 替換原檔。 */
    private fun convertToWebp(path: String): String? {
        return try {
            val bmp = BitmapFactory.decodeFile(path) ?: return null
            val newPath = path.substringBeforeLast('.') + ".webp"
            // WEBP_LOSSY 要 API 30；minSdk 26 嘅舊機 fallback 舊 WEBP enum（30 開頭 mark deprecated，功能不變）
            val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
                      else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            FileOutputStream(newPath).use { bmp.compress(fmt, 85, it) }
            bmp.recycle()
            File(path).delete()
            newPath
        } catch (e: Exception) {
            null
        }
    }

    fun ensureWebp(db: Db, slug: String, cid: String): Int {
        var n = 0
        for ((pi, f) in donePages(db, slug, cid)) {
            if (f.name.lowercase().endsWith(".webp")) continue
            val newPath = convertToWebp(f.absolutePath) ?: continue
            db.updateImagePath(slug, cid, pi, newPath)
            n++
        }
        return n
    }

    /** cbz 缺失或任何頁面比佢新 → 要重建。 */
    private fun cbzStale(cbz: File, pages: List<Pair<Int, File>>): Boolean {
        if (!cbz.exists()) return true
        val mtime = cbz.lastModified()
        return pages.any { (_, p) -> runCatching { p.lastModified() > mtime }.getOrDefault(false) }
    }

    fun ensureCbz(db: Db, slug: String, cid: String, comicDir: File): Pair<File?, Boolean> {
        val pages = donePages(db, slug, cid)
        if (pages.isEmpty()) return null to false
        val cbz = File(comicDir, cbzFilename(db, slug, cid))
        // 清理舊命名 scheme 嘅 cbz（只限同一漫畫目錄內）
        val old = db.getChapter(slug, cid)?.cbzPath
        if (old != null) {
            val oldFile = File(old)
            if (oldFile.absolutePath != cbz.absolutePath &&
                oldFile.parentFile?.absolutePath == comicDir.absolutePath
            ) runCatching { oldFile.delete() }
        }
        if (!cbzStale(cbz, pages)) return cbz to false
        ZipOutputStream(cbz.outputStream().buffered()).use { z ->
            for ((_, p) in pages.sortedBy { it.second.name }) {
                z.putNextEntry(ZipEntry(p.name))
                p.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        db.setChapterCbz(slug, cid, cbz.absolutePath)
        return cbz to true
    }

    /** 輸出 = 全部 WebP（用戶 2026-11-17 指定，CBZ 開關已刪除）：已完成章節就地轉 webp（冪等）。 */
    fun ensureOutputs(db: Db, slug: String, cid: String, comicDir: File): List<String> {
        val msgs = mutableListOf<String>()
        val n = ensureWebp(db, slug, cid)
        if (n > 0) msgs += "  ⇄ $n 頁已轉為 WebP"
        return msgs
    }

    /** 刪除漫畫喺磁碟上嘅所有檔案（漫畫目錄整個刪）。 */
    fun deleteComicFiles(db: Db, slug: String) {
        Storage.existingComicDir(db.firstImagePath(slug))?.deleteRecursively()
    }

    /** 只刪除 CBZ 壓縮檔（圖片保留）—「CBZ 選擇刪除」。 */
    fun deleteCbzFiles(db: Db, slug: String): Int {
        val dir = Storage.existingComicDir(db.firstImagePath(slug)) ?: return 0
        var n = 0
        dir.walkTopDown().filter { it.isFile && it.extension.equals("cbz", true) }.forEach {
            if (it.delete()) n++
        }
        if (n > 0) db.clearCbzPaths(slug)
        return n
    }
}
