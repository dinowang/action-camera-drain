---
title: "Action Camera Catch：可重連的後端下載工作台"
description: "明確將下載生命週期留在 Go 後端，加入 job discovery、snapshot、recent retention、多 job 控制與三區響應式工作台。"
keywords:
  - Action Camera Catch
  - background download
  - Server-Sent Events
  - job snapshot
  - responsive layout
  - Go
  - NAS
author: dinowang
type: Notes / Record
createdAt: 2026-09-30 01:32:43
updatedAt: 2026-09-30 01:32:43
references:
  - type: prompt
    path: ../.prompt/action-camera-catch.md
  - type: ancestor
    path: ./20260520-01-ACTION-CAMERA-CATCH-BOOTSTRAP.md
  - type: ancestor
    path: ./20260908-02-CATCH-CLOUD-CLEANUP.md
  - type: derived
    path: ../src/website/README.md
---

# Action Camera Catch：可重連的後端下載工作台

## 背景

Catch 的 Azure 下載原本已由 Go 後端執行，瀏覽器只建立 job 並透過 SSE 讀取事件。
不過 UI 只記住單一 job ID；頁面重新整理後無法找到仍在執行的工作，也不能同時監看
多個 container downloads。

## 主要變更

- Job 新增 thread-safe snapshot，持有 containers、state、時間、檔案／bytes 進度、
  concurrency、throughput 與最後訊息。
- 新增 `GET /api/jobs` 與 `GET /api/jobs/{id}`，讓瀏覽器重開後重新發現 active 與
  recent jobs。
- Terminal jobs 最多保留 50 筆且不超過 24 小時；active jobs 不受 retention 影響。
- 每個 job event history 設定上限，避免長時間服務無限累積記憶體。
- 同一 container、重疊 multi-container request 與 wildcard jobs 互斥，不允許多個
  workers 同時寫入相同 `.part`。
- Terminal job 不接受取消，API 回 `409 Conflict`，避免回傳假成功。
- SSE replay 遇到 `job-done` 後結束；active job 可由重新開啟的瀏覽器接回。

## UI 工作台

- 桌面版使用三區 layout：
  - 左側 30%：Blob Entries／containers；
  - 右上：Blob Entries Details；
  - 右下：Download Log and Status。
- 每區獨立捲動，container 或 job 數量增加時不推擠整頁。
- Download Jobs 改為多 job cards，可各自顯示進度、事件記錄並取消。
- 窄螢幕依 Containers → Blob Details → Download Jobs 順序堆疊。

## 生命週期邊界

- 下載資料流只存在於 Catch 後端與 Azure／NAS 之間，不經過瀏覽器。
- 關閉、重新整理或稍後重新開啟瀏覽器不會停止後端 job。
- Catch process 或 container 重啟仍會終止 in-memory jobs；本輪不新增資料庫或跨程序
  resume，未完成檔案依既有規則於下次同步整檔重抓。

## 驗證

- Job snapshot、bounded history、24 小時／50 筆 retention。
- 同 container、wildcard 與不同 container reservation 行為。
- Job list／detail API、terminal cancel 與 SSE terminal replay。
- Go race tests、vet、Linux amd64／arm64 builds。
- 桌面 30/70 + 65/35 layout 與窄螢幕堆疊的瀏覽器畫面檢查。
