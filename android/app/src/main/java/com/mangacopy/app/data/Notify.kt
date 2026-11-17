package com.mangacopy.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mangacopy.app.MainActivity

/**
 * 系統通知（用戶 2026-11-18：「書架有更新用 ANDROID 系統通知提示，開關放設置」）—
 * 而家只有一種通知：ListUpdater 後台檢查發現新章節。
 * 權限：targetSdk 35 → Android 13+ 要 POST_NOTIFICATIONS（設置頁開關撳制時請求）；
 * 未授權時靜默跳過（唔搶 dialog）。
 */
object Notify {
    private const val CHANNEL = "bookshelf_new"
    private const val ID_CHAPTERS = 1001

    private lateinit var ctx: Context

    fun init(context: Context) {
        ctx = context.applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "書架新章節", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "書架漫畫有新章節時提示" })
    }

    /** 通知權限（設置頁顯示實際狀態用）。 */
    fun areEnabled() = ::ctx.isInitialized && NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    /** 書架新章通知；開關/權限未過就靜默跳過。 */
    fun chaptersUpdate(newChapters: Int, comics: Int) {
        if (!areEnabled()) return
        val tap = Intent(ctx, MainActivity::class.java)
            .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pi = PendingIntent.getActivity(ctx, 0, tap, PendingIntent.FLAG_IMMUTABLE)
        val title = if (comics > 1) "書架 $comics 部漫畫有更新" else "書架漫畫有更新"
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(ctx.applicationInfo.icon)
            .setContentTitle(title)
            .setContentText("檢查到 $newChapters 個新章節 — タップ開書架睇")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("本次後台檢查發現 $comics 部書共 $newChapters 個新章節，" +
                        "去書架撳「下載」即可更新。"))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(ID_CHAPTERS, n) }
    }
}
