package com.mangacopy.app.download

import android.util.Log
import com.mangacopy.app.data.Db

/**
 * 啟動自動清理（用戶 2026-11-18）：「閱讀過不代表要放書架，只有手動放書架的才算；
 * 每次打開背景操作，自動清理那些已下載但冇放書架嘅內容」—
 * 掃描「有圖落盤但 favorite=0」嘅漫畫：先刪磁碟檔（要趁 images.path 未清之前攞路徑），
 * 再 purge DB 下載數據（chapters/images/進度 kv；comics＋history 保留）。
 */
object LibraryCleaner {

    /** 返回清理咗幾多部；每部獨立 runCatching，一部失敗唔阻其余。 */
    fun run(db: Db): Int {
        var cleaned = 0
        for (slug in db.downloadedNotFavorite()) {
            try {
                Outputs.deleteComicFiles(db, slug)   // 磁碟（以第一張圖路徑定位漫畫目錄）
                db.purgeComicData(slug)               // DB
                cleaned++
                Log.i("MCLIB", "自動清理未放書架內容：$slug")
            } catch (e: Exception) {
                Log.w("MCLIB", "清理 $slug 失敗：${e.message}")
            }
        }
        if (cleaned > 0) Log.i("MCLIB", "啟動清理完成：共 $cleaned 部")
        return cleaned
    }
}
