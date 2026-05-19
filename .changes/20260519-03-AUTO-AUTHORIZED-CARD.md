---
title: USB 卡片自動授權（SAF 一次性）
description: 把「每次插卡都要選資料夾」壓到「第一次插卡按一次 OK、之後完全自動」。利用 StorageVolume.createOpenDocumentTreeIntent 預先把 SAF picker 停在卡片根目錄，並用 DataStore 記住已授權的 tree URI；下次同一張卡插上即直接套用、自動掃描。
keywords:
  - android
  - saf
  - storage-access-framework
  - usb-mass-storage
  - StorageVolume
  - persistent-uri-permission
  - ux
author: dinowang
type: Notes / Record
favicon: "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCI+PHBhdGggZmlsbD0iIzM0QTg1MyIgZD0iTTkgMTYuMTdMNC44MyAxMmwtMS40MiAxLjQxTDkgMTkgMjEgN2wtMS40MS0xLjQxTDkgMTYuMTdaIi8+PC9zdmc+"
createdAt: 2026-05-19 11:25:00
updatedAt: 2026-05-19 11:25:00
references:
  - type: ancestor
    path: .changes/20260519-02-CONTAINER-SELECTION.md
  - type: reference
    uri: https://developer.android.com/reference/android/os/storage/StorageVolume#createOpenDocumentTreeIntent()
  - type: reference
    uri: https://developer.android.com/reference/android/content/ContentResolver#getPersistedUriPermissions()
---

# USB 卡片自動授權

## 動機

Android 11+ 對 removable storage 限定走 SAF，App 沒辦法繞掉 picker。但實際上 **picker 只需要按一次**：

- `StorageVolume.createOpenDocumentTreeIntent()` 可以把 picker **預先停在卡片根目錄**，使用者只要直接按一次「使用此資料夾」
- `ContentResolver.takePersistableUriPermission()` 可以把授權持久化，App 重啟、裝置重插都還在
- `getPersistedUriPermissions()` 可以驗證該授權還活著

所以可以做到：

| 情境 | 體驗 |
|---|---|
| 第一次插一張新卡 | 自動跳出 SAF picker，已停在卡片根目錄 → 按一下 OK |
| 重插同一張卡（同 vid:pid:serial） | **零點擊**，直接掃描、出 plan |
| App 重啟 | 同上 |

## 變更摘要

### 新增

- `data/storage/GrantedTreeStore.kt` — DataStore，key 是 `vid:pid:serial`，value 是 tree URI 字串
- `domain/usb/RemovableVolumeProvider.kt` — 包 `StorageManager`：
  - `removableVolumes()`：filter `isRemovable && !isPrimary`
  - `pickerIntentForRemovable()`：若剛好有一顆移除式 volume → 用 `StorageVolume.createOpenDocumentTreeIntent()` 產生 pre-seeded Intent；多於一顆或零顆回 null（讓上層走 fallback）

### 修改

- `ui/MainViewModel.kt`
  - 注入 `GrantedTreeStore` + `RemovableVolumeProvider`
  - `CardUiState` 多一個 `pickerIntent: Intent?` 欄位
  - `reconcileFromUsb` → 每張新卡呼叫 `resolveCardSource`：
    1. 查 `GrantedTreeStore` + `getPersistedUriPermissions` 雙重驗證 → 命中就 `attachTreeUri()` 自動 plan
    2. 沒命中時呼叫 `pickerIntentForRemovable()` 把 Intent 塞進 `pickerIntent`
  - `attachTreeUri()` 成功後寫入 `GrantedTreeStore`（key 為 `usbKey(dev)`）
  - 新增 `forgetGrant(deviceId)`（功能完整，UI 暫未串）

- `ui/MainScreen.kt`
  - `CardItem` 用兩個 launcher：`OpenDocumentTree`（fallback）+ `StartActivityForResult`（吃 pre-seeded Intent）
  - 文案：有 pre-seeded Intent 時提示「按一次授權，下次自動」，按鈕變「授權此卡（一次性）」

## 限制與權衡

- **USB → StorageVolume 的對應**目前是啟發式：「剛好一顆 removable+非 primary」就視為這顆卡。同時插兩顆讀卡機時退回 fallback picker（功能仍正確，只是 picker 不會自動停在某顆卡）。Android 沒有正規 API 把 `UsbDevice` 對到 `StorageVolume`。
- **NTFS / OS 不認的格式**仍然 OS 端就 mount 不起來，StorageVolume 不會出現 → 同樣退回 fallback picker；要解這條只能走 libaums 等第三方 USB-Mass-Storage 直連方案，本輪不做。
- **權限失效**（使用者去設定清掉 / 重灌 App）→ `getPersistedUriPermissions` 不會包含該 URI，會自動回到「按一次」流程。

## 驗證

```text
./gradlew testDebugUnitTest assembleDebug ✅
JVM 單元測試：25 個（無變動）
```

未做的測試：`GrantedTreeStore` / `RemovableVolumeProvider` 都需要 Android runtime（DataStore + StorageManager 都不純 JVM），留待之後 instrumentation test 一起。

## 後續可做

- 設定頁顯示已記住的卡片清單，可手動 `forgetGrant`
- 同時插多張卡時，按 USB metadata（vid:pid:serial）對到 StorageVolume.uuid 的啟發式 / 比對
- 評估 libaums 直連 USB Mass Storage（exFAT 風險、實機驗證）作為極致路徑
