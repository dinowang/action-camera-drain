---
title: "Action Camera Catch — 反向歸檔網站（首版）"
description: "新增 src/website/ 模組：Go + Azure SDK + embed.FS 嵌入式 SPA，把上傳到 Azure Blob 的素材拉回 NAS。Docker Compose 部署、GHCR multi-arch image、GitHub Actions CI/CD。"
keywords:
  - Action Camera Catch
  - Go
  - Azure Blob
  - Docker
  - ghcr.io
  - GitHub Actions
  - NAS
  - SSE
author: dinowang
type: Plan
createdAt: 2026-05-20 01:50:00
updatedAt: 2026-05-20 01:50:00
references:
  - type: prompt
    path: ../.prompt/action-camera-catch.md
  - type: derived
    path: ../src/website/README.md
  - type: derived
    path: ../.github/workflows/catch-docker.yml
---

# Action Camera Catch — 反向歸檔網站（首版）

## 動機

Drain 把記憶卡的素材上傳到 Azure Blob 後，雲端只是「中繼」。長期保存仍要落到家用 NAS。Catch 就是這個反向動作：在 NAS 上跑一個容器化的網站服務，把 Blob 拉回本地路徑，container ↔ 目錄 1:1。

## 設計重點

- **與 Drain 對稱**：metadata `mtime` 回寫 `os.Chtimes`；skip 規則 `size + mtime` 完全一致才跳過；併發演算法同樣是吞吐回授。
- **節省記憶體**：Go + `FROM scratch` final image，閒置 ~10–30 MB、image ~15 MB。
- **無 build step 的前端**：`embed.FS` 內嵌 HTML/CSS/JS，最終只有一支 binary。
- **失敗不留垃圾**：原子寫 `.part → fsync → rename → chtimes`；任何錯誤刪 `.part` 整檔重抓（依規格不做 resume）。
- **不刪雲端**：下載完保留 Blob，雲端是備援。
- **無內建排程**：靠 NAS 的 cron / Task Scheduler 從外部呼叫 API。

## 落地內容

- `src/website/` Go module：
  - `cmd/catch/main.go` 進入點 + signal handler
  - `internal/config/`、`internal/azblob/`、`internal/localfs/`、`internal/plan/`、`internal/worker/`、`internal/job/`、`internal/httpd/`、`internal/web/`
  - 單元測試：config / localfs / plan 共三組（含原子寫失敗清掉 `.part`）
- `src/website/Dockerfile` — multi-stage、`FROM scratch`、non-root
- `src/website/docker-compose.yml` — 範例：Synology `/volume1/video/action-cameras:/data`
- `src/website/.env.example`
- `src/website/README.md`（中文）— 對應表、env、API、限制
- `.github/workflows/catch-docker.yml` — `go vet` + `go test` → buildx → 推 `ghcr.io/dinowang/action-camera-catch:{branch,sha,latest,tag}`，linux/amd64 + linux/arm64

## API 速覽

| Method | Path | 用途 |
| --- | --- | --- |
| `GET` | `/api/containers` | 容器清單 + 計數 |
| `GET` | `/api/containers/{c}/blobs` | 容器內 blob + skip/pending 標籤 |
| `POST` | `/api/jobs` | 開 job |
| `GET` | `/api/jobs/{id}/events` | SSE 進度 |
| `DELETE` | `/api/jobs/{id}` | 取消 |

## 待跟進

- ContentMD5 驗證（目前僅 size + mtime）
- 整合測試：跑 Azurite 端對端
- 對 Synology Task Scheduler 寫一支 cron sample 腳本
- 大檔下載中 server 端 retry / range resume 評估
