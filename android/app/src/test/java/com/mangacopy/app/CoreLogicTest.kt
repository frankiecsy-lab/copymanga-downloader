package com.mangacopy.app

import com.mangacopy.app.download.ImageDownloader
import com.mangacopy.app.source.ListingParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 純邏輯單元測試（JVM）— 覆蓋移植自桌面版嘅關鍵解析／命名代碼。
 *  fixtures 對照 cm/listing.py / cm/download.py 嘅真實格式。
 */
class CoreLogicTest {

    // ---------- ListingParser ----------

    @Test
    fun `主路徑解析 - python dict literal 轉 JSON`() {
        val raw = "[{'path_word': 'man-x', 'name': '某漫畫', 'author': [{'name': '作者A'}, {'name': 'B'}], " +
            "'cover': 'https://cdn/c.jpg', 'status': 1}]"
        val html = """<div class="exemptComic-box" total="123" list="${escapeAttr(raw)}"></div>"""
        val page = ListingParser.parse(html)
        assertEquals(123, page.total)
        assertEquals(1, page.items.size)
        val it = page.items[0]
        assertEquals("man-x", it.pathWord)
        assertEquals("某漫畫", it.name)
        assertEquals("作者A, B", it.author)
        assertEquals("https://cdn/c.jpg", it.coverUrl)
        assertEquals(1, it.status)
    }

    @Test
    fun `entity 編碼 - 單引號同 & 要正確還原`() {
        val raw = "&#x27;path_word&#x27;: &#x27;abc&#x27;, &#x27;name&#x27;: &#x27;A &amp; B&#x27;"
        val out = ListingParser.unescapeHtml(raw)
        assertTrue(out.contains("'path_word': 'abc'"))
        assertTrue(out.contains("'name': 'A & B'"))
    }

    @Test
    fun `fallback 路徑 - 壞 JSON 都抽到 path_word`() {
        // list= 內容唔係有效 JSON（例如 name 內含未轉義單引號）→ regex 保命
        val bad = "[{'path_word': 'good-one', 'name': 'it's broken}, {'path_word': 'two'}]"
        val html = """<div class="exemptComic-box" total="9" list="$bad"></div>"""
        val page = ListingParser.parse(html)
        assertEquals(9, page.total)
        assertTrue(page.items.any { it.pathWord == "good-one" })
        assertTrue(page.items.any { it.pathWord == "two" })
    }

    @Test
    fun `冇 list 屬性 - 返回空`() {
        val page = ListingParser.parse("<html>nothing</html>")
        assertTrue(page.items.isEmpty())
        assertNull(page.total)
    }

    // ---------- ImageDownloader._to_webp ----------

    @Test
    fun `webp 變體 - 尺寸後綴替換 + query 去除`() {
        assertEquals(
            "https://img.x/p/001.c1500x.webp",
            ImageDownloader.toWebp("https://img.x/p/001.c1500x.jpg?token=abc")
        )
        assertEquals(
            "https://img.x/p/002.c1500x.webp",
            ImageDownloader.toWebp("https://img.x/p/002.c800x.webp")
        )
        assertNull(ImageDownloader.toWebp("https://img.x/p/plain.jpg"))
    }

    // ---------- Storage.safe（透過重複嘅消毒規則） ----------

    @Test
    fun `檔名消毒規則 - 唔合法字元換底線`() {
        // Storage.safe 同一套正則（避免測試要拉 Android Context，直接測正則行為）
        val clean = """a\b/c:d*e?f"g<h>i|j""".replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim(' ', '.')
        assertEquals("a_b_c_d_e_f_g_h_i_j", clean)
    }

    private fun escapeAttr(s: String) =
        s.replace("&", "&amp;").replace("\"", "&quot;")
}
