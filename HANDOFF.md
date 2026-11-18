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
| M2 AI 整理 | ⏸ **使用者選擇暫時跳過 AI** | M2a ✅ commit `9acf7ea`。M2b 程式寫完、已裝到手機、確認不會閃退，已 commit，但**未接真實 API 測過**（使用者沒有設定 API key）。M2c 費用上限未開始，等使用者開始用 AI 再做 |
| M3 提醒系統 | ✅ 完成，使用者實測通過 | 時間選擇改用自製滾輪 `ui/WheelPicker.kt`（使用者不要內建轉盤）；App 圖示來自根目錄的 `極簡鈴鐺待辦圖示.png`（未進 repo） |
| M4 加密備份、發布流程 | 未開始 | |

## 立即待辦（接手後先做）

1. M2b 已 commit（程式完成，但尚未接真實 API 實測）。
2. 下一步可能是 **M4 加密備份與發布流程**，請使用者確認後再開始。
3. 使用者之後若要啟用 AI：
   - 使用者會在 console.anthropic.com 儲值（已討論過，$5 大約可以用 3 到 8 個月），API key 自己在手機輸入，**不要讓使用者貼到對話裡**。
   - 進到「狀態」→「AI 整理」→「設定 API key」→「立即整理」。
   - 實測項目：每小時整理、草稿改寫、疑似完成、分享預填、AI 建議關鍵字。
   - 整理品質只能請使用者描述錯誤類型，我們不能讀訊息內容。
   - 實測通過後再做 M2c（費用上限，預設每月 $5，用到 80% 提醒）。

## AI 供應商的決策紀錄（2026-11-19）

- 使用者問過能否用自己的 Claude 或 ChatGPT 訂閱：**都不行**，第三方 App 只能用 API key，另外計費。
- 評估過 Gemini：
  - 免費方案依條款，送出的內容會被用來改進 Google 產品，**可能有人工審查**，條款也明寫不要送敏感資訊。這不適合工作對話。
  - 付費的 `gemini-3.5-flash-lite`（$0.30／$2.50）比 Claude Haiku 5.5（$0.10／$0.50）貴。
  - Google 的 Java SDK 沒有說明支援 Android。
- 結論是維持 Claude（`claude-haiku-5-5`），使用者決定先跳過 AI。若使用者之後又想換 Gemini，先提醒使用者上面的資料使用問題。

## M2b 實作摘要

- 依賴：`com.anthropic:anthropic-java:3.3.0`、`androidx.work:work-runtime-ktx:2.11.0`。
- `core/AiPlan.kt`（純 Kotlin，有測試）：
  - 系統提示詞與 JSON schema（結構化輸出）
  - `buildInput` 組輸入，`parse` 驗證 AI 回傳：不在清單的 todo_id 丟掉、訊息 id 過濾、due 解析失敗視為沒有截止時間
  - 另有 `AiShare`（分享預填）、`AiKeywords`（關鍵字建議）
- `ai/AiClient.kt`：
  - SDK 呼叫，effort 設為 LOW，使用 `output_config.format` json_schema
  - 錯誤分成 Retry（網路、429、5xx：訊息留在佇列）與 Fail（401、403、400、拒答、截斷）兩類
- `ai/AiOrganizer.kt`：
  - 流程：讀佇列（每次最多 200 則、一輪最多 5 次）→ `Redactor` 遮蔽 + `Pseudonymizer` 換代稱 → 呼叫 AI → 換回真名 → 寫入待辦 → 標記 processedAt
  - 拒答或截斷時標記已處理，避免重複花錢；API key 錯誤時訊息保留
- `ai/AiWorker.kt`：
  - WorkManager 每小時執行，只在 08:00–22:59 送出，需要網路；在 `DailyApp.onCreate` 排程
  - `AiAssist` 負責分享預填與關鍵字建議
- `data/AiStore.kt`：
  - API key 用 `KeystoreBox`（從 DbKey 抽出，alias `dailyapp_api_key`）加密保存
  - 也記錄上次執行結果與每月 token 用量
- DB v4：`todos.aiGenerated`。
- 介面：
  - 「狀態」頁新增 AI 區塊（`ui/AiSection.kt`）
  - 待辦加上 AI、急、可能已完成三種標籤，並提供「確認完成／還沒」按鈕
  - 分享時顯示「AI 整理中…」並自動預填
  - 身分編輯框可用「AI 建議關鍵字」
- 沒有 API key 時，以上功能全部安靜略過，App 照常運作。訊息會留在佇列，超過 30 天照原規則刪除。

## 程式結構（`app/`，package `com.dailyapp`）

- `core/`：純 Kotlin，有單元測試
  - `Model.kt`：Platform、ParsedMessage、訊息雜湊
  - `MentionDetector.kt`：先做 NFKC 正規化，並移除格式字元
  - `ReminderPlanner.kt`：提醒時間的計算
  - `Privacy.kt`：送 AI 前的個資遮蔽與成員代稱
  - `AiPlan.kt`：AI 提示詞、schema、回傳解析（M2b）
- `data/`：
  - Room + SQLCipher。密碼用 Keystore 的 AES-GCM 金鑰包裝
  - Entities、DAOs、`Repository.ingest()`（去重、白名單、點名後建立草稿待辦）
  - `HealthStore`（SharedPreferences）
  - `AiStore`：API key（Keystore 加密）、AI 執行狀態與用量（M2b）
  - 身分：`Role`、`GroupRole`（多對多），群組另有 `extraKeywords`。DB 目前是 v4
- `ai/`（M2b）：`AiClient`、`AiOrganizer`、`AiWorker`、`AiAssist`
- `notify/`：
  - `ChatListenerService`：提供 `isConnected` 與 `requestRebind`
  - `NotificationExtractor`：解析通知
  - `Alerts`：兩個通知管道，urgent 與 general
  - `Reminders.kt`：AlarmManager 排程、`ReminderReceiver`、`BootReceiver`、`ReminderSettings`
- `ui/`：
  - `Theme.kt`：黑白極簡風格與 Noto Serif TC 字型，沿用使用者 create-pdf skill 的預設風格
  - 各畫面：Todo、Group（上方是身分管理）、Health（介面上叫「狀態」，含 `AiSection`）、TodoEditDialog
  - `WheelPicker.kt`：滾輪選擇器與日期時間對話框，分鐘以 5 分為單位
- 測試：`app/src/test/...`，目前 49 個，全部通過。其中 `AiClientTest` 不連網，只檢查送出的請求內容。

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
- **Bash heredoc 若同時含 Kotlin 字串與 `'''`，可能會解析失敗**。大段 Python 改檔腳本改成先寫到 scratchpad 再執行，或直接用 Write 重寫檔案。
- **SDK jar 位置**：Gradle 的 `transforms/` 下是瘦身過的 jar，`javap` 查不到類別；要用 `modules-2/files-2.1/` 下的原始 jar。
- **截圖檢查**：`adb exec-out screencap -p > scratchpad/x.png`，再用 `am start -n com.dailyapp/.ui.MainActivity --ei tab N` 切換分頁（0 待辦、1 群組、2 狀態）。

## 隱私規則（使用者的工作訊息）

- 不要直接讀取通知或資料庫中的訊息內容。分析一律先去識別化，只看結構或統計。
- 群組頁的畫面上會出現群組名稱，非必要不要截圖；要確認有沒有閃退時，看 logcat 就好。
- AI 相關的 log 只記數量，不記訊息內容。
- API key、簽章金鑰、`*.jsonl` 記錄不得進 repo（已寫在 `.gitignore`）。
- 本機 commit 沒問題，但 **repo 尚未推送到 GitHub**。推送前要提醒使用者確認 repo 設為 Private。

## 使用者偏好

- 使用繁體中文溝通，偏好以問答方式確認決策（grill-me）。
- 介面採黑白極簡風格，同 create-pdf skill 的個人預設。
- 需要 commit 時，使用者會明確說出來。
