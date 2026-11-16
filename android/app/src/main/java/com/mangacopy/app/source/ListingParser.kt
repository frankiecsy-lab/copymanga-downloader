package com.mangacopy.app.source

import org.json.JSONArray
import org.json.JSONObject

/**
 * 漫畫列表 HTML 解析 — 1:1 移植自 cm/listing.py。
 * /comics 頁冇 WAF（純 HTML），每頁將条目以 python-dict-style JSON 嵌入
 * <div class="exemptComic-box" total="N" list="..."> 嘅 list= 屬性。
 *
 * 兩級解析（同桌面版）：
 *   1) 正規路徑：html unescape → ' 換 " → JSON parse
 *   2) fallback：直接 regex 抽 path_word/name/cover/status（保命，唔會整啲漫畫）
 */
object ListingParser {

    data class Item(
        val pathWord: String,
        val name: String,
        val author: String,
        val coverUrl: String?,
        val status: Int?,
    )

    data class Page(val items: List<Item>, val total: Int?)

    private val LIST_RE = Regex(
        "exemptComic-box[^>]*?total=\"(\\d+)\"[^>]*?list=\"([^\"]+)\"",
        RegexOption.DOT_MATCHES_ALL
    )

    fun parse(html: String): Page {
        val m = LIST_RE.find(html) ?: return Page(emptyList(), null)
        val total = m.groupValues[1].toIntOrNull()
        val raw = unescapeHtml(m.groupValues[2])
        val items = try {
            jsonPath(raw)
        } catch (e: Exception) {
            fallbackPath(raw)
        }
        return Page(items.filter { it.pathWord.isNotEmpty() }, total)
    }

    /** 主路徑：python dict literal → JSON。 */
    private fun jsonPath(raw: String): List<Item> {
        val arr = JSONArray(raw.replace("'", "\""))
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val d: JSONObject = arr.optJSONObject(i) ?: continue
            val author = d.optJSONArray("author")?.let { a ->
                (0 until a.length())
                    .mapNotNull { a.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotEmpty() } }
                    .joinToString(", ")
            } ?: ""
            val pw = d.optString("path_word")
            if (pw.isEmpty()) continue
            out.add(
                Item(
                    pathWord = pw,
                    name = d.optString("name").trim(),
                    author = author,
                    coverUrl = d.optString("cover").takeIf { it.isNotEmpty() },
                    status = if (d.isNull("status")) null else d.optInt("status", -1).takeIf { it >= 0 },
                )
            )
        }
        return out
    }

    /** 保命路徑：直接 regex 抽關鍵欄位（同桌面版 fallback 一致，按 index 對齊）。 */
    private val PW_RE = Regex("'path_word':\\s*'([^']+)'")
    private val NAME_RE = Regex("'name':\\s*'([^']*)'")
    private val COVER_RE = Regex("'cover':\\s*'([^']*)'")
    private val STATUS_RE = Regex("'status':\\s*(\\d+)")

    private fun fallbackPath(raw: String): List<Item> {
        val pws = PW_RE.findAll(raw).map { it.groupValues[1] }.toList()
        val names = NAME_RE.findAll(raw).map { it.groupValues[1] }.toList()
        val covers = COVER_RE.findAll(raw).map { it.groupValues[1] }.toList()
        val statuses = STATUS_RE.findAll(raw).map { it.groupValues[1].toInt() }.toList()
        return pws.mapIndexed { i, pw ->
            Item(
                pathWord = pw,
                name = names.getOrNull(i) ?: "",
                author = "",
                coverUrl = covers.getOrNull(i)?.takeIf { it.isNotEmpty() },
                status = statuses.getOrNull(i),
            )
        }
    }

    /** html.unescape 常用子集（清單資料只涉及呢幾個 entity）。 */
    fun unescapeHtml(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
            .replace("&apos;", "'")
            .replace(Regex("&#(\\d+);")) { m ->
                m.groupValues[1].toIntOrNull()?.takeIf { it in 32..0x10FFFF }
                    ?.let { String(Character.toChars(it)) } ?: m.value
            }
            .replace("&amp;", "&")   // & 必須最後解（防止 &amp;lt; 雙重解碼）
}
