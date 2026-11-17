package com.mangacopy.app.download

import com.mangacopy.app.data.AppConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 圖片下載 — 1:1 移植 cm/download.py。
 * 圖片域名冇 WAF：純 HTTP。每頁優先攞 .c1500x.webp（壓縮變體），404 就退回渲染實際請求嘅 URL。
 * 每檔計 sha256 俾 DB 做續傳核對。
 */
object ImageDownloader {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val SIZE_RE = Regex("\\.c\\d+x\\.(webp|jpg|jpeg|png)$", RegexOption.IGNORE_CASE)

    /** 有尺寸後綴 → 換 .c1500x.webp 變體；冇 → null。 */
    fun toWebp(url: String): String? {
        val base = url.substringBefore('?')
        val m = SIZE_RE.find(base) ?: return null
        return base.substring(0, m.range.first) + AppConfig.IMAGE_SIZE_SUFFIX
    }

    data class Result(val path: File, val sha256: String, val size: Long)

    /** 下載一頁到 dest_dir/<page_index+1:03d>.<ext>；重試 3 次（遞增退避）。 */
    fun downloadImage(url: String, destDir: File, pageIndex: Int): Result {
        destDir.mkdirs()
        val candidates = mutableListOf<String>()
        toWebp(url)?.let { candidates.add(it) }
        candidates.add(url.substringBefore('?'))

        var lastErr = "unknown"
        for (cand in candidates) {
            val low = cand.lowercase()
            val ext = when {
                low.endsWith(".webp") -> ".webp"
                low.endsWith(".jpg") || low.endsWith(".jpeg") -> ".jpg"
                else -> ".png"
            }
            val dest = File(destDir, "%03d".format(pageIndex + 1) + ext)
            for (attempt in 0 until AppConfig.DOWNLOAD_RETRIES) {
                var missingVariant = false
                try {
                    val request = Request.Builder().url(cand)
                        .header("User-Agent", AppConfig.USER_AGENT).build()
                    http.newCall(request).execute().use { resp ->
                        val bytes = resp.body?.bytes()
                        if (resp.isSuccessful && bytes != null && bytes.isNotEmpty()) {
                            dest.writeBytes(bytes)
                            val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
                                .joinToString("") { "%02x".format(it) }
                            return Result(dest, sha, bytes.size.toLong())
                        }
                        lastErr = "HTTP ${resp.code}"
                        // 404 = webp 壓縮變體唔存在 → 即刻 fallback 返原 URL，
                        // 唔好食 retries sleep（之前每頁白等 1-3 秒 = 「每頁好慢」元兇）
                        missingVariant = resp.code == 404
                    }
                } catch (e: Exception) {
                    lastErr = e.message ?: "error"
                }
                if (missingVariant) break
                Thread.sleep(1_000L + attempt * 1_000L)
            }
        }
        error("failed to download image after retries: $url ($lastErr)")
    }
}
