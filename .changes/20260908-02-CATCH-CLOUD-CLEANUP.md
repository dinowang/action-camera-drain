---
title: "Action Camera Catch：驗證後刪除雲端 container"
description: "Catch 在重新逐檔驗證 NAS 副本後，可由使用者明確刪除整個 Azure Blob container，並阻止與下載 job 競爭。"
keywords:
  - Action Camera Catch
  - Azure Blob Storage
  - container deletion
  - NAS verification
  - concurrency
  - Go
author: dinowang
type: Notes / Record
createdAt: 2026-09-08 11:08:01
updatedAt: 2026-09-08 11:08:01
references:
  - type: prompt
    path: ../.prompt/action-camera-catch.md
  - type: ancestor
    path: ./20260520-01-ACTION-CAMERA-CATCH-BOOTSTRAP.md
  - type: derived
    path: ../src/website/README.md
---

# Action Camera Catch：驗證後刪除雲端 container

## 背景

Catch 原先只負責把 Azure Blob 素材下載至 NAS，雲端內容永久保留。本次新增由使用者
明確觸發的 container 清理功能；由於刪除不可逆，前端顯示與上一個下載 job 的成功
狀態都不能直接作為刪除依據。

## 主要變更

- Azure storage port 與 production client 新增 container deletion。
- 新增 cleanup service；每次刪除前重新列出全部 blobs，逐檔以本地 size 與 metadata
  `mtime` 驗證 NAS 副本。
- 第一次驗證完成後再次列出遠端 snapshot；blob 名稱、size、mtime、ETag 或
  Content-MD5 有任何變動都中止。
- verified blobs 以 ETag `If-Match` 條件逐一刪除；完成後再次確認 container 已空，
  才刪除 container 本身，避免覆寫或新到達的 blob 被無條件清除。
- 刪除驗證不接受 sidecar fallback；實際檔案 mtime 必須吻合，避免同大小的本地檔案
  被外部改寫後仍誤判為安全。
- blob path 必須解析在對應 container 目錄內；包含 traversal segment 或不安全
  separator 的名稱會被拒絕下載與刪除驗證。
- `job.Manager` 新增 container operation reservation：
  - wildcard download 會阻止所有 container deletion；
  - 指定 container 的 download 與 deletion 不能重疊；
  - 不同 container 的 download 仍可並行。
- 新增 `DELETE /api/containers/{name}`；驗證不完整、遠端變動或操作衝突回
  `409 Conflict`。
- Web UI 在 container 已完整落地時提供「刪除雲端」，並在確認對話框中標示將刪除
  整個 container。
- 刪除只作用於 Azure；NAS 目錄、影片與 sidecar 不會移除。

## 安全限制

Catch 會阻止自身的下載與刪除操作互相競爭，並以雙重 snapshot 偵測驗證期間的遠端
變動。Azure Blob Storage 沒有能將 container 內容在驗證與刪除之間完全凍結的
transaction；若其他 writer 仍有權限上傳，執行清理時仍需避免同時寫入該 container。

SAS 模式需在既有 read/list 權限之外加入 delete permission，才能刪除 container。

## 驗證範圍

- 完整匹配後成功刪除。
- 本地缺檔、size／mtime 不符或 metadata 缺失時拒絕。
- 第二次遠端 snapshot 變動時拒絕。
- Azure delete 錯誤不回報成功。
- wildcard／同 container download 與 deletion 互斥。
- HTTP 成功與 `409 Conflict` response。
