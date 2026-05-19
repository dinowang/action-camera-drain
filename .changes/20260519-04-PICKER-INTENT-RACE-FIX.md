---
title: SAF picker 預載卡片根目錄 — race fix
description: 修正 20260519-03 的卡片 SAF picker 預載 race condition — vold 還沒掛起 USB 卷宗時 USB_DEVICE_ATTACHED 已先送達，導致預載 Intent 在 ViewModel 中算出 null 後再也不會更新；按鈕按下時 picker 就退回手機內部儲存。改成在使用者點按那一刻才向 StorageManager 查 removable volume。
keywords:
  - android
  - saf
  - storage-access-framework
  - usb-mass-storage
  - StorageVolume
  - race-condition
  - bugfix
  - ux
author: dinowang
type: Notes / Record
favicon: "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCI+PHBhdGggZmlsbD0iI0VBNDMzNSIgZD0iTTEyIDJDNi40OCAyIDIgNi40OCAyIDEyczQuNDggMTAgMTAgMTAgMTAtNC40OCAxMC0xMFMxNy41MiAyIDEyIDJ6bTEgMTVoLTJ2LTJoMnYyem0wLTRoLTJWN2gydjZ6Ii8+PC9zdmc+"
createdAt: 2026-05-19 11:47:00
updatedAt: 2026-05-19 11:47:00
references:
  - type: ancestor
    path: .changes/20260519-03-AUTO-AUTHORIZED-CARD.md
---

# SAF picker 預載卡片根目錄 — race fix

## 症狀

實機（S25 Ultra + Realtek 0bda:0316 讀卡機，exFAT 卡）測 20260519-03 的「自動授權」流程時：

- 按「授權此卡（一次性）」打開 SAF picker，**仍然停在手機內部儲存**而不是 USB 儲存裝置。
- Samsung My Files 同時間能看到「USB 儲存裝置 1」，`adb shell sm list-volumes` 也顯示 `public:8,65 mounted 0000-0000` — Android 端確認已掛載，但 picker 沒被預載。

## 根因

20260519-03 在收到 `ACTION_USB_DEVICE_ATTACHED` 的瞬間就在 `MainViewModel.resolveCardSource` 裡呼叫 `StorageManager.storageVolumes` 取得 removable volume，把 `StorageVolume.createOpenDocumentTreeIntent()` 預先算好、塞進 `CardUiState.pickerIntent`。

但 USB attach 廣播和 vold 真正掛起卷宗是 **兩條獨立的 pipeline**：

1. `UsbManager` 在 USB 列舉完成時送出 `ACTION_USB_DEVICE_ATTACHED`。
2. vold / `StorageManager` 才開始實際 mount。Mount 完成才會發 `Intent.ACTION_MEDIA_MOUNTED` 並更新 `storageVolumes`。

(1) 先到 (2) 後到的情況下，`resolveCardSource` 那一刻 `storageVolumes` 還不包含這顆卡，`pickerIntentForRemovable()` 回傳 null。`CardUiState.pickerIntent` 從此就是 null，再也沒有第二次機會。

## 修法

把「找 removable volume」**從 attach 時點延後到使用者按按鈕的那一刻**，那時候 vold 必然早已完成 mount：

- 移除 `CardUiState.pickerIntent` 欄位。
- 新增 `MainViewModel.buildPickerIntent(): Intent?` — 每次呼叫都當場去問 `StorageManager`。
- `MainScreen.CardItem` 在 click handler 裡呼叫 `buildPickerIntent()`，有結果就用 `StartActivityForResult` 啟動預載 picker，沒有就 fallback 到 `OpenDocumentTree`。
- 移除舊的「按鈕文案依 `pickerIntent` 切換」邏輯，統一為「授權此卡的根目錄」。

`GrantedTreeStore` 自動恢復授權路徑不受影響（那條完全跟 `StorageVolume` 無關，只比對 `vid:pid:serial → treeUri`）。

## 變更檔案

- `app/src/main/java/.../ui/MainViewModel.kt`
  - 移除 `CardUiState.pickerIntent`
  - `resolveCardSource` 不再預先計算 picker intent
  - 新增 `buildPickerIntent()`
- `app/src/main/java/.../ui/MainScreen.kt`
  - `CardsSection` / `CardItem` 接受 `buildPickerIntent: () -> Intent?`
  - 點按時動態決定 launcher

## 驗證

- `./gradlew :app:testDebugUnitTest assembleDebug` ✅
- 實機重測：插卡 → 按「授權此卡的根目錄」→ SAF picker 直接停在「USB 隨身碟」根目錄，看見 DCIM
- 授權後 89 files / 100.1 MB 完整上傳，14.8 MB/s，workers=8，state COMPLETED

## 已知非問題

- 同一張 vid:pid 但無 serial 的讀卡機，多張不同物理卡會共用同一個 `usbKey`。`GrantedTreeStore` 仍能命中，但理論上是不同卡共用授權 — 維持 20260519-03 的取捨。
- `buildPickerIntent` 在 ≥1 顆 removable volume 時仍只取第一顆。多 USB 同時插入未處理（這個 App 的使用情境不會出現）。
