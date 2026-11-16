package com.mangacopy.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mangacopy.app.download.DownloadEngine

/**
 * 下載管理 — 即時進度（漫畫/章節/頁）+ 停止 + 日誌流（同桌面版 console log 體驗一致）。
 */
@Composable
fun DownloadsScreen() {
    val state by DownloadEngine.state.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(state.log.size) {
        if (state.log.isNotEmpty()) listState.animateScrollToItem(state.log.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("下載管理") })
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            Card(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (state.running) "下載中" else "空闲",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (state.running) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        if (state.running) {
                            Button(
                                onClick = { DownloadEngine.requestStop() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error),
                            ) { Text("停止") }
                        }
                    }
                    if (state.running) {
                        Spacer(Modifier.height(8.dp))
                        Text(state.comicName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(
                            state.chapterTitle.ifEmpty { "載入章節列表…" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        )
                        if (state.pageTotal > 0) {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { state.pageDone.toFloat() / state.pageTotal },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "本頁章節 ${state.pageDone}/${state.pageTotal} 頁",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        } else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    state.lastError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text("最近錯誤：$it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Text(
                "日誌",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            ) {
                items(state.log) { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
                if (state.log.isEmpty()) item {
                    Text(
                        "（未有日誌 — 去書架/詳情頁撳「下載」開始）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}
