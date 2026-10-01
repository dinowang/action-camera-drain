---
title: "Action Camera Catch：現代化 Surface 視覺"
description: "降低 Catch 工作台的框線密度，以留白、柔和 surface、分隔線與選取色帶建立更清楚的視覺層級。"
keywords:
  - Action Camera Catch
  - user interface
  - modern surfaces
  - visual hierarchy
  - accessibility
  - responsive design
author: dinowang
type: Notes / Record
createdAt: 2026-10-01 17:03:53
updatedAt: 2026-10-01 17:03:53
references:
  - type: ancestor
    path: ./20261001-01-CATCH-COMPACT-WORKSPACE.md
---

# Action Camera Catch：現代化 Surface 視覺

## 調整內容

- 移除 panel 與一般按鈕的實體框線，改用柔和陰影與 surface 色彩區分層級。
- Container entries 改為無框列表與細分隔線；目前選取項目使用淡藍 surface 與左側色帶。
- Blob table 保留必要的列分隔線，移除外層重複邊界，並加入低對比 hover surface。
- Running job 使用柔和 surface；terminal jobs 改為精簡分隔列，不再呈現完整卡片外框。
- 危險操作使用淡紅底與紅色文字，避免所有操作都使用高飽和實心按鈕。
- 補上清楚的 `focus-visible` 樣式、觸控最佳化與 reduced-motion fallback。

## 保留行為

- 桌面單 viewport 與 panel 內捲動。
- 選取 Blob Details 後的右欄 50/50 Grid。
- Container hover／focus actions 與觸控裝置固定操作列。
- FIFO jobs、內容驗證與雲端刪除安全流程。
