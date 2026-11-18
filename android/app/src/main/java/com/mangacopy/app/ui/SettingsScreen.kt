package com.mangacopy.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mangacopy.app.data.AppConfig
import com.mangacopy.app.data.Backup
import com.mangacopy.app.data.Db
import com.mangacopy.app.data.Storage
import com.mangacopy.app.data.UpdateChecker
import com.mangacopy.app.download.DownloadEngine
import com.mangacopy.app.download.RepoUpdater
import com.mangacopy.app.source.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 閱讀默認設定嘅 kv key（Reader 讀同一套）。 */
object ReaderPrefs {
    const val MODE = "read_mode"        // paged | scroll（流水）
    const val RTL = "read_rtl"          // true = 漫畫式右到左
    const val SPREAD = "read_spread"    // true = 雙頁
    const val KEEP_SCREEN_ON = "read_keep_screen_on"
    const val IMMERSIVE = "read_immersive"   // 用戶：「全屏唔顯示 UI，按一下中間先顯示」

    fun mode(db: Db) = db.getMeta(MODE, "paged")
    // 用戶（2026-11-18）：右→左閱讀方向**默認關閉**（一般左→右）；想睇漫畫式要自己去設置/閱讀器開
    fun rtl(db: Db) = db.getMeta(RTL, "false") == "true"
    fun spread(db: Db) = db.getMeta(SPREAD, "false") != "false"
    fun keepScreenOn(db: Db) = db.getMeta(KEEP_SCREEN_ON, "true") != "false"
    // 用戶（2026-11-18）：全屏模式**默認開啓**；關閉＝上方 UI＋進度條常駐
    fun immersive(db: Db) = db.getMeta(IMMERSIVE, "true") != "false"
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
    val repoState by RepoUpdater.state.collectAsState()
    // 手動檢查 GitHub 更新（用戶 2026-11-18：「設置中加一個更新GITHUB檢查」）
    val updChecking by UpdateChecker.checking.collectAsState()
    val updResult by UpdateChecker.result.collectAsState()

    // ---- 書架新章通知開關（用戶 2026-11-18）＋Android 13+ 權限請求 ----
    val context = LocalContext.current
    val ioScope = rememberCoroutineScope()
    var notifyOn by remember { mutableStateOf(db.getMeta("notify_ch", "true") == "true") }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted ->
        notifyOn = granted
        db.setMeta("notify_ch", if (granted) "true" else "false")
    }

    // ---- 備份匯出／匯入（用戶 2026-11-18：「備份所有設置及書架」）----
    var backupMsg by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        ioScope.launch {
            backupMsg = runCatching {
                withContext(Dispatchers.IO) {
                    val json = Backup.exportJson(db)
                    context.contentResolver.openOutputStream(uri)
                        ?.use { it.write(json.toByteArray()) } ?: error("無法寫入檔案")
                    "匯出成功（${"%.1f".format(json.length / 1024.0)} KB）— 拷貝呢個檔去新機匯入即可"
                }
            }.getOrElse { "匯出失敗：${it.message}" }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        ioScope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)
                        ?.use { it.readBytes().decodeToString() } ?: error("無法讀取檔案")
                    Backup.importJson(db, text)
                }
            }
            backupMsg = r.fold(
                { (s, b) -> "匯入成功：設置 $s 項 · 書架 $b 部" },
                { "匯入失敗：${it.message}" })
            if (r.isSuccess) {   // 重新讀返頁面所有設置（匯入可能改咗任何一項）
                UiTheme.load(db)   // 主題即時生效
                mode = ReaderPrefs.mode(db); rtl = ReaderPrefs.rtl(db)
                spread = ReaderPrefs.spread(db); keepOn = ReaderPrefs.keepScreenOn(db)
                notifyOn = db.getMeta("notify_ch", "true") == "true"
            }
        }
    }

    LaunchedEffect(Unit) {
        usedBytes = withContext(Dispatchers.IO) {
            Storage.downloadsDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        }
        RepoUpdater.restoreSummary()   // 跨啟動重現上次倉庫更新摘要
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

            SectionTitle("倉庫")
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("更新倉庫")
                    Text("向 CopyManga 抓取一次全站漫畫清單，同上次倉庫快照對比 —" +
                            "只統計增／減少咗幾多**套**漫畫（唔涉及章節）。" +
                            "全程背景執行，中斷会自动斷點續跑",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (repoState.running) {
                    IconButton(onClick = { RepoUpdater.stop() }) {
                        Icon(Icons.Filled.Stop, "停止倉庫更新")
                    }
                }
                Button(
                    enabled = !repoState.running,
                    onClick = { RepoUpdater.start() },
                ) { Text(if (repoState.running) "更新中" else "更新") }
            }
            if (repoState.running) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "抓取中 ${repoState.fetched}/${repoState.total} 套　·　新增 ${repoState.added} 套" +
                        "　·　已跑 ${repoState.pagesDone} 頁",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    maxLines = 1,
                )
                LinearProgressIndicator(
                    progress = { if (repoState.total > 0)
                        (repoState.fetched.toFloat() / repoState.total).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp),
                )
            } else if (repoState.lastFinishAt > 0) {
                val mins = ((System.currentTimeMillis() - repoState.lastFinishAt) / 60_000).toInt()
                Text(
                    "上次更新倉庫：%s前　·　增 ${repoState.lastAdded} 套 / 減 ${repoState.lastRemoved} 套" +
                        "　·　全站 ${repoState.lastTotal} 部".format(
                            if (mins < 1) "剛剛" else if (mins < 60) "$mins 分鐘"
                            else if (mins < 1440) "${mins / 60} 鐘" else "${mins / 1440} 日"
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            repoState.lastError?.let {
                Text("錯誤：$it（再撳「更新」會由斷點續跑）", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
            Text(
                "啟動保養：每次開 App 3 秒後自動清理「已下載但冇放書架」嘅內容＋檢查書架新章節",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            SectionTitle("通知")
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("書架新章提醒")
                    Text("背景檢查書架發現新章節時發 Android 系統通知（App 完全收起時唔會檢查）。" +
                            "開關需要授權通知權限（Android 13+）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = notifyOn,
                    onCheckedChange = { want ->
                        if (want) {
                            val granted = Build.VERSION.SDK_INT < 33 ||
                                ContextCompat.checkSelfPermission(context,
                                    Manifest.permission.POST_NOTIFICATIONS) ==
                                    PackageManager.PERMISSION_GRANTED
                            if (granted) { notifyOn = true; db.setMeta("notify_ch", "true") }
                            else permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else { notifyOn = false; db.setMeta("notify_ch", "false") }
                    },
                )
            }

            SectionTitle("備份")
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("匯出／匯入「全部設置＋書架」做一個 JSON 檔 — 換機秒搬。" +
                        "下載嘅圖片／章節內容唔納入（體積太大，返新機重新下載）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm",
                            java.util.Locale.US).format(java.util.Date())
                        exportLauncher.launch("kiecomic_backup_$stamp.json")
                    }) { Text("匯出備份") }
                    OutlinedButton(onClick = {
                        importLauncher.launch(arrayOf("application/json", "text/*",
                            "application/octet-stream"))
                    }) { Text("匯入備份") }
                }
                backupMsg?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("匯入失敗") || it.startsWith("匯出失敗"))
                            MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }

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
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("檢查 GitHub 更新")
                    Text("即時向 GitHub Releases 查最新 Android 版本" +
                            "（啟動時已會自動查，12 小時節流；呢隻制繞過節流強制查一次）。" +
                            "發現新版會彈窗帶下載連結。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(
                    enabled = !updChecking,
                    onClick = { UpdateChecker.checkNow() },
                ) { Text(if (updChecking) "檢查中…" else "檢查") }
            }
            updResult?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("檢查失敗")) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            }
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
