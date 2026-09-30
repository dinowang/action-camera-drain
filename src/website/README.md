# Action Camera Catch

把 [Action Camera Drain](../android/) 上傳到 Azure Blob 的素材**反向**拉回 NAS 的網站服務。Container ↔ 目錄 1:1 映射，已下載過的自動跳過。

```
Azure Blob Storage  →  Catch (web service)  →  NAS volume
   (按 deviceKey 分類)        後端下載 / 瀏覽器控制       /data/<container>/<blob name>
```

下載資料流直接由 Catch 後端送至 NAS，不經過瀏覽器。瀏覽器只建立 job、讀取進度與送出
取消命令，因此關閉或重新整理頁面不會停止下載；重新開啟頁面後會自動找回同一個 Catch
程序內仍在執行的 jobs。

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

二選一（**SAS 必須是 account-level**；service SAS 不夠）：

```bash
# Mode A: 連線字串
export AZURE_STORAGE_CONNECTION_STRING='DefaultEndpointsProtocol=https;AccountName=...;AccountKey=...;EndpointSuffix=core.windows.net'

# Mode B: 帳戶 + SAS
export AZURE_STORAGE_ACCOUNT_NAME='mystorage'
export AZURE_STORAGE_SAS_TOKEN='sv=2024-08-04&ss=b&srt=sco&sp=rld&...'
```

一般下載只需要 read/list；若要使用「刪除雲端」功能，SAS 還必須包含 delete
permission（`sp` 包含 `d`）以及 container resource type。

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
| `DELETE` | `/api/containers/{c}` | 重新驗證 NAS 副本後，刪除整個 Azure container |
| `GET` | `/api/jobs` | 列出 active jobs 與 24 小時內最多 50 筆 recent jobs |
| `POST` | `/api/jobs` | body: `{ "containers": ["foo"] }` 或 `{ "containers": ["*"] }`；回 `{ "id": "..." }` |
| `GET` | `/api/jobs/{id}` | 取得可重建 UI 的 job snapshot |
| `GET` | `/api/jobs/{id}/events` | SSE：`job-start` / `file-start` / `file-skip` / `file-done` / `file-failed` / `concurrency` / `job-done` |
| `DELETE` | `/api/jobs/{id}` | 取消執行中的 job；terminal job 回 `409 Conflict` |

外部 cron 觸發範例：

```bash
curl -X POST -H 'Content-Type: application/json' \
  -d '{"containers":["*"]}' \
  http://nas.lan:8080/api/jobs
```

## 背景下載與瀏覽器重連

- Job 由 Catch 後端 goroutine 擁有，不依賴建立 job 的 HTTP request 或 SSE 連線存活。
- 頁面載入會呼叫 `GET /api/jobs`，重新建立所有 active job cards，並重新連接各自的
  SSE；近期完成、失敗與取消紀錄則由 snapshot 直接顯示。
- 可同時監看與分別取消多個不同 container 的 jobs。
- 同一 container、與 multi-container request 重疊的 container，或任何 wildcard job
  之間會互斥；重複啟動回 `409 Conflict`，避免多個 workers 同時寫同一個 `.part`。
- Terminal jobs 在程序記憶體內最多保留 50 筆且不超過 24 小時；active jobs 不會因
  retention 被移除。

Catch process 或 container 重啟時，記憶體內 jobs 仍會停止且不會自動恢復。本專案依
既有規格移除未完成 `.part`，下次同步重新下載整個檔案；本輪不引入資料庫或跨程序
resume。

## Web 工作台

桌面版分為三個可獨立捲動區域：

- 左側：containers 與 remote／pending／downloaded 摘要。
- 右上：所選 container 的 blob details。
- 右下：active 與 recent download jobs、進度及事件記錄。

窄螢幕依序堆疊 Containers、Blob Details、Download Jobs，不要求水平捲動。

## 刪除雲端 container

Container 列表只有在所有遠端檔案目前都能於 NAS 以 size + metadata `mtime` 驗證時，
才會啟用「刪除雲端」。按下後仍會由伺服器重新執行完整驗證，不能以網頁上次載入的
狀態或前一個下載 job 的結果取代。

刪除驗證刻意比一般同步 skip 更嚴格：檔案系統實際 mtime 必須吻合；只有
`.actr-mtime` sidecar 而檔案 mtime 不符時，仍會拒絕刪除，避免 NAS 檔案被外部程式
改寫後誤刪唯一的雲端副本。

安全流程：

1. 暫停該 container 與 Catch 內其他重疊操作。
2. 重新列出全部 blobs，逐檔驗證 NAS 副本。
3. 再次列出遠端 snapshot；名稱、size、mtime、ETag 或 Content-MD5 有變更就中止。
4. 以每個 blob 的 ETag 作為 `If-Match` 條件逐一刪除；任一 blob 被覆寫就立即停止。
5. 再次確認 container 已空，才刪除 container 本身。

本地 container 目錄、影片與 `.actr-mtime` sidecar 不會被刪除。若 Drain 或其他程式
仍可同時寫入相同 container，執行刪除時仍應避免啟動新的上傳；Azure 不提供能將
container 內容在驗證與刪除之間完全凍結的 transaction。Catch 已用條件式 blob
刪除與刪除前空 container 檢查縮小競爭窗口；操作期間仍不應讓其他 writer 上傳。

## 限制 / 已知事項

- **刪除需明確操作**：下載完成不會自動刪除；必須按下「刪除雲端」並確認。
- **不做 resume**：依規格，下載失敗就抹除 `.part` 整檔重抓。
- **不做 process restart recovery**：Catch process/container 重啟後不恢復記憶體內 jobs。
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
