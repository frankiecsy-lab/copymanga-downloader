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

    @Test
    fun `章節URL域名自癒 - 死域名換活域名保留路徑`() {
        // 站域名日變 + 路由器 DNS 劫持（AGENTS.md 知識庫 #11）：DB 舊章 URL 照 probe 結果 rebase
        val dead = "https://www.mangacopy.com/comic/yiquanchaoren/chapter/efbce9a3-3c7e-11f1-9278-fa163e02432f"
        val base = "https://www.copymanga.fun"
        assertEquals(
            "$base/comic/yiquanchaoren/chapter/efbce9a3-3c7e-11f1-9278-fa163e02432f",
            com.mangacopy.app.source.CopyMangaSource.rebaseChapterUrl(dead, base),
        )
        // 同域名 → 原樣返回（唔好郁已正常嘅 URL）
        assertEquals(base + "/comic/x/chapter/1",
            com.mangacopy.app.source.CopyMangaSource.rebaseChapterUrl(base + "/comic/x/chapter/1", base))
        // 垃圾輸入 → 原樣返回唔炸
        assertEquals("not-a-url",
            com.mangacopy.app.source.CopyMangaSource.rebaseChapterUrl("not-a-url", base))
    }

    // ---------- UpdateChecker 版本比較（GitHub 檢查更新用） ----------

    @Test
    fun `版本比較 - 數值逐段、10 大過 9`() {
        assertTrue(com.mangacopy.app.data.UpdateChecker.cmp("0.3.5", "0.3.4") > 0)
        assertTrue(com.mangacopy.app.data.UpdateChecker.cmp("0.3.10", "0.3.9") > 0)   // 唔可以字串比較
        assertTrue(com.mangacopy.app.data.UpdateChecker.cmp("0.4.0", "0.3.9") > 0)
        assertTrue(com.mangacopy.app.data.UpdateChecker.cmp("0.3.3", "0.3.4") < 0)
        assertEquals(0, com.mangacopy.app.data.UpdateChecker.cmp("0.3.4", "0.3.4"))
        assertTrue(com.mangacopy.app.data.UpdateChecker.cmp("0.3.4.1", "0.3.4") > 0)  // 多一段視為更新
    }

    // ---------- 卷 TAB 分組（v0.3.7 詳情頁卷過濾用） ----------

    @Test
    fun `體積分組 - 卷數擷取（阿拉伯＋中文數字）`() {
        val v = com.mangacopy.app.source.ChapterGroups::volumeOf
        assertEquals(17, v("第17卷"))
        assertEquals(22, v("第22卷附錄+番外"))          // 附錄歸所属卷
        assertEquals(3, v("第 3 卷 起點"))              // 有空格都認
        assertNull(v("第405话 激突"))                    // 單話冇卷
        assertNull(v("番外：某日的故事"))
        assertNull(v(null))
        assertEquals(11, com.mangacopy.app.source.ChapterGroups.cjkToInt("十一"))
        assertEquals(23, com.mangacopy.app.source.ChapterGroups.cjkToInt("二十三"))
        assertEquals(105, com.mangacopy.app.source.ChapterGroups.cjkToInt("一百五"))
        assertEquals(113, com.mangacopy.app.source.ChapterGroups.cjkToInt("一百一十三"))
    }

    private fun escapeAttr(s: String) =
        s.replace("&", "&amp;").replace("\"", "&quot;")
}
