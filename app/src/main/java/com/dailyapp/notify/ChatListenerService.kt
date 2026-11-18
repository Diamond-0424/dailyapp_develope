package com.dailyapp.notify

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.dailyapp.core.Platform
import com.dailyapp.data.AppDatabase
import com.dailyapp.data.HealthStore
import com.dailyapp.data.IngestEvent
import com.dailyapp.data.Repository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 監聽 LINE、Discord、Telegram 的通知。只讀取，不取消或修改任何通知。 */
class ChatListenerService : NotificationListenerService() {
    private val onError = CoroutineExceptionHandler { _, t -> Log.e(TAG, "ingest failed", t) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + onError)
    private lateinit var repo: Repository
    private lateinit var health: HealthStore

    override fun onCreate() {
        super.onCreate()
        repo = Repository(AppDatabase.get(this))
        health = HealthStore(this)
    }

    override fun onListenerConnected() {
        isConnected = true
        health.onConnected()
        if (health.shouldCleanup()) scope.launch { repo.cleanupOldMessages() }
    }

    override fun onListenerDisconnected() {
        isConnected = false
        health.onDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val platform = Platform.fromPackage(sbn.packageName) ?: return
        val result = runCatching { NotificationExtractor.extract(sbn) }
            .onFailure { Log.e(TAG, "extract failed for ${sbn.packageName}", it) }
            .getOrNull()

        when (result) {
            is NotificationExtractor.Result.Ok -> {
                health.onNotification(platform, result.conv.mode)
                scope.launch {
                    for (ev in repo.ingest(result.conv)) when (ev) {
                        is IngestEvent.NewGroup -> Alerts.newGroup(this@ChatListenerService, ev.group)
                        is IngestEvent.Mentioned ->
                            Alerts.mentioned(this@ChatListenerService, ev.group, ev.todoId, ev.sender, ev.text)
                    }
                    if (health.shouldCleanup()) repo.cleanupOldMessages()
                }
            }
            NotificationExtractor.Result.Skipped -> health.onNotification(platform, com.dailyapp.core.ParseMode.MESSAGING_STYLE)
            NotificationExtractor.Result.Unparsed, null -> health.onNotification(platform, null)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ChatListener"

        /**
         * 服務目前是否真的連上（與 UI 同一個 process）。
         * 「已授權」不等於「已連線」：紅米未開自啟動時，系統會拒絕啟動服務。
         */
        @Volatile var isConnected = false
            private set

        /** 已授權但未連線時，請系統重新綁定（例如使用者剛開啟自啟動）。 */
        fun requestRebind(ctx: android.content.Context) {
            runCatching {
                NotificationListenerService.requestRebind(
                    android.content.ComponentName(ctx, ChatListenerService::class.java)
                )
            }
        }
    }
}
