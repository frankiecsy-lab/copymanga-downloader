package com.mangacopy.app.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mangacopy.app.data.Db
import com.mangacopy.app.data.Storage
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 產物生成 — 1:1 移植 cm/outputs.py：
 *   CBZ  ：每章頁面打 zip → <漫畫dir>/<名>_<第一位作者>_vol_NN.cbz（冪等：缺/舊先重建）
 *   WEBP ：非 webp 頁面就地上轉 webp（桌面用 Pillow，Android 用 BitmapFactory.compress）
 * 兩個開關存喺 kv（output_cbz / output_webp），切換後對已下載章節重新 ensure 即可，唔使重落。
 */
object Outputs {

    fun outputFlags(db: Db): Pair<Boolean, Boolean> {
        val cbz = db.getMeta("output_cbz")
        val webp = db.getMeta("output_webp")
        if (cbz == null && webp == null) return true to true   // 默認兩個都開
        return (cbz != "0") to (webp != "0")
    }

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
            FileOutputStream(newPath).use { bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }
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

    /** 對齊桌面 ensure_outputs：將所選輸出格式套用到已完成章節（冪等）。 */
    fun ensureOutputs(db: Db, slug: String, cid: String, comicDir: File): List<String> {
        val (cbzOn, webpOn) = outputFlags(db)
        val msgs = mutableListOf<String>()
        if (webpOn) {
            val n = ensureWebp(db, slug, cid)
            if (n > 0) msgs += "  ⇄ $n 頁已轉為 WebP"
        }
        if (cbzOn) {
            val (p, rebuilt) = ensureCbz(db, slug, cid, comicDir)
            if (p != null && rebuilt) msgs += "  📦 CBZ：${p.name}"
        }
        return msgs
    }

    /** 刪除漫畫喺磁碟上嘅所有檔案（漫畫目錄整個刪）。 */
    fun deleteComicFiles(db: Db, slug: String) {
        Storage.existingComicDir(db.firstImagePath(slug))?.deleteRecursively()
    }
}
