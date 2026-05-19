---
title: Container Selection (Azure Blob)
description: 為 Azure Blob 上傳目標加入「container 選擇 / 新建」流程，並修掉 IngestPlan 把 container 烘進 blob name 造成 URL 重複的 bug。
keywords:
  - android
  - azure-blob
  - container
  - sas
  - upload
  - ui
  - datastore
  - rest
author: dinowang
type: Notes / Record
favicon: "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCI+PHBhdGggZmlsbD0iIzAwNzhENCIgZD0iTTQgN2gxNnYxMEg0VjdtMC0yYTIgMiAwIDAgMC0yIDJ2MTBhMiAyIDAgMCAwIDIgMmgxNmEyIDIgMCAwIDAgMi0yVjdhMiAyIDAgMCAwLTItMmgtNGwtMi0ySDhsLTIgMkg0Ii8+PC9zdmc+"
createdAt: 2026-05-19 10:28:00
updatedAt: 2026-05-19 10:28:00
references:
  - type: ancestor
    path: .changes/20260519-01-DRAIN-APP-STANDARD-FLOW.md
  - type: reference
    uri: https://learn.microsoft.com/rest/api/storageservices/list-containers2
  - type: reference
    uri: https://learn.microsoft.com/rest/api/storageservices/create-container
---

# Container Selection

## 動機

「一個 container = 一次（可能多天的）旅行計畫」。
原本 `UploadConfig.AzureBlob.container` 是寫死在 JSON 設定裡的單一字串，這次把它變成可以在 UI 上選 / 建立 / 切換的對象，並記住每個設定 profile 上次選到的 container。

## 變更摘要

### 修掉的既有 bug

- `IngestPlanner` 之前把 `container` 烘進 blob name（`"$container/$bucket/$subdir/$file"`），
  但 `AzureBlobClient` 又會自己把 container 接成路徑前段 →
  最終 URL 會變成 `{account}/{container}/{container}/{bucket}/...`，container 出現兩次。
- 修法：`IngestPlanner` 不再吃 `container`，產出的 blob name 只剩 `{bucket}/[{subdir}/]{file}`；
  container 由 UploadEngine / UI 在請求時帶入。

### 行為變化

- 上層畫面新增一張「Container（一個旅行計畫）」卡片，介於 Config 卡與裝置列表之間。
  - 文字欄位：可手動輸入既有名稱或新建名稱
  - 下拉清單：列出可用 container（來源見下）
  - 重新整理 icon：重抓 Azure 上的 container 清單
  - 「使用此 container」按鈕：當輸入字串命中遠端清單時啟用
  - 「在 Azure 新建」按鈕：當輸入字串符合 Azure 命名規則且不在遠端清單時啟用
- 啟動時自動 seed：用 `ContainerSelectionRepository.getLast(configId)`，若無則退回 `UploadConfig.AzureBlob.container`
- 切換 container 會清空既有 plan / progress 並自動重 plan（blob path 與 container 綁定）
- 上傳一旦開始即視為強化使用者意圖，將該 container 寫入「last used」與 history MRU

### 拉取 container 清單的權限要求

- `listContainers()` 直接打 `GET {account}/?comp=list`，要求 SAS 必須包含
  - `srt=s`（service resource）
  - `sp=l`（list permission）
- 缺權限時 → UI 顯示「無法列出 container（SAS 可能缺 srt=s/sp=l）」的紅字提示，但仍可繼續使用「輸入既有名稱 + 使用此 container」或新建流程；近期手打過的 container 會以 `recent` 來源出現在下拉中。
- `createContainer(name)` 走 `PUT {account}/{name}?restype=container`，要求 SAS 包含 `sp=c`；對 `409` 視為「已存在」不報錯。

## 檔案異動

新增：
- `data/storage/ContainerSelectionRepository.kt` — DataStore-backed，per-configId 記住 last + history MRU（上限 20）

修改：
- `data/storage/AzureBlobClient.kt`
  - 移除建構子 container 欄位，container 改為每次呼叫的參數
  - 新增 `listContainers()` / `createContainer(name)`
  - 暴露 `defaultContainer` 作為 UI seed 用途
- `domain/filing/IngestPlanner.kt` + `IngestPlan` — 拿掉 `container` 欄位 / 建構子參數；blob name 不再含 container
- `domain/upload/UploadEngine.kt` — 建構子新增 `container: String`，所有 client 呼叫帶入
- `ui/MainViewModel.kt`
  - 新增 `ContainerPickerState` + `container: StateFlow<ContainerPickerState>`
  - `onContainerInputChanged` / `selectContainer` / `createContainer` / `refreshRemoteContainers`
  - `invalidatePlans()` — 切換 container 時清舊 plan、自動 replan
  - `startUpload` 改為從 picker 取目前 container，`scope.sourceId` 帶入 container 以確保 checkpoint 區隔
- `ui/MainScreen.kt` — 新增 `ContainerSection` 區塊（在 Config 卡之下）

測試：
- `AzureBlobClientTest`：原 4 案改為以 `container` 為第一參數呼叫，並新增
  - `listContainersParsesXml`
  - `createContainerHits201`
  - `createContainerTolerates409`
- `FilingTest`：3 案的 `assertEquals` 期望值移除 `ingest/` 前綴；`IngestPlanner` 不再吃 `container` 參數

## 驗證

```text
./gradlew testDebugUnitTest assembleDebug ✅
./gradlew lintDebug ✅  (warnings only — menuAnchor deprecated, 預期)
JVM 單元測試：25 個 (Filing 18 + AzureBlobClient 7)
```

## 後續可做

- 上傳前自動檢查 container 是否存在（缺則 prompt 自動建立）
- 上傳完成後抹卡（先前一直 deferred）
- 第二組（非 Azure）上傳 destination 與真正的 profile 切換
