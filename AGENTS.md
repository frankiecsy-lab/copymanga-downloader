# AI 自動進度管理、記憶與 Git 全自動版控規範

## 1. 核心開發環境與溝通語言
* **開發環境**：Windows 11 + PyCharm + Claude Code。
* **溝通語言**：在開發與對話過程中，**全程必須使用繁體廣東話**（HK Cantonese）與開發者溝通。
* **環境相容性**：所有腳本與指令編寫必須原生支援 Windows 11 環境，檔案路徑處理優先使用相容性工具（如 Python `pathlib`）。

## 2. 專案記憶與無縫接手機制 (State & Memory Management)
* **狀態即時同步**：任何開發步驟完成、架構變更或功能調整後，必須馬上將整套方案的架構要求、最新進度與細節，詳細記錄到對應的 Markdown 文檔（如 `README.md`、`ARCHITECTURE.md` 或本 `AGENTS.md`）。
* **發佈日誌**：每完成一個核心功能，須同步在 `CHANGELOG.md` 記載更新摘要，方便追蹤。
* **無縫接手目標**：確保任何全新、獨立的 AI Agent 在讀取這些 `.md` 文檔後，都能**無時差馬上接手**，並完全掌握最新功能的開發進度與業務邏輯。

## 3. 測試與錯誤自救機制 (Testing & Error Handling)
* **先測試後提交**：執行 Git 提交前，必須自動運行專案的測試指令。測試通過後方可觸發自動 PUSH；若測試失敗，則自動進入修復流程。
* **卡關上限 (Anti-Loop)**：方針針對同一錯誤連續修復超過 **3 次**仍告失敗時，AI 必須立即停止自動修改，盤點已嘗試的方案，並向開發者回報尋求引導。
* **安全還原 (Rollback)**：若修改導致系統崩潰或大規模 Regression，須善用 Git 工具將代碼還原至健康狀態，嚴禁將崩潰代碼 Push 至遠端。
* **測試環境清理 (Workspace Cleanup)**：
  * 當所有功能測試完成、準備進行 Git 提交前，**必須自動清理環境**。
  * 凡是為了本次測試而臨時建立的虛擬資料、臨時測試腳本（例如 `test_tmp.py`、`dummy_data.json` 等臨時檔案），**必須一律徹底刪除**，嚴禁將測試廢料提交進代碼庫。
  * **【核心禁令】嚴禁刪除本地的 `venv` 虛擬環境資料夾！** 
  * **【依賴維護】** 若測試與開發期間有新增、變更或移除 Python 第三方套件，必須在清理階段自動執行 `pip freeze > requirements.txt`，以動態同步並清理專案的依賴套件清單。

## 4. Git 全自動版控與部署規範 (Automated Git Workflow)
* **原子化提交 (Atomic Commits)**：每當完成**一個獨立功能**或**一個修復步驟**，必須即時執行 Git 流程。
* **全自動自動化**：自動執行 `git add .`、`git commit` 並且 `git push` 到遠端代碼庫。
* **日誌規範 (Commit Message)**：
  * 必須使用**繁體中文**編寫 Commit Message。
  * 內容必須詳細記載本次提交的修改內容、流程邏輯以及對整體專案的影響。
* **遠端 SSH 自動部署規範 (Automated SSH Deployment)**：
  * 每次本地成功 `git push` 到遠端代碼庫後，AI 必須**立刻自動執行部署指令**。
  * 必須利用 Windows 11 本地的 SSH 工具，依據 `.env` 設定的伺服器資料連線至遠端伺服器。
  * 自動在遠端伺服器對應的網站資料夾執行部署流程（例如：`git pull`、重新安裝依賴、重啟服務、或清除伺服器快取）。
  * 部署完成後，必須自動檢查伺服器狀態（如透過 `curl` 測試網站狀態碼），確認遠端網站更新成功且運行正常後，方可結束任務。
* **工作匯報規範 (Principle & Flow Summary)**：
  * 每次功能製作、修改或修復完成，並**成功自動部署至伺服器後**，在對話視窗內**必須使用繁體廣東話**向開發者進行簡短匯報。
  * 匯報內容必須清晰拆解：**【製作原理】**（背後使用了什麼技術、設計模式、底層邏輯或核心演算法）以及**【代碼運行流程與部署狀態】**（由頭到尾資料是怎樣流動的、遠端部署與運行是否一切正常）。

## 5. 機密資料與環境變數管理 (.env Policy)
* **唯一事實來源 (Single Source of Truth)**：專案運作與部署所需的所有伺服器 IP、SSH 帳號密碼、API 網址、MCP 參數、密碼、API Key（包括 TTLOCK、FUTU 等）等敏感資料，**必須優先讀取 `.env` 檔案**。
* **動態更新機制**：如果使用者在對話視窗中提供了新的密碼、Token 或 API Key，AI 必須在驗證該資料有效及可用後，**第一時間自動更新或寫入 `.env` 檔案**。
* **安全防護**：嚴禁將 `.env` 檔案或任何明文密碼提交至 Git 倉庫（必須在 `.gitignore` 中將其忽略）。

## 6. 開發知識庫與踩坑記錄 (Knowledge Base & Lessons Learned)
* **經驗動態沉澱**：開發過程中凡是發現**重要技術限制**（如 API 頻率限制 Rate Limit、硬體瓶頸）、**關鍵說明書/官方文檔網址**、或**耗時很久才解開的特異 Bug**，必須馬上寫入專案知識庫。
* **記錄存放位置**：
  * 如果是全域或跨模組的重大限制，直接更新在本 `AGENTS.md` 的最底部 [附錄：開發知識庫]。
  * 如果是特定模組的細節，則在該模組目錄下建立或更新 `README.md`。
* **記錄格式規範**：必須用簡潔的繁體中文列明：
  * **【問題/限制】**：簡述遇到了什麼（例如：某 API 每分鐘限制 60 次）。
  * **【正確資源】**：附上好不容易找到的官方正確說明書連結（例如：[官方 Docs 網址]）。
  * **【解決/避坑方案】**：AI 與開發者最終採用的應對程式碼邏輯或策略，確保下次遇到時能直接秒殺。

---
## 附錄：開發知識庫 (隨時動態追加)

### 📌 核心 API 說明書與文件速查表

#### 1. 🥇 核心接口：HKQuant API
* **【名稱】**：HKQuant API (Swagger UI)
* **【正確資源】**：https://hkquant.com
* **【核心架構準則】**：本專案對接富途（Futu）功能時，**一律直接調用 HKQuant API 提供的端點**。AI 嚴禁自行安裝富途官方 SDK 或嘗試啟動本地 OpenD 行程。所有行情數據與交易指令，均透過此 Swagger 文件定義的格式發送至 `://hkquant.com`。

#### 2. 🥈 輔助對照：富途 Futu 官方 API (FutuOpenD)
* **【名稱】**：富途命令行 OpenD 啟動與參數配置規範
* **【正確資源】**：https://futunn.com
* **【備註說明】**：此網址**僅作為輔助參考與功能對照用途**。當開發 HKQuant 相關功能遇到邏輯不清晰（例如：搶行情權限、特定 K 線參數、到價提醒回調）時，可參閱此官方文件的底層邏輯進行代碼設計。

### 📌 踩坑記錄（構建與打包）

#### 1. PyInstaller 會 wipe 成個輸出目錄
* **【問題/限制】**：`PyInstaller`（one-dir / COLLECT）**每次 build 都會刪除整個 `dist\MangaCopy\` 資料夾** — 包括放喺入面嘅 portable user data（`data\mangacopy.db`、`downloads\` 下載咗嘅漫畫）。2026-09-27 重燒時 frozen DB 即被清走。
* **【解決/避坑方案】**：`build.bat` 已加固 — build 前自動將 `data\` + `downloads\` move 去 `dist\_userdata\`，build 後（包括失敗路徑）move 返；同碟 move = instant rename，幾 GB 都唔會拖慢。**日後手動重燒或改 spec 時，務必先保護呢兩個目錄**（或者乾脆直接跑 `build.bat`）。

#### 2. Windows batch 檔必須用 CRLF 行尾
* **【問題/限制】**：`.bat` 檔案若係 LF（Unix）行尾，cmd.exe 會搵唔到 label — `call :label` / `goto :label` **靜默失敗**、執行直接跳過該行（無明顯錯誤訊息），極難排查。
* **【解決/避坑方案】**：用編輯器/AI 建立或修改 `.bat` 後，必須確認行尾係 CRLF；如唔係用 `sed -i 's/$/\r/' file.bat` 轉換。改動 label/goto 邏輯後要實際執行一次該路徑驗證（例如故意觸發失敗分支）。

#### 3. Windows SearchIndexer (sihost.exe) lock downloads 目錄令 build 失敗
* **【問題/限制】**：`build.bat` step 1 staging user data 時 `move dist\MangaCopy\downloads → dist\_userdata\` 可能報 **Access is denied** — 即使 MangaCopy.exe 根本冇運行。元兇係 Windows Search 嘅 per-user 索引進程 `sihost.exe`：佢枚举/索引 webp 檔案時會 hold 住 `downloads` 目錄嘅 open handle，令該目錄本身同埋直接子項無法 rename/move（更深層子目錄操作卻正常），極易誤判做「EXE 仲開緊」。2026-09-27 重燒 EXE 時踩到。
* **【正確資源】**：[Microsoft Docs: Restart Manager API](https://learn.microsoft.com/en-us/windows/win32/api/rstrtmgr/ne-rstrtmgr-rmtarget_resource)（查詢「邊個程序開緊某目錄樹下檔案」嘅標準工具；注意 Insider build 上 rstrtmgr.dll 可能壞咗，唔好依賴）。
* **【解決/避坑方案】**：`taskkill /f /im sihost.exe` 後重跑 `build.bat`（sihost 會由 SearchIndexer 服務自動重生，對系統無影響）。排查順序：① `tasklist` 確認 EXE 冇運行 → ② 用 Python `os.rename` 分別測試頂層目錄 vs 子目錄，定位 lock 喺邊一層 → ③ PowerShell COM（`(New-Object -ComObject Shell.Application).Windows()` + `.LocationURL`）枚举 Explorer 視窗位置排除 Explorer → ④ 都唔係就係 sihost / Defender 實時掃描。

#### 4. Git Bash 嘅 `tar` 唔支援 ZIP（解壓工具鏈 zip 靜默失敗）
* **【問題/限制】**：Windows 11 系統自帶 `tar.exe`（bsdtar）食 ZIP，但 Git Bash 內置嘅係 GNU tar — `tar -xf xxx.zip` 報 `This does not look like a tar archive`。而且如果外層用 `bash script.sh | tee log`，pipe 會吞咗非零 exit code，睇落「成功」其實失敗。
* **【解決/避坑方案】**：解 ZIP 一律用 `powershell.exe -NoProfile -Command "Expand-Archive -LiteralPath ... -DestinationPath ... -Force"`（見 `D:\android-tools\setup_tools2.sh`）；長腳本唔好 pipe 到 tee，直接 `> log 2>&1` 保留 exit code。

#### 5. Android 工具鏈喺 D:\android-tools（本機 Java 8 用唔著）
* **【問題/限制】**：本機系統 Java 只有 1.8，Gradle 8.9 / AGP 8.7 要 JDK 17+；Android SDK 原先完全冇。
* **【正確資源】**：Temurin JDK17（`https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse`）、Google `commandlinetools-win-11076708_latest.zip`（解壓後必須放 `<sdk>\cmdline-tools\latest\` 先至畀 sdkmanager 認）。
* **【解決/避坑方案】**：全部裝 `D:\android-tools\{jdk17,sdk,gradle-8.9}`（repo 之外）。**每次構建都要 `JAVA_HOME="D:/android-tools/jdk17"`**；`android/local.properties` 寫 `sdk.dir`（已 gitignore）。詳細指令見 `android/README.md`。

#### 6. 離屏 WebView viewport 係 0×0，lazy-load 永遠唔觸發
* **【問題/限制】**：Android `WebView` 冇 attach 到視窗又冇經 measure/layout 時，JS 嘅 `innerWidth/innerHeight` 係 0 — 站點圖片 lazy-load 嘅 IntersectionObserver 永遠唔會 enter viewport，`render_chapter` 永遠收唔到圖（Playwright 冇呢個問題因為真 window）。
* **【解決/避坑方案】**：創建後手動畀屏幕尺寸：`measure(...); layout(0,0,screenW,screenH)`（已寫入 `WebViewRenderer.ensure()`）。同理適用所有離屏渲染场景。

#### 7. Session 中斷可以令已寫入嘅檔案靜默消失
* **【問題/限制】**：2026-11-17 開發 Android 期間，`DownloadEngine.kt` 寫入時工具回報「File created successfully」，但 session 重啟後個檔案唔喺盤上，之後 UI 引用全部 Unresolved reference。
* **【解決/避坑方案】**：**跨 session 恢復後，凡要繼續編輯一個模組，先 `ls` 核對檔案清單再講**；編譯（`gradle compileDebugKotlin` / `python -m py_compile`）係最後防線，唔好單信 write 成功訊息。

#### 8. callbackFlow 內 `launch{}` 預設 inherit collect 者嘅 Dispatcher → 堵死主線程
* **【問題/限制】**：Android Compose `LaunchedEffect` 用 Main dispatcher collect Flow；`callbackFlow { launch { OkHttp同步下載 } }` 嘅 `launch` 冇指定 dispatcher 就會跟住 collect 者行喺主線程 — 網絡 I/O 直接卡死 UI（冇 ANR 表象、冇異常，極難歸因）。2026-11-17 ChapterPreloader 踩過：preflow 零下載、App 假死。
* **【解決/避坑方案】**：callbackFlow 內凡做阻塞 I/O 一律 `launch(Dispatchers.IO)`；同理适用于 `flow { }` builder（collect 喺邊就喺邊行）。

#### 9. Pager 跨章瞬間 clamp 去舊 index（flash-frame）會污染 readPos → 連環誤跳
* **【問題/限制】**：切章（currentCid 改變）瞬間 `HorizontalPager` 嘅 currentPage 未 reset，會短暫停留喺上一章嘅 index（例如尾卡 index=12）；若 debounce 保存協程恰好呢一刻寫入，新章 readPos 被污染成「尾頁」→ 開場就係尾卡 → 觸發自動接下一章 → 連環跳章。同理 LazyColumn scrollToItem 未企穩前 layoutInfo 都係舊值。
* **【解決/避坑方案】**：ReaderScreen 用 `switching` 屏蔽旗：`gotoChapter` 設 true → 恢復 effect 完成 `scrollToPage`/`scrollToItem` 定位後（`restoreDone`）先清；保存協程 `if (!switching.value)` 先寫 DB。恢复定位必須喺「頁數足夠」之後先跳（`pages.size > pendingRestore`），越界 fallback 尾頁。

#### 10. Android 無後端嘅本地假站 E2E 方法論（站點維護時驗證抓取/下載/閱讀全鏈路）
* **【問題/限制】**：CopyManga 詳情頁全线維護時，無法用真實站做章節渲染/跨章 E2E；mockk 又覆蓋唔到 WebViewRenderer 真實 JS 路徑。
* **【解決/避坑方案】**（已驗證方法論，工具全部系統自帶）：
  1. PC Python 生成假站 HTML（**1:1 仿真實站 DOM**：`.comicContent-list li img[data-src]` + 「N / M」badge + 純 Python 生成色調 PNG 逐章逐頁不同色方便截圖鑑章）+ `python -m http.server 8080 --bind 127.0.0.1`
  2. `adb reverse tcp:8080 tcp:8080` → 手機 App 直接訪 `http://127.0.0.1:8080`
  3. cleartext HTTP：`app/src/debug/AndroidManifest.xml` 加 `usesCleartextTraffic=true` ＋ **`tools:replace`**（manifest merger 強制，冇就 build 失敗）；只影響 debug variant
  4. DB seed：PC 起 `sqlite3` 建 v4 schema seed → `adb exec-out run-as com.mangacopy.app` 側 `cp`（**cp 前必須 rm `mangacopy.db*` 連 `-journal/-wal/-shm`**，否則舊 WAL 覆蓋新 data）
  5. 驗證 pull DB：`run-as cat databases/mangacopy.db` + **`cat ...db-wal`** 落同目錄開（app 用 WAL mode，最新寫入未 checkpoint 只喺 -wal 入面，淨 pull 主檔會睇到舊態）
  6. 章節 url 欄直接寫假站 URL 入 seed → WebViewRenderer/renderChapter/逐頁下載/跨章預載全部行**真實代碼路徑**
* **【注意】**：Git Bash 會 mangle `/data/...` 路徑 → adb shell 指令一律加 `MSYS_NO_PATHCONV=1`；background http server 有時間上限會自動俾 kill，長 test 要設大 `timeout`。

#### 11. CopyManga 站點域現狀（2026-11-17 實測）
* **【問題/限制】**：詳情頁全线維護 — `.fun` 404「服務器搬遷維護中」、`.site` 5.6KB WAF redirect shell、`.io` 連唔上、`.mangacopy.com` 404 + ASUS 路由器 DNS 劫持（cert CN=blocking.asus.hns.tm）。**唯 searchcl 搜尋 API 只喺 `https://www.copymanga.fun` 存活**（JSON code 200）。
* **【解決/避坑方案】**：App 維持多域名 fallback 策略；搜尋功能用 `.fun` searchcl；詳情/章節 E2E 用本地假站方法論（見第 10 條）替代，站點復原後再跑真實站回归。

*(此處留空，供 AI 在後續開發中自動填入發現的頻率限制、新官方文檔網址等珍貴經驗)*
