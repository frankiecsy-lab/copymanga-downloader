package com.mangacopy.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangacopy.app.R
import com.mangacopy.app.data.AppConfig
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

/**
 * 全局標題欄品牌 — ComfyUI LOGO + KIECOMIC 字標 + 版本徽章，一行搞掂。
 * （歷次演化：tab 名膠囊曾放中間→分兩行→**用戶最終刪除**「上方唔使顯示書架/搜尋/下載/設置，
 * 刪」— 底欄/rail 已有 tab 指示，上方唔重複；右側留返給畫面按鈕。）
 */
@Composable
fun BrandTitle(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(id = R.drawable.kie_logo),
            contentDescription = "KIECOMIC",
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "KIECOMIC",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            letterSpacing = 3.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(6.dp),
        ) {
            Text(
                "v" + AppConfig.VERSION_NAME,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * 四大 tab 各自嘅背景色調（用戶：「書架、搜尋、下載及設定，用不同的背景來容易區分，
 * 也要暗色淺色」）— 極淡色調，唔搶封面/內容；深淺色兩套，跟 UiTheme 即時切換。
 */
@Composable
fun tabBackground(kind: String): Color {
    val dark = when (UiTheme.mode.value) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    return when (kind) {
        "shelf"    -> if (dark) Color(0xFF171410) else Color(0xFFFDF8EE)   // 暖褐（書架）
        "browse"   -> if (dark) Color(0xFF0E1317) else Color(0xFFF0F5FA)   // 淡藍（搜尋）
        "download" -> if (dark) Color(0xFF0F1512) else Color(0xFFF1F7F1)   // 淡綠（下載）
        "settings" -> if (dark) Color(0xFF131017) else Color(0xFFF7F3FA)   // 淡紫（設置）
        else -> MaterialTheme.colorScheme.background
    }
}

@Composable
fun MangaCopyTheme(content: @Composable () -> Unit) {
    val dark = when (UiTheme.mode.value) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    syncWindow(dark)   // 視窗底色／system bars 跟足 app 內日夜（用戶：要跟淺深色模式配合）
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

/**
 * 視窗層同步（用戶 2026-11-17：「上面白色可以都變黑嗎？要跟淺深色模式配合」）—
 * enableEdgeToEdge 下狀態欄/鑽孔一帶露出嘅係 **window background**：
 * 之前硬嚟 Theme.Material.Light（白底），閱讀器沉浸式頂部就留一條白。
 * 呢度將 decor 底色 + system bars 透明度 + 圖標深淺（isAppearanceLightStatusBars）
 * 全部跟 UiTheme 三態即時切換：深黑淺白，設置頁改主題即刻見效。
 */
@Composable
private fun syncWindow(dark: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.decorView.setBackgroundColor(
            if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE,
        )
        androidx.core.view.WindowCompat.getInsetsController(window, view)
            .isAppearanceLightStatusBars = !dark
    }
}
