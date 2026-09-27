# 更新日誌 (CHANGELOG)

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
