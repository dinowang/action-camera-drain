---
title: "記憶卡內容歸檔邏輯：以裝置為單位上傳到 Azure Blob Storage"
description: "Action Camera Drain 將記憶卡內容上傳到 Azure Blob 時，如何辨識來源裝置、決定目的路徑、處理衝突的判斷流程；無法辨識的一律歸到 unknown，不嘗試處理罕見的卡片交換情境。"
keywords:
  - Azure Blob Storage
  - 歸檔
  - 運動相機
  - GoPro
  - Insta360
  - DJI
  - MP4 metadata
  - GPMF
  - 裝置辨識
  - 上傳
author: dinowang
type: Technical Explanation
favicon: ""
convertTo: html-embedded
createdAt: 2026-05-19 02:28:00
updatedAt: 2026-05-19 09:55:00
references:
  - type: prompt
    path: ../.prompt/eval-action-camera-flash-detection.md
  - type: ancestor
    path: ./eval-action-camera-flash-detection.md
  - type: derived
    path: ../src/filing-poc/filing.py
  - type: derived
    path: ../src/filing-poc/mp4probe.py
  - type: derived
    path: ../src/filing-poc/insta360probe.py
  - type: reference
    uri: https://learn.microsoft.com/azure/storage/blobs/storage-blobs-introduction
  - type: reference
    uri: https://learn.microsoft.com/rest/api/storageservices/naming-and-referencing-containers--blobs--and-metadata
  - type: reference
    uri: https://github.com/gopro/gpmf-parser
  - type: reference
    uri: https://exiftool.org/TagNames/QuickTime.html
---

# 記憶卡內容歸檔邏輯：以裝置為單位上傳到 Azure Blob Storage

## 目的與設計取捨

本文承接 [`eval-action-camera-flash-detection.md`](./eval-action-camera-flash-detection.md) 的辨識結論，定義 Action Camera Drain 上傳階段的**歸檔邏輯**：給定一張卡，要把卡上每一個檔案放到 Azure Blob 上的哪一個 blob name。

**核心取捨**：

- **以「裝置」為主要分桶單位**。同一台相機產生的素材聚在一起，方便事後尋找與處理。
- **無法判斷裝置的檔案，一律歸到 `unknown/`**，不嘗試「猜」。
- **不處理罕見情境**：一張卡被多台相機交換使用是少數情況。如果出現我們的演算法分不開，**就讓它落到同一個裝置桶**，不增加額外的探測邏輯。寧可少分，不要錯分。
- **以一次上傳會話（session）為決策單位**。一張卡 = 一次 ingest plan = 一次「先算好所有目的路徑、再執行上傳」。

## 名詞

| 詞 | 定義 |
| -- | --- |
| **Device** | 一台實體相機。理想識別為 `(brand, model, serialNumber)`；現實中會有缺項。 |
| **DeviceKey** | Device 在 blob path 中的字串呈現，例如 `gopro-hero11-a1b2c3d4`。 |
| **DeviceId 短碼** | `serialNumber` 經單向 hash 後取前 8 字元的 base32 字串，避免在路徑明文洩露相機序號（PII）。 |
| **Ingest plan** | 上傳前生成的「來源檔 → 目的 blob name」對應表，含信心分數與衝突檢查。 |
| **Asset group** | 共用同一個 4 位數編號的主檔 + 代理檔（例：`GH010001.MP4` + `GH010001.LRV` + `GH010001.THM`）；必須一起歸到同一個 device 桶。 |

## 高階流程

整體流程是一個**先掃描、後上傳**的兩段式設計。掃描階段廉價、可重試、可給使用者預覽；上傳階段才動 Azure。

1. 取得磁區根目錄（SAF tree URI 或 USB host mount）。
2. **裝置偵測**（本文重點）— 走訪 `DCIM/` 與其他關注目錄，產出一個 `Devices[]` 列表與一張 `FileToDeviceMap`。
3. **規劃 ingest plan** — 把每個檔案算出最終 blob name；偵測衝突；給使用者預覽。
4. 使用者確認後執行上傳。
5. 上傳成功 → 驗證 → 詢問是否清空卡。

下面只展開步驟 2 與 3。

## 步驟 2：裝置偵測

採用 [`eval-action-camera-flash-detection.md`](./eval-action-camera-flash-detection.md) 的訊號模型，但實作上拆成**三個階段的 cascade**，由便宜到貴依序執行；每一階段可以拿到的訊息越來越精確，但任一階段失敗都會 fall back 而不是中斷。已在 POC（`src/filing-poc/`）對兩張真實 Insta360 ONE RS 卡（4K Mod 與 360° Mod，同一機身序號）以及合成的 GoPro mdta MP4 驗證。

| 階段 | 成本 | 拿到 | 失敗會怎樣 |
| :-: | --- | --- | --- |
| **Phase 1** | 純檔名／資料夾 pattern，不開檔 | `brand`（含 Insta360 lens 區分） | 該桶歸 `unknown/` |
| **Phase 2** | 每桶開 1–2 支 MP4／.insv 抽 `moov/udta`、`meta/mdta` | `brand` / `model` / `firmware` / `serial`（多為 GoPro、DJI、新版 Insta360） | 用 Phase 1 結果 |
| **Phase 2.5** | 卡上找 `DCIM/fileinfo_list.list` 解 protobuf 字串 | Insta360 `model` / `firmware` / `serial`（覆蓋 ONE R/RS + 4K Mod 這個 Ambarella `udta/AMBA` 抽不到的盲點） | 用 Phase 1/2 結果 |

> **抽樣，不全掃**。Phase 2 對每桶最多開 2 支檔案（讀檔尾 64 KB + `moov` 整顆，<1 MB），不是逐檔分析。

### 2.1 預處理：dotfile 過濾

走訪前先排除任何路徑片段以 `.` 開頭的檔案 — macOS 的 `.DS_Store` / `._*` AppleDouble、Spotlight 殘檔、以及 Insta360 自帶的 `._Thumb` 與 `.cutting-plan/` 快取整顆子樹都跳過。實作上把這條規則放在 file walker 裡（`is_hidden_path()`），偵測階段看不到這些檔案。

### 2.2 Phase 1：以資料夾／檔名 pattern 分桶

走訪 `DCIM/` 一層，每個子資料夾視為一個**候選 device bucket**。對每桶套用以下訊號表（**第一個命中的決定品牌**）：

| 訊號 | 偵測結果 |
| --- | --- |
| 檔名前綴 `GH`/`GX`/`GP`/`GOPR`/`GS` + 副檔名 `MP4`/`JPG`/`LRV`/`THM` | GoPro |
| 資料夾名以 `GOPRO` 結尾 | GoPro |
| 出現 `.insv` / `.insp` 副檔名 | Insta360（lens=360）|
| 檔名 `(VID|LRV)_<日期>_<時間>_01_<序號>.mp4` | Insta360（lens=flat）|
| 檔名 `DJI_<序號>.MP4` 或 `.JPG` | DJI |
| 都不符合 | 退到下一階段或歸 `unknown/` |

> **為什麼用「子資料夾」當主鍵**：DCF 規範下，相機通常把素材寫進固定字尾（`GOPRO`／`MEDIA`／`Camera01`）的子資料夾。這層分桶幾乎免費，且**與檔案內容解耦** — 沒有 IO 成本。

Phase 1 拿到的最多到 brand（與 Insta360 的 lens 模組）；**model 與 serial 永遠拿不到**。

### 2.3 Phase 2：MP4 / .insv 內部 metadata atom

對每個 Phase 1 已有 brand 的桶（或仍是 `unknown` 但有可解析檔的桶），開 1～2 支最新的 `.MP4`／`.mp4`／`.insv`，讀 `moov` atom 內以下兩處：

| 來源 | 對應欄位 | 適用 |
| --- | --- | --- |
| `moov/udta/©mak`、`©mod`、`©swr`（Apple 經典 4-cc） | make / model / firmware | 老韌體常見 |
| `moov/udta/meta`（handler `mdta`）下的 `keys` + `ilst` | `com.apple.quicktime.make` / `.model` / `.software` / `.cameraserialnumber` | 現代 GoPro / DJI / 新款 Insta360 / Apple 裝置 |

讀取範圍只需要 `moov` atom — 通常在檔尾（先讀檔尾 64 KB 找 box header 反向定位）或檔頭幾 MB；總成本 < 1 MB random read，**單檔 < 100 ms** 級別。實作見 [`mp4probe.py`](../src/filing-poc/mp4probe.py)。

**抽出後的合併規則**：

- Phase 1 已有 brand 且 Phase 2 brand 對得上 → 用 Phase 2 的 model / serial 補齊。
- Phase 1 有 brand 但 Phase 2 brand 不同 → 標 `brand mismatch`，**信任 Phase 1**（filename pattern 在實務上比 metadata 字串更穩定）。
- Phase 1 沒 brand 但 Phase 2 抽到 → 升級該桶為 Phase 2 結果。
- Phase 2 完全抽不到 → 桶維持 Phase 1 結果。

**已知限制**：Insta360 ONE RS（韌體 v2.0.11_build3 實機驗證）`moov/udta` 內**只有** Ambarella SoC 私有的 `udta/AMBA` 二進位 blob，沒有 Apple `©mak/©mod/©swr` 或 `meta/mdta`，這條路在 ONE RS 上**抽不到** model 或 serial。因此引入 Phase 2.5。

### 2.4 Phase 2.5：Insta360 `fileinfo_list.list` 替代路徑

Insta360 在卡上維護一份索引檔 `DCIM/fileinfo_list.list`（或卡根層），protobuf-shaped binary，每張卡的每個媒體檔對應一筆 record。Record 內含三個 ASCII 字串欄位（已實機驗證於 ONE RS v2.0.11_build3）：

| protobuf 欄位 | tag byte | 內容 | 範例 |
| ---: | :-: | --- | --- |
| 1 | `0x0a` | 機身序號 | `IRBEN2204WB7GK` |
| 2 | `0x12` | 型號（以 `Insta360 ` 起頭） | `Insta360 OneRS` |
| 3 | `0x1a` | 韌體版本 | `v2.0.11_build3` |

**不需要完整 protobuf 解析器**：以「`0x12 <len> Insta360 …`」為 anchor 向前找 `0x0a` 序號欄、向後找 `0x1a` 韌體欄。整檔有上百筆 record，全部相同（卡是一台機身寫的） → 多數決即可。實作見 [`insta360probe.py`](../src/filing-poc/insta360probe.py)，<150 行。

Phase 2.5 **只套用到 Phase 1 已標為 Insta360 的桶**。其他品牌的桶不會被誤覆蓋（混卡 edge case）。

> X3 / X4 / X5 系列是否同 schema、欄位編號是否一致，**待驗證**。Phase 2.5 失敗時自然 fall back，整體流程不會中斷。

### 2.5 訊號合併與裝置確定

每個桶最後產出 `Device { brand, model?, lens?, serial?, source }`。`source` 標籤反映訊號鏈：

| `source` 值 | 意義 |
| --- | --- |
| `detected` | 只跑了 Phase 1 |
| `detected+probed` | Phase 1 + Phase 2 都有貢獻 |
| `detected+fileinfo` | Phase 1 + Phase 2.5（典型 Insta360 路徑）|
| `probed` / `probed-fileinfo` | Phase 1 失敗，靠 Phase 2 / 2.5 救回 |
| `inherited` | 卡根的零散檔，繼承自唯一的兄弟桶的 Device |
| `override` | CLI / UI 強制覆寫 |
| `no-match` | 所有訊號都失敗 → `unknown/` |

**桶合併規則**（跨桶相同 deviceKey 自動 dedupe）：

- 同 `(brand, model, serial, lens)` 跨多個 DCIM 子資料夾 → 合併（同一台相機跨 `100GOPRO`／`101GOPRO`）。
- 同 `(brand, model)`、不同 `serial` → 視為**不同 device**。
- 同 `(brand, model)`、皆無 `serial` → **不強分**，合併為同一個 Device，再靠 §3.5 衝突偵測讓使用者看見。
- **卡根的 `(card root)` 桶**：本身沒有 Phase 1 訊號。若卡上所有其他桶都指向**同一個** deviceKey，則 `(card root)` 桶**繼承**該 Device（標 `inherited`）。多 device 的混卡上，`(card root)` 維持 unknown。

### 2.6 把每個檔案綁到 Device

對候選桶內的每個檔案：

- **主檔**：依桶分配到對應的 Device。
- **代理檔（`.LRV` / `.THM` / `.LRF`） / sidecar（`.SRT`）**：依「同基底檔名」隱式跟著主檔（共享 source subdir，所以路徑天然落在同一個 device 桶裡）。
- **位於 `DCIM/` 外的檔案**：跟著 `(card root)` 桶走（見 §2.5 的繼承規則）。

至此產出 `FileToDeviceMap: Map<sourcePath, Device | null>`，`null` 代表 `unknown/`。

## 步驟 3：規劃 ingest plan（Blob name 對應）

### 3.1 DeviceKey 計算

```
deviceKey = "{brand}-{model}[-{deviceIdShort}][-{lensTag}]"
```

組成規則：

| 段 | 規則 | 範例 |
| -- | ---- | ---- |
| `brand` | 小寫、`[a-z0-9-]`，未知時 → 不在這條路徑出現（會走 `unknown/`） | `gopro`、`insta360`、`dji` |
| `model` | 小寫；空白與 `_` 轉 `-`；移除非 `[a-z0-9-]` 字元；**若以 `{brand}-` 開頭則去除前綴**（避免 `Insta360 OneRS` 變成 `insta360-insta360-oners` 這種重複）| `hero11-black`、`oners`、`osmo-action-4` |
| `lensTag` | 只在 Insta360 ONE R / RS 偵測到模組差異時加 | `lens-360`、`lens-flat` |
| `deviceIdShort` | `lower(base32(sha256(serialNumber))[:8])`；**只在能取得 serialNumber 時加** | `6hsgaflx` |

實作上 deviceKey 的段落順序固定為 `brand`-`model`-`lensTag`-`deviceIdShort`（POC 行為驗證過）。

DeviceKey 範例（含真實實機驗證結果）：

- `gopro-hero11-black-a1b2c3d4`（假設 Phase 2 抽到 serial）
- `gopro-hero11-black`（沒抓到 serial，但 brand+model 已知）
- `insta360-oners-lens-flat-6hsgaflx` ← 真實機身 `IRBEN2204WB7GK` 經 hash
- `insta360-oners-lens-360-6hsgaflx` ← 同機身，不同模組，**共用短碼**
- `insta360-lens-flat`（Phase 2.5 失敗的 fallback：沒 model 也沒 serial）
- `dji-osmo-action-4`
- `unknown`（連 brand 都判不到，所有檔案歸到此桶）

### 3.2 Blob name 形狀

**Azure Blob 不是真正的階層檔案系統**，blob name 就是 container 內的一條字串；`/` 只是慣例分隔符，Azure Portal / Storage Explorer 會把它呈現成資料夾。命名規則需服膺 [Azure 命名規範](https://learn.microsoft.com/rest/api/storageservices/naming-and-referencing-containers--blobs--and-metadata)：blob name 最長 1024 字元、URL-safe、區分大小寫。

採用的結構：

```
<container>/
  <deviceKey>/
    <sourceSubdir>/
      <originalFileName>
```

- `container`：由使用者在上傳前選定／建立（不在本文範圍）。
- `<sourceSubdir>`：**原樣保留**卡上的 DCIM 子資料夾名（例 `100GOPRO`、`101MEDIA`、`Camera01`）。如此同一支相機跨資料夾的素材仍保留時間／批次語意。
- `<originalFileName>`：**完全不改名**。保留 `GH010001.MP4`、`DJI_0001.MP4`、`VID_..._00_001.insv`。代理檔（`.LRV`／`.THM`／`.LRF`）跟主檔同前綴 → 落在同目錄、不會被遺漏。

範例：

```
ingest-2026-05/                                                ← container
  gopro-hero11-black-a1b2c3d4/100GOPRO/GH010001.MP4
  gopro-hero11-black-a1b2c3d4/100GOPRO/GH010001.LRV
  gopro-hero11-black-a1b2c3d4/100GOPRO/GH010001.THM
  gopro-hero11-black-a1b2c3d4/101GOPRO/GH010052.MP4
  insta360-one-rs-lens-flat-7h3kp2qx/Camera01/VID_20240519_123456.mp4
  dji-osmo-action-4/100MEDIA/DJI_0001.MP4
  dji-osmo-action-4/100MEDIA/DJI_0001.LRF
  unknown/100MEDIA/MOV_0033.MP4                                ← 連 brand 都判不出來的雜牌
```

### 3.3 為什麼**不**在路徑加時戳

> 「為什麼不加 `<yyyymmdd>/` 一層做進一步分流？」

- 時戳資訊已經寫在 blob 的 metadata（建立日期）與檔案本身的 EXIF/MP4 metadata 裡。
- 同一台相機的同一卷素材跨午夜很常見，硬拆會把連續事件切成兩個資料夾。
- 真正要做時間導向的查詢，下游 indexer（或 Azure 的 Tags / Index）更合適，不要把它焊死在 blob name 上。

### 3.4 `unknown/` 的填法

下列檔案一律落到 `unknown/`：

- 候選桶完全沒線索（連 brand 都判不到）。
- 在 `DCIM/` 之外被掃到的檔案（例如直接放在卡根目錄的 `MISC.MP4`）。當卡上所有其他桶都歸到同一台 device 時，這些檔依 §2.5 的繼承規則跟著走（標 `inherited`）；當卡上是 unknown 或混 device 時，這些檔直接歸 `unknown/<檔名>`（不增加中間 source subdir 層）。
- MP4 parser 解析失敗的 corrupt 檔案。

`unknown/` 內部仍**保留 source subdir**（有的話），方便事後人工辨認。

### 3.5 衝突偵測（最後一道防線）

ingest plan 構建完畢後，**強制**對「目的 blob name」做唯一性檢查：

| 衝突情境 | 處理 |
| -------- | ---- |
| 兩個 source 檔算出**同一個** blob name | UI 上必須警告；上傳前不解決就不能執行 |
| 目的 container 上**已存在**同名 blob | 預設策略：**skip**（呈現「此檔已存在於目的端，將略過」）；使用者可改 overwrite 或 rename 為 `{stem}-{shortHash}{ext}` |
| 同名但 size / md5 一致 | 視為冪等成功，標記為 `already-uploaded` |
| 同名但 size / md5 不同 | 視為真衝突，套上面策略 |

衝突的常見來源：

1. 兩台**同型號、無 serial**、且 DCF 子資料夾名相同（例：兩台 GoPro 同型號都寫到 `100GOPRO/`，序號又撞）。這是我們**主動放棄**處理的情境 — `eval-action-camera-flash-detection.md` 已標示型號級可信、device 級在無 serial 時不可信。讓使用者看到衝突清單並決定。
2. 重複 ingest 同一張卡。本來就該是冪等的。
3. 卡上事前被別人手動複製過檔。

### 3.6 Plan 呈現格式（給人看的，不是給 logger）

ingest plan 的預設視角是**逐 filing profile 一行**，不逐檔。原因：多數情況下一張卡只有一個 profile（單機卡），逐檔列 200 多行對使用者沒幫助。

預設摘要視角的結構：

```
source     : <path>
container  : <azure-container>
total      : N file(s), human-size

Filing plan:
  <deviceKey>          N files,  size   [source-tag]
    └─ <sub-dir>/       n files,  size   (e.g. <sample-filename>)
    └─ <sub-dir>/       n files,  size

[Probe notes:]
  - [<bucket>] brand mismatch: ...

[Issues:]
  CONFLICT   <blob-name>
              <- <source-path>
              <- <source-path>
  OVERSIZED (N bytes)  <blob-name>

Needs attention: ...   或   Looks clean.  No conflicts, no unknowns, no oversized names.
```

- **subdir 細分**只在「一個 profile 內有多個 source subdir」或「profile 是 `unknown`」時展開。
- **逐檔對應**只在 `--verbose` 才印出。
- **`unknown/` 桶永遠展開**，方便使用者決定下一步（手動覆寫品牌、改檔名、跳過）。

## 流程總覽

| 步 | 動作 | 對應章節 |
| :-: | --- | --- |
| 1 | 掛載卡（SAF tree URI 或 USB host） | — |
| 2 | 以 DCIM 子資料夾建立候選桶 | §2.1 |
| 3 | 每桶抽 1–3 支檔讀 MP4 metadata（brand / model / serial / lens） | §2.2 |
| 4 | 合併同 `deviceKey` 的桶；無 serial 的同型號桶 → 合併 | §2.3 |
| 5 | 建立 `FileToDeviceMap`（代理檔跟主檔走；孤兒檔跟桶走） | §2.4 |
| 6 | 算出每檔的 blob name；未知 → `unknown/` | §3.1 ~ §3.4 |
| 7 | 衝突偵測（目的端唯一性）；有衝突 → UI 警告，不能直接送出 | §3.5 |
| 8 | 使用者確認後執行上傳 | — |

> 步驟 1–7 全在記憶體完成、不動 Azure；步驟 8 才實際呼叫 Blob API。

## 邊界情境與顯式不處理

下列情境**設計上不嘗試解決**，因為發生機率低、且解法會把架構顯著複雜化：

1. **同卡多台同型號相機，皆無 serial**：合併成同一個 device 桶。靠衝突偵測讓使用者看見。
2. **使用者在 PC 上重新命名／搬動過檔案**：以呈現的檔名為準。原始 DCF 結構若被破壞，就會走到 `unknown/<檔名>`（沒有中間 subdir）。
3. **卡上同時存在某品牌的 360° 與平面素材**（罕見的 Insta360 模組混用）：依 lensTag 自然分桶；不額外做交叉驗證。
4. **GoPro CASN 對應同一裝置但韌體版本不同**：deviceKey 不含 firmware，自然會合併。
5. **時間異常**（相機時鐘錯亂、跨時區）：不參與 deviceKey 計算，不影響分桶。
6. **Azure 目的端的容器 / 路徑命名衝突 by user**：上傳前 dry-run 一遍 blob exists 檢查；UI 提示。

## 風險與限制

- **DeviceId 雖經 hash，仍是強識別子**。同一台相機在同一個 deployment 下會穩定產生同一個短碼；若洩漏與相機關聯，仍可被反向關聯到設備擁有者。應在 app 設定中提供「不在路徑出現裝置短碼」的選項（會退回到「同型號合併」策略）。
- **MP4 metadata 字串不是嚴格規範**：`make`／`model` 在不同韌體間可能變動（如 `GoPro` vs `gopro` vs `GoPro Inc.`），需在程式中做正規化映射表，並隨韌體更新維護。
- **GPMF 解析成本**僅在 GoPro 路徑啟用；其他品牌靠 `udta`／`mdta` 字串足矣，不要在每張卡都載入 GPMF parser。
- **Azure Blob name 長度上限 1024**。原檔名通常短，但 `<deviceKey>/<sourceSubdir>/<originalFileName>` 拼起來仍應檢查上限；超過時直接 fail-fast 並要求縮短 container／調整 deviceKey 規則（不要默默截斷）。
- **大小寫**：Azure Blob name 區分大小寫；本流程**保留原始大小寫**（GoPro `.MP4`、Insta360 `.mp4`），不做正規化以避免「同檔不同 case」的誤判。
- **冪等性**：同一張卡多次 ingest 必須得到同樣的 blob name。deviceKey 的 hash 必須是純函式（input = serialNumber，無 salt、無 timestamp）。

## 與 POC（`src/filing-poc/`）的對應

本文描述的邏輯**已實作為 dry-run POC**，可對任意來源路徑跑出一份 ingest plan，但不會接 Azure、不會動卡。各章節對應到 POC 檔案如下：

| 本文 | POC 檔案 / 函式 |
| --- | --- |
| §2.1 dotfile 過濾 | `filing.py::is_hidden_path()` |
| §2.2 Phase 1 偵測 | `filing.py::detect_bucket()` |
| §2.3 Phase 2（MP4 atom） | `mp4probe.py::probe()` + `extract_camera_fields()` |
| §2.4 Phase 2.5（fileinfo_list.list） | `insta360probe.py::find_fileinfo()` + `probe()` |
| §2.5 桶合併與 `(card root)` 繼承 | `filing.py::main()` 內 Phase 2 + inherit 區段 |
| §3.1 deviceKey 計算 | `filing.py::Device.key()` |
| §3.2 Blob name | `filing.py::plan_blob_name()` |
| §3.5 衝突偵測 | `filing.py::main()` 收集 `occurrences` / `oversized` |
| §3.6 plan 呈現格式 | `filing.py::main()` 末段 summary view + `--verbose` |

POC 附有單元測試（`src/filing-poc/tests/`），分別針對 MP4 atom reader（合成 fixture）與 fileinfo_list.list reader（合成 fixture）驗證各種 happy / robustness path。實機端則於兩張真實 Insta360 ONE RS 卡跑通：

```
insta360-oners-lens-flat-6hsgaflx   201 files   55.3 GB   [detected+fileinfo]
insta360-oners-lens-360-6hsgaflx    595 files   127.6 GB  [detected+fileinfo]
```

兩個 deviceKey 共用 `6hsgaflx` 短碼，反映出**同一機身配不同鏡頭模組**。

## 與 `eval-action-camera-flash-detection.md` 的對應

| 該文章節 | 在本文如何使用 |
| -------- | -------------- |
| 各品牌指紋表 | §2.2 Phase 1、§2.3 Phase 2 的 brand / model / serial 抽取依據 |
| GPMF FourCC `MINF` / `CASN` / `MUID` | §2.3 GoPro 路徑的型號與 serial 來源（Phase 3，POC 尚未實作）|
| Insta360 模組差異（360° vs 4K Mod） | §2.2、§3.1 `lensTag` 規則 |
| `udta/AMBA` ONE RS 抽不到 model | §2.3 「已知限制」、§2.4 替代路徑的動機 |
| DCF `100MEDIA` 不獨家 DJI 的警告 | §2.5 同 brand+model 無 serial 不再強分的依據 |
| 限制：使用者改過卡 / 雜牌 / 舊韌體 | §3.4 `unknown/` 一律收容；§4 顯式不處理 |

## 後續 / TODO

- [x] ~~對 Insta360 ONE R / RS + 4K Mod 做一輪 atom dump~~ — 已實機驗證；`udta/AMBA` 內無可用字串，改走 Phase 2.5 `fileinfo_list.list`。
- [ ] 把品牌字串正規化表寫成資料檔（如 `assets/brand-normalization.json`），與韌體更新解耦。目前是寫死在 `mp4probe.py::_BRAND_MAP`。
- [ ] 對 GoPro 與 DJI 兩家做一輪實機 atom dump，把 `make`／`model` 字串實際樣本記下來作為測試資料（POC 目前只在合成 fixture 跑過）。
- [ ] **Phase 3（GoPro GPMF）**：對 GoPro 路徑加 `MINF` / `CASN` 抽取，拿到精確 model 與 serial。等手邊有 GoPro 卡再做。
- [ ] **Phase 2.5 跨型號驗證**：Insta360 X3 / X4 / X5 系列的 `fileinfo_list.list` schema 是否相同，欄位編號是否一致。
- [ ] 設計衝突清單的 UI（哪些資料需要呈現給使用者；最少要看到：source path、預定 blob name、衝突類型、處理選項）。
- [ ] 決策：DeviceId 短碼的 hash 是否要 namespace 一層（例如 `sha256(brand + ":" + model + ":" + serial)`）以避免跨品牌撞碼。傾向是要，但成本極低。
- [ ] 把 POC 的 plan 結構序列化成 `plan.json`，讓未來實際上傳階段（Android 端）可以讀取相同 plan 重跑、續傳、冪等。
