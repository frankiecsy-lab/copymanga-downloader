package com.mangacopy.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mangacopy.app.ui.App
import com.mangacopy.app.ui.MangaCopyTheme
import com.mangacopy.app.ui.UiTheme

/**
 * MangaCopy Android 入口 — 書架 / 發現 / 下載 / 設置 四頁 + 詳情 + 閱讀器。
 * （主題：用 Material3 默認动态調色，跟系統深淺色。）
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UiTheme.load(com.mangacopy.app.data.Db.get())   // 冷啟動由 kv 讀返主題
            MangaCopyTheme {
                App()
            }
        }
    }
}
