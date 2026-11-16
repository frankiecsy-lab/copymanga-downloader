# MangaCopy Android（app 模組）

Android APK：喺手機/平板 **搜尋瀏覽 → 收藏 → 下載 → 離線閱讀** CopyManga 漫畫。
同桌面版（`cm/` + `app.py`）共用同一個倉庫同同一套業務邏輯（1:1 移植）。

## 功能（v0.1.0）

- 📚 **書架**：收藏管理、封面網格、進度徽章、長按選單（更新書單／重新下載／刪檔／移除收藏）
- 🧭 **發現**：下載源列表瀏覽（人氣排序、連載狀態篩選、無限加載、一鍵收藏）
- ⬇️ **下載管理**：即時進度（漫畫/章節/頁）、停止、日誌流；**停止/恢復零丟失**（SQLite 狀態機）
- 📖 **閱讀器**：翻頁（單頁／雙頁）／流水、漫畫式右→左／左→右、全屏沉浸、保持螢幕開啟、前後章節
- 📦 **輸出格式**：WebP 圖片 ＋ CBZ（`<名>_<作者>_vol_NN.cbz`）兩邊可分開開關（設置頁）
- 🔄 **更新書單**：重新渲染詳情頁，新章節自動入隊下載（同桌面版「檢查更新」一樣嘅 diff 邏輯）
- 📱 **手機/平板適配**：底部導航欄（<840dp）／左側 NavigationRail（≥840dp）；書架網格 Adaptive 自動排
- 🧩 **下載源抽象**：`MangaSource` 接口（browse/detail/chapterImageUrls），現只 CopyManga，預留擴展

## 核心架構 — 三層抓取策略（同桌面版完全一致）

```
┌─ UI（Compose）─────────────────────────────────────────────┐
│  書架 / 發現 / 詳情 / 下載管理 / 閱讀器 / 設置              │
└──────────────┬─────────────────────────────────────────────┘
┌─ 抓取層 ──────┴─────────────────────────────────────────────┐
│ CopyMangaSource (MangaSource 接口)                          │
│   browse  ─ 純 HTTP：/comics 列表冇 WAF（OkHttp+ListingParser）│
│   detail  ─ WebViewRenderer（真 Chromium 過 WAF + AES JS）   │
│   images  ─ WebViewRenderer 振盪滾動收 data-src             │
└──────────────┬─────────────────────────────────────────────┘
┌─ 落盤層 ──────┴─────────────────────────────────────────────┐
│ DownloadEngine（狀態機，移植 cm/engine.py）                  │
│ ImageDownloader（webp 優先 + sha256 + 重試）→ Outputs        │
│ Db（SQLite：comics/chapters/images/kv，語義同 cm/db.py）     │
└──────────────────────────────────────────────────────────────┘
```

關鍵等價：Playwright `page.goto`→`onPageFinished`；`wait_for_selector`→JS 輪詢；
network events 收圖→直接讀 `data-src`（DOM 順序＝閱讀順序）；badge 總頁數核對→同一正則。

## 目錄結構

```
android/
  app/src/main/java/com/mangacopy/app/
    MainActivity.kt / MangaCopyApp.kt（Coil ImageLoader 加 UA）
    data/     AppConfig.kt（=config.py）Db.kt（=db.py）Storage.kt（目錄佈局+_safe）
    source/   Models.kt（MangaSource 接口）CopyMangaSource.kt ListingParser.kt WebViewRenderer.kt
    download/ DownloadEngine.kt（=engine.py）ImageDownloader.kt（=download.py）Outputs.kt（=outputs.py）
    ui/       App.kt（自適應導航）Bookshelf/Browse/Detail/Downloads/Settings/Reader
  app/src/test/java/...  CoreLogicTest.kt（解析/命名/webp 變體 6 tests）
```

下載輸出：`/sdcard/Android/data/com.mangacopy.app/files/downloads/<狀態>/<名 - 作者>/…`
（app 專屬外部目錄：唔使權限、卸載即清、文件管理器可見）

## 本機構建（Windows 11）

工具鏈裝喺 **`D:\android-tools\`**（repo 之外，系統 Java 只有 8 用唔著）：

| 組件 | 路徑 |
|---|---|
| JDK 17 (Temurin) | `D:\android-tools\jdk17` |
| Android SDK（platform 35 / build-tools 35 / platform-tools） | `D:\android-tools\sdk` |
| Gradle 8.9 | `D:\android-tools\gradle-8.9` |

```bash
# 一次性安裝（zip 已下載；注意 Git Bash tar 唔支援 zip，腳本用 PowerShell 解壓）
bash /d/android-tools/setup_tools2.sh

# 構建 APK（必須 JAVA_HOME 指向 jdk17）
cd android
JAVA_HOME="D:/android-tools/jdk17" /d/android-tools/gradle-8.9/bin/gradle.bat testDebugUnitTest assembleDebug
# 產物：app/build/outputs/apk/debug/app-debug.apk
```

版本錨點：AGP 8.7.3 ↔ Gradle 8.9 ↔ Kotlin 2.0.21（內置 Compose 編譯器）↔ Compose BOM 2024.10.01。
升級前要核對 AGP↔Gradle 對照表。`local.properties`（SDK 路徑）屬本機，已 gitignore。

## 已知限制 / 待辦

1. **未實機驗證**：WebView 渲染 WAF 頁面屬核心假設（同桌面版 Playwright 同理），需實機試真架下載；
   部分 OEM 後台 WebView 可能 throttle JS timer（下載時建議螢幕開啟／App 喺前臺）。
2. **無前台服務**：下載跑喺 App 進程入面，App 被系統回收就停（下次啟動自動續傳，唔會爛檔）。
   後續可加 Foreground Service + 通知欄進度。
3. Debug 簽名未上正式 keystore；正式版需 release signing。
4. 搜尋站內漫畫未做（站點列表只有人氣排序；可考慮接站內 search 端點）。
5. CBZ 只係輸出產物；閱讀器直接讀 webp 檔案目錄。
