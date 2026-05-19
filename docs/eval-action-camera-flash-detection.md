---
title: "可行性評估：從 USB 讀卡機識別運動相機的品牌與型號"
description: "評估當運動相機的記憶卡透過 USB 讀卡機連到電腦或手機時，是否能可靠地辨識出來源相機的品牌（GoPro / Insta360 / DJI / …）與型號，以及具體的做法、限制與風險。"
keywords:
  - 運動相機
  - GoPro
  - Insta360
  - DJI Osmo Action
  - DCF
  - GPMF
  - MP4 metadata
  - SD card
  - USB OTG
  - 指紋辨識
author: dinowang
type: Evaluation
favicon: ""
convertTo: html-embedded
createdAt: 2026-05-19 02:05:00
updatedAt: 2026-05-19 03:35:00
references:
  - type: prompt
    path: ../.prompt/eval-action-camera-flash-detection.md
  - type: reference
    uri: https://en.wikipedia.org/wiki/Design_rule_for_Camera_File_system
  - type: reference
    uri: https://github.com/gopro/gpmf-parser
  - type: reference
    uri: https://exiftool.org/TagNames/GoPro.html
  - type: reference
    uri: https://exiftool.org/TagNames/QuickTime.html
  - type: reference
    uri: https://github.com/Insta360Develop/CameraSDK-Cpp
---

# 可行性評估：從 USB 讀卡機識別運動相機的品牌與型號

## 結論摘要

只要記憶卡沒有被使用者用電腦動過手腳，**品牌**（GoPro / Insta360 / DJI / …）的判斷在實務上對 GoPro 與 360° 模式的 Insta360 是**可靠的**。**Insta360 ONE R / RS 掛平面鏡頭模組**（如 4K Wide-Angle Mod）時卡上只剩 `.mp4`，這時副檔名訊號失效，只能靠 MP4 內部 metadata。**型號**的判斷對 GoPro 與 DJI 來說是可靠的（型號字串直接寫在檔案的 metadata 裡），Insta360 則介於可靠與推論之間，取決於使用了哪個鏡頭模組。

辨識邏輯應該是**多訊號加上信心分數**的設計，不能只看單一指紋。卡本身是一個普通的 FAT/exFAT 磁區 — 使用者在電腦上動過的任何操作都可能把線索抹掉。

> 結論：作為「盡力而為」的功能 **可行**；作為「保證一定正確」的功能 **不可行**。結果應該以「應該是 <品牌>/<型號>」加上信心分數呈現，並永遠允許使用者手動覆寫。

## 背景

依照 Action Camera Drain 的使用流程（見 `.github/copilot-instructions.md`），記憶卡會從相機取出、放進 USB 讀卡機、接到 Android 手機（Android 14+，`minSdk = 34`）。若能辨識來源相機，App 可以：

- 預設合理的上傳目錄／資料夾名稱。
- 採用對應的擷取策略（例如配對 Insta360 的兩個 `.insv` 半邊、忽略 GoPro 的 `.LRV` 與 `.THM` 代理檔、跟著 DJI 的 `100MEDIA` 分頁流程）。
- 顯示符合該型號的 UI 提示與進度預估。

它**不是**安全性／身份驗證訊號 — 使用者隨時可以做出一張看起來像任何品牌的卡。

## 卡上會看到什麼（跨品牌共通基線）

所有主流運動相機都會把卡格式化成 **DCF**（Design rule for Camera File system，JEITA CP-3461）。DCF 也是所有 DSLR 與手機相機共用的標準，所以*外層*長相是一樣的：

- 檔案系統是 **FAT32**（≤ 32 GB）或 **exFAT**（> 32 GB）。
- 最上層目錄：`DCIM/`。
- `DCIM/` 底下是 `NNNAAAAA` 命名的子資料夾 — 三位數 `100..999` 加上五個英數字元，通常會帶廠牌字尾。
- 檔案命名 `AAAAnnnn.EXT` — 四個英數字元加上四位數序號。

來源：[Wikipedia: DCF](https://en.wikipedia.org/wiki/Design_rule_for_Camera_File_system)（引自 JEITA CP-3461）。

也就是說 **`DCIM/` 的存在只能告訴你「這是*一張*相機卡」，沒辦法分辨是哪一台**。品牌／型號的判斷得從廠商各自塞進去的內容下手。

## 各品牌的指紋

### GoPro（HERO 5 以後，現代韌體）

| 訊號 | 位置 | 可靠度 | 備註 |
| ---- | ---- | :----: | ---- |
| DCIM 子資料夾 `100GOPRO`、`101GOPRO`、… | 檔案系統 | 高 | 符合 DCF 規範；`GOPRO` 字尾是強訊號。 |
| 檔名前綴 `GH`、`GX`、`GP`、`GS`、`GOPR` | 檔案系統 | 高 | 兩字母前綴對應編碼／分段策略。HERO 5–13 的使用者文件廣泛記載；視為**業界廣為流傳**，**未經 GoPro 官方文件直接驗證**。 |
| 同 4 位數編號的 `.LRV`（低解析代理）與 `.THM`（縮圖）伴隨檔 | 檔案系統 | 高 | 在卡上幾乎是 GoPro 的獨家標誌。 |
| MP4 中的 **GPMF metadata track**（`gpmd` handler）| 檔案內部 | **非常高** | GPMF（GoPro Metadata Format）規格在 `gopro/gpmf-parser` 有完整文件。包含 FourCC keys `MINF`（Model）、`DVNM`（DeviceName）、`FMWR`（FirmwareVersion）、`CASN`（CameraSerialNumber）、`MUID`（MediaUniqueID）。來源：[gopro/gpmf-parser](https://github.com/gopro/gpmf-parser)、[exiftool GoPro tags](https://exiftool.org/TagNames/GoPro.html)。 |
| MP4 `udta`／`mdta` 內的 `firmware` keys | 檔案內部 | 高 | exiftool／標準 MP4 atom parser 都讀得到（`com.gopro.firmware`、`com.gopro.serial` 等 mdta keys，exiftool 的 QuickTime 模組會抽出）。 |

**結論：** GoPro 是最容易辨識的。不用打開任何檔案，靠 `DCIM/100GOPRO`／`.LRV` 就可以證明品牌；**型號字串**從 GPMF 取，是精確值。

### Insta360（ONE X / X2 / X3 / X4 / X5、ONE R/RS 等）

> ⚠️ **重要前提：副檔名訊號取決於鏡頭模組**。`.insv` / `.insp` 是雙魚眼縫合素材專用 — 由 ONE X 系列（X / X2 / X3 / X4 / X5）以及 ONE R / RS **掛上 360° 鏡頭模組**時產生。ONE R / RS 在掛 **4K Wide-Angle Mod**（或其他平面鏡頭模組，例如 1-Inch Wide-Angle、Leica 1-Inch）時，因為是純 2D 拍攝、沒有縫合需求，**只會寫出標準的 `.mp4`**，卡上不會有 `.insv` / `.insp`。
> 來源：使用者於 2026-05-19 的實測回報（ONE / RS + 4K 鏡頭模組）；本評估視為已驗證的觀察點，需要再從 Insta360 文件補一份書面引用。

| 訊號 | 位置 | 可靠度 | 備註 |
| ---- | ---- | :----: | ---- |
| 檔名副檔名 `.insv`（影片）與 `.insp`（照片）| 檔案系統 | **高 — 但只在 360° 模組** | 360° 模組會產生；**平面模組（如 ONE R/RS + 4K Mod）不會有**。Insta360 自家解析工具與 SDK 都吃這個格式（[Insta360Develop/CameraSDK-Cpp](https://github.com/Insta360Develop/CameraSDK-Cpp)）。副檔名語意視為「由工具生態確立」，內部格式視為廠商私有。 |
| `.insv` 是 MP4 容器（H.264/H.265 in MP4）外加 Insta360 的附加 trailer | 檔案內部 | 中 | 標準 MP4 atom 讀取器可以解析 `moov`；型號通常會寫進 `udta`／`mdta` 字串。標記為**部分驗證** — 具體 atom name 隨韌體略有變動。 |
| 配對檔：360° 機型出現 `VID_..._00_001.insv` 與 `..._10_001.insv` 兩個半邊 | 檔案系統 | 高 | `_00_` / `_10_` 對應兩顆鏡頭，**只**會在 360° 拍攝模式下出現（ONE X 系列固定如此；ONE R / RS 僅在掛 360° 模組時如此）。 |
| 純 `.mp4`（平面模組產出）內的 MP4 `udta`／`mdta` 與韌體字串 | 檔案內部 | 中–高 | 平面 2D 模組路徑下，這是品牌與型號**幾乎唯一**的訊號。需要從 `moov/udta`、`moov/meta`（mdta）抽 `make`／`model`／`firmware`，並能識別 Insta360 / Arashi Vision 的字串。**未獨立驗證** — 待用實機檔做 atom dump 補上。 |
| Insta360 風格的 DCIM 子資料夾命名（例如 `Camera01`）| 檔案系統 | 低–中 | 命名隨型號與韌體變動；只能當輔助訊號。 |

**結論：**
- **掛 360° 模組**（ONE X 全系列；ONE R/RS 掛 360° 模組）：副檔名是非常強的品牌訊號。型號從 `.insv` 內的 MP4 metadata atom 抽，或從配對檔 pattern 推「是否 360°」。
- **ONE R/RS 掛 4K 或其他平面模組**：副檔名訊號**完全失效**，卡上看起來就是「一張普通 DCF 卡裝著 `.mp4`」。**MP4 atom 也抽不到** — ONE RS 的 `moov/udta` 寫的是 Ambarella SoC 私有的 `AMBA` blob，內部是二進位、沒有公開 schema、僅含 codec 名稱字串（已實機驗證於韌體 v2.0.11_build3）。
- **替代路徑（已實機驗證）**：Insta360 會在卡上維護一份 `DCIM/fileinfo_list.list`（或卡根層），protobuf 格式但容易解析。每個 record 內含三個 ASCII 字串欄位：
  - 欄位 1（`0x0a`）：機身序號（例：`IRBEN2204WB7GK`）
  - 欄位 2（`0x12`）：型號（例：`Insta360 OneRS`）
  - 欄位 3（`0x1a`）：韌體版本（例：`v2.0.11_build3`）

  以這個檔為「Phase 2.5」的訊號源，可以在 ONE RS（即使掛 4K Mod）拿到精確型號與序號，完全繞過 AMBA blob 的逆向。實作見 [`src/filing-poc/insta360probe.py`](../src/filing-poc/insta360probe.py)。新款 X3／X4 系列上 fileinfo 是否仍是同格式**待驗證**。

### DJI Osmo Action（以及 Osmo Pocket 系列，但有保留）

| 訊號 | 位置 | 可靠度 | 備註 |
| ---- | ---- | :----: | ---- |
| DCIM 子資料夾名 `DCIM/100MEDIA`、`101MEDIA`、… | 檔案系統 | 中 | `100MEDIA` 是非常常見的 DCF 命名，**並非 DJI 獨家** — 一堆無品牌相機、行車記錄器也都這樣命名。只能當輔助訊號。 |
| 檔名前綴 `DJI_` | 檔案系統 | 高 | 例如 `DJI_0001.MP4`。Osmo Action 與 Osmo Pocket 系列上是常態。**未從單一 DJI 官方來源直接驗證**；基於跨韌體版本的使用者可見行為廣泛重現。 |
| 與 `.MP4` 並列的 `.LRF` 低解析代理檔 | 檔案系統 | 中–高 | Osmo Action 常見；是否出現取決於拍攝模式。 |
| MP4 `udta` 字串內含 `DJI` 與型號 | 檔案內部 | 高 | 標準 MP4 atom parser（與 exiftool 的 QuickTime 模組）都讀得到 maker 與 model 字串。 |
| 附帶 `.SRT`／`.LRF` telemetry／代理檔 | 檔案系統 | 中 | telemetry SRT 的格式特徵相對 DJI-specific，但主要出現在空拍機素材，而非 Osmo Action。 |

**結論：** `DJI_` 前綴搭配 MP4 metadata 是可靠的。**不要單靠 `100MEDIA` 判斷 DJI**。

### Sony Action Cam / 其他 / 未知

- Sony Action Cam 部份模式會寫 **AVCHD** 結構（`PRIVATE/AVCHD/...`） — 結構與 DCF 截然不同，本身就是強指紋。
- 雜牌「運動相機」（AKASO、SJCAM、Apeman、…）通常什麼都不寫 — 純 DCF 加上 `MOV_` / `IMG_` / `100MEDIA`，MP4 metadata 裡也不見得會塞品牌字串。這類應該直接歸到 **「未知」**，不要硬猜。

## 建議的偵測策略

1. **掛載並列舉磁區** — 透過 Android 的 USB host API（`UsbManager`）或 Storage Access Framework（`ACTION_OPEN_DOCUMENT_TREE` 讓使用者授權整個樹）。Android 原生不開放直接讀取 block device。
2. **第一輪 — 只看檔案系統，不開檔**：
   - 走訪 `DCIM/` 一層。
   - 對訊號打分：`*GOPRO` 資料夾、`GH/GX/GP/GOPR*.MP4`、`*.LRV`、`*.THM`、`*.insv`、`*.insp`、`DJI_*.MP4`、`*.LRF`、`PRIVATE/AVCHD/`。
3. **第二輪 — 讀 MP4 atom** — 對少量代表性檔案（例如最新的 1–3 個）：
   - 解析 `moov/udta` 與 `moov/meta`（mdta）中的 `make`、`model`、`firmware` 字串。
   - GoPro 還要進一步定位 `gpmd`-handler 軌道，讀 GPMF（FourCC `MINF`／`DVNM`／`FMWR`）。
4. **信心分數** — 把訊號加總成 `brand`、`model` 的預測各自帶上信心區間。≥ 2 個獨立印證訊號視為「高」。
5. **永遠允許 UI 手動覆寫**。

## 需要的軟體 / 驅動程式

| 層次 | Android（本專案目標）| 一般 PC |
| ---- | -------------------- | ------- |
| Block device 存取 | ❌ 沒 root 一般做不到。改用 SAF／USB host。 | OS 原生掛載。 |
| FAT32 / exFAT 掛載 | 由 Android storage stack 自動處理，使用者授權 USB 裝置或文件樹之後即可。Android 14+（`minSdk = 34`）保證支援 exFAT。 | 原生支援。 |
| MP4 atom 讀取 | Android 沒內建第一方函式庫可用。選項：自己寫一個小 parser（需要的 atom 不到 100 行），或引用一個 permissive license 的 lib（例如 `mp4parser`／`isobmff`）。 | exiftool 全包。 |
| GPMF 解析 | 移植 [`gopro/gpmf-parser`](https://github.com/gopro/gpmf-parser)（C，雙授權 Apache-2.0 / MIT — 都相容）。只需要品牌／型號相關的 FourCC（`MINF`、`DVNM`、`FMWR`、`CASN`、`MUID`），sensor stream 可略過。 | 同上，再加上 exiftool。 |
| Insta360 `.insv`／`.insp` 解析 | 對品牌／型號判斷而言，把 MP4 外殼當成一般 MP4 處理即可。**不要**依賴 Insta360 CameraSDK — 那是 USB 控制導向（跟連上的相機對話），跟讀「已經放在磁碟上的卡」無關。 | Insta360 Studio 可做完整 demux。 |

## 限制與風險

1. **使用者改過的卡。** 如果使用者在 PC 上動過檔案結構或重新命名，檔案系統線索就不可靠。MP4 metadata 在「複製」時會留下，但「轉檔／剪輯」之後就掉了。
2. **雜牌與白牌相機。** 通常只有通用 DCF、MP4 也沒寫品牌字串。要規劃「未知」這個結果。
3. **舊韌體與模組化機身。** GPMF 在 HERO 4 以前覆蓋不全；Insta360 ONE（一代）少了後期的部分 metadata 約定。Insta360 ONE R / RS 的指紋會**隨換上的鏡頭模組變動** — 360° 模組產生 `.insv`／`.insp`，平面模組（4K Mod、1-Inch Wide-Angle、Leica 1-Inch …）只產生 `.mp4`，這時必須回頭看 MP4 內部 metadata。不確定就退回看 atom 字串，再退而求其次讓使用者覆寫。
4. **同一張卡用在兩台相機。** `DCIM/` 底下可能多個子資料夾、各自帶不同品牌訊號。要做到逐資料夾或逐檔判斷，而不是整張卡只給一個答案。
5. **DCIM 資料夾撞名。** `100MEDIA` 太通用，不能單憑這個就判 DJI。
6. **個資。** `CASN`（Camera Serial Number）與 `MUID`（Media Unique ID）是裝置身份識別。沒拿到使用者明確同意之前，不要寫 log、也不要傳出裝置。
7. **Android 儲存權限。** Android 14+ 之後，廣義的 `READ_EXTERNAL_STORAGE` 已經拿不到想要的東西；要走 SAF 樹 URI 流程，或透過 `UsbManager` 取得特定裝置授權。權限 UX 要在開發初期就規劃。
8. **驅動程式。** 讀「卡」本身**不需要**廠商驅動 — 就是一張普通的 FAT/exFAT mass-storage 裝置。廠商 SDK（GoPro OpenGoPro、Insta360 CameraSDK）做的是透過 USB 或 Wi-Fi 跟*相機*對話，不是讀 SD 卡，這裡用不到。

## 給 Action Camera Drain 的建議落地路徑

- **Phase 1 — 廉價、可直接出貨。** 只看檔案系統做品牌判斷（資料夾名／檔名前綴）。對於設定預設上傳目錄與套用 GoPro／Insta360／DJI 擷取預設值已經夠用。UI 上要顯示信心。
- **Phase 2 — 漸進。** 對每個偵測到的資料夾，打開一支 MP4，讀 `moov/udta`／`moov/meta` 的 `make`／`model`。能拉到型號級的準確度。
- **Phase 3 — 必要時才做。** 移植 GPMF 中跟品牌／型號相關的子集到 GoPro，抓 `MINF`（精確型號）與 `FMWR`（韌體）。sensor stream 不做。
- **絕不**只靠單一訊號；**永遠**留手動覆寫。

## 測試 / 驗證計畫

- 蒐集一份已知樣本：GoPro HERO 9/10/11/12/13、Insta360 ONE X2/X3/X4（360° 模式）、**Insta360 ONE R / RS 配 360° 模組與 4K 平面模組各一組**、DJI Osmo Action 3/4/5、外加一台無品牌（AKASO 等級）。
- 每一台記錄：最上層 layout、DCIM 子資料夾、副檔名、MP4 atom dump（例如 `exiftool -G -a` 輸出）、有 GPMF 的話也 dump 一份。
- 把樣本固定在 `src/android/app/src/test/resources/`（或類似位置），對偵測器寫單元測試，逐一比對。
- 每筆驗證結果以一條獨立紀錄列管，逐步把上方標 **未驗證** 的條目換掉。

## Rollback 計畫

- 偵測功能放在單一 feature flag 後面；上傳主流程必須在 `brand = Unknown`、`model = Unknown` 的情況下也能完整運作。偵測一旦出問題就 fallback 到這條路徑，UI 顯示手動品牌選擇器。

## 後續 / TODO

- [ ] 對 community.gopro.com 上的權威頁面驗證 GoPro 檔名前綴（`GH`／`GX`／`GP`／`GS`）語意 — 本次評估時打不開。
- [ ] 對 `onlinehelp.insta360.com`／`www.insta360.com/support` 驗證 Insta360 `.insv`／`.insp` 副檔名語意 — 本次評估時無回應；目前的描述是根據官方 SDK 與廣為採用的第三方工具推得。
- [x] ~~對 Insta360 ONE R / RS + 4K Mod（以及其他平面模組）做實機 atom dump，確認 `moov/udta` / `moov/meta` 內可用於判斷品牌／型號的字串~~ — 已實機驗證（2026-05-19）：ONE RS 在 v2.0.11_build3 韌體下，`moov/udta` 內僅有 `AMBA` 私有 blob，無 Apple `©mak/©mod/©swr` 或 `meta/mdta`。改用 `DCIM/fileinfo_list.list` 取得型號／序號／韌體，行得通。
- [ ] 對 Insta360 X3 / X4 / X5 系列驗證 `fileinfo_list.list` 是否同 schema、欄位編號（serial=1, model=2, firmware=3）是否一致。
- [ ] 對最新的 DJI 支援頁驗證 Osmo Action 檔名 pattern。
- [ ] Spike：用 Kotlin 寫一個最小化的 MP4 `udta`／`mdta` 讀取器，量在 100 MB+ 檔案上的成本（只需要前幾 KB 與 `moov` atom）。
- [ ] 決策：要 vendor 一小段 `gpmf-parser`（C → JNI），還是用 Kotlin 重寫 FourCC scan。
