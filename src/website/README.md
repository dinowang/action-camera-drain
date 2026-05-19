# Action Camera Catch

把 [Action Camera Drain](../android/) 上傳到 Azure Blob 的素材**反向**拉回 NAS 的網站服務。Container ↔ 目錄 1:1 映射，已下載過的自動跳過。

```
Azure Blob Storage  →  Catch (web service)  →  NAS volume
   (按 deviceKey 分類)        瀏覽 / 觸發 / 進度        /data/<container>/<blob name>
```

## 對應關係（Drain ↔ Catch）

| 概念 | Drain（上傳） | Catch（下載） |
| --- | --- | --- |
| 寫入 metadata | `mtime` / `mtime_iso` / `size` / `source_name` | 讀 `mtime` → `os.Chtimes` |
| 跳過判定 | HEAD blob，size + mtime 一致 → skip | stat local，size + mtime 一致 → skip |
| 原子寫入 | PutBlock × N → PutBlockList | 寫 `.part` → fsync → rename → chtimes |
| 失敗處理 | 刪 checkpoint + 刪 remote | 刪 `.part`，下次整檔重抓（不做 resume） |
| 併發 | AdaptiveConcurrency（吞吐回授） | 同樣的吞吐回授演算法 |
| 來源時間 | 從本地檔案 `lastModified` | 從 metadata `mtime`，**不**用 blob `Last-Modified` |

## 快速啟動

### 1. 準備憑證

二選一（**SAS 必須是 account-level**，並具備 List Containers 權限；service SAS 不夠）：

```bash
# Mode A: 連線字串
export AZURE_STORAGE_CONNECTION_STRING='DefaultEndpointsProtocol=https;AccountName=...;AccountKey=...;EndpointSuffix=core.windows.net'

# Mode B: 帳戶 + SAS
export AZURE_STORAGE_ACCOUNT_NAME='mystorage'
export AZURE_STORAGE_SAS_TOKEN='sv=2024-08-04&ss=b&srt=sco&sp=rl&...'
```

### 2. 本地跑

```bash
cd src/website
DOWNLOAD_ROOT=$PWD/downloads HTTP_PORT=8080 \
  go run ./cmd/catch
```

瀏覽器開 <http://localhost:8080>。

### 3. Docker（NAS 部署）

```bash
cp .env.example .env  # 填入憑證
docker compose up -d
```

或直接拉預編譯 image：

```bash
docker run -d \
  --name catch \
  -p 8080:8080 \
  --env-file .env \
  -v /volume1/video/action-cameras:/data \
  ghcr.io/dinowang/action-camera-catch:latest
```

### Container UID（重要）

Catch 寫檔後會用 `os.Chtimes()` 把來源 mtime 蓋回去。Linux 規定 chtimes 的 caller UID 要等於檔案 owner UID 或是 root。

> **預設**：image 內不指定 USER → 容器以 root 跑 → chtimes 永遠成立。
> 容器是隔離的、只碰 `/data`，對 NAS 來說 root mode 沒安全顧慮。

如果你堅持非 root，請在 `docker-compose.yml` 加 `user:` 對到 host 上掛載點實際的 owner UID/GID：

```bash
# 在 NAS host 上查 UID/GID
stat -c '%u:%g' /volume1/video/action-cameras
```

```yaml
services:
  catch:
    user: "1026:100"   # 換成上一行查出來的數字
```

若 chtimes 仍然失敗（例如 SMB 掛 share 又沒給 UNIX extension），Catch 會把預期 mtime 寫到 sidecar dotfile `.<filename>.actr-mtime` 並發 `file-warning` 事件，下次 sync 還是會正確跳過已下載的檔案。

## 環境變數

| Name | 預設 | 說明 |
| --- | --- | --- |
| `AZURE_STORAGE_CONNECTION_STRING` | — | 二選一 |
| `AZURE_STORAGE_ACCOUNT_NAME` + `AZURE_STORAGE_SAS_TOKEN` | — | 二選一 |
| `DOWNLOAD_ROOT` | `/data` | NAS 掛載點；container → 子目錄 |
| `HTTP_PORT` | `8080` | |
| `MIN_CONCURRENCY` | `2` | 自適應併發下界 |
| `MAX_CONCURRENCY` | `8` | 自適應併發上界 |
| `LOG_LEVEL` | `info` | |

## API

| Method | Path | 用途 |
| --- | --- | --- |
| `GET` | `/` | 嵌入式 SPA |
| `GET` | `/healthz` | 健康檢查 |
| `GET` | `/api/containers` | 列容器 + 每個容器的 remote / pending / skipped 計數 |
| `GET` | `/api/containers/{c}/blobs` | 列 blob：name / size / mtime / status |
| `POST` | `/api/jobs` | body: `{ "containers": ["foo"] }` 或 `{ "containers": ["*"] }`；回 `{ "id": "..." }` |
| `GET` | `/api/jobs/{id}/events` | SSE：`job-start` / `file-start` / `file-skip` / `file-done` / `file-failed` / `concurrency` / `job-done` |
| `DELETE` | `/api/jobs/{id}` | 取消 |

外部 cron 觸發範例：

```bash
curl -X POST -H 'Content-Type: application/json' \
  -d '{"containers":["*"]}' \
  http://nas.lan:8080/api/jobs
```

## 限制 / 已知事項

- **不刪雲端**：下載完成不會刪除 Blob（雲端是備援來源）。
- **不做 resume**：依規格，下載失敗就抹除 `.part` 整檔重抓。
- **無內建排程**：靠 NAS 的 cron / Task Scheduler 從外部呼叫 API。
- **無 Auth**：預設信任 NAS 內網；如需公開請套 reverse proxy + Basic Auth。
- **SAS 限制**：必須 account-level + List Containers 權限。

## 開發

```bash
cd src/website
go vet ./...
go test ./...
go run ./cmd/catch
```

目錄佈局：

```
src/website/
├── cmd/catch/main.go              # 進入點
├── internal/
│   ├── config/                    # env 載入 + 驗證
│   ├── azblob/                    # Azure SDK 包裝（List / HEAD / Download）
│   ├── localfs/                   # path mapping、原子寫、skip 判定
│   ├── plan/                      # remote vs local 差集
│   ├── worker/                    # 自適應併發 pool
│   ├── job/                       # job 生命週期 + SSE 廣播
│   ├── httpd/                     # mux + handlers + SSE
│   └── web/                       # embed.FS：HTML/CSS/JS
├── Dockerfile
├── docker-compose.yml
├── .env.example
└── go.mod
```
