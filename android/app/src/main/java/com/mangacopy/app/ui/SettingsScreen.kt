package com.mangacopy.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mangacopy.app.data.AppConfig
import com.mangacopy.app.data.Db
import com.mangacopy.app.data.Storage
import com.mangacopy.app.download.DownloadEngine
import com.mangacopy.app.download.ListUpdater
import com.mangacopy.app.source.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 閱讀默認設定嘅 kv key（Reader 讀同一套）。 */
object ReaderPrefs {
    const val MODE = "read_mode"        // paged | scroll（流水）
    const val RTL = "read_rtl"          // true = 漫畫式右到左
    const val SPREAD = "read_spread"    // true = 雙頁
    const val KEEP_SCREEN_ON = "read_keep_screen_on"
    const val IMMERSIVE = "read_immersive"   // 用戶：「全屏唔顯示 UI，按一下中間先顯示」

    fun mode(db: Db) = db.getMeta(MODE, "paged")
    fun rtl(db: Db) = db.getMeta(RTL, "true") != "false"
    fun spread(db: Db) = db.getMeta(SPREAD, "false") != "false"
    fun keepScreenOn(db: Db) = db.getMeta(KEEP_SCREEN_ON, "true") != "false"
    fun immersive(db: Db) = db.getMeta(IMMERSIVE, "false") == "true"
}

/**
 * 設置 — 下載源（現只 CopyManga，預留擴展）、閱讀默認、儲存用量。
 * 輸出格式固定全部 WebP（用戶 2026-11-17：「刪除 CBZ 嘅選擇，默認全部 WEBP」— 開關區已移除）。
 */
@Composable
fun SettingsScreen() {
    val db = remember { Db.get() }
    var mode by remember { mutableStateOf(ReaderPrefs.mode(db)) }
    var rtl by remember { mutableStateOf(ReaderPrefs.rtl(db)) }
    var spread by remember { mutableStateOf(ReaderPrefs.spread(db)) }
    var keepOn by remember { mutableStateOf(ReaderPrefs.keepScreenOn(db)) }
    var usedBytes by remember { mutableStateOf(-1L) }
    val listState by ListUpdater.state.collectAsState()

    LaunchedEffect(Unit) {
        usedBytes = withContext(Dispatchers.IO) {
            Storage.downloadsDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        }
    }

    val bg = tabBackground("settings")   // 淡紫：tab 背景區分（用戶要求）
    Scaffold(
        containerColor = bg,
        topBar = {
            // 用戶（2026-11-17）：「設置嘅 title 跟另外三個頁面低咗一點」—
            // 唔再用 TopAppBar（內置 64dp 高度＋內容 padding 會壓低品牌行），
            // 改同書架/搜尋/下載一樣嘅 Column padding(top=8) 結構，四頁高度一致
            Column(Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp)) {
                BrandTitle()
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {

            SectionTitle("下載源")
            SourceRegistry.all.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = true, onClick = {})
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(s.displayName)
                        Text("站點渲染繞過 WAF · 全部輸出 WebP",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(
                "（源接口已抽象化，日後可加新源）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            SectionTitle("外觀")
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("主題模式")
                    Text("跟系統深淺色，或者固定淺色/暗黑（即時生效）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SingleChoiceSegmentedButtonRow {
                    listOf("system" to "跟系統", "light" to "淺色", "dark" to "暗黑")
                        .forEachIndexed { i, (v, label) ->
                            SegmentedButton(
                                selected = UiTheme.mode.value == v,
                                onClick = { UiTheme.set(db, v) },
                                shape = SegmentedButtonDefaults.itemShape(i, 3),
                            ) { Text(label, maxLines = 1) }
                        }
                }
            }

            SectionTitle("清單與預載")
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("手動更新全部漫畫清單")
                    Text("重新渲染書架所有漫畫嘅章節清單、檢查新章節（唔會自動落圖）；" +
                            "App 每次開啟 3 秒後都會自動做一次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(
                    enabled = !listState.running && !DownloadEngine.isRunning &&
                            db.favoritePathWords().isNotEmpty(),
                    onClick = { ListUpdater.start(db.favoritePathWords(), force = true) },
                ) { Text(if (listState.running) "更新中" else "更新") }
            }
            if (listState.running) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "更新中 ${listState.done}/${listState.total}" +
                        "　·　當前：${listState.current}　·　新增 ${listState.newChapters} 章",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    maxLines = 1,
                )
                LinearProgressIndicator(
                    progress = { if (listState.total > 0) listState.done.toFloat() / listState.total else 0f },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp),
                )
            } else if (listState.lastFinishAt > 0) {
                val mins = ((System.currentTimeMillis() - listState.lastFinishAt) / 60_000).toInt()
                Text(
                    "上次檢查：%s前完成　·　新增 ${listState.newChapters} 章".format(
                        if (mins < 1) "剛剛" else if (mins < 60) "$mins 分鐘" else "${mins / 60} 鐘"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            listState.lastError?.let {
                Text("錯誤：$it", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
            // 2026-11-17 用戶指令：取消「預下載張數 3/5/10/15」設定 —
            // 即時閱讀改為一開波即成話全量下載（第 1 頁好即開讀），冇首 N 檔概念

            SectionTitle("閱讀默認")
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("翻頁模式", Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = mode == "paged", onClick = { mode = "paged"; db.setMeta(ReaderPrefs.MODE, "paged") },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("翻頁") }
                    SegmentedButton(
                        selected = mode == "scroll", onClick = { mode = "scroll"; db.setMeta(ReaderPrefs.MODE, "scroll") },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("流水") }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("閱讀方向")
                    Text("翻頁模式生效：漫畫（右→左）或一般（左→右）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = rtl,
                    onCheckedChange = { rtl = it; db.setMeta(ReaderPrefs.RTL, if (it) "true" else "false") },
                    thumbContent = { Text(if (rtl) "R" else "L", style = MaterialTheme.typography.labelMedium) },
                )
            }
            Row(Modifier.padding(horizontal = 16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("雙頁相簿")
                    Text("翻頁模式每次顯示兩頁（平板/橫屏建議開）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = spread, onCheckedChange = {
                    spread = it; db.setMeta(ReaderPrefs.SPREAD, if (it) "true" else "false")
                })
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text("閱讀時保持螢幕開啟", Modifier.weight(1f))
                Switch(checked = keepOn, onCheckedChange = {
                    keepOn = it; db.setMeta(ReaderPrefs.KEEP_SCREEN_ON, if (it) "true" else "false")
                })
            }

            SectionTitle("儲存")
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    if (usedBytes < 0) "計算中…"
                    else "下載佔用：%s".format(fmtBytes(usedBytes)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    Storage.downloadsDir.absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionTitle("關於")
            Text(
                "KIECOMIC v${AppConfig.VERSION_NAME}（Android）\n源碼同桌面版一庫：copymanga-downloader",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

fun fmtBytes(b: Long): String = when {
    b >= 1_000_000_000 -> "%.2f GB".format(b / 1e9)
    b >= 1_000_000 -> "%.1f MB".format(b / 1e6)
    else -> "%.0f KB".format(b / 1e3)
}
