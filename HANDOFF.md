# HANDOFF — dailyapp

> 交接文件。新的 context 請先讀本檔，再讀 `spec.md`（完整規格與所有已確認的決策）。
> 最後更新：2026-11-19

## 專案一句話

Android App（Kotlin + Jetpack Compose），透過 `NotificationListenerService` 讀取 LINE、Discord、Telegram 工作群組的通知，整理出待辦並定期提醒。自用，不上架，**沒有後端**。使用者的手機是紅米（HyperOS）。

## 里程碑進度

| 階段 | 狀態 | 備註 |
|---|---|---|
| M0 真機驗證 | ✅ 完成 | 探針 App 在 `probe/`；驗證結果記在 spec 2.1.1 |
| M1 監聽、白名單、@ 待辦 | ✅ 完成，已 commit `4626ba8` | LINE 依使用者要求**未實測** |
| M2 AI 整理 | 🟡 **進行中**，拆成 M2a／M2b／M2c | M2a：身分設定檔、個資處理（不需 API key）。M2b：API key 輸入、每小時整理、合併。M2c：費用上限。API key 由使用者在 App 內輸入，不要讓他貼到對話裡 |
| M3 提醒系統 | ✅ 完成，使用者實測通過 | 時間選擇改用自製滾輪 `ui/WheelPicker.kt`（使用者不要內建轉盤）；App 圖示來自根目錄的 `極簡鈴鐺待辦圖示.png`（未進 repo） |
| M4 加密備份、發布流程 | 未開始 | |

## 立即待辦（接手後先做）

1. 進行 M2a：身分設定檔（Role、GroupRole）、送 AI 前的個資遮蔽與成員代稱（純 Kotlin，寫單元測試）。規格見 spec 3.2、4.3。
2. M2b 動工前先讀 `claude-api` skill 確認模型 ID 與 API 用法。

## 程式結構（`app/`，package `com.dailyapp`）

- `core/`：純 Kotlin，有單元測試
  - `Model.kt`：Platform、ParsedMessage、訊息雜湊
  - `MentionDetector.kt`：先做 NFKC 正規化，並移除格式字元
  - `ReminderPlanner.kt`：提醒時間的計算
- `data/`：
  - Room + SQLCipher。密碼用 Keystore 的 AES-GCM 金鑰包裝
  - Entities、DAOs、`Repository.ingest()`（去重、白名單、點名後建立草稿待辦）
  - `HealthStore`（SharedPreferences）
- `notify/`：
  - `ChatListenerService`：提供 `isConnected` 與 `requestRebind`
  - `NotificationExtractor`：解析通知
  - `Alerts`：兩個通知管道，urgent 與 general
  - `Reminders.kt`：AlarmManager 排程、`ReminderReceiver`、`BootReceiver`、`ReminderSettings`
- `ui/`：
  - `Theme.kt`：黑白極簡風格與 Noto Serif TC 字型，沿用使用者 create-pdf skill 的預設風格
  - 各畫面：Todo、Group、Health（介面上叫「狀態」）、TodoEditDialog
  - `WheelPicker.kt`：滾輪選擇器與日期時間對話框，分鐘以 5 分為單位
- 測試：`app/src/test/...`，目前 25 個，全部通過。

其他目錄：
- `probe/`：M0 探針 App（`com.dailyapp.probe`），手機上仍安裝著，通知存取權也還開著。
- `tools/sanitize_probe.mjs`：探針記錄的去識別化腳本。**分析真實通知前一定要先跑它**，只讀處理後的輸出。

## 建置與安裝（Windows，Git Bash）

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # JDK 25
./gradlew :app:testDebugUnitTest :app:assembleDebug
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk       # 用 run_in_background
```

- 建置工具版本：Gradle 9.9.1、AGP 9.5.1（內建 Kotlin）、KSP 2.3.14、Compose BOM 2026.09.00、Room 2.8.5、SQLCipher 4.20.0、compileSdk 37。
- **舊版 Gradle 8.x 不支援 JDK 25**，這是之前遇過的問題。

## 踩過的坑（很重要）

- **小米 USB 安裝**：每次 `adb install` 都要使用者在手機上按「繼續安裝」，否則會失敗並出現 `INSTALL_FAILED_USER_RESTRICTED`。指令要放到背景執行，並提醒使用者看手機。
- **Git Bash 會改寫路徑**：對手機路徑（例如 `/sdcard/...`）下 adb 指令前，要先 `export MSYS_NO_PATHCONV=1`。曾因此把 APK 推到錯誤位置。
- **`pm grant` 被小米擋下**：POST_NOTIFICATIONS 權限只能由使用者在手機上允許。`cmd notification allow_listener` 則可以用。
- **HyperOS 自啟動**：沒開自啟動時，系統會拒絕綁定監聽服務，logcat 會出現 `AutoStartManagerService: Reject service`。「狀態」頁會偵測「已授權但未連線」並呼叫 requestRebind。
- **通訊 App 本身收不到通知**很常見，容易被誤認是本 App 擋的。見 spec 2.1.1 的排查經驗。
- **Discord 的 @** 用的是顯示名稱（可能是上標或花體字），而且被 U+2068/U+2069 包住；`selfDisplayName` 則是帳號名稱。已用 NFKC 正規化處理。
- **截圖檢查**：`adb exec-out screencap -p > scratchpad/x.png`，再用 `am start -n com.dailyapp/.ui.MainActivity --ei tab N` 切換分頁（0 待辦、1 群組、2 狀態）。

## 隱私規則（使用者的工作訊息）

- 不要直接讀取通知或資料庫中的訊息內容。分析一律先去識別化，只看結構或統計。
- API key、簽章金鑰、`*.jsonl` 記錄不得進 repo（已寫在 `.gitignore`）。
- 本機 commit 沒問題，但 **repo 尚未推送到 GitHub**。推送前要提醒使用者確認 repo 設為 Private。

## 使用者偏好

- 使用繁體中文溝通，喜歡以問答方式確認決策（grill-me）。
- 介面採黑白極簡風格，同 create-pdf skill 的個人預設。
- 需要 commit 時，使用者會明確說出來。
