# KIECOMIC Android（app 模組）

Android APK：喺手機/平板 **搜尋瀏覽 → 收藏 → 下載 → 離線閱讀** CopyManga 漫畫。
同桌面版（`cm/` + `app.py`）共用同一個倉庫同同一套業務邏輯（1:1 移植）。
**v0.3.2 起品牌統一為「KIECOMIC」**（launcher 名稱／icon／全局標題欄均係，
LOGO 素材 `res/drawable-nodpi/kie_logo.png`）。

## v0.3.3 重點（2026-11-17）

- 🤏 **放大後按兩下返回原大小**（雙擊偵測只認放大狀態下嘅純單擊；拖曳/pinch 清鏈）
- 🎯 **點擊區統一**：翻頁＋流水都係「中央方型區（上下左右留 20%）＝顯示/收起 UI，
  區外先轉頁」（翻頁按左右半屏 rtl 換向／流水按上下半屏）；UI 顯示 5 秒自動收返全屏
- 📏 底部 taskbar 矮化：NavigationBar 80dp → **40dp**
- 版本 0.3.3（versionCode 6）

## v0.3.2 重點（2026-11-17）

- 🏷 **品牌 KIECOMIC**：launcher label＋adaptive icon＋`BrandTitle` 全局標題欄
  （LOGO＋KIECOMIC＋版本徽章一行）；四頁頂欄結構/高度統一（設置頁棄 TopAppBar）
- 🎛 **頂欄全面 icon 化**（根治 425dp 窄屏文字掣同品牌重疊）：下載頁
  PlayArrow/Stop/DeleteSweep/Delete/SelectAll/Clear/Close；書架 Refresh；
  詳情頁〔批量/更新/排序/收藏〕四 icon；back 圓底 31dp；搜尋「共 N 部」右上徽章
- 🔍 **章節 GRID 純色系**：零 tick — surface/secondaryContainer（已下）/
  primaryContainer＋描邊（在讀）/tertiaryContainer（揀中）/紅框（error）；16sp 垂直置中
- 🤏 **兩指縮放**（`ZoomablePage` 手寫 awaitEachGesture：單指 1× 時零干預、兩指先
  consume；1–4×＋pan clamp；抬手復位；縮放中鎖 pager）
- 🖥 **全屏閱讀**（kv `read_immersive`）：默認收全部 UI，tap 中間喚醒、5 秒自動收
- 🌗 **日夜視窗同步**：`values(-night)/themes.xml`＋`syncWindow(dark)` — 沉浸式頂部白欄根治；
  **橫屏安全區**：根 Box `safeDrawingPadding()`（status bar/鑽孔/nav 一次 consume）
- 📚 書架封面徽章：刪左上進度藥丸、置頂 ⭐ 大 1.5 倍、新章徽章改「NEW」

## 功能（v0.3.1）

- 📚 **書架**：收藏管理、封面網格、進度徽章、長按選單（更新書單／重新下載／刪檔／移除收藏）；
  **每次開 App 背景自動檢查新章節**（啟動 3 秒後 ListUpdater，只渲染詳情頁唔落圖）＋新章節徽章
- 🔍 **搜尋**（原「發現」）：模糊書名/作者搜尋、種類 filter、browse 列表、連載狀態篩選、無限加載、一鍵收藏
- ⬇️ **下載管理**：未下載／已下載分區、一行式隊列行（bar＋序號）、**排隊次序可調**、批量取消/刪除；
  **停止/恢復零丟失**（SQLite 狀態機）；「一直卡下載中」熔斷（渲染超時/連續失敗即標 error）
- ⚡ **無縫跨章節閱讀**（v0.3 最終形態）：
  - **成話一開波全量下載**（v0.3，开发者最終指令）：取消「首 N 頁預讀」設定（3/5/10/15 已刪）—
    撳章節＝即開，`onPartial` 有新頁 URL 即刻 4 lane 爆落成話晒圖，第 1 頁好即刻開讀，
    其後頁陸續即到；補頁循環直到成話齊頁先標 done（站點限速/error 有熔斷＋逃生門提示）
  - **章節清單秒取**（v0.3）：站點附錄頁 JS 只喺頁面頂部 append（見知識庫），
    `dispatchEvent(new Event('scroll'))` 合成事件每次 guaranteed +1 頁 → 224 頁章 20 秒攞齊清單
  - **後台預載下一章**：本章齊頁、或翻到尾前 4 頁即觸發下一章 preflow（工具欄顯示「後面已備 x 頁」）
  - **翻盡自動接章**：尾卡／footer／goNext 全部要 `chapterComplete`（= 成話**真・齊頁**）才放行；
    未備齊顯示「備齊晒成話先至通去下一章」，唔會第幾頁就掟去下一章；
    最後一章無尾卡／footer 顯示「最後一章 · 全書完」，唔會誤跳
  - **數字章節序**（v0.3）：站點 flow 序＝最新排最前，「下一章」會倒退 —
    ≥90% 標題含數字時閱讀器改用數字序（第21卷→附錄+番外→第22卷 正確接續）
  - **頁碼藥丸**（v0.3）：畫面底部常駐「第 X / N 頁」（跟拖曳實時更新）；下方拖曳條 scope＝當前話
  - **全章拖曳佔位制**（v0.3.1）：顯示量程＝**全章總頁數**（唔再係已備頁數）— 拖曳條可以拖盡全章，
    未落盤頁顯示「第 N 頁 · 準備中 · 已備 x/y」佔位（spinner），一頁落盤即刻補真圖（imgTick 實時刷）
  - **首頁速出**（v0.3.1）：render 章節清單同 4 lane 落圖 **並行**（drainer 輪詢 pendingImages 即刻出隊）—
    第 1 頁 URL 到手 <1 秒就落盤開讀（實測 tap 章節→p1 到手 2.2 秒），唔使等成話清單 render 完
  - **降落頁優先落盤**（v0.3.1）：preflow 帶 priorityPage=readPos — 跨章回上一章/尾頁恢復時
    降落嗰頁排下載隊最前（實測 p217 全書第一，其後頁升序自動補）；
    配套修復：入章恢復要等量程確立先跳（temporarily total 未到手會 clamp 錯尾）、
    readPos=0 新章都要定位企穩先清 switching 屏蔽旗（舊版窗口期連環跳章 23→24→25附錄）、
    補頁循環每輪結尾必 emit 進度（舊版 url 缺殘行令 prog 停 0/0 副標題誤報）
  - **頭卡無縫回上一章**（v0.3.1）：翻頁模式 index 0 = 頭卡（「上一章 · 標題」）— 喺第 1 頁再掃
    即無縫入上一章降落尾頁（同尾卡向前對稱）；新章入場 `initialPage=1` 直接見第 1 頁唔困頭卡
  - **跨章前後導航**：工具欄 ‹ › 章節箭頭、翻頁邊界 goPrev/goNext 自動跨章
  - **進度記憶**：每章 readPos 300ms debounce 持久保存；重入/跨章恢復原頁（越界 fallback 尾頁；
    翻頁↔流水切換都落返當前進度）；跨章 goPrev 落返上一章尾頁
- 🧭 **底部 tab 必落根板**（v0.3.1）：書架／搜尋／下載／設置任一個 tab 切換都乾淨回到該 tab 根清單
  （純 popUpTo＋launchSingleTop，唔 save/restore state — 官方模板嘅 restoreState 會連詳情頁/章節清單
  一齊還原，實測「逗留喺章節」踩中）
- 📖 **閱讀器**：翻頁（單頁／雙頁）／流水、漫畫式右→左／左→右、全屏沉浸（隱底欄）、保持螢幕開啟
- 📦 **輸出格式**：WebP（默認）＋ CBZ（`<名>_<作者>_vol_NN.cbz`）兩開關；CBZ 可選存檔後刪除
- 📱 **手機/平板適配**：底部導航欄（<840dp）／左側 NavigationRail（≥840dp）；全站列表默認 GRID 可切 LIST
- 🧩 **下載源抽象**：`MangaSource` 接口（browse/detail/chapterImageUrls），現只 CopyManga，預留擴展
- 🕘 **歷史**：最近閱讀（HistoryScreen）

## 核心架構 — 三層抓取策略（同桌面版完全一致）

```
┌─ UI（Compose）─────────────────────────────────────────────┐
│  書架 / 發現 / 詳情 / 下載管理 / 閱讀器 / 設置              │
└──────────────┬─────────────────────────────────────────────┘
┌─ 抓取層 ──────┴─────────────────────────────────────────────┐
│ CopyMangaSource (MangaSource 接口)                          │
│   browse  ─ 純 HTTP：/comics 列表冇 WAF（OkHttp+ListingParser）│
│   detail  ─ WebViewRenderer（真 Chromium 過 WAF + AES JS）   │
│   images  ─ WebViewRenderer 合成 scroll 事件收 data-src      │
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

## 實機 E2E（8 吋 pad 1880×3008，**真實 CopyManga 站**一拳超人）

v0.2（Redmi 假站期）：無縫跨章（第1→2→3話自動接）/書尾「全書完」/跨章返回尾頁/進度恢復 — 通過
（本地假站方法論沉澱喺根目錄 `AGENTS.md` 知識庫第 10 條）。

v0.3（2026-11-17 實機 ＋ 線上真實站 .fun）全部通過：
- **完整睇完成話先跳**：第21卷 224 頁全落盤、逐頁翻到尾（唔會第 4 頁就跳）；未備齊時尾卡顯示
  「備齊晒成話先至通去下一章」闸死；備齊後尾卡一撳即刻接「22卷附錄+番外」
- **零等待連翻**：12 頁 4.2 秒（「後面已備 220 頁」）；13 頁附錄章 12 次 tap 零等待翻到 13/13
- **清單秒取**：224 頁章 20 秒、206 頁章 19 秒攞齊全部 URL（合成 scroll 事件法）
- **拖曳跳頁**：滑杆 14→214 即時定位；頁碼藥丸「第 X / 224 頁」跟手
- **跨章接續時序**：附錄 13/13 → 尾卡 → 第22卷（206 頁已全部備好）即時開卷第 1 頁

調試 log tag：`MCWEB`（渲染）/ `MCPRE`（預載）。

## 已知限制 / 待辦

1. 部分 OEM 後台 WebView 可能 throttle JS timer（下載時建議螢幕開啟／App 喺前臺）。
   （v0.3 已用真實站線上跑晒渲染/落圖/跨章回归 — 舊「維護中未重跑」限制解除。）
2. **無前台服務**：下載跑喺 App 進程入面，App 被系統回收就停（下次啟動自動續傳，唔會爛檔）。
   後續可加 Foreground Service + 通知欄進度。
3. Debug 簽名未上正式 keystore；正式版需 release signing。
4. CBZ 只係輸出產物；閱讀器直接讀 webp 檔案目錄。
5. 搜尋用 `.fun` searchcl 端點；倘站點搬域名需更新 `AppConfig` 端點清單（全部域名 fallback 已寫）。
