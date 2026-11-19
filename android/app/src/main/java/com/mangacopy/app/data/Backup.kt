package com.mangacopy.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * 備份匯出／匯入（用戶 2026-11-18：「使用者備份所有設置及書架」）—
 * 純 JSON 檔（SAF 文件選擇器讀寫，人眼可讀、跨機可攜）：
 *   settings  — 只匯出**用戶設置**（白名單）；新章記時/閱讀進度/倉庫游標等
 *               本機內部狀態一律唔好帶走（帶去另一部機只會污染）
 *   bookshelf — 收藏漫畫行（path_word＋名/作者/封面/狀態/簡介），
 *               匯入即 upsert＋setFavorite(true)，返新機即刻有書架
 * 下載嘅圖片/章節內容唔納入（體積過大，靠重新下載）。
 */
object Backup {

    /** 匯出嘅設置 key 白名單（新增設置要喺呢度加，否則備份唔到）。
     *  字串對應：ReaderPrefs（SettingsScreen.kt）／list_mode（App.kt ListMode）／
     *  ui_theme（Theme.kt UiTheme）— data 層唔反 import ui 層，所以用字面值。 */
    val SETTING_KEYS = listOf(
        "read_mode", "read_rtl", "read_spread", "read_keep_screen_on", "read_immersive",
        "list_mode", "ui_theme", "notify_ch",
        "rotation_lock", "rotation_lock_dir",   // v0.3.7 旋轉鎖定（方向都要跟備份走）
    )

    fun exportJson(db: Db): String {
        val root = JSONObject()
        root.put("app", "kiecomic")
        root.put("backup_version", 1)
        root.put("exported_at", System.currentTimeMillis())
        val s = JSONObject()
        SETTING_KEYS.forEach { k -> db.getMeta(k)?.let { s.put(k, it) } }
        root.put("settings", s)
        val arr = JSONArray()
        db.listComics(favoriteOnly = true).forEach { c ->
            arr.put(JSONObject()
                .put("path_word", c.pathWord)
                .put("name", c.name ?: "")
                .put("author", c.author ?: "")
                .put("cover_url", c.coverUrl ?: "")
                .put("serial_status", c.serialStatus ?: "")
                .put("synopsis", c.synopsis ?: ""))
        }
        root.put("bookshelf", arr)
        return root.toString(2)
    }

    /** 匯入；返回 (設置項數, 書架部數)。格式唔啱即刻擲 IOException。 */
    fun importJson(db: Db, text: String): Pair<Int, Int> {
        val root = try { JSONObject(text) } catch (e: Exception) {
            throw IOException("JSON 解析失敗：唔係有效備份檔")
        }
        if (root.optString("app") != "kiecomic")
            throw IOException("唔係 KIECOMIC 備份檔（app 欄位唔啱）")
        var nSet = 0
        root.optJSONObject("settings")?.let { o ->
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k in SETTING_KEYS) { db.setMeta(k, o.optString(k)); nSet++ }
            }
        }
        var nShelf = 0
        root.optJSONArray("bookshelf")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pw = o.optString("path_word")
                if (pw.isEmpty()) continue
                db.upsertComic(pw,
                    name = o.optString("name").ifEmpty { null },
                    author = o.optString("author").ifEmpty { null },
                    coverUrl = o.optString("cover_url").ifEmpty { null },
                    serialStatus = o.optString("serial_status").ifEmpty { null },
                    synopsis = o.optString("synopsis").ifEmpty { null })
                db.setFavorite(pw, true)
                nShelf++
            }
        }
        return nSet to nShelf
    }
}
