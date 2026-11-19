# 更新日誌 (CHANGELOG)

## 2026-11-20 — 改進：KIECOMIC Android v0.3.9（回前景即檢查更新，每日一次）

用戶：「每次打開APP到前景都要CHECK UPDATE，每日一次」：

- 觸發時機：由「进程啟動一次」改成 **App 級 `LifecycleResumeEffect`** — 每次由後台返
  前景（ON_RESUME）都觸發檢查；掛根 Composable，任何 tab／詳情／閱讀器內返前景都照 check
- 節流改制：12 小時時間戳（kv `upd_ts`）→ **日曆日閘**（kv `upd_day`=yyyy-MM-dd）：
  今日未 check 先打 GitHub API；日 marker **先寫** — API 失敗當日都唔重複刷網絡
  （設置頁手動「檢查」制仍可随时繞過）；今日已 check → 直接用快取決定提唔提醒彈窗
- 倉庫衛生：v0.3.8 誤提交的臨時 E2E 廢料（`_ui.py`/`_ui.xml`）剔除，
  `.gitignore` 補 `_ui.*`/`_shot.png`/`_build_*.log`
- 版本 0.3.9（versionCode 12）

## 2026-11-20 — 改進：KIECOMIC Android v0.3.8（撤銷卷 TAB 過濾 — 用戶取消需求）

用戶：「如果有分卷或者話，上方一個TAB切換FILTER — 以上取消」。v0.3.7 已上線嘅卷 TAB
功能全數撤回：

- 移除 `ChapterGroups.kt`（卷數擷取）＋其單元測試＋詳情頁 volTabs/volSel/shown 過濾機制
  與 FilterChip TAB 列；章節清單同批量「全選」返直接用全量 `chapters`
- 其餘 v0.3.7 功能（離線秒出／從頭睇第 1 頁／旋轉鎖定／新增章提示移位）**全部保留不動**
- 版本 0.3.8（versionCode 11）

## 2026-11-20 — 改進：KIECOMIC Android v0.3.7（清單離線秒出＋卷 TAB＋從頭睇第一頁＋旋轉鎖定＋新增章提示移位）

用戶五項批次（「0.3.7 build release」）：

### 📖 章節清單：離線先讀、每次入頁都更新
- 舊邏輯有 15 分鐘過期閘 — 閘內入頁完全唔更新；現行：**入頁永遠即刻顯示 DB 離線清單**
  （有舊清單唔全屏幕轉圈），跟住**每次入頁**都背景 renderDetail diff 新章
- 有舊清單時更新失敗靜默（唔阻離線閱讀）；連結（章節 url／圖片 url）本來就持久喺 DB

### 🗂 卷 TAB 過濾器（用戶：「如果有分『卷』或者『話』，上方一個TAB切換FILTER」）
- `ChapterGroups.volumeOf()`：「第17卷」「第22卷附錄+番外」歸第22卷；「第405话」→「話」tab；
  支援中文數字（第十一卷）— 純 JVM 邏輯有單元測試
- 有分卷先出 TAB（全書／第N卷×n／話，FilterChip 列可橫滚、帶章數）；再撳已選 tab = 取消過濾；
  選擇模式「全選(N)」只揀**當前 tab 可見**嘅章

### 🔝 從頭睇一定第 1 頁（用戶：「從頭睇就一定要第一章第一頁」）
- 閱讀器路由加可選 `?land=0` query（ReaderScreen `land` 參數 → landOverride 機制）—
  舊版「從頭睇」入第一章會還原以前睇開嘅頁；現在降落第 1 頁＋preflow 降落頁優先落盤同步改用 land

### 🔄 設置「旋轉鎖定」可保持（用戶：「加一個旋轉鎖定可保持的設置」）
- kv `rotation_lock` ＋ `rotation_lock_dir`：開關即時凍結**目前**方向（橫式用
  SENSOR_LANDSCAPE — 橫住翻 180° 唔跌返直），重開 App 照鎖；關 = 回 Manifest fullSensor
- `MainActivity.applyRotationLock()` 單一事實來源（開關＋onCreate 共用）；兩 key 已入備份白名單

### 🏷「新增 N 章」提示移到「章節 1/129」右邊（用戶指定位置）
- 更新結果（新增 N 章／已經係最新清單／更新失敗）同進度行掛同一行右側；失敗紅色

- 版本 0.3.7（versionCode 10）

## 2026-11-18 — 改進：KIECOMIC Android v0.3.6（更新彈窗 App 內直接下載 APK＋自動開啟安裝）

用戶：「更新不想跳轉，想它直接下載APK自動打開」— 更新流程由「開瀏覽器去 Release 頁」改成全內置：

### ⬇️ 發現新版 → 彈窗直接下載安裝（唔再跳瀏覽器）
- `fetchLatest()` 由 GitHub Release **assets 攞 `.apk` 直鏈**（`browser_download_url`，首選含
  debug 嗰個；冇 apk 嘅 release 直接跳過）；快取 kv 升三段式 `ver|頁url|APK直鏈`，
  讀到舊兩段式快取會自動後台補刷一次（升級銜接唔會死喺度）
- 新下載狀態機 `UpdateChecker.Download`：Idle → Running(read/total，每 256KB 節流 emit 進度)
  → **Ready（完成即自動 startActivity 開系統安裝界面）**／NeedPerm／Fail
- APK 落 `getExternalFilesDir(null)/updates/KIECOMIC-android-vX.Y.Z.apk`
  （app 專屬目錄：零 storage 權限、卸載即清）；先落 `download.part` 再 rename（防半檔）
- Android 7+ 禁 file:// 跨進程 → **FileProvider**（`${applicationId}.fileprovider`，
  只暴露 `updates/` 一個目錄）＋ `ACTION_VIEW` + `application/vnd.android.package-archive`
  ＋ `FLAG_GRANT_READ_URI_PERMISSION`
- Android 8+「安裝未知應用程式」特殊權限：Manifest 聲明 `REQUEST_INSTALL_PACKAGES` ＋
  運行時 `canRequestPackageInstalls()` 檢查 — 未授權**唔會白嘔完成先失敗**：檔照落盤轉
  NeedPerm，彈窗变「去授權」→ `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 授權頁，返嚟撳多一次即裝
- 彈窗按鈕跟狀態走：直接下載安裝／下載中…(45%)／去授權／立即安裝／重試下載；
  下載中唔容關閉（防誤觸）；APK 下載用獨立 OkHttp（冇 20s callTimeout，慢網都落得完）
- 實機 E2E（pad 全真實鏈路）：改裝「假舊版」觸發彈窗 → 直接下載 → 授權流程 →
  系統安裝器自動彈出 → 裝完 `versionName=0.3.6` ✓

## 2026-11-18 — 改進＋修復：KIECOMIC Android v0.3.5（全站 LAZYLOAD 根治 ＋ 倉庫 ＋ 通知 ＋ 備份 ＋ GitHub 檢查更新）

深夜批次最終定稿 v0.3.5（用戶：「看到了，V0.3.5 UPDATE」；中途曾以 0.3.4 同版號迭代發佈過一次）：

### ♾️ 搜尋/瀏覽頁全站分批 LAZYLOAD（用戶：「只显示几十个」→「没有LAZYLOAD」→「有时會ENDLISS LOADING」三輪排查定案）
- **站點實測**：`/comics?ordering&offset&limit=50` limit 硬上限 50、深 offset 可用、
  total=46811；`&status=1`（連載中→16456）／`&theme=maoxian`（冒險→7014）服務端過濾全部有效
- **endless loading 根因（2am 實測截圖復現）**：舊版 `loadPage` 用 `if (!loading)` 閘 —
  轉 filter 時舊分頁請求仲在飛 → 新 filter 首載被**靜默吞掉** → items 已清冇人重試 →
  空白＋spinner 無限轉；舊請求完成後仲會將**錯 filter 數據＋錯 offset** 寫入清單
- **重做載入狀態機**：單飛 `pageJob`（轉 filter／刷新即 `cancel()` 舊請求）＋ `AtomicInteger`
  世代號（舊 job 所有狀態寫入都要 gen 匹配先成立）；`total` 一齊 reset（徽章跟 filter 如實，
  唔再掛住舊 46811）
- **主動預取 lazyload**：`rememberLazyGridState`／`rememberLazyListState` + `snapshotFlow` —
  距底 10 格即提早拉下一頁（舊版淨靠 footer 可見被動觸發）；載完一批 footer 推出螢幕自然停
- **被攔截 shell 頁防風暴**：首頁真空且 total=null → 如實報錯「站點回應異常」停鏈
  （舊版無限快重試 → `invalidateBase` 全線探測風暴 log 實測 13 輪循環燒網）；
  列表/搜索 OkHttp 超時 30s/60s → **12/25/callTimeout 30s** 快 fail 如實報錯
- **ListUpdater 失敗退讓**：詳情頁檢查失敗等 10 秒先試下一部（站點維護時唔好逐部連打）
- 實機回归（8 吋 pad 1880×3008）：深滚 12 屏同頂部標題重疊=0（真的逐頁 fetch）；
  載入中轉「連載中」→ badge 即時 46811→16456、清單重載冇卡死；題材「冒險」→ 7014＋chip ✓

### 🏷 題材分類 FILTER（用戶：「加一個題材分類，FILTER ICON」）
- 搜尋框右邊 filter icon → ModalBottomSheet 站點原生 65 個題材（`AppConfig.TOPICS`，
  /comics 頁導航抽出）→ `?theme=slug` **服務端**過濾；已揀顯示「題材：名」chip 一撳清返

### 📚 閱讀過 ≠ 放書架 ＋ 啟動自動清理（用戶：「閱讀過不代表要放書架」＋「每次打開自動清理已下載但冇放書架嘅內容」）
- `openFromSearch` 剝走自動 `setFavorite` — 入詳情只 upsert（歷史/進度需要），收藏一律手動
- 啟動 3 秒後 `LibraryCleaner.run()`：掃「有圖落盤但 favorite=0」→ 先刪磁碟檔（趁
  images.path 未清攞路徑）再 purgeComicData（chapters/images/進度 kv；comics＋history 保留）
- 實機验证：「全知讀者視角」(65 圖 favorite=0) 被清，書架 8 部＋歷史 9 條完整 ✓

### 🗃 設置「更新倉庫」（用戶定稿語義：只統計全站**套數**增減，唔包括章節）
- `repo` 表全站 path_word 快照＋runTs 蓋印制：完整跑完先 prune（last_seen≠runTs＝已下架）；
  半輪 prune 會誤報天量減少 → 嚴禁；kv `repo_run`="runTs|offset|added" **斷點續跑**
- 設置頁：更新／停止制、進度行（抓取中 x/總數·新增 N 套·已跑幾多頁＋進度條）、
  完成摘要「上次更新倉庫：幾時前·增X/減Y套·全站Z部」（kv 持久化跨啟動重現）

### 🔄 GitHub 檢查更新（用戶：「用 GITHUB，有更新要在打開APP時提醒一次」）
- `UpdateChecker`：啟動查 `api.github.com/repos/.../releases` → 解析 `android-vX.Y.Z` tag
  逐段數值比較（0.3.10>0.3.9）；12 小時節流＋kv 快取（節流期內開 App 唔打網直接用快取）
- 有新版先彈一次 dialog（kv `upd_seen` 記版本號，同一版只騷擾一次）；「去下載」開瀏覽器
  Release 頁；全鏈路實測：pad 上真 API → kv `upd_latest=0.3.4|github url` ✓ 無新版唔彈
- **設置「關於」區手動「檢查 GitHub 更新」制**（用戶：「設置中加一個更新GITHUB檢查」）：
  `checkNow()` 繞過節流強制查一次＋即時回報「已是最新版本 v0.3.4 ✔／發現新版本…／
  檢查失敗…」；發現新版會清 `upd_seen` 強制彈窗（用戶 명시檢查理应即刻見到入口）；
  pad 實測撳制 → 5 秒內顯示回報文字 ✓
- 純邏輯 `cmp()` 加單元測試

### 🔔 書架新章 Android 系統通知 ＋ 設置開關（用戶：「書架有更新用系統通知提示，開關放設置」）
- `Notify`：channel「書架新章節」（IMPORTANCE_DEFAULT）；ListUpdater 檢查完成且
  newChapters>0 且開關（kv `notify_ch` 默認開）→ 通知「書架 N 部漫畫有更新·共 X 個新章節」
  → tap 開返 App；Android 13+ POST_NOTIFICATIONS 運行時授權（設置頁撳開關時請求，
  Manifest 已加權限）；未授權靜默跳過
- 實測：channel 已註冊（dumpsys notification 確認）、設置頁開關渲染正常 ✓

### 💾 備份匯出／匯入（用戶：「使用者備份所有設置及書架」）
- `Backup`：SAF 文件選擇器（CreateDocument／OpenDocument）讀寫 JSON —
  `settings` 只匯出**用戶設置白名單**（閱讀默認／主題／列表模式／通知開關 —
  新章記時/閱讀進度/倉庫游標等本機內部狀態一律唔帶走）＋`bookshelf` 收藏全行
  （path_word/名/作者/封面/狀態/簡介）；匯入即 upsert＋setFavorite ＋ `UiTheme.load` 即時生效
  ＋頁面所有開關重讀；唔係 KIECOMIC 檔／JSON 壞 → 如實報錯
- 下載圖片唔納入備份（體積太大，返新機重新下載）；實測設置頁兩制渲染正常 ✓

## 2026-11-18 — 改進：KIECOMIC Android v0.3.4（跨章雙向著陸鐵律 ＋ 全屏/閱讀方向默認反轉 ＋ 流水定位加固）

### 🔀 跨章雙向著陸鐵律（用戶：「每話第一頁做上一頁，必須返回上一話**最後一頁**；最後一頁做下一頁，必須係下一話**第一頁**」）
- `gotoChapter(id, land)` 加降落頁 sentinel：`land=0`＝第 1 頁／`land=Int.MAX_VALUE`＝尾頁／
  `null`＝還原該章 readPos（手動開章照舊返去睇開嗰頁）；8 個跨章呼叫點全部按方向標注
  （next 系→0、prev 系→MAX），`pendingRestore = landOverride ?: db.readPos(...)`
- **根治流水尾頁著陸失準**：背景落圖（imgTick→placeholder 換真圖高度突变）會推走剛
  `scrollToItem` 定位嘅位置（實測 tgt=14 最終停 6；入章 readPos 恢復 tgt=5 直接冇郁—
  即流水恢復從來未企穩過）→ 定位後驗證＋補跳（最多 3 次×120ms）；尾頁未落圖時 LazyColumn
  clamp 到尾（`canScrollForward=false`）視作已企穩，唔使再補
- **根治 footer 假可見無故跳章**：未落盤佔位頁好短，成 14 個 item＋footer 全部排得落
  viewport → 睇緊第 1 頁都誤判「睇到尾」→ 無操作都被掟去下一章；補硬
  `firstVisibleItemIndex >= totalPages - 2` 先容許 footer 自動接章（AGENTS.md 知識庫 #25）
- **根治舊章 prog 污染量程**：切章瞬間 `totalPages = max(舊章 total, pages.size)` 用住
  上一章殘留進度做假量程 → `gotoChapter` 內**同步** reset `prog=Prog(true,0,0)`
- 向後跨章落尾頁瞬間 footer 已可見 → `landedAtTail` 旗壓住自動接（用戶翻返上頁即復活），
  唔會一降落又俾 footer 掟返去下一章
- 實機回归（Realme 1264×2780）：翻頁模式 227 p1→226 尾 13/13、226 尾→227 p1 1/14 ✓；
  流水向後 218 p1→217 尾（tgt=MAX→13，落圖期間 clamp 尾視覺到尾）✓；流水向前
  `restore tgt=0 settle vis=0`→218 第 1 頁 ✓；流水入章 readPos 恢復 tgt=1 企穩 ✓

### 🖥 全屏模式默認**開啓**（用戶定稿：「關閉全屏＝上方 UI＋進度條**常駐**」）
- kv `read_immersive` 默認 true：默認全收 UI、tap 中央方型區喚醒（頂欄＋滑杆＋藥丸同顯）、
  5 秒自動收返；**OFF 時頂欄＋進度條＋頁碼藥丸常駐**（倒數只喺 immersive=true 先生效）
- 5 秒倒數條件收緊做 `showBar && immersive && !showSheet`（開緊設定 sheet 唔會倒數收欄）

### 📖 閱讀方向默認**關閉**右→左（用戶：「右>左 默認關閉」）
- kv `read_rtl` 默認 false＝一般左→右；想睇漫畫式翻頁去設置／閱讀器設定自己開

- 版本 0.3.4（versionCode 7）；三件套 testDebugUnitTest、assembleDebug、lintDebug 全過

## 2026-11-17 — 改進：KIECOMIC Android v0.3.3（雙擊還原縮放 ＋ 閱讀點擊中央方型區 ＋ 底欄矮化）

### 🤏 放大後「按兩下」返回原大小
- `ZoomablePage` 手勢迴圈加雙擊偵測：放大狀態（scale>1）下連續兩下**純單擊**
  （冇多指、位移≤touchSlop）間隔 ≤ `doubleTapTimeout` 即復位 1×；拖曳/pinch 自動清雙擊鏈。
  位移累計只在 consume 分支做 — `calculatePan/positionChange` 會 consume 位置變化，
  scale==1 單指完全唔碰，Pager 橫滑／翻頁點擊零干擾（詳見 AGENTS.md 知識庫 #22）

### 🎯 閱讀點擊區重定義（用戶：「所有閱讀模式按中間都唔應該換頁 —
### 中間＝上下左右留 20% 嘅正中方型範圍」）
- 翻頁＋流水兩模式統一：**中央方型區（x/y 均喺 20%–80%）＝ 顯示/收起 UI**；
  區外先轉頁 — 翻頁按左右半屏（rtl 換向）、流水按上下半屏
- 根治流水模式舊映射（中間 20%–55% 係 goPrev →「按中間唔通全屏、變咗轉頁」）
- 配合既有：tap 中央顯示 UI → **5 秒無操作自動收返全屏**（流水模式實測
  顯示→6 秒收回全部通過）

### 📏 底部 taskbar 高度迭代（用戶多輪：「矮一點」→「還是高」→「44dp」→「40dp」→「太低顯示唔全」）
- `NavigationBar` 高度預設 80dp → 60dp → 52dp → 44dp → 40dp（太低壓爆 item）→ 48dp（仍嫌矮）
  → 56dp（再高一點點）→ **60dp 定稿**（用戶指定；icon＋label＋揀中膠囊完整）

### 🐛 搜尋頁閃退修復（用戶：「按搜尋就閃退了」）
- 起因：為修「全部 N 部已載完」頁腳挤咗第一格內 wrap，將 LazyVerticalGrid 頁腳
  改 span 橫跨全行 — 但 span lambda 嘅參數 `it` 係 **item index**（單元素清單＝0），
  `GridItemSpan(0)` 拋 `IllegalArgumentException: The span value should be higher than 0`
  → 搜尋結果不足一屏（頁腳可見被 compose）即 FATAL 閃退
- 正解：`span = { GridItemSpan(maxLineSpan) }` — `LazyGridItemSpanScope.maxLineSpan`
  先係行內總格數；呢類 span 參數錯誤屬 runtime 斷言，編譯期過唔到（AGENTS.md 知識庫 #24）
- 實機回归：搜「gantz」→ 3 結果 →「—— 全部 3 部已載完 ——」全寬置中顯示、程序生還 ✓

- 版本 0.3.3（versionCode 6）；三件套 testDebugUnitTest 7/7、assembleDebug、
  lintDebug 全過；Realme 實機：流水模式入 232 話 → tap 中央出 UI → 5 秒自動收 ✓

## 2026-11-17 — 新功能：KIECOMIC Android v0.3.2（品牌改名 KIECOMIC ＋ 全頁 icon 化 ＋ 兩指縮放 ＋ 全屏閱讀 ＋ 日夜視窗同步）

### 🏷 品牌統一「KIECOMIC」（开发者：「app 名稱改 KIECOMIC，呢個亦係整體以後嘅名」）
- `AndroidManifest` launcher label、全局標題欄（ComfyUI LOGO＋KIECOMIC 字標＋版本徽章
  `BrandTitle`）、設置頁關於、launcher icon（`res/drawable-nodpi/kie_logo.png`＋
  adaptive icon anydpi-v26）全部統一；四頁頂欄高度對齊（設置頁棄用 TopAppBar 改
  Column padding(top=8)，同書架/搜尋/下載一致）

### 🎛 頂欄按鈕群全面 icon 化（窄屏 425dp 根治重疊）
- **下載頁**：開始下載→▶ PlayArrow、停止→■ Stop（error 色）、清除全部→DeleteSweep、
  刪除全部→Delete、全選/取消全選/離開→SelectAll/Clear/Close（旧「清除全部」文字掣
  同品牌重疊 bug 一併根治）
- **書架**：全部更新書單→Refresh icon（更新中轉圈仔）；刪「收藏 N 部」閑置行
- **詳情頁**：頂欄四 icon〔批量選擇 SelectAll／更新章節 Refresh／排序 Sort+DropdownMenu／
  收藏 Star〕；卡內「更新章節」文字掣與排序 chips 全部上移；back 圓底 48→43→39→31dp
- **搜尋頁**：部數改「共 N 部」徽章放品牌行右上角

### 🔍 章節 GRID 純色系零符號（开发者：「唔使打 tick，用顏色表達在讀/有下載/冇下載」）
- 冇下載＝surface／已下載＝secondaryContainer／閱讀中＝primaryContainer＋1.5dp primary
  描邊／揀中＝tertiaryContainer／出錯＝紅框；標題 16sp（bodyMedium）＋Box 置中真·垂直置中

### 🤏 閱讀器兩指縮放 ＋ 全屏模式
- `ZoomablePage`：手寫 `awaitEachGesture`＋`calculateZoom/calculatePan` — scale==1 單指
  完全唔 consume（pager/tap 照常）、兩指落即 onClaim＋consume；pinch-to-zoom 1–4×＋
  pan clamp `±(scale-1)*size/2`；抬手 ≤1.02 自動復位；共享 `zoomScale` →
  `userScrollEnabled` 鎖 pager 防邊縮邊甩頁；換章/轉模式自動復位
- **全屏閱讀**（kv `read_immersive`）：開關喺閱讀器面板；默認收埋工具欄/頁碼藥丸/滑杆，
  tap 畫面中間顯示、5 秒無操作自動收回全屏（面板開緊暫停倒數）
- 工具欄刪左右章按鈕；滑杆改膠囊（RoundedCornerShape 50%）左右留 5% 邊位；
  副標題刪「後面已備 N 頁」

### 🌗 系統級視覺修復
- **日夜視窗同步**：`values(-night)/themes.xml`（Theme.MangaCopy 白/黑 windowBackground＋
  透明 bars）＋ `syncWindow(dark)` SideEffect（decor 底色＋system bars 圖標深淺跟 UiTheme
  三態即時切換）— 沉浸式閱讀頂部白欄根治
- **橫屏安全區**：MainActivity 根 `Box(safeDrawingPadding())` — enableEdgeToEdge 下
  status bar／鑽孔／nav bar 一次 consume，橫屏 top bar 唔再俾遮

### 📚 書架封面徽章重構（开发者：「左上唔使顯示下載咗幾多、置頂星大 1.5 倍、新章顯示 NEW」）
- 刪左上進度藥丸；置頂 ⭐ 11sp→17sp；新章徽章「+N 新章」→「NEW」（仍只計更新書單
  diff 且未落盤嘅章，右下角位置不變）

### 🧬 數據層同輪修復（配合 0.3.2）
- `CopyMangaSource` 域名探測自癒（死域自動跳下一候選）；`ChapterPreloader` 完成判定
  防 false-complete（只認 chapters.status／done≥expected）；`Db.ensureColumns` 冪等
  遷移；`DownloadEngine` paused/queue_order 狀態機；`ListUpdater` 新章 diff 標記
- 測試：`testDebugUnitTest` 7/7 ✓、`assembleDebug` ✓、`lintDebug` 0 error ✓
- 實機（Realme RMX5060 1264×2780 真實站）：四頁頂欄/詳情頁/書架/閱讀器全屏全鏈路截圖验收 ✓

## 2026-11-17 — 改進：MangaCopy Android v0.3.1（全章拖曳佔位 ＋ 降落頁優先 ＋ 頭卡回上一章 ＋ 跨章保護閘加固）

### 🖐 全章拖曳佔位制（开发者：「下方拖拉時可以一直拖拉，圖片一直更新」）
- 閱讀器顯示量程由「已落盤頁」擴做**全章總頁數**：拖曳條拖盡全章，未落盤頁以 pageIndex
  對應 `PageSlotPlaceholder`（spinner「第 N 頁 · 準備中 · 已備 x/y」，站點削頁如實顯示
  「呢頁欠緊」）；`readyByIndex: Map<Int,File>` 令 error 缺頁都唔甩位，一頁落盤 imgTick
  bump → slot 即時補返真圖（流水模式同款佔位）

### ⚡ 首頁速出 ＋ 降落頁優先落盤（开发者：「下載咗第一頁就顯示」「上咗尾頁，先讀到尾頁出來先至自動下載其他頁數」）
- `ChapterPreloader`：render 章節清單同落圖 **drainer 並行** — onPartial 一有 URL 入 DB
  即刻 4 lane 出隊（實測 tap 章節→p1 到手 2.2 秒，22 頁話 6.95 秒入阅读開读）
- `preflow(priorityPage=readPos)`：降落頁排 pending 隊最前（穩定排序，其餘升序照落）—
  實測 217 頁話降落尾頁 **p217 全書第一**出隊；跨章回上一章即刻見到降落嗰頁

### ↔️ 頭卡無縫回上一章（开发者：「滑動下一頁可以去下一章，滑動上一頁唔可以回上一章」）
- 翻頁模式 index 0 = 真頁「頭卡」（← 上一章 · 標題）：喺第 1 頁再掃即無縫入上一章降落尾頁
  （同尾卡向前對稱）；`pg != 0 先更新 prev` 防拖曳途中 (0,scrolling) 洗旗；
  新章入場 `rememberPagerState(initialPage = 1)` 直接見第 1 頁唔困頭卡
- 實機：265→掃→264 尾頁✓、24卷→掃→23卷 217/217 ✓

### 🧭 底部 tab 必落根板（开发者：「書架按了就一定要到書架清單，不要逗留在章節」）
- `App.kt` 刪官方 bottom-nav 模板嘅 saveState/restoreState（會連 detail/reader 堆疊 snapshot
  一齊還原 → 逗留章節）；改純 `popUpTo(startDestination)` ＋ launchSingleTop — 書架/搜尋
  兩條路徑實測均落根清單

### 🛡 跨章保護閘加固（實測連環跳章 23→24→25卷附錄 根治，詳見 AGENTS.md 知識庫 #17）
- **readPos=0 新章都要「定位→企穩→先清 switching 旗」**：pager 跨章瞬間仲 clamp 緊舊章
  index，舊版第一幀清旗令尾卡/頭卡 auto 效果窗口期連環誤跳
- **恢復等量程確立**：入章一刻 prog.total 未到手（量程 temporarily=已備頁數）會將尾頁
  readPos clamp 去已備尾頁（216 降落 200）；只有 rendering=false 先准當「站點削頁」clamp
- **補頁循環零 emit 修復**：pending 全部冇 url 時（殘行）由頭到尾冇 emit → prog 永停 0/0
  副標題誤報「成話下載中 0/210」；每輪結尾強制 emit 真進度

### 實機 E2E（8 吋 pad ＋ 真實站 `.fun`，一拳超人第23卷 217 頁 DB 手術可控場景）
未備 17 頁→降落尾頁 217/217（p217 第一出隊）→ 未備 67 頁→滑杆拖入未備區即刻見
「準備中 · 已備 210/217」佔位 → CDN 2 秒補齊即時變真圖 → 刪 7 頁觸發真站渲染修復 →
尾卡顯示「下一章 · 第24卷（準備緊…）」候 nextReady 先放行 → 尾卡自動接 24卷後
**15 秒零連跳** → 頭卡掃返 23卷 217/217；tab 根板、死章如實報錯 — 全部通過

### 測試與構建
`gradle testDebugUnitTest assembleDebug lintDebug` **BUILD SUCCESSFUL**；
`versionName=0.3.1 / versionCode=4`；`dist/MangaCopy-android-v0.3.1-debug.apk`

## 2026-11-17 — 改進：MangaCopy Android v0.3.0（成話全量預載 ＋ 站點章節清單秒取 ＋ 防誤跳閘）

### ⚡ 章節清單秒取（WebViewRenderer，實測 224 頁章 20 秒攞齊）
- **拆包實錘**：站點附錄頁 JS（`comic_content_pass202508141534.js`，Dean Edwards packer 去混淆）
  嘅 append 條件係 `ul.offsetTop/16 > scrollY` — **只喺頁面頂部幾十 px 內先吐新頁**，
  初始 for 循環只 append 3 張 → 舊「振盪滾動」策略大卷死喺 3-4 頁
- **合成事件法**：`window.dispatchEvent(new Event('scroll'))` 直接觸發 `window.onscroll`，
  scrollY 恆 0 永遠喺閾值內 → 每次調用 guaranteed +1 頁（224 頁 20 秒 / 206 頁 19 秒）
- `loadsImagesAutomatically=false`：渲染 WebView 永不載圖（URL 交 OkHttp 另落）—
  防 lazyload webp 解碼霸佔主線程令 append 由 25/s 跌至 1.5/s

### 📲 預載策略最終形態（开发者指令：唔使首 N 頁，一開波爆落成話）
- 刪除「即時閱讀預下載張數 3/5/10/15」設定 ＋ `firstN`/Stage A/向前窗口全套分級邏輯
- `ChapterPreloader` 改補頁循環：渲染→4 lane 升序爆落→未齊頁 4 秒後再渲染攞多啲頁，
  直到**成話真・齊頁**先標 done＋ensureOutputs＋emit `rendering=false`；
  20 輪未齊／3 連零進展 → 如實報錯（閱讀器尾卡開逃生門），**嚴禁扮完成**
- **被動模式 false-complete 修復**：舊版以「pending 空」為完結條件 — 站點未滴完 URL 時
  誤報完成（實測 89 頁瞬間變返 3 頁→誤跳章）；改只認 done/error 狀態或 done≥expected

### 🚧 防誤跳閘（「唔可以第 4 頁就跳」）
- 尾卡點擊／footer 點擊／goNext 三處全部 gate `chapterComplete`（＝預載器最終態）；
  未備齊尾卡顯示「備齊晒成話先至通去下一章」
- 下一章 preflow 觸發條件收緊：本章齊頁、或真係去到尾前 4 頁先開火

### 🔢 閱讀器修正
- **數字章節序**：站點 flow 序＝最新排最前 →「下一章」倒退 bug（第17卷跳返第16卷）；
  ≥90% 標題含數字時改用數字序（21卷→附錄+番外→22卷 正確接續）
- 常駐頁碼藥丸「第 X / N 頁」（跟拖曳實時更新，尾卡顯示下一章標題）；工具欄副標題加當前頁
- 設定頁版本字串 0.3.0；`versionName=0.3.0 / versionCode=3`

### 實機 E2E（8 吋 pad 1880×3008 ＋ **真實線上站** copymanga.fun，一拳超人）
第21卷 224 頁完整睇晒（12 頁連翻 4.2 秒零等待、滑杆 14→214 即跳）→ 尾卡即刻接
22卷附錄+番外（13 頁 12 tap 零等待）→ 尾卡即刻接第22卷（206 頁已全部備好）；
未備齊闸「備齊晒成話先至通去下一章」實測生效 — 全部通過（logcat MCPRE/MCWEB 佐證）

### 測試與構建
`gradle testDebugUnitTest assembleDebug lintDebug` **BUILD SUCCESSFUL**；
`dist/MangaCopy-android-v0.3.0-debug.apk`（versionName 0.3.0）

## 2026-11-17 — 新功能：MangaCopy Android APK v0.2.0（無縫跨章節閱讀 ＋ Wave #2 體驗升級）

### ⚡ 核心：無縫無感下載睇漫畫、跨章節（開發者要求）
- **即時閱讀**：`ChapterPreloader.preflow` — renderChapter `onPartial` 有新頁 URL 即刻入 DB 即刻落 webp，首 N 頁（可設，默認 5）到手即刻開讀，其餘背景補落
- **後台預載下一章**：本章首 N 頁一到手即悄悄 preflow 下一章，副標題實時顯示「下一章已備 x/y」；
  in-flight 防重（ConcurrentHashMap key=slug:cid，重複 preflow 自動變被動輪詢），收尾必 emit `rendering=false` 最終態
- **翻盡自動接章**：翻頁模式本章尾卡（下一章卡＋進度條，備齊自動接上／撳一下即刻繼續）；流水模式 LazyColumn footer 卡；
  最後一章無尾卡／footer 顯示「最後一章 · 全書完」，實測唔會誤跳
- **跨章前後導航**：工具欄 ‹ › 章節箭頭；翻頁邊界 goPrev/goNext 自動跨章；跨章 goPrev 落返上一章**尾頁**
- **進度記憶**：每章 readPos（kv `readpos:<slug>:<cid>`）300ms debounce 持續保存；重入/跨章恢復原頁、
  越界 fallback 尾頁；翻頁↔流水切換都落返當前進度（mode 入 LaunchedEffect key）；
  switching 屏蔽旗防止跨章瞬間 pager clamp flash-frame 污染 readPos（連環誤跳根因）
- **閱讀器全屏沉浸**：App.kt 喺 reader 路由唔套 Scaffold（隱底欄/側欄）

### Wave #2（開發者 15 項要求）
- CBZ 存檔後可選刪除、輸出默認全 WebP；開 App 自動背景更新書架清單（ListUpdater）＋新章節徽章；設置頁手動更新全部清單＋進度
- 下載 tab 分「未下載/已下載」、批量取消/刪除、隊列調序（`comics.queue_order`）；修「一直卡下載中」（渲染超時/連續失敗熔斷）；一行式隊列行（bar＋序號）
- 章節 FlowRow 排序 chips（流式/正序/倒序/未下載優先）；封面名水平置中；點章節=即時閱讀、點下載=批量模式
- 主菜單「發現」改「搜尋」（模糊書名/作者）＋種類 filter；所有列表默認 GRID 可切 LIST；歷史記錄（HistoryScreen）
- **DB v4**：`comics.queue_order`、`chapters.sort_index`（PRAGMA user_version=4）

### 實機 E2E 驗證（Redmi 25079RPDCC ＋ 本地假站 1:1 仿真實 DOM）
站點詳情頁全线維護中（見知識庫），改用 `adb reverse` ＋ PC 假站（`.comicContent-list li img[data-src]`＋「x / N」badge）
跑**真實代碼路徑**（WebViewRenderer 渲染/逐頁下載/跨章預載全真實執行）：
第1話→第2話→第3話自動無縫接章、後台預載「下一章已備 12/12」、防重被動分支、書尾翻頁/流水均無誤跳、
跨章返回落上一章尾頁、readPos 全對、切模式保進度 — 全部通過（logcat MCPRE/MCWEB ＋ DB readpos 雙重佐證）。

### 測試與構建
`gradle testDebugUnitTest assembleDebug lintDebug` **BUILD SUCCESSFUL**（test 6/6、lint 0 error）；
`app-debug.apk` 17.99MB（versionName 0.2.0）。新增 `app/src/debug/AndroidManifest.xml`
（debug 容許 cleartext HTTP 供本機 E2E，release 不受影響）。

## 2026-11-17 — 新功能：MangaCopy Android APK v0.1.0（手機/平板下載＋離線閱讀）

### 功能說明
新增 `android/` Gradle 模組（Kotlin + Jetpack Compose，minSdk 26 / target 35），業務邏輯 1:1 移植自桌面版 `cm/`：
- **書架**（收藏管理 + 封面網格 + 進度徽章 + 長按選單）／**發現**（源列表瀏覽 + 狀態篩選 + 無限加載）
- **下載管理**：即時進度、停止、日誌流；SQLite 狀態機令**停止/恢復零丟失**（同桌面版 engine.py 語義一致）
- **閱讀器**：翻頁（單頁/雙頁）/流水、閱讀方向（漫畫右→左 / 左→右）、全屏沉浸、保持螢幕開啟、前後章節
- **輸出格式**：WebP / CBZ 兩開關（`<名>_<作者>_vol_NN.cbz` 同桌面版命名一致）
- **更新書單**：重新渲染詳情頁 diff 出新章節自動入隊（= 桌面版「檢查更新」）
- **手機/平板適配**：<840dp 底部導航欄、≥840dp NavigationRail；書架 Adaptive 網格
- **下載源抽象**：`MangaSource` 接口（browse/detail/chapterImageUrls），現只有 CopyManga，預留擴展

### 核心技術 — 三層抓取策略（同桌面版一致）
- `/comics` 列表**冇 WAF** → 純 OkHttp + `ListingParser`（移植 `listing.py` 兩級解析 + fallback）
- 詳情頁/章節頁 **WAF + AES 混淆 JS** → `WebViewRenderer`：用 Android 內置 Chromium 執行站內 JS（Playwright 等價物）；`onPageFinished`≈goto、JS 輪詢≈wait_for_selector、**完整移植振盪滾動**（+500/−120）激發 lazy-load IntersectionObserver、直接讀 `data-src`（DOM 順序＝閱讀順序）+ badge 總頁數核對
- 圖片域名冇 WAF → `ImageDownloader`（webp 優先 + sha256 + 3 次重試）；輸出到 app 專屬外部目錄（唔使權限）
- 關鍵坑：**離屏 WebView viewport 係 0×0 → lazy-load 永遠唔觸發**，必須手動 measure/layout 畀屏幕尺寸

### 工具鏈（本機首次搭建，全部裝 `D:\android-tools\`，repo 以外）
JDK 17 (Temurin) + Android SDK（platform 35 / build-tools 35 / platform-tools）+ Gradle 8.9。
版本錨點：AGP 8.7.3 ↔ Gradle 8.9 ↔ Kotlin 2.0.21 ↔ Compose BOM 2024.10.01。構建指令見 `android/README.md`。

### 測試
`gradle testDebugUnitTest` 6/6 通過（列表解析主路徑/entity/fallback/空頁、webp URL 變體、檔名消毒）；
`assembleDebug` 成功產出 `app-debug.apk`（17.8MB，aapt2 badging 驗證 package/min/target 正確）。
**未實機驗證** — WebView 過 WAF 同後台渲染行為需實機確認（見 android/README.md 已知限制）。

## 2026-09-27 — 維護：重燒 EXE ＋ build.bat 錯誤訊息加固（SearchIndexer lock 踩坑）

### 背景
重燒 EXE 時 `build.bat` step 1（staging user data）move `dist\MangaCopy\downloads` 報 **Access is denied**，但 MangaCopy.exe 根本冇運行。排查後確認元兇係 Windows Search 嘅 per-user 索引進程 `sihost.exe`：佢索引 webp 檔案時 hold 住 `downloads` 目錄嘅 open handle，令該目錄本身同直接子項無法 rename/move（更深層子目錄卻正常），極易誤判做「EXE 仲開緊」。

### 修改
- **build.bat**：`:err_move` 錯誤訊息由「is MangaCopy.exe still running?」擴充為兩個可能原因（EXE 運行中 / sihost.exe hold 住資料夾）＋ 一鍵解鎖指令 `taskkill /f /im sihost.exe`。純文字改動、無邏輯變更，CRLF 行尾已驗證。
- **AGENTS.md 知識庫**：踩坑記錄新增第 3 條 — 完整排查路徑（tasklist → Python `os.rename` 分層測試定位 lock 層級 → PowerShell COM 枚举 Explorer 視窗位置 → sihost/Defender）。

### 驗證
`taskkill /f /im sihost.exe` 後重跑 build.bat，5 步全部成功：PyInstaller one-dir 重建 ＋ Chromium 153.0.8010.12 (v1243) 裝入 portable 資料夾；user data 完整還原（`data\mangacopy.db` 15.7MB ＋ `downloads\` 2947 個 webp），staging 目錄自動清理。

## 2026-09-27 — CBZ 命名微調（多作者只取第一位）＋ 下載列表管理（清空／個別移除）

### 1. CBZ 檔名：多位作者只取第一位
- 之前 `author` 欄整串入檔名，例如 `Dr.STONE_稻垣理一郎, Boichi_vol_01.cbz`；而家按逗號（`,` 或全角 `，`）切開**只取第一位** → **`Dr.STONE_稻垣理一郎_vol_01.cbz`**。單一作者不受影響。
- 實作：`cm/outputs.py::_cbz_filename()` — `re.split(r"[,，]", author, 1)[0]` 先切再 `_clean()`。

### 2. 下載列表（✓ 勾選隊列）管理
- **清空**：Row2 新增「清空下載列表」按鈕（跟喺「取消全選」之後）— 取消**全部**漫畫嘅勾選，包括被篩選隱藏咗嘅行（「取消全選」只影響可見行）。非破壞性操作，唔會刪任何檔案。
- **個別移除**：清單視圖右鍵選單新增「從下載列表移除」（只喺該部已勾選時顯示）；圖格視圖原本已有「取消勾選下載」。
- 實作：`cm/db.py::clear_selection()`（`UPDATE comics SET selected=0`）；`app.py::_clear_download_list()` / `_remove_from_queue(slug)` — 改 DB 後即時同步 tree ✓ 欄／card badge 同底部計數，唔使重載。

### 測試
temp-DB + withdrawn-window GUI smoke test：CBZ 命名三個 case（ASCII 逗號多作者 → `Dr.STONE_稻垣理一郎_vol_01.cbz`、全角逗號、單作者不變）✓；下載列表 — 勾選 2/3 部 → 個別移除後 DB/tree 欄/計數全部同步 ✓ → 清空後 `selected_path_words()==[]`、所有行 ✓ 消失、計數 0 ✓；右鍵選單對已勾選行正常建立 ✓。臨時腳本同 temp 資料夾已刪除。

## 2026-09-27 — 修復：切換主題/換漫畫時 GUI 卡死（TclError: image "pyimageN" doesn't exist）

### Bug：右欄顯示過原圖封面後，按 🌙 切換主題或點選其他漫畫，GUI 無反應（似卡死），console 出 `TclError: image "pyimage8" doesn't exist`
- **根因**：tkinter 經典 PhotoImage 生命周期陷阱。full-res 封面 PhotoImage 喺 `_make_thumb()` 建立後**冇任何 Python 引用**（設計上唔入 `thumb_cache` 慳 RAM），而 Tk widget 只保存 image 嘅**名稱字串**（"pyimageN"）。`_set_detail_image()` 返回後 refcount 歸零 → CPython 即刻 GC 掉 PhotoImage → Tcl 刪除該 image，但 `_detail_img_lbl` 仍然指向死咗嘅名稱。之後任何令 Tk 重新評估呢個 label 嘅操作都會炸：
  - 切換主題 → `_apply_widget_colors()` 對 label `configure(bg=...)` → TclError（用戶回報嘅 traceback）；異常中斷咗 `_toggle_theme()`，後面嘅 `_render()` 唔會執行 → 主題按鈕似係無反應。
  - 點選其他漫畫 → `_set_detail_placeholder()` 做 `configure(image="", text="")` → 同樣 TclError → placeholder 永遠顯示唔到、詳情欄卡喺舊封面 → 用戶感覺「GUI 卡死」。
- **修復**（全部 `app.py`，3 行）：加 `self._detail_photo` 單一引用槽 —
  - `_set_detail_image()`：`configure(image=photo)` 後存 `self._detail_photo = photo`（顯示期間保持存活）。
  - `_set_detail_placeholder()`：先 `configure(image="")` 清走 Tk 引用，**然後**先至 `self._detail_photo = None` 釋放 RAM（順序重要）。
  - `__init__`/viewer 建立處初始化 `self._detail_photo = None`。
- **RAM 行為不變**：同一時間只有一張 full-res 封面顯示，隱藏即釋放；唔會累積。

### 測試
withdrawn-window GUI smoke test：先喺未修復代碼上重現用戶一模一樣嘅 traceback（line 240 `configure(bg=...)` → TclError），修復後 — 模擬真實路徑（建立 full-res photo → `_set_detail_image` → 刪本地引用 + `gc.collect()`）→ 雙向切換主題 ✓、placeholder 換圖 ✓、隱藏後引用已釋放 ✓；測試腳本已刪除。

## 2026-09-27 — CBZ 檔案命名改為「漫畫名字_作者_vol_NN」格式

### 功能說明
每章嘅 CBZ 檔名由舊嘅「章節標題.cbz」改做 **`<漫畫名>_<作者>_vol_NN.cbz`**（NN = 該章按標題排序嘅序號，兩位數起：vol_01、vol_02 … vol_10）。例：`魔都精兵的奴隸_竹村洋平, タカヒロ_vol_01.cbz`。

### 實作
- **`cm/outputs.py::_cbz_filename(slug, cid)`**（新）：由 DB `comics.name` / `comics.author` 組裝檔名；章節序號 = 該章喺 `db.all_chapters()`（ORDER BY title，同 engine 下載順序一致）嘅 1-based 位置。作者為空時自動略過嗰段（唔會留雙底線：`無作者漫_vol_01.cbz`）；漫畫名缺失時 fallback 去 path_word。
- **`cm/outputs.py::_clean()`**（新）：檔名片段消毒（同舊 `_safe_name` 規則，但空值保持空、唔會 fallback 做 "chapter"）。
- **`ensure_cbz()`**：改用新檔名；另加舊檔清理 — 若 DB `chapters.cbz_path` 指向同一漫畫資料夾入面嘅舊命名檔案（例如升級前下載嘅 `<章節標題>.cbz`），重建時自動刪除，確保每章只有一份 CBZ。冪等性不變：新檔存在且比所有頁面新就跳過。

### 行為細節 / 邊界情況
- vol 編號跟隨標題排序（同 app 其他嘅章節順序一致）；「檢查更新」加入新章後，新章按排序位置取得自己嘅 vol_NN，舊章編號唔變（除非新章排喺前面）。
- 100+ 章自然擴為三位數（vol_100），唔會截斷。
- 無新增依賴；requirements.txt 不變。

### 測試
temp DB + temp 資料夾自動化測試驗證：有作者（含逗號多作者）/ 無作者兩種命名、vol_01–vol_03 序號、冪等跳過重建、舊命名 cbz 自動清理、`ensure_outputs()` 端到端 — 全部通過；臨時腳本同 temp 資料夾已刪除。

## 2026-09-27 — 維護：清理臨時檔 + 重燒 EXE + build.bat 數據保護加固

### 清理
- 刪除 `__pycache__/`、`cm/__pycache__/`、PyInstaller 中間目錄 `build/`（39MB）、縮圖快取 `data/thumbs/`（481 張、36MB，純 cache 可隨時重生成）。
- 刪除重複 build 資料夾 `dist/MangaCopy - 複製`（939MB；已用 md5 驗證其 DB 同主 build 完全一致、downloads 為空 — 純重複檔）。

### EXE 重燒
- `build.bat` 重建：PyInstaller one-dir + Chromium v1243（Chrome for Testing 153.0.8010.12）裝入 `dist\MangaCopy\playwright-browsers\`。
- Smoke test：frozen exe 啟動後穩定運行 12 秒無 crash（進程存活、~78MB），然後 kill — 確認打包完整可用。
- 合併遠端新功能「full-res 原圖預覽」（33cf7aa）後再跑一次 `build.bat` 重燒 — 最終 EXE 包含該功能；呢次運行同時端到端驗證咗加固版 build.bat 嘅 staging/restore 流程。

### 踩坑與修復（重要）
- **PyInstaller 每次 build 會 wipe 成個 `dist\MangaCopy` 輸出目錄** — 連 portable app 自己嘅 `data\mangacopy.db`、`downloads\` 都一齊刪。本次重燒即中招：frozen DB 被清走；幸而當時 chapters=0（從未下載過任何章節）、kv 無自訂下載路徑，用較新嘅 dev 端 DB（喺 git）還原，零損失。
- **build.bat 加固**：build 前自動將 `data\` + `downloads\` move 去 `dist\_userdata\`，build 後（包括失敗路徑）move 返 — 同碟 move = instant rename，幾 GB comics 都唔會拖慢；exe 運行緊鎖檔時即時報錯中止。
- **batch 行尾坑**：`.bat` 用 LF 行尾時 cmd.exe 會搵唔到 label — `call :label` 靜默失敗、執行直接跳過（無明顯錯誤），極難排查。已轉 CRLF 並用同結構最小測試腳本驗證 restore 路徑通過後刪除。
- `.gitignore` 新增 `data/thumbs/`，防止縮圖快取被 `git add .` 掃進 repo。

### 狀態
- `dist\MangaCopy\` 即開即用：新 exe + `_internal`（211MB）+ Chromium（707MB）+ data/DB（md5 同 dev 一致）。

## 2026-09-27 — 右欄預覽改原圖顯示（full-res scrollable viewer）

### 功能說明
右欄詳情封面由「縮到 520×680 再顯示」改為**原圖原生像素顯示**：大圖可經 scroll bar / 滑鼠 wheel 拖動瀏覽，細圖自動置中。

### 實作（全部 `app.py`）
- **新 cover viewer**：placeholder label（載入中／無封面／失敗提示）+ `tk.Canvas` + 雙向 scrollbar；原圖以 native size 放喺 canvas window 入面，`scrollregion` 跟隨圖片實際尺寸。
- **`_load_thumb` resize policy**：只有 detail 尺寸（`DETAIL_W=520`）跳過 `img.thumbnail()` — 即右欄拿到嘅係原圖；grid/清單縮圖照舊縮到 138px。
- **RAM 控制**：full-res PhotoImage **唔入** `thumb_cache`（一張 1587×2494 RGB ≈ 10MB，累積會爆）；disk cache（`data/thumbs/*.jpg`）保留 — 原圖只下載一次，之後由磁碟重讀。
- 小於視口嘅封面自動置中；某軸冇 overflow 時該 scrollbar 隱藏（ttk.Scrollbar 唔支援 `-state`，用 pack/forget 切換）。
- 主題切換（light/dark）會重新上色 viewer 背景。

### 測試
temp DB + withdrawn window GUI smoke test：初始 placeholder、大圖 native scrollregion、小圖置中＋scrollbar 隱藏、placeholder 切返、主題切換、端到端（fake network → `_show_detail` → worker fetch → event dispatch → 顯示，確認無 memory cache、有 disk cache）— 6/6 通過；測試腳本已刪除。
## 2026-09-27 — 新功能：已下載漫畫「檢查更新」+ 自動下載新章節

### 功能說明
對已下載完成嘅漫畫一鍵「🔄 檢查更新」：逐部重新抓取網站章節列表，發現新章節即自動下載（連 CBZ/WEBP 後處理一齊做）；已有頁面/章節絕不重抓。

### 實作
- **`cm/db.py::downloaded_path_words()`**：搵出「已完全下載」嘅漫畫 — `status='done'`，或所有 tracked chapters 都 done（兩條件 UNION；同 GUI 綠行定義 `_is_downloaded` 一致）。
- **`cm/engine.py` check_updates 模式**（`start(slugs, check_updates=True)`）：此模式下 `_process_comic` 步驟 1 由「首次先抓章節列表」改為「必定重新渲染 detail page」，經 `db.add_chapters()` 冪等合併 — 舊 done 章節保持狀態唔會重下，只有真正新嘅 row 以 pending 插入 → 再流入正常 pending-chapter 下載循環；順帶刷新 synopsis / serial_status。日誌顯示「共 N 章（新增 M）」。
- **`app.py` GUI**：
  - Row2 加藍色「🔄 檢查更新」按鈕（「重下(選取)」之前）：目標 = 全部已下載漫畫；engine thread 執行、可「停止下載」中斷、log + 「正在下載」panel live 進度。
  - 右鍵選單（清單/圖格兩視圖）加「檢查更新(此部)」：單一作品同步到網站狀態。
  - 新 `_run_is_update` flag：完成時 log 顯示「■ 更新檢查完成 — 所有新章節處理完畢。」而唔係「全部選取的漫畫下載完成。」

### 行為細節 / 邊界情況
- 新章節下載中途停止：該作保持 `status='done'`，下次檢查更新會再撈到、未完成章節自動重試。
- 網站刪除咗某章：本地記錄/檔案保留（`add_chapters` 唔會刪已有 row）。
- 無新增依賴；requirements.txt 不變。

### 測試
temp DB 自動化測試驗證：`downloaded_path_words()` 四種狀態識別（done / 部分下載 / 全新 / 全章 done 但 status 未設）、章節合併冪等性（舊章保持 done、新章 pending）、中斷後可再撈、Engine 簽名 — 全部通過；測試腳本已刪除。

## 2026-09-27 — 修復封面圖片完全不顯示（threading bug）

### Bug：所有縮圖/封面從頭到尾都冇顯示過
- **根因**：tkinter 唔係 thread-safe。`_load_thumb` 喺 worker thread 直接叫
  `root.after(...)` 同 `ImageTk.PhotoImage(...)`，呢兩樣都係 Tk 調用——喺本機 Python/Tcl
  build 會即刻擲 `RuntimeError: main thread is not in main loop`（被 try/except 吞咗，
  console 先見到 traceback），所以右欄詳情封面、GRID 卡片縮圖、清單縮圖全部靜默失敗。
- **修復**：
  - worker thread 只做網絡下載 + PIL resize（純 Python）；PIL image 經 `self.events` queue
    傳返主線程，由 `_poll_events`（100ms poller）執行新嘅 `("ui", fn)` 事件類型。
  - **PhotoImage 改喺 Tk thread 建立**（新 `_make_thumb()`），cache 值由 `(photo, path)` 簡化做 PhotoImage。
  - 一併修埋其他 worker→Tk 越界調用：三個「清除」動作嘅 `root.after(0, _reload_tree/_refresh_list)`
    全部改經 queue（之前同樣會靜默失敗，清完清單 UI 唔會刷新）。
  - `_show_detail` 加「載入中…」placeholder；封面下載失敗時右欄顯示「(封面載入失敗)」而唔係永遠轉圈。
- **驗證**：temp DB 自動化測試（失敗路徑 dispatch、queue 成功路徑）+ 真實網絡端到端
  （真 cover URL → 右欄出圖 ✓、138px grid 尺寸獨立快取 ✓）。重新打包 EXE，frozen DB 備份還原。

## 2026-09-27 — DPI 清晰化 + GRID 視圖 + 淺/暗色主題（第三輪）

### High DPI Awareness（修復打包後字體模糊）
- `app.py` 開頭新增 `_enable_high_dpi()`：喺建立任何 Tk window **之前**用 ctypes 宣告
  `SetProcessDpiAwarenessContext(PER_MONITOR_AWARE_V2)`（Win10 1703+），失敗則 fallback
  `SetProcessDpiAwareness(2)` → `SetProcessDPIAware()`。Windows 唔再 bitmap-stretch 成個 app，
  125%/150% 縮放下文字同控件以原生解析度渲染、無毛邊；跨不同縮放比例嘅螢幕移動時會自動重排。

### OUTPUT 改 CHECKBOX（CBZ / WEBP）
- 工具列「輸出」由下拉改為 **兩個 checkbox：☑ CBZ ☑ WEBP**，默認全開（= 舊 BOTH）。
- 選擇即時存入 DB meta `output_cbz` / `output_webp`（"1"/"0"），重啟保留。
- `cm/outputs.py::_output_flags()` 讀新 key；舊版存嘅單一 `output_mode`（CBZ/WEBP/BOTH）自動兼容映射。

### GRID 圖片顯示模式
- Row2 右側新增「▦ 圖格視圖 / ☰ 清單視圖」切換按鈕，左欄喺 tree 同封面卡片網格之間互換。
- 卡片：封面縮圖（138×170，async 下載、Semaphore(8) 限流、按尺寸快取）+ 名稱；左上 ✓ badge
  （點擊=勾選/取消勾選下載）、右上 ★（收藏）、已下載綠邊框、選中藍邊框。
- 單擊=顯示右欄詳情、雙擊=開原連結、右鍵=選單（勾選下載／重新下載此漫畫／開啟原連結）。
- 下載中每秒 live 更新：完成嘅卡片即時轉綠邊；「全選(可見)」兩種視圖都生效。

### 佈局與主題
- **左欄清單佔 ~70% 寬度**：Panedwindow `<Configure>` 自動 `sashpos(0, width*0.7)`，
  用戶手拖 sash（`<<PaneChanged>>`）後即停止自動定位；右欄詳情標題 wraplength 跟隨實際寬度。
- **淺色/暗色模式**：Row1 新增「🌙 暗色模式 / ☀️ 淺色模式」按鈕；兩套完整色板（THEMES dict），
  覆蓋全部 ttk styles + tk.Text/log + tree tags + grid 卡片 + 右鍵選單；選擇存入 DB meta `ui_theme`。
- **默認全屏啟動**：`root.state("zoomed")`（maximized）。

### 測試與打包
- 臨時自動化測試（temp DB）驗證：checkbox 默認/持久化/legacy fallback、主題切換+持久化、
  grid 渲染/reflow/勾選、sash 70%（1400px→980px）、全選(可見)——全部通過後已刪除腳本。
- 重新打包 portable EXE；frozen DB WAL 合併後備份並還原。

## 2026-09-27 — OUTPUT 格式選擇 + GUI 微調（第二輪）

### 新功能：OUTPUT 輸出格式（CBZ / WEBP / BOTH）
- **工具列新增「輸出」下拉**（第一列，★只看收藏之後），三個選項：`CBZ`、`WEBP`、`BOTH`；選擇即時存入 DB meta（`output_mode`），重啟保留。
- **新模組 `cm/outputs.py`**（冪等後處理）：
  - `CBZ`：章節全部頁下載完後，按頁序 zip 成 `<漫畫資料夾>/<章節>.cbz`（ZIP_STORED），路徑寫入 `chapters.cbz_path`。
  - `WEBP`：將章節內非 webp 嘅頁（jpg/png fallback）用 Pillow 轉為 `.webp`（quality=85）並**原地取代**原檔，同步更新 `images.path`。
  - `BOTH`：兩者都做（先轉 webp、再 zip，所以 CBZ 入面係 webp 頁）。
- **觸發點**（`cm/engine.py`）：章節標記 done 時即時產生；每次處理完一部漫畫再做一次全章節 post-pass → 之後改 OUTPUT 模式唔使重新下載就會補產輸出。
- **冪等保證**：webp 頁跳過；CBZ 只喺缺失或比任何頁新舊時重建（mtime 比較）。
- `cm/db.py` 新增 `update_image_path()`、`set_chapter_cbz()`。
- 依賴：新增 `Pillow>=10.0`（requirements.txt）；spec 加 `PIL`/`PIL.Image` hiddenimports（lazy import）。

### GUI 微調
- **右側預覽欄固定 ~300px**（frame width=300、weight=0，清單佔其餘空間）；標題 wraplength 同步收窄。
- **已下載行綠色背景**：tree 新增 `done` tag（palette `done_bg #e0f0e2`），`_populate_tree` 套用、`_refresh_rows_live` 每秒同步 → 下載完成即時變綠。
- **「更改位置」按鈕靠左 + 深色**：新 `Dark.TButton` style（#1f2937 深藍灰底、白字 Segoe UI Semibold），排喺路徑 label 之前，label 填滿剩餘寬度；文字改「📂 更改位置」。

### 打包
- 重新打包 portable EXE；frozen DB（27,108 部）WAL 合併後備份並還原。

## 2026-09-27 — GUI 全面改版 + 篩選 Bug 修復

### GUI 改版（app.py）
- **視窗加寬**：預設尺寸由 `1080×720` 改為 `1520×840`，並設最小尺寸 `1180×640`。
- **上方工具列拆成兩列分組**（確保所有按鈕完整露出）：
  - 第一列：搜尋 / 狀態▾ / ★只看收藏 ……（右側）開啟資料夾 / 停止下載 / 開始下載
  - 第二列：更新清單 / 停止更新 / 清空並重抓 ｜ 全選(可見) / 取消全選 ｜ 重下(選取) / 清單清除 / 下載清除（垂直分隔線分組）
- **左邊清單加寬**：各欄位寬度調整（sel/fav=34、name=220、author=110、serial=58、progress=168、status=82、link=40），並新增 `<Configure>` 綁定 `_on_tree_configure`，令「名稱」欄自動撐滿剩餘空間 → 一打開即顯示全部欄位、無水平捲軸。
- **統計狀態欄放喺清單區同下方 LOG 之間**：全寬橫向「統計」卡片（`Card.TLabelframe`），單行顯示 `全部/未下載/已下載/出錯 ｜ ★收藏 ｜ 連載中/已完結/短篇`，唔再佔右側預覽欄空間。
- 底部保留進度條 + 日誌區；下載中面板行為不變。

### Bug 修復（cm/db.py）
- **SQL 運算子優先級**：搜尋條件加括號 `(name LIKE ? OR author LIKE ?)`，確保 `AND serial_status=?` / `AND favorite=1` 正確套用 → 「狀態」篩選同「只看收藏」而家真正生效。

### 打包
- 重新以 PyInstaller 打包 portable EXE（`MangaCopy.spec`），Chromium 由預設 Playwright 路徑複製入 `dist/MangaCopy/playwright-browsers/`，784 部漫畫嘅 frozen DB 已備份並還原。
