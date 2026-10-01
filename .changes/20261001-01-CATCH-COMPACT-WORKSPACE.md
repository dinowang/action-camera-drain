---
title: "Action Camera Catch：緊湊單視窗工作台與循序同步 Queue"
description: "將 Catch 桌面介面限制在單一 viewport，加入動態 Blob Details、緊湊操作、FIFO job queue 與 terminal record 清除。"
keywords:
  - Action Camera Catch
  - compact workspace
  - FIFO queue
  - background download
  - job cleanup
  - responsive layout
  - Go
author: dinowang
type: Notes / Record
createdAt: 2026-10-01 13:59:05
updatedAt: 2026-10-01 13:59:05
references:
  - type: ancestor
    path: ./20260930-01-CATCH-RECONNECTABLE-JOBS.md
  - type: derived
    path: ../src/website/README.md
---

# Action Camera Catch：緊湊單視窗工作台與循序同步 Queue

## 背景

前一版已提供可重連的三區工作台與多 job 監看。本次延伸其 viewport-aware layout，
針對大量 containers 與 recent jobs 同時出現時的資訊密度進一步調整，避免空白 Details
與長期顯示的操作列占用主要畫面。

## 工作台

- 桌面版使用完整可用 viewport，document 本身不產生垂直捲動。
- Containers、Blob Details 與 Download Jobs 各自在 panel 內容區捲動。
- 未選擇 container 時隱藏 Blob Details，Download Jobs 使用完整右欄。
- 選擇 container 後，Blob Details 與 Download Jobs 各占右欄一半。
- Container 操作在桌面 hover／focus 時顯示；觸控裝置維持直接可操作。
- Active、queued 與 terminal job cards 使用更緊湊的間距與狀態呈現。

## 循序同步

- 新建立的同步工作進入全域 FIFO queue。
- Catch 同一時間只執行一個 job；前一筆完整結束後才啟動下一筆。
- 單一 job 內仍保留 adaptive file concurrency，不將檔案下載降為單執行緒。
- Queued jobs 可取消，取消後不會觸發 Azure listing 或下載。
- Queued 與 running jobs 都參與 container reservation，避免同一範圍重複排隊。
- 只有 running job 使用 SSE；queued jobs 共用低頻清單輪詢，避免大量排隊工作耗盡
  瀏覽器對同一 origin 的長連線額度。

## 清除工作紀錄

- Terminal jobs 可逐筆清除，也可一次清除全部。
- 清除只移除 Catch process 記憶體中的 job snapshot 與 event history。
- 清除不會刪除 NAS 檔案、Azure Blob 或 container。
- Running／queued jobs 不可清除 record，必須先使用取消操作。
- 未手動清除的紀錄仍依 24 小時／最多 50 筆規則自動淘汰。

## API

- `DELETE /api/jobs/{id}/record`：清除單一 terminal record。
- `DELETE /api/jobs?state=terminal`：清除所有 terminal records並回傳清除筆數。
- 既有 `DELETE /api/jobs/{id}` 保持取消 queued／running job 的用途。

## 驗證

- FIFO 執行順序、queued cancellation 與 reservation。
- 單筆／批次 terminal record cleanup，以及 active job 保留。
- Job cleanup HTTP API 的成功、not found 與 conflict responses。
- 桌面單 viewport、動態 Details 50/50 Grid、panel-local scrolling。
- 窄螢幕堆疊與觸控操作 fallback。
