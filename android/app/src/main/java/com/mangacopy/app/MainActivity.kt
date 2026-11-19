package com.mangacopy.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import com.mangacopy.app.data.Db
import com.mangacopy.app.ui.App
import com.mangacopy.app.ui.MangaCopyTheme
import com.mangacopy.app.ui.UiTheme

/**
 * MangaCopy Android 入口 — 書架 / 發現 / 下載 / 設置 四頁 + 詳情 + 閱讀器。
 * （主題：用 Material3 默認动态調色，跟系統深淺色。）
 *
 * 【橫屏安全區】enableEdgeToEdge 令內容伸延到 status bar / 鑽孔之下 —
 * 新實機（Realme RMX5060 橫屏）實測右側狀態列直接蓋住 top bar 按鈕。
 * 正解：根 Box 加 safeDrawingPadding()（system bars + displayCutout 一次過 consume），
 * 所有畫面連閱讀器統一受保護；閱讀器沉浸式隱 bar 時 insets=0，唔會白白留黑邊。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyRotationLock()   // v0.3.7：kv 持久化嘅旋轉鎖定開機即還原
        setContent {
            UiTheme.load(Db.get())   // 冷啟動由 kv 讀返主題
            MangaCopyTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) { App() }
            }
        }
    }

    /**
     * 旋轉鎖定（用戶 2026-11-20：「加一個旋轉鎖定可保持的設置」）—
     * kv「rotation_lock」開＋kv「rotation_lock_dir」記低鎖定时方向：
     * 直式 → PORTRAIT；橫式 → SENSOR_LANDSCAPE（橫住翻 180° 都唔會跌返直）。
     * 關 → UNSPECIFIED＝交返 Manifest 嘅 fullSensor 自由旋轉。
     * 設置頁開關同 onCreate 都入呢個函數（單一事實來源，唔會兩邊行唔同邏輯）。
     */
    fun applyRotationLock() {
        val db = Db.get()
        requestedOrientation =
            if (db.getMeta("rotation_lock", "false") == "true") {
                if (db.getMeta("rotation_lock_dir", Configuration.ORIENTATION_PORTRAIT.toString())
                        ?.toIntOrNull() == Configuration.ORIENTATION_LANDSCAPE
                ) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
