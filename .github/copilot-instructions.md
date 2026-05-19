# Action Camera Drain

為運動相機使用者打造的內容快速交付 App — 把記憶卡上的素材搬到手機，然後把卡清空。

預期使用流程：

1. 使用者把記憶卡從運動相機取下。
2. 使用者把卡放進讀卡機。
3. 讀卡機接到支援 PD（Power Delivery）的 USB 外接裝置。
4. USB 裝置接到手機。
5. 系統自動啟動 Action Camera Drain App，或由使用者手動開啟。
6. 使用者選定或新建上傳目的地。
7. App 上傳卡片內容，完成後做內容確認，並詢問是否清空卡。
8. 退卡完成。

## 倉儲結構

- `src/android/` — 目前唯一的程式碼模組；標準 Gradle Android 專案（`:app`）。
  - Package：`net.dinowang.actioncameradrain`
  - UI：Jetpack Compose（Material 3）。進入點：`app/src/main/java/.../MainActivity.kt`。
  - 主題程式碼放在 `.../ui/theme/`。
- `docs/` — 長文件；資產分別放在 `docs/images/` 與 `docs/diagrams/`。
- `.changes/` — 經過策劃的變更紀錄（見下方流程）。索引在 `.changes/README.md`。
- `.prompt/workflows/` — 開發者在 Copilot session 中以 `@` 引用的 prompt 檔。每次叫用都當成是對前一版的 diff 來處理。
- `action-camera-drain.code-workspace` — VS Code 的標準多根工作區進入點；開這個比開倉根目錄更符合專案視角。

## 建置 / 測試 / Lint

所有 Gradle 指令都得在 `src/android/` 下執行（`gradlew` 與 `settings.gradle.kts` 都在那裡）。

```bash
cd src/android
./gradlew assembleDebug              # 建置 debug APK
./gradlew test                       # 全部 JVM 單元測試
./gradlew :app:testDebugUnitTest     # debug variant 的單元測試
./gradlew connectedAndroidTest       # 儀器測試（需裝置／模擬器）
./gradlew lint                       # Android Lint
```

跑單一單元測試：

```bash
./gradlew :app:testDebugUnitTest --tests "net.dinowang.actioncameradrain.ExampleUnitTest.<methodName>"
```

跑單一儀器測試：

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.dinowang.actioncameradrain.ExampleInstrumentedTest#<methodName>
```

## 工具鏈限制

以下版本固定在 `src/android/gradle/libs.versions.toml` 與 `app/build.gradle.kts`，**不要隨意升版**：

- AGP `9.2.1`、Kotlin `2.2.10`、Compose BOM `2026.02.01`
- `compileSdk = 36`（`minorApiLevel = 1`）、`targetSdk = 36`、`minSdk = 34`
- `JavaVersion.VERSION_11`（`sourceCompatibility` / `targetCompatibility`）
- 所有相依套件版本一律走 `libs.versions.toml` version catalog — 先加進 catalog，再在 `app/build.gradle.kts` 以 `libs.xxx` 引用。**不要**在 `build.gradle.kts` 寫死座標。

## 本倉特有的慣例

### 變更紀錄（`.changes/`）

- 檔名：`YYYYMMDD-NN-PURPOSE.md`，purpose 用大寫（例：`20240624-01-API-OVERVIEW.md`）。`NN` 每天從 `01` 起算。
- `.changes/README.md` 是索引，分 **Active / Canonical** 與 **Obsolete / Merged** 兩段 — 新增、合併、退役任何一筆紀錄時都要同步更新。
- 整併流程依 `.prompt/workflows/changes-consolidation.md`：兩段式（Analyze & Propose → 明確核可後 Apply）。擔任整併者時，**不要**動到 `.changes/` 以外的任何東西。
- 舊資料：若出現 `.history/` 目錄，請改名為 `.changes/`。

### Prompt 檔（`.prompt/`）

- 開發者在這裡寫 prompt，再以 `@` 在對話中引用。同一份檔案可能跨多次 session 重複送，因此要對比上一版差異、據此規劃工作。
- 開發者要 commit 由某份 prompt 觸發的變更時，**那份 prompt 檔也要一起進到同一個 commit**。

### Markdown 文件

- 使用 GFM。Ordered list 內含子內容（程式碼區塊、表格）時，子內容要**縮排 3 個空白**，對齊 `N. ` 後的字，否則編號會被重設。
- 文件引用的圖片放在 `docs/images/<doc-basename>/...`（非 `docs/` 下的 markdown 則放在文件旁的 `images/<basename>/` 子資料夾）。Diagram-as-Code 的原始檔放在渲染出來的圖檔旁邊。
- **絕對不要**把 Mermaid 寫在 fenced code block 裡（在 dark theme 下表現很差），ASCII art 也避免 — 改用 `diagram-maker` skill。
- front-matter 帶 `convertTo: html` 或 `html-embedded` 的文件，每次編輯後都要重新渲染出同名的 `.html`。

### Git

- **絕對不要**自動 `git push`。也**不要**自動 `commit && push`；讓開發者自己檢視並決定。
- 你被要求 commit 時，要在訊息最後加上 co-author trailer：
  `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`

### 安全

- 不要把密碼、API key 或其他機敏字串印到畫面上 — 做遮罩，或請開發者去看 `.env`。
- 對「API 是否支援」、「版本相容性」、「慣例規範」等斷言，要對權威來源（官方文件、規格、原始碼）做事實查核才能寫進文件；查不到的就標 「unverified」 或乾脆不寫。
