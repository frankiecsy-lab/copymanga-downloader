package com.mangacopy.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
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
        setContent {
            UiTheme.load(com.mangacopy.app.data.Db.get())   // 冷啟動由 kv 讀返主題
            MangaCopyTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) { App() }
            }
        }
    }
}
