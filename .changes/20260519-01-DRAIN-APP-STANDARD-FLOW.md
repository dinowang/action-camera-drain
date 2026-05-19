---
title: "Standard Flow 首版實作：USB 偵測 → 識別 → Azure Blob 上傳"
description: "把 Android App 從 Hello World 樣板擴成可端到端執行的最小可用版本：UploadConfig 載入、USB 偵測 + SAF 掛載、filing-poc 邏輯 Kotlin port（含 MP4 / Insta360 fileinfo 偵測）、Azure Blob REST 上傳引擎（多執行緒、自適應併發、斷點續傳、失敗重試）、Compose UI。"
keywords:
  - Action Camera Drain
  - Android
  - Jetpack Compose
  - Azure Blob Storage
  - SAS
  - USB Host
  - Storage Access Framework
  - filing-poc port
  - MP4 metadata
  - 斷點續傳
author: dinowang
type: Notes / Record
favicon: ""
createdAt: 2026-05-19 09:51:51
updatedAt: 2026-05-19 09:51:51
references:
  - type: prompt
    path: ../.prompt/impl-drain-app-standard.md
  - type: reference
    path: ../docs/flash-filing-logic.md
  - type: reference
    path: ../src/filing-poc
  - type: reference
    uri: https://learn.microsoft.com/rest/api/storageservices/put-block
  - type: reference
    uri: https://learn.microsoft.com/rest/api/storageservices/put-block-list
---

# Standard Flow 首版實作

## 範圍

依 `.prompt/impl-drain-app-standard.md` 完成的最小可用版本。原 `MainActivity.kt` 為 Hello World 樣板，本輪擴成端到端流程：載入上傳設定 → 偵測 USB / 掛載 SAF tree → 走 filing-poc 邏輯產出 ingest plan → 以多執行緒上傳到 Azure Blob，支援斷點續傳與失敗自我修復。

## 互動確認的設計選擇

- 機密儲存：`res/raw/upload_config.json`（真實檔案 git-ignore；附 `upload_config_sample.json` 樣板與佔位 `?sv=...&sig=...` SAS）。
- 識別邏輯：Phase 1（filename / folder pattern）+ MP4 atom probe + Insta360 `fileinfo_list.list`。
- Azure client：OkHttp + REST（PutBlock / PutBlockList / DELETE），不引入 `com.azure:azure-storage-blob` SDK。
- 抹卡：**本輪不做**，上傳完成只顯示 state。

## 結構

```
app/src/main/java/net/dinowang/actioncameradrain/
├── MainActivity.kt                  # 進入點，掛上 MainScreen
├── data/
│   ├── config/
│   │   ├── UploadConfig.kt          # sealed UploadConfig + UploadAuth + maskSecret
│   │   └── ConfigRepository.kt      # 從 res/raw 載入，缺真實檔回退到 sample
│   └── storage/
│       └── AzureBlobClient.kt       # PutBlock / PutBlockList / DELETE
├── domain/
│   ├── filing/
│   │   ├── MediaSource.kt           # MediaSource / MediaFile 抽象（測試友善）
│   │   ├── SafMediaSource.kt        # DocumentFile-backed 實作
│   │   ├── Device.kt                # Device + DeviceKey（normalize、base32(sha256(serial))）
│   │   ├── DcimDetector.kt          # 檔名 pattern → brand/lens
│   │   ├── Mp4Probe.kt              # MP4 box walker，udta + meta(mdta keys+ilst)
│   │   ├── Insta360Probe.kt         # fileinfo_list.list 掃描
│   │   └── IngestPlanner.kt         # 整合上述，產 IngestPlan / conflicts / oversized
│   ├── upload/
│   │   ├── UploadModels.kt          # FileUploadStatus / UploadProgress / StartMode / UploadCheckpoint
│   │   ├── CheckpointStore.kt       # DataStore-backed，key = config:source:blob
│   │   ├── AdaptiveConcurrency.kt   # ResizableGate + ThroughputTracker + 簡易爬山
│   │   └── UploadEngine.kt          # 主迴圈（workers + sampler + 重試 + 失敗清遠端）
│   └── usb/
│       └── UsbCardWatcher.kt        # USB_DEVICE_ATTACHED/DETACHED + 目前清單
└── ui/
    ├── MainViewModel.kt             # AndroidViewModel；管理 cards / plan / engine
    └── MainScreen.kt                # 上=Config 卡片，下=每卡列表（plan、進度、Start/Restart）
```

## 行為對應

| Prompt 條目 | 實作位置 |
| --- | --- |
| 預設上傳設定上方顯示、可切換（現階段一組） | `ConfigSection` + `ExposedDropdownMenuBox`（單一選項） |
| Azure SAS / Connection String 存 resources | `res/raw/upload_config.json`（git-ignore）+ `upload_config_sample.json` |
| 掃描 USB 裝置，列在下方 | `UsbCardWatcher` + `MainViewModel.reconcileFromUsb` |
| 卡片識別 + ingest plan | `IngestPlanner.plan(SafMediaSource)` |
| 點按鈕開始上傳，顯示進度 | `MainScreen.CardItem` 的 Start / 繼續 / 從頭開始 |
| 檔名包含日期時間與本地相同 | `IngestPlanner.planBlobName` 直接保留原檔名 |
| 從斷點接續 / 重新開始 | `StartMode.RESUME` vs `RESTART`；`CheckpointStore` per-blob block list |
| 多執行緒、可變併發 | `AdaptiveConcurrency` + `ResizableGate`；每秒 `tick` 依 EWMA 吞吐量 ±1 |
| 失敗時重新上傳、抹遠端 | `UploadEngine.uploadOne` catch → `client.deleteBlob` + checkpoint.delete + retry |
| 卡片喪失 / EJECT → 失敗，重接後可續 | `reportCardLost` → `cancel`；存活的 checkpoint 仍可後續 RESUME |

## 不在本輪 scope

- 自動啟動（USB attach intent filter）
- 第二組（非 Azure）上傳設定
- 上傳完成後抹卡
- 內容驗證 hash（md5 對比）
- 真正動態 thread 增刪：目前 worker 數 = 起始 target × 2，靠 `ResizableGate.target` 限制有效在飛 worker 數（軟性縮放），夠用但不是理論最優

## 驗證

- `./gradlew :app:testDebugUnitTest` — Filing + AzureBlobClient 共 22 個單元測試，全綠。
  - `DeviceKey` normalize、序號 hash 短碼、stripRedundantBrandPrefix
  - `DcimDetector` GoPro / Insta360 (360 / flat) / DJI / unknown
  - `IngestPlanner` 路徑切分、root 繼承、隱藏檔過濾、blob path 組合
  - `AzureBlobClient` 用 MockWebServer 驗證 PutBlock query string、PutBlockList XML、DELETE 容忍 404
- `./gradlew :app:assembleDebug` — APK 成功
- `./gradlew :app:lintDebug` — 0 error；warnings 全是 newer-version 提示，按 repo 規則不升

## 待後續

- 上傳完成後抹卡（DocumentFile.delete 整棵卡內容；需另一輪明確同意）
- Adaptive 縮放可改成 hard cancel + token pool（目前是 soft shrink）
- 加 SHA-256 / Content-MD5 驗證
- 加 `ACTION_USB_DEVICE_ATTACHED` intent filter，讓系統自動啟動 App
- `MainScreen` 沒有寫 instrumented test，僅靠單元測試 + 手動驗證
