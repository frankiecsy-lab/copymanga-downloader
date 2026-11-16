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
    SQLiteOpenHelper(context.applicationContext, "mangacopy.db", null, 1) {

    companion object {
        @Volatile private var instance: Db? = null
        fun init(context: Context) {
            instance ?: synchronized(this) {
                instance ?: Db(context).also {
                    it.writableDatabase // 觸發 onCreate
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
        db.execSQL("PRAGMA journal_mode=WAL")
        db.execSQL("PRAGMA synchronous=NORMAL")
    }

    override fun onCreate(db: SQLiteDatabase) {
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
            );
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
            );
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
            );
            CREATE INDEX IF NOT EXISTS idx_chapters_status ON chapters(comic_path_word, status);
            CREATE INDEX IF NOT EXISTS idx_images_status   ON images(chapter_id, status);
            CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT);
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) { /* 首版無遷移 */ }

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
    ) {
        val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: pathWord
        val firstAuthor: String get() = (author ?: "").split(",", "，").firstOrNull()?.trim() ?: ""
    }

    fun getComic(pathWord: String): Comic? =
        readableDatabase.rawQuery("SELECT * FROM comics WHERE path_word=?", arrayOf(pathWord))
            .use { if (it.moveToFirst()) comicFrom(it) else null }

    /** 書架列表：收藏優先排序同桌面版（updated_at DESC）。 */
    fun listComics(search: String = "", favoriteOnly: Boolean = false): List<Comic> {
        val q = "%${search}%"
        val sql = StringBuilder("SELECT * FROM comics WHERE (name LIKE ? OR author LIKE ?)")
        val args = mutableListOf(q, q)
        if (favoriteOnly) sql.append(" AND favorite=1")
        sql.append(" ORDER BY updated_at DESC")
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
    )

    // ------------------------------------------------------------------ chapters
    /** 冪等 upsert（同桌面版 add_chapters 一致）：error 章節重設為 pending 以便重試。 */
    fun addChapters(pathWord: String, chapters: List<Triple<String, String, String>>): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((cid, title, url) in chapters) {
                val cur = db.rawQuery(
                    "SELECT status FROM chapters WHERE comic_path_word=? AND chapter_id=?",
                    arrayOf(pathWord, cid)
                )
                val old = cur.use { if (it.moveToFirst()) it.getString(0) else null }
                if (old == null) {
                    db.execSQL(
                        "INSERT INTO chapters(comic_path_word,chapter_id,title,url,status,updated_at) " +
                            "VALUES(?,?,?,?,'pending',?)",
                        arrayOf(pathWord, cid, title, url, now())
                    )
                } else {
                    val newStatus = if (old == "error") "pending" else old
                    db.execSQL(
                        "UPDATE chapters SET title=?, url=?, status=?, updated_at=? " +
                            "WHERE comic_path_word=? AND chapter_id=?",
                        arrayOf(title, url, newStatus, now(), pathWord, cid)
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return chapters.size
    }

    data class Chapter(
        val pathWord: String, val chapterId: String, val title: String?, val url: String?,
        val status: String, val imageCount: Int, val expectedPages: Int, val cbzPath: String?,
    )

    /** 順序 = ORDER BY title（同桌面版；CBZ vol 序號都係呢個位置）。 */
    fun allChapters(pathWord: String): List<Chapter> = query(
        "SELECT * FROM chapters WHERE comic_path_word=? ORDER BY title", arrayOf(pathWord)
    )

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

    /** 重置一個漫畫嘅下載狀態（保留檔案與資料夾，強制完整重落）。 */
    fun resetComicDownloads(pathWord: String) {
        val db = writableDatabase
        db.execSQL("UPDATE images SET status='pending', updated_at=? WHERE comic_path_word=?", arrayOf(now(), pathWord))
        db.execSQL(
            "UPDATE chapters SET status='pending', image_count=0, expected_pages=0, updated_at=? WHERE comic_path_word=?",
            arrayOf(now(), pathWord)
        )
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
