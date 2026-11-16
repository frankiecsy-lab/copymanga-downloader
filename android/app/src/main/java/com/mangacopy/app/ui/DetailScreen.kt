package com.mangacopy.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.mangacopy.app.data.Db
import com.mangacopy.app.download.DownloadEngine

/**
 * 漫畫詳情 — 章節清單（狀態徽章）+ 下載/檢查更新/閱讀。
 * 章節順序 = ORDER BY title（同桌面版同 CBZ vol 序號一致）。
 */
@Composable
fun DetailScreen(nav: NavHostController, slug: String) {
    val db = remember { Db.get() }
    var comic by remember { mutableStateOf(db.getComic(slug)) }
    var chapters by remember { mutableStateOf(db.allChapters(slug)) }
    var showSynopsis by remember { mutableStateOf(false) }
    val engineState by DownloadEngine.state.collectAsState()

    LifecycleResumeEffect(slug) {
        comic = db.getComic(slug)
        chapters = db.allChapters(slug)
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(comic?.displayName ?: "", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            db.setFavorite(slug, !(comic?.favorite ?: false))
                            comic = db.getComic(slug)
                        },
                    ) {
                        Icon(
                            if (comic?.favorite == true) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = "收藏",
                            tint = if (comic?.favorite == true) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { pad ->
        val c = comic
        if (c == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("搵唔到呢部漫畫")
            }
            return@Scaffold
        }
        Column(Modifier.padding(pad)) {
            Row(Modifier.padding(horizontal = 16.dp)) {
                AsyncImage(
                    model = c.coverUrl, contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(92.dp).height(126.dp).clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    if (c.author?.isNotBlank() == true) Text(c.author, style = MaterialTheme.typography.bodyMedium)
                    c.serialStatus?.takeIf { it.isNotBlank() }?.let {
                        SuggestionChip(onClick = {}, enabled = false,
                            label = { Text(it, style = MaterialTheme.typography.labelSmall) })
                    }
                    Spacer(Modifier.height(6.dp))
                    val prog = db.comicProgress(slug)
                    Text(
                        if (prog.chTotal > 0) "章節 ${prog.chDone}/${prog.chTotal}" +
                                if (prog.chErr > 0) "（${prog.chErr} 章出錯）" else ""
                        else "未有章節資料 — 撳「開始下載」載入",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (prog.chErr > 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !engineState.running,
                            onClick = {
                                DownloadEngine.start(listOf(slug))
                                nav.navigate("downloads") { launchSingleTop = true }
                            },
                        ) { Text(if (prog.chDone > 0) "繼續下載" else "開始下載") }
                        OutlinedButton(
                            enabled = !engineState.running && chapters.isNotEmpty(),
                            onClick = {
                                DownloadEngine.start(listOf(slug), checkUpdates = true)
                                nav.navigate("downloads") { launchSingleTop = true }
                            },
                        ) { Text("檢查更新") }
                    }
                }
            }

            c.synopsis?.takeIf { it.isNotBlank() }?.let { syn ->
                TextButton(onClick = { showSynopsis = !showSynopsis }) {
                    Text(if (showSynopsis) "收起簡介 ▲" else "查看簡介 ▼",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (showSynopsis) Text(
                    syn, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            HorizontalDivider(Modifier.padding(top = 4.dp))

            LazyColumn(Modifier.fillMaxSize()) {
                items(chapters, key = { it.chapterId }) { ch ->
                    val done = ch.status == "done"
                    val err = ch.status == "error"
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                if (done) nav.navigate("reader/$slug/${ch.chapterId}")
                                else if (!engineState.running) {
                                    DownloadEngine.start(listOf(slug))
                                    nav.navigate("downloads") { launchSingleTop = true }
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            when {
                                done -> Icons.Filled.CheckCircle
                                err -> Icons.Filled.ErrorOutline
                                else -> Icons.Filled.RadioButtonUnchecked
                            },
                            contentDescription = null,
                            tint = when {
                                done -> MaterialTheme.colorScheme.primary
                                err -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.outline
                            },
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            ch.title ?: "章節",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (done) FontWeight.Normal else FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        if (done) {
                            Icon(Icons.Filled.MenuBook, "閱讀",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
