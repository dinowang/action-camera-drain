# filing-poc

[`/docs/flash-filing-logic.md`](../../docs/flash-filing-logic.md) 中歸檔配置的 dry-run 驗證工具。

走訪指定來源路徑，**自動辨識**每個 DCIM bucket 屬於哪台相機（純檔名／資料夾啟發法、不開檔），逐檔印出對應的 blob 名稱。沒有 Azure I/O、沒有 MP4 解析。

## 環境需求

- Python 3.10+。沒有第三方套件。

## 用法

```bash
# 自動辨識（建議用法）：
./filing.py /Volumes/Untitled

# 來源可以是卡根、DCIM/、或更深的相機 bucket 目錄
./filing.py /Volumes/Untitled
./filing.py /Volumes/Untitled/DCIM
./filing.py /Volumes/Untitled/DCIM/Camera01

# 覆寫自動偵測（CLI 旗標套用到所有 bucket）：
./filing.py /path/to/card --brand GoPro --model "HERO11 Black" --serial XYZ

# 關閉偵測（全部進 unknown/）：
./filing.py /path/to/card --no-detect
```

## 自動辨識規則（Phase 1）

只看資料夾名與檔名 pattern，**不開檔**。可以判出的層次：

| 偵測結果 | 觸發訊號 | deviceKey |
| --- | --- | --- |
| GoPro | 檔名 `(GH|GX|GP|GOPR|GS)\d+\.(MP4|JPG|LRV|THM)`，或資料夾以 `GOPRO` 結尾 | `gopro` |
| Insta360 360° 模組 | 出現 `.insv` / `.insp` 副檔名 | `insta360-lens-360` |
| Insta360 平面模組（4K Mod 等） | 檔名 `(VID|LRV)_\d{8}_\d{6}_01_\d+\.mp4` | `insta360-lens-flat` |
| DJI | 檔名 `DJI_\d+\.(MP4|JPG)` | `dji` |
| 都不像 | — | `unknown/` |

**model / serial 拿不到**（要 MP4 metadata，Phase 2 才做）。所以 deviceKey 是品牌級的桶。

## 來源路徑解析

| 你指到 | rel path 看起來像 | source subdir 取到 |
| ------ | ----------------- | ------------------ |
| 卡根（含 `DCIM/`） | `DCIM/Camera01/foo.insv` | `Camera01` |
| `DCIM/` 本身 | `Camera01/foo.insv` | `Camera01` |
| 單一相機 bucket | `foo.insv` | `_root`（沒上層脈絡） |
| 卡根下、`DCIM/` 之外 | `MISC/foo.txt` | 第一層子目錄名 |
| 直接在來源根的檔 | `STRAY.MP4` | `_root` |

## 路徑層的 dot-file 過濾

任何**路徑片段**以 `.` 起頭的檔案會被跳過，整顆子樹都不會出現在輸出裡。涵蓋：

- macOS：`.DS_Store`、AppleDouble shadow（`._VID_..insv` 等）、`.Spotlight-V100/`、`.fseventsd/`
- Insta360 自帶：`._Thumb`、`.cutting-plan/` 整個快取子樹

## `_root` 桶的繼承規則

`_root`（卡根、DCIM/ 外的零散檔）本身沒有偵測訊號。規則：

- 如果這張卡上**所有有辨識到的 bucket 都指向同一個 device**（單機卡的常態）→ `_root` 繼承該 device。輸出標記 `[inherited]`。
- 若卡上偵測到**多個不同 device**（混卡）→ `_root` 不繼承，落到 `unknown/`。

## 輸出

頭部會印一段 bucket → device 對照表，標明每個桶的判斷來源（`detected` / `override` / `inherited`）。然後逐檔列出：

```
<相對於來源的路徑>  ->  <container>/<deviceKey-或-unknown>/<sourceSubdir>/<filename>
```

末段在必要時印：

- `# CONFLICTS` — 兩個來源檔指向同一 blob 名稱
- `# OVERSIZED` — blob 名稱超過 Azure 1024-byte 上限

離開碼：

| Code | 意義 |
| ---: | ---- |
| 0 | 已產生計畫，且無衝突／超長 |
| 2 | 已產生計畫，但偵測到衝突或超長 |
| 1 | 用法錯誤 / IO 錯誤 |

## 強制執行的歸檔規則

- `deviceKey = {brand}-{model}[-lens-{lens}][-{deviceIdShort}]`
- `deviceIdShort = lower(base32(sha256(serial)))[:8]` — 純函式，無 salt、無 timestamp；同一個 serial 永遠產出同一個短碼。
- `brand` 或 `model` 任一缺失 → 退化為更短的 deviceKey；連 `brand` 也判不到 → `unknown/`。**我們不亂猜**。
- 卡上的相機 bucket 名稱（`100GOPRO`、`Camera01`、…）原樣保留在 blob 路徑裡。
- 不在任何相機 bucket 下的檔案歸到 `<bucket>/_root/<filename>`。
- 保留原始檔名的大小寫與副檔名。
- 衝突明確報告，**不會**默默處理。

## 這支 POC **刻意不做**的事

- 不解析 MP4 / GPMF — 拿不到 `model` 與 `serial`，所以同型號多台相機分不開（會合併成同一桶；§3.5 的衝突偵測會把問題浮上來）。
- 不做任何 Azure I/O。
- 不算檔案內容的 hash（沒有 md5 冪等比對）。

## 相關文件

- 歸檔配置規格：[`docs/flash-filing-logic.md`](../../docs/flash-filing-logic.md)
- 偵測可行性與指紋表：[`docs/eval-action-camera-flash-detection.md`](../../docs/eval-action-camera-flash-detection.md)
