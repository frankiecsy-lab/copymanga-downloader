package com.mangacopy.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import com.mangacopy.app.data.Db

/**
 * 全域主題 — 同桌面版 ui_theme 一樣嘅三態：跟系統 / 淺色 / 暗黑，kv key 同名。
 *
 * mode 係頂層 Compose state：設置頁一改，成個 app（由 MainActivity 根重組）即時換色，
 * 唔使重啟。冷啟動由 MainActivity 調 load() 由 DB 讀返。
 */
object UiTheme {
    const val KEY = "ui_theme"           // 同桌面版 cm 嘅 meta key 一致
    val mode = mutableStateOf("system")  // system | light | dark

    fun load(db: Db) { mode.value = db.getMeta(KEY, "system") ?: "system" }
    fun set(db: Db, v: String) { db.setMeta(KEY, v); mode.value = v }
}

@Composable
fun MangaCopyTheme(content: @Composable () -> Unit) {
    val dark = when (UiTheme.mode.value) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
