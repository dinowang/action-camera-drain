---
title: "Drain App：UIDT 背景上傳與持久化進度"
description: "把上傳工作從 MainViewModel 移至 Android 14+ User-Initiated Data Transfer Job，加入通知、網路策略、跨 Activity/process 的進度恢復，以及更完整的 restart、驗證與拔卡處理。"
keywords:
  - Action Camera Drain
  - Android
  - JobScheduler
  - UIDT
  - background upload
  - Azure Blob Storage
  - checkpoint
  - Jetpack Compose
author: dinowang
type: Notes / Record
favicon: ""
createdAt: 2026-09-08 02:18:47
updatedAt: 2026-09-08 02:18:47
references:
  - type: prompt
    path: ../.prompt/impl-drain-app-standard.md
  - type: ancestor
    path: ./20260519-01-DRAIN-APP-STANDARD-FLOW.md
---

# Drain App：UIDT 背景上傳與持久化進度

## 背景

Standard Flow 首版由 `MainViewModel` 建立並持有 `UploadEngine`。離開 App、Activity
重建或 process 被系統回收時，上傳工作的擁有者與即時進度都會消失，無法滿足長時間
記憶卡上傳在螢幕關閉後仍持續執行的要求。

本次改用 Android 14+ 的 User-Initiated Data Transfer Job（UIDT）。這與專案
`minSdk = 34` 一致，並符合「使用者明確按下 Start 後開始長時間資料傳輸」的情境。

## 主要變更

- 新增 `UploadJobService` 與 `UploadJobScheduler`，由 Android `JobScheduler`
  代管上傳生命週期。
- 新增必要的 UIDT permission、JobService manifest declaration 與進度通知。
- 新增 `TransferStore`，持久化來源 URI、目的 container、start mode、網路策略、
  工作狀態與進度摘要。
- UI 改為觀察持久化 transfer state；Activity 重建後可重新顯示背景工作進度。
- Remote 區新增「Wi-Fi only」與「Any network」選擇，並套用至 JobInfo network
  constraint。
- Any network 模式切到計量網路時，所有 workers 共用 10 Mbps 上傳上限，且工作啟動
  於計量網路時最多使用 2 個 workers，避免壟斷手機頻寬；非計量網路維持自適應全速。
- Android 13+ 在排程前要求通知權限；拒絕時不啟動背景上傳。
- 通知提供 Pause / Cancel action；App 內相同按鈕走同一 scheduler/controller。
- 將 SAF tree 可用性檢查抽成共用元件，Job 執行期間即使 App 不在前景也會偵測拔卡。

## UploadEngine 修正

- 改為 caller-owned suspend 執行器，不再建立自己的長生命週期 coroutine scope。
- worker pool 容量可達 adaptive concurrency 的完整上限。
- Restart 會刪除 plan 中所有遠端 blob，而非只處理仍有 checkpoint 的項目。
- 單檔重試前會清除 checkpoint 與遠端 committed blob。
- 每次執行使用獨立 ownership token；只有 blob metadata 證明屬於該次執行時才允許
  刪除，避免後續執行誤刪前一次成功上傳或其他 client 寫入的內容。
- 修正失敗重試造成 `doneBytes` 重複累計的問題。
- Put Block List 完成後以 HEAD 比對 content length 與 `mtime` metadata；通過後才算完成。
- Pause 保留 checkpoint；卡片斷線標示失敗，重新接回後可選 Resume。
- Cancel 清除未完成 checkpoint／本次執行擁有的 partial committed blob；已完成且驗證過
  的檔案保留。

## 範圍決策

- 本階段不實作清空記憶卡。
- 上傳設定仍沿用單一 Azure Blob resources profile。
- 不引入 WorkManager；UIDT 所需 API 由目前最低支援的 Android 14 直接提供。
- 保留使用者既有的 AGP 與 Gradle wrapper 升級，不在本變更中回退。

## 驗證

- `:app:testDebugUnitTest`
- `:app:assembleDebug`
- `:app:lintDebug`
