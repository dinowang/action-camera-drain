---
title: "Action Camera Catch：缺少 mtime 的內容驗證憑證"
description: "以 Content-MD5 或條件式下載加 SHA-256 驗證缺少 mtime metadata 的 blobs，保留相同本地內容並維持安全雲端刪除。"
keywords:
  - Action Camera Catch
  - Azure Blob Storage
  - content verification
  - Content-MD5
  - SHA-256
  - ETag
  - verification receipt
  - cloud cleanup
author: dinowang
type: Notes / Record
createdAt: 2026-10-01 16:09:03
updatedAt: 2026-10-01 16:09:03
references:
  - type: ancestor
    path: ./20260908-02-CATCH-CLOUD-CLEANUP.md
  - type: ancestor
    path: ./20261001-01-CATCH-COMPACT-WORKSPACE.md
  - type: derived
    path: ../src/website/README.md
---

# Action Camera Catch：缺少 mtime 的內容驗證憑證

## 背景

既有 Catch 以 Azure blob 的 `mtime` metadata 配合 NAS 檔案 size／mtime 判定是否已
下載。自行下載或由其他工具建立的 blobs 可能沒有 `mtime`；即使 NAS 已有相同內容，
原本仍會在每次同步重新下載，且無法通過「刪除雲端」的安全驗證。

本次延伸既有 skip 與雲端 cleanup 規則，不從檔名推造時間，也不降低為 size-only
驗證。

## 自動內容驗證

- Blob 有 Content-MD5 時，Catch 計算本地 MD5；相同就保留本地檔案且不下載。
- Blob 沒有 Content-MD5 時，Catch 以 listing ETag 作 `If-Match` 條件下載到
  `.verify.part`，再比較遠端暫存檔與本地檔案的 SHA-256。
- 內容相同時移除暫存檔；內容不同時才以已 fsync 的暫存檔原子取代本地檔案。
- 缺少本地檔案或 size 不同時直接下載，但仍為無 mtime blob 建立 receipt。
- 取消、傳輸失敗、size 不符、Content-MD5 不符或 ETag conflict 都保留原本本地檔案。

第一次驗證沒有 Content-MD5 的 blob 仍需讀取完整遠端內容。完成後可使用 receipt
避免後續同步重複下載。

## Verification receipt

- 檔名：`.<filename>.actr-verified.json`。
- Receipt 綁定 remote ETag、remote size、digest algorithm／value，以及建立時的 local
  size／mtime。
- Receipt 以 temp + fsync + atomic rename 寫入。
- 遠端 ETag／size 或本地 size／mtime 改變時，一般同步不再信任 receipt，重新進入
  VERIFY 狀態。
- 正常具有 `mtime` metadata 的 blobs 繼續使用既有 mtime 與 `.actr-mtime` 規則。

## 雲端刪除安全

- 一般同步可使用 receipt 的 remote identity 與 local stat 快速 skip。
- 「刪除雲端」不只信任快速判定；它會重新計算本地 digest 並與 receipt 比較。
- Cleanup 仍要求 fresh listing 的 ETag／size 與 receipt 一致。
- Remote snapshot re-list、每個 blob 的 ETag conditional delete，以及刪除 container
  前的 empty confirmation 均維持不變。

因此，同大小且偽裝成原 mtime 的本地內容修改，也會在雲端刪除前被 digest 驗證擋下。

## UI 與工作進度

- Blob Details 新增 `VERIFY` 狀態。
- Container 摘要分別顯示待同步與待驗證數量。
- Job event log 顯示保留本地、下載缺檔或由 Azure 取代的驗證結果。
- Job logical progress 以檔案大小累進；adaptive throughput 只計算實際下載 bytes，
  純本地 Content-MD5 驗證不會產生虛假的網路速度。

## 驗證

- Content-MD5 相同時不呼叫下載。
- 無 Content-MD5 時的相同內容保留與不同內容取代。
- Receipt 的 ETag／local stat invalidation。
- 同大小本地竄改在雲端刪除前被重新雜湊拒絕。
- 條件式下載送出 `If-Match`。
- 失敗時清理 verification temp 且保留原檔。
