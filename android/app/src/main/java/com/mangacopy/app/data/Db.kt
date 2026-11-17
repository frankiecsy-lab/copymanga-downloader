package com.mangacopy.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * SQLite 狀態機 — 1:1 移植自 cm/db.py，停止/恢復零丟失嘅核心：
 * 每行 comic / chapter / image 都帶 status + checksum，重啟後跳過所有 done。
 * 表結構同桌面版一致（comics / chapters / images / kv），方便日後匯入桌面 DB。
 */
class Db private constructor(context: Context) :
    // v5：comics.pinned（書架置頂）— 新版本號先會觸發 onUpgrade → createSchema 嘅冪等 ALTER
    SQLiteOpenHelper(context.applicationContext, "mangacopy.db", null, 5) {

    companion object {
        @Volatile private var instance: Db? = null
        fun init(context: Context) {
            instance ?: synchronized(this) {
                instance ?: Db(context).also {
                    it.writableDatabase // 觸發 onCreate
                    // 【教訓】onUpgrade 只在 version 號變動先跑 — 同一 version 內新加嘅
                    // 冪等 ALTER（例如 v5 加完 pinned 再加 paused）對已裝機用戶永遠唔會執行
                    // → 「no such column」開機即崩（2026-11-17 連踩兩次 pinned/paused）。
                    // 正解：冪等遷移每次開 DB 都跑一次（ALTER duplicate → runCatching 吞）。
                    it.ensureColumns(it.writableDatabase)
                    instance = it
                }
            }
        }
        fun get(): Db = instance ?: error("Db 未初始化 — 先調用 Db.init()")
        private fun now(): String =
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onOpen(db: SQLiteDatabase) {
        // journal_mode setter 會回傳一行結果 → execSQL 會炸（SQLITE_OK + "queries ... rawQuery only"），必須 rawQuery
        db.rawQuery("PRAGMA journal_mode=WAL", null).use { it.moveToFirst() }
        db.execSQL("PRAGMA synchronous=NORMAL")     // setter 無回傳行，execSQL 安全
    }

    override fun onCreate(db: SQLiteDatabase) = createSchema(db)

    // v1 有 bug：多语句 execSQL 得咗第一句（net 只有 comics 表）→ v2 用逐句建表；
    // 全部 IF NOT EXISTS，舊庫 upgrade 即自愈
    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) = createSchema(db)

    /** 【重要】Android execSQL 一次只執行一句 — 多语句字串會靜默只行第一句，必須逐句分開。 */
    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS comics (
                path_word   TEXT PRIMARY KEY,
                name        TEXT,
                author      TEXT,
                cover_url   TEXT,
                synopsis    TEXT,
                serial_status TEXT DEFAULT '',
                status      TEXT DEFAULT 'new',
                selected    INTEGER DEFAULT 0,
                favorite    INTEGER DEFAULT 0,
                last_seen   TEXT,
                updated_at  TEXT
            )""".trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chapters (
                comic_path_word TEXT,
                chapter_id      TEXT,
                title           TEXT,
                url             TEXT,
                status          TEXT DEFAULT 'pending',
                image_count     INTEGER DEFAULT 0,
                expected_pages  INTEGER DEFAULT 0,
                cbz_path        TEXT,
                updated_at      TEXT,
                PRIMARY KEY (comic_path_word, chapter_id)
            )""".trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS images (
                comic_path_word TEXT,
                chapter_id      TEXT,
                page_index      INTEGER,
                url             TEXT,
                path            TEXT,
                sha256          TEXT,
                size            INTEGER,
                status          TEXT DEFAULT 'pending',
                updated_at      TEXT,
                PRIMARY KEY (comic_path_word, chapter_id, page_index)
            )""".trimIndent()
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS history (path_word TEXT PRIMARY KEY, viewed_at TEXT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chapters_status ON chapters(comic_path_word, status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_images_status ON images(chapter_id, status)")
        // v4：隊列次序 + 站點原始章節序（流式）
        ensureColumns(db)
    }

    /**
     * 冪等加欄 — 每次開 DB 都跑（見 init() 教訓）：已存在報 duplicate column → 吞咗。
     * 日後新加欄一律加喺呢度。
     */
    private fun ensureColumns(db: SQLiteDatabase) {
        runCatching { db.execSQL("ALTER TABLE comics ADD COLUMN queue_order INTEGER DEFAULT 0") }
        runCatching { db.execSQL("ALTER TABLE chapters ADD COLUMN sort_index INTEGER DEFAULT 0") }
        // v5：取消下載 paused ＋ 書架置頂 pinned（用戶要求）
        runCatching { db.execSQL("ALTER TABLE comics ADD COLUMN paused INTEGER DEFAULT 0") }
        runCatching { db.execSQL("ALTER TABLE comics ADD COLUMN pinned INTEGER DEFAULT 0") }
    }

    // ------------------------------------------------------------------ kv/meta
    fun setMeta(key: String, value: String) {
        writableDatabase.execSQL(
            "INSERT INTO kv(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            arrayOf(key, value)
        )
    }

    fun getMeta(key: String, default: String? = null): String? =
        readableDatabase.rawQuery("SELECT value FROM kv WHERE key=?", arrayOf(key))
            .use { if (it.moveToFirst()) it.getString(0) else default }

    // ------------------------------------------------------------------ comics
    /** 只覆蓋本次有值嘅欄位（同桌面版 upsert 語義一致，唔會用空值抹走舊資料）。 */
    fun upsertComic(
        pathWord: String, name: String? = null, author: String? = null,
        coverUrl: String? = null, synopsis: String? = null, serialStatus: String? = null,
    ) {
        val db = writableDatabase
        val exists = db.rawQuery(
            "SELECT 1 FROM comics WHERE path_word=?", arrayOf(pathWord)
        ).use { it.moveToFirst() }
        if (!exists) {
            db.execSQL(
                "INSERT INTO comics(path_word,name,author,cover_url,synopsis,serial_status,status,last_seen,updated_at) " +
                    "VALUES(?,?,?,?,?,?,'new',?,?)",
                arrayOf(pathWord, name, author, coverUrl, synopsis, serialStatus ?: "", now(), now())
            )
        } else {
            val sets = StringBuilder("last_seen=?,updated_at=?")
            val vals = mutableListOf<Any?>(now(), now())
            listOf(
                "name" to name, "author" to author, "cover_url" to coverUrl,
                "synopsis" to synopsis, "serial_status" to serialStatus
            ).forEach { (col, v) ->
                if (!v.isNullOrEmpty()) { sets.append(", $col=?"); vals.add(v) }
            }
            vals.add(pathWord)
            db.execSQL("UPDATE comics SET $sets WHERE path_word=?", vals.toTypedArray())
        }
    }

    data class Comic(
        val pathWord: String, val name: String?, val author: String?, val coverUrl: String?,
        val synopsis: String?, val serialStatus: String?, val status: String?, val favorite: Boolean,
        val pinned: Boolean = false,
    ) {
        val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: pathWord
        val firstAuthor: String get() = (author ?: "").split(",", "，").firstOrNull()?.trim() ?: ""
    }

    fun getComic(pathWord: String): Comic? =
        readableDatabase.rawQuery("SELECT * FROM comics WHERE path_word=?", arrayOf(pathWord))
            .use { if (it.moveToFirst()) comicFrom(it) else null }

    /** 書架列表：收藏優先排序同桌面版（updated_at DESC）；置頂（pinned）排最前。 */
    fun listComics(search: String = "", favoriteOnly: Boolean = false): List<Comic> {
        val q = "%${search}%"
        val sql = StringBuilder("SELECT * FROM comics WHERE (name LIKE ? OR author LIKE ?)")
        val args = mutableListOf(q, q)
        if (favoriteOnly) sql.append(" AND favorite=1")
        sql.append(" ORDER BY pinned DESC, updated_at DESC")
        return readableDatabase.rawQuery(sql.toString(), args.toTypedArray())
            .use { c -> generateSequence { if (c.moveToNext()) comicFrom(c) else null }.toList() }
    }

    fun favoritePathWords(): List<String> =
        readableDatabase.rawQuery(
            "SELECT path_word FROM comics WHERE favorite=1 ORDER BY updated_at DESC", null
        ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }

    fun setFavorite(pathWord: String, fav: Boolean) {
        writableDatabase.execSQL(
            "UPDATE comics SET favorite=? WHERE path_word=?",
            arrayOf(if (fav) 1 else 0, pathWord)
        )
    }

    /** 書架置頂開關 — 唔碰 updated_at（否则置頂本身會觸發重新排序）。 */
    fun setPinned(pathWord: String, pin: Boolean) {
        writableDatabase.execSQL(
            "UPDATE comics SET pinned=? WHERE path_word=?",
            arrayOf(if (pin) 1 else 0, pathWord)
        )
    }

    /**
     * 取消下載（2026-11-17 用戶：「取消未下載的不要移出書架」）— paused=1 即移出下載佇列，
     * 書架／章節紀錄全保留；任何明確嘅 DownloadEngine.start(slugs) 會自動解除。
     */
    fun setPaused(pathWord: String, paused: Boolean) {
        writableDatabase.execSQL(
            "UPDATE comics SET paused=? WHERE path_word=?",
            arrayOf(if (paused) 1 else 0, pathWord)
        )
    }

    fun setComicStatus(pathWord: String, status: String) {
        writableDatabase.execSQL(
            "UPDATE comics SET status=?, updated_at=? WHERE path_word=?",
            arrayOf(status, now(), pathWord)
        )
    }

    /** 移除收藏（唔刪檔案）。 */
    fun removeFromShelf(pathWord: String) = setFavorite(pathWord, false)

    private fun comicFrom(c: Cursor) = Comic(
        pathWord = c.getString(c.getColumnIndexOrThrow("path_word")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        author = c.getString(c.getColumnIndexOrThrow("author")),
        coverUrl = c.getString(c.getColumnIndexOrThrow("cover_url")),
        synopsis = c.getString(c.getColumnIndexOrThrow("synopsis")),
        serialStatus = c.getString(c.getColumnIndexOrThrow("serial_status")),
        status = c.getString(c.getColumnIndexOrThrow("status")),
        favorite = c.getInt(c.getColumnIndexOrThrow("favorite")) == 1,
        pinned = c.getColumnIndex("pinned").takeIf { it >= 0 }
            ?.let { c.getInt(it) == 1 } ?: false,   // 舊庫未 ALTER 時容錯
    )

    // ------------------------------------------------------------------ chapters
    /** 標題入面第一組數字（「第123話」→123）；冇就 null。用嚟偵測章節序方向。 */
    private fun titleNumber(title: String?): Long? =
        title?.let { Regex("\\d+").find(it)?.value?.toLongOrNull() }

    /**
     * 閱讀序正規化：站點詳情頁章節列表可能係「新→舊」（最尾一章排最前）。
     * 跨章節 auto-next（chapterIndex+1）同「第一章」定義都要 flow=舊→新，
     * 所以 upsert 前按標題數字偵測方向：頭尾都攞到數字且頭 > 尾 → 反轉。
     * 攞唔到數字（例：純文字標題）就維持站點原序，唔好亂反。
     */
    private fun inReadingOrder(chapters: List<Triple<String, String, String>>)
        : List<Triple<String, String, String>> {
        if (chapters.size < 2) return chapters
        // 2026-11-17 真站實測修：邊緣章節可能係「番外/增页」等冇數字標題（例如最新
        // 「22卷增页」排第一、最尾一條無號）→ 由兩端向內搵第一粒數字先比較方向
        val a = chapters.take(12).firstNotNullOfOrNull { titleNumber(it.second) }
        val b = chapters.reversed().take(12).firstNotNullOfOrNull { titleNumber(it.second) }
        return if (a != null && b != null && a > b) chapters.reversed() else chapters
    }

    /**
     * 冪等 upsert（同桌面版 add_chapters 一致）：error 章節重設為 pending 以便重試。
     * sort_index = 閱讀序位置（經 inReadingOrder 正規化，舊→新 = 「流式」顯示序）。
     */
    fun addChapters(pathWord: String, chapters: List<Triple<String, String, String>>): Int {
        val db = writableDatabase
        val ordered = inReadingOrder(chapters)
        // 「新章」標記只喺**已有清單**嘅情況下成立（首次建清單 = 全書入庫，唔算新章）。
        // 新插入嘅 cid 累積落 kv「newch:$slug」→ 書架「+N 新章」徽章只計呢啲 cid 仲未
        // 下載（pending）嘅數量（用戶：「要喺更新書單前及後有差別先顯示；新章睇咗就唔好再提示」）
        val hadChapters = db.rawQuery(
            "SELECT 1 FROM chapters WHERE comic_path_word=? LIMIT 1", arrayOf(pathWord)
        ).use { it.moveToFirst() }
        val newCids = mutableListOf<String>()
        db.beginTransaction()
        try {
            for ((i, t) in ordered.withIndex()) {
                val (cid, title, url) = t
                val cur = db.rawQuery(
                    "SELECT status FROM chapters WHERE comic_path_word=? AND chapter_id=?",
                    arrayOf(pathWord, cid)
                )
                val old = cur.use { if (it.moveToFirst()) it.getString(0) else null }
                if (old == null) {
                    db.execSQL(
                        "INSERT INTO chapters(comic_path_word,chapter_id,title,url,status,sort_index,updated_at) " +
                            "VALUES(?,?,?,?,'pending',?,?)",
                        arrayOf(pathWord, cid, title, url, i, now())
                    )
                    newCids += cid
                } else {
                    val newStatus = if (old == "error") "pending" else old
                    db.execSQL(
                        "UPDATE chapters SET title=?, url=?, status=?, sort_index=?, updated_at=? " +
                            "WHERE comic_path_word=? AND chapter_id=?",
                        arrayOf(title, url, newStatus, i, now(), pathWord, cid)
                    )
                }
            }
            // （2026-11-17 回滚：曾加「刪滯留卷組行」清理，後實證一拳超人等漫畫
            // 以「卷」為真實章節單位 — 202 頁巨章就係「第17卷」，刪除會誤殺清單，唔做）
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (hadChapters && newCids.isNotEmpty()) markNewChapters(pathWord, newCids)
        return chapters.size
    }

    /** 累積記低本轮 diff 出嚟嘅新章 cid（kv「newch:$slug」，逗號分隔）。 */
    private fun markNewChapters(pathWord: String, cids: List<String>) {
        val cur = getMeta("newch:$pathWord")?.split(',')?.filter { it.isNotBlank() }
            ?.toMutableSet() ?: mutableSetOf()
        cur += cids
        setMeta("newch:$pathWord", cur.joinToString(","))
    }

    /**
     * 書架「+N 新章」徽章計數 — 只計「上輪更新書單 diff 標記嘅新章」之中仍 pending
     * （未下載）嘅數量：新章下載／睇完 → 自動消失；由頭到尾都冇 diff → 永遠 0。
     */
    fun newChapterPending(pathWord: String): Int {
        val ids = getMeta("newch:$pathWord")?.split(',')?.filter { it.isNotBlank() }?.toSet()
            ?: return 0
        if (ids.isEmpty()) return 0
        val pending = readableDatabase.rawQuery(
            "SELECT chapter_id FROM chapters WHERE comic_path_word=? AND status='pending'",
            arrayOf(pathWord)
        ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet() }
        return ids.intersect(pending).size
    }

    data class Chapter(
        val pathWord: String, val chapterId: String, val title: String?, val url: String?,
        val status: String, val imageCount: Int, val expectedPages: Int, val cbzPath: String?,
    )

    /**
     * 章節清單。order：
     *   title（默認，同桌面版；**CBZ vol 序號永遠用呢個位置**，唔受 UI 排序影響）
     *   flow   = 站點詳情頁原始序（sort_index，用戶預設嘅「流式」）
     *   title_desc / pending_first（未下載／出錯排前面）
     */
    fun allChapters(pathWord: String, order: String = "title"): List<Chapter> {
        // 2026-11-17 真站實測修：236 章用**字符串**序 →「第99话」排喺「第236话」後面、
        // 「第10话」喺「第2话」前面，倒序 UI 頂部唔係最新章。title/title_desc 改**數字序**
        // （標題第一組數字；Kotlin sortedBy 穩定 → 同號/無號以 sort_index 站序做 tie-break；
        // 無號如「22卷增页」正序排尾、倒序排頭 — 增页本来就係最尾先出嘅附加內容）
        if (order == "title" || order == "title_desc") {
            val rows = query(
                "SELECT * FROM chapters WHERE comic_path_word=? ORDER BY sort_index ASC, title ASC",
                arrayOf(pathWord)
            )
            return if (order == "title")
                rows.sortedBy { titleNumber(it.title) ?: Long.MAX_VALUE }          // 升序，無號排尾
            else
                rows.sortedWith(compareByDescending { titleNumber(it.title) ?: Long.MIN_VALUE })  // 降序，無號排最尾
        }
        val ord = when (order) {
            "pending_first" -> "(status='done') ASC, sort_index ASC, title ASC"
            else -> "sort_index ASC, title ASC"   // flow / 其他一律站序
        }
        return query("SELECT * FROM chapters WHERE comic_path_word=? ORDER BY $ord", arrayOf(pathWord))
    }

    fun pendingChapters(pathWord: String): List<Chapter> = query(
        "SELECT * FROM chapters WHERE comic_path_word=? AND status='pending' ORDER BY title",
        arrayOf(pathWord)
    )

    fun getChapter(pathWord: String, cid: String): Chapter? = query(
        "SELECT * FROM chapters WHERE comic_path_word=? AND chapter_id=?", arrayOf(pathWord, cid)
    ).firstOrNull()

    fun resetErrorChapters(pathWord: String) {
        val db = writableDatabase
        db.execSQL(
            "UPDATE chapters SET status='pending', updated_at=? WHERE comic_path_word=? AND status='error'",
            arrayOf(now(), pathWord)
        )
        db.execSQL(
            "UPDATE images SET status='pending', updated_at=? WHERE comic_path_word=? AND status='error'",
            arrayOf(now(), pathWord)
        )
    }

    /** 只重設指定章節（error→pending），done 唔動 — 供「tap 單章下載／批次揀下載」。 */
    fun resetChaptersToPending(pathWord: String, cids: Set<String>) {
        val db = writableDatabase
        for (cid in cids) {
            db.execSQL(
                "UPDATE chapters SET status='pending', updated_at=? " +
                    "WHERE comic_path_word=? AND chapter_id=? AND status='error'",
                arrayOf(now(), pathWord, cid)
            )
        }
    }

    /**
     * 下載佇列：書架（favorite=1）內有 pending/error 章節、且未「取消下載」（paused=0）嘅漫畫。
     *次序 = queue_order（下載 tab ↑↓ 調序）→ updated_at。
     */
    fun comicsNeedingWork(): List<String> = readableDatabase.rawQuery(
        "SELECT DISTINCT ch.comic_path_word FROM chapters ch " +
            "JOIN comics c ON c.path_word = ch.comic_path_word " +
            "WHERE ch.status IN ('pending','error') AND c.favorite=1 AND IFNULL(c.paused,0)=0 " +
            "ORDER BY c.queue_order ASC, c.updated_at DESC", null
    ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }

    // ------------------------------------------------------------------ history
    /** 瀏覽紀錄：入詳情頁就記（重訪更新時間），列表 JOIN comics 攞名稱/封面/簡介/作者。 */
    fun recordHistory(pathWord: String) {
        writableDatabase.execSQL(
            "INSERT INTO history(path_word, viewed_at) VALUES(?,?) " +
                "ON CONFLICT(path_word) DO UPDATE SET viewed_at=excluded.viewed_at",
            arrayOf(pathWord, now())
        )
    }

    fun historyEntries(limit: Int = 100): List<Comic> = readableDatabase.rawQuery(
        "SELECT c.* FROM history h JOIN comics c ON c.path_word = h.path_word " +
            "ORDER BY h.viewed_at DESC LIMIT $limit", null
    ).use { c -> generateSequence { if (c.moveToNext()) comicFrom(c) else null }.toList() }

    fun removeHistory(pathWord: String) {
        writableDatabase.execSQL("DELETE FROM history WHERE path_word=?", arrayOf(pathWord))
    }

    fun clearHistory() { writableDatabase.execSQL("DELETE FROM history") }

    fun setChapterStatus(pathWord: String, cid: String, status: String, imageCount: Int = 0) {
        writableDatabase.execSQL(
            "UPDATE chapters SET status=?, image_count=?, updated_at=? WHERE comic_path_word=? AND chapter_id=?",
            arrayOf(status, imageCount, now(), pathWord, cid)
        )
    }

    fun setChapterExpected(pathWord: String, cid: String, n: Int) {
        writableDatabase.execSQL(
            "UPDATE chapters SET expected_pages=? WHERE comic_path_word=? AND chapter_id=?",
            arrayOf(n, pathWord, cid)
        )
    }

    fun setChapterCbz(pathWord: String, cid: String, cbzPath: String) {
        writableDatabase.execSQL(
            "UPDATE chapters SET cbz_path=?, updated_at=? WHERE comic_path_word=? AND chapter_id=?",
            arrayOf(cbzPath, now(), pathWord, cid)
        )
    }

    /** 章節 URL 域名自癒持久化（舊域名被劫持/死咗 → 渲染成功時寫返活域名 URL）。 */
    fun setChapterUrl(pathWord: String, cid: String, url: String) {
        writableDatabase.execSQL(
            "UPDATE chapters SET url=?, updated_at=? WHERE comic_path_word=? AND chapter_id=?",
            arrayOf(url, now(), pathWord, cid)
        )
    }

    /** 將列表寫返落 queue_order（0..n-1）— 下載隊列拖-order 持久化。 */
    fun requeue(slugs: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            slugs.forEachIndexed { i, s ->
                db.execSQL("UPDATE comics SET queue_order=? WHERE path_word=?", arrayOf(i, s))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** 有 done 章節嘅漫畫（已下載 tab）。 */
    fun doneComics(): List<Comic> = readableDatabase.rawQuery(
        "SELECT c.* FROM comics c WHERE EXISTS (SELECT 1 FROM chapters ch " +
            "WHERE ch.comic_path_word=c.path_word AND ch.status='done') " +
            "ORDER BY c.updated_at DESC", null
    ).use { c -> generateSequence { if (c.moveToNext()) comicFrom(c) else null }.toList() }

    /** 清除 CBZ 路徑記錄（檔被刪後）。 */
    fun clearCbzPaths(pathWord: String) {
        writableDatabase.execSQL(
            "UPDATE chapters SET cbz_path=NULL WHERE comic_path_word=?", arrayOf(pathWord)
        )
    }

    /** 閱讀進度（每章記住睇到第幾頁）— kv 持久化。 */
    fun saveReadPos(pathWord: String, cid: String, page: Int) =
        setMeta("readpos:$pathWord:$cid", page.toString())

    fun readPos(pathWord: String, cid: String): Int =
        getMeta("readpos:$pathWord:$cid")?.toIntOrNull() ?: 0

    /** 重置一個漫畫嘅下載狀態（保留檔案與資料夾，強制完整重落）。 */
    fun resetComicDownloads(pathWord: String) {
        val db = writableDatabase
        db.execSQL("UPDATE images SET status='pending', updated_at=? WHERE comic_path_word=?", arrayOf(now(), pathWord))
        db.execSQL(
            "UPDATE chapters SET status='pending', image_count=0, expected_pages=0, updated_at=? WHERE comic_path_word=?",
            arrayOf(now(), pathWord)
        )
        // 全部變返 pending → 清「新章」標記，避免舊 diff 全書誤點徽章
        setMeta("newch:$pathWord", "")
    }

    private fun query(sql: String, args: Array<String>): List<Chapter> =
        readableDatabase.rawQuery(sql, args).use { c ->
            generateSequence {
                if (c.moveToNext()) Chapter(
                    pathWord = c.getString(c.getColumnIndexOrThrow("comic_path_word")),
                    chapterId = c.getString(c.getColumnIndexOrThrow("chapter_id")),
                    title = c.getString(c.getColumnIndexOrThrow("title")),
                    url = c.getString(c.getColumnIndexOrThrow("url")),
                    status = c.getString(c.getColumnIndexOrThrow("status")) ?: "pending",
                    imageCount = c.getInt(c.getColumnIndexOrThrow("image_count")),
                    expectedPages = c.getInt(c.getColumnIndexOrThrow("expected_pages")),
                    cbzPath = c.getString(c.getColumnIndexOrThrow("cbz_path")),
                ) else null
            }.toList()
        }

    // ------------------------------------------------------------------ images
    data class PageImage(
        val chapterId: String, val pageIndex: Int, val url: String?, val path: String?,
        val status: String,
    )

    /** 冪等 upsert（同桌面版）：error→pending；超出新頁數嘅舊頁刪除。 */
    fun addImages(pathWord: String, cid: String, urls: List<String>): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            urls.forEachIndexed { i, u ->
                val cur = db.rawQuery(
                    "SELECT status FROM images WHERE comic_path_word=? AND chapter_id=? AND page_index=?",
                    arrayOf(pathWord, cid, i.toString())
                )
                val old = cur.use { if (it.moveToFirst()) it.getString(0) else null }
                if (old == null) {
                    db.execSQL(
                        "INSERT INTO images(comic_path_word,chapter_id,page_index,url,status,updated_at) " +
                            "VALUES(?,?,?,?, 'pending', ?)",
                        arrayOf(pathWord, cid, i, u, now())
                    )
                } else {
                    val newStatus = if (old == "error") "pending" else old
                    db.execSQL(
                        "UPDATE images SET url=?, status=?, updated_at=? " +
                            "WHERE comic_path_word=? AND chapter_id=? AND page_index=?",
                        arrayOf(u, newStatus, now(), pathWord, cid, i)
                    )
                }
            }
            db.execSQL(
                "DELETE FROM images WHERE comic_path_word=? AND chapter_id=? AND page_index>=?",
                arrayOf(pathWord, cid, urls.size)
            )
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return urls.size
    }

    fun pendingImages(pathWord: String, cid: String): List<PageImage> = imagesQuery(
        "SELECT * FROM images WHERE comic_path_word=? AND chapter_id=? AND status='pending' ORDER BY page_index",
        pathWord, cid
    )

    fun allImages(pathWord: String, cid: String): List<PageImage> = imagesQuery(
        "SELECT * FROM images WHERE comic_path_word=? AND chapter_id=? ORDER BY page_index",
        pathWord, cid
    )

    fun firstImagePath(pathWord: String): String? =
        readableDatabase.rawQuery(
            "SELECT path FROM images WHERE comic_path_word=? AND path IS NOT NULL LIMIT 1",
            arrayOf(pathWord)
        ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun markImageDone(pathWord: String, cid: String, pageIndex: Int, path: String, sha256: String, size: Long) {
        writableDatabase.execSQL(
            "UPDATE images SET status='done', path=?, sha256=?, size=?, updated_at=? " +
                "WHERE comic_path_word=? AND chapter_id=? AND page_index=?",
            arrayOf(path, sha256, size, now(), pathWord, cid, pageIndex)
        )
    }

    fun markImageError(pathWord: String, cid: String, pageIndex: Int) {
        writableDatabase.execSQL(
            "UPDATE images SET status='error', updated_at=? " +
                "WHERE comic_path_word=? AND chapter_id=? AND page_index=?",
            arrayOf(now(), pathWord, cid, pageIndex)
        )
    }

    fun updateImagePath(pathWord: String, cid: String, pageIndex: Int, path: String) {
        writableDatabase.execSQL(
            "UPDATE images SET path=?, updated_at=? WHERE comic_path_word=? AND chapter_id=? AND page_index=?",
            arrayOf(path, now(), pathWord, cid, pageIndex)
        )
    }

    private fun imagesQuery(sql: String, pathWord: String, cid: String): List<PageImage> =
        readableDatabase.rawQuery(sql, arrayOf(pathWord, cid)).use { c ->
            generateSequence {
                if (c.moveToNext()) PageImage(
                    chapterId = c.getString(c.getColumnIndexOrThrow("chapter_id")),
                    pageIndex = c.getInt(c.getColumnIndexOrThrow("page_index")),
                    url = c.getString(c.getColumnIndexOrThrow("url")),
                    path = c.getString(c.getColumnIndexOrThrow("path")),
                    status = c.getString(c.getColumnIndexOrThrow("status")) ?: "pending",
                ) else null
            }.toList()
        }

    // ------------------------------------------------------------------ 進度統計
    data class ComicProgress(val imgDone: Int, val imgTotal: Int, val chDone: Int, val chErr: Int, val chTotal: Int)

    /** per-comic 進度（兩條聚合 query，同桌面版 comic_status_map 語義一致）。 */
    fun comicProgress(pathWord: String): ComicProgress {
        val db = readableDatabase
        val r1 = db.rawQuery(
            "SELECT SUM(status='done'), COUNT(*) FROM images WHERE comic_path_word=?", arrayOf(pathWord)
        ).use { if (it.moveToFirst()) Pair(it.getInt(0), it.getInt(1)) else Pair(0, 0) }
        val r2 = db.rawQuery(
            "SELECT SUM(status='done'), SUM(status='error'), COUNT(*) FROM chapters WHERE comic_path_word=?",
            arrayOf(pathWord)
        ).use { if (it.moveToFirst()) Triple(it.getInt(0), it.getInt(1), it.getInt(2)) else Triple(0, 0, 0) }
        return ComicProgress(r1.first, r1.second, r2.first, r2.second, r2.third)
    }

    /** 下載完成（全部章節 done）嘅漫畫 path_word 集 — 供「檢查更新」。 */
    fun downloadedPathWords(): List<String> =
        readableDatabase.rawQuery(
            "SELECT path_word FROM comics WHERE status='done' " +
                "UNION SELECT comic_path_word FROM chapters GROUP BY comic_path_word " +
                "HAVING COUNT(*) > 0 AND SUM(status='done') = COUNT(*)", null
        ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
}
