開發一個網站 (Action Camera Catch) ，作與 Action Camera Drain 相反的事情

讓使用者可以從遠端下載檔案到本地的路徑中，container 映射至目錄名稱

技術細節
- 專案路徑 src/website
- 這個網站會被容器化 (Docker Compose)，儲存影片的路徑高機率會映射到 NAS 的儲存區
- 使用 GitHub Actions 進行 CI/CD
  - 容器映象檔儲存於 ghcr.io
  - 同時支持 amd64 和 arm64 架構
- 下載的檔案會從 Azure Blob Storage 下載，使用 SAS Token 進行授權和 Connection String 都儲存在環境變數中
- 下載的檔案名稱包含日期時間資訊必須要完全與遠端相同；檔案時間要從 metadata 中讀取和置換，不可以直接使用 blob 的時間資訊
- 下載一樣是啟發式多執行緒，可視網路速度自動增減執行緒，以確保能最快完成
- 下載過程若失敗，處裡中的檔案要視為未完成，必須重新下載，也要先抹除本地的檔案
- 要避免重複下載，就像前台避免重複上傳一樣
