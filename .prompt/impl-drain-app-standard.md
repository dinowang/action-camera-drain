# Action Camera Drain

Project's copilot-instructions.md /Users/dinowang/Project/action-camera-drain/.github/copilot-instructions.md

## Standard Flow

打開 App 取得預設的上傳設定，顯示在上方，並且可以切換到不同的設定（現階段只實作一組）

> 預設的上傳設定為 Azure Blob Storage，使用 SAS Token 進行授權和 Connection String 都儲存在 resources 中

掃描已經插在系統中的 USB 裝置，必須是外接記憶卡裝置，若有，執行卡片識別和上傳計畫，並且顯示在下方的列表中，裝置的執行計畫有一個按鈕，點擊後會開始執行上傳計畫，並且顯示上傳的進度和狀態

> 上傳計畫參考 /Users/dinowang/Project/action-camera-drain/src/filing-poc (python)

上傳至目標的檔案，檔案名稱包含日期時間資訊必須要完全與本地相同；
上傳至標的系統，可以從斷點接續開始，也可以讓使用者決定是否重新開始；
使用多執行緒上傳，可視網路速度自動增減執行緒，以確保能最快完成；
上傳過程若失敗，處裡中的檔案要視為未完成，必須重新上傳，也要先抹除遠端的檔案；
過程中記憶卡喪失連線或 EJECT 都要視為失敗，必須重新上傳處裡中的檔案，重新接回後可接續上傳；