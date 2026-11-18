package com.dailyapp.probe

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/** M0：把目標 App 的通知原始內容完整記錄下來，供分析欄位結構。 */
class ProbeListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        LogStore.event(this, "listener_connected")
    }

    override fun onListenerDisconnected() {
        LogStore.event(this, "listener_disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in TARGETS) return
        val n = sbn.notification
        val o = JSONObject()
            .put("type", "posted")
            .put("ts", System.currentTimeMillis())
            .put("pkg", sbn.packageName)
            .put("key", sbn.key)
            .put("id", sbn.id)
            .put("tag", sbn.tag ?: JSONObject.NULL)
            .put("postTime", sbn.postTime)
            .put("channelId", n.channelId ?: JSONObject.NULL)
            .put("category", n.category ?: JSONObject.NULL)
            .put("group", n.group ?: JSONObject.NULL)
            .put("isGroupSummary", (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0)
            .put("hasContentIntent", n.contentIntent != null)
            // 1 = 全部允許；2、3、4 = 各種勿擾模式
            .put("interruptionFilter", currentInterruptionFilter)
            .put("extras", Dump.bundle(n.extras))
        LogStore.append(this, o)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName !in TARGETS) return
        LogStore.append(
            this,
            JSONObject()
                .put("type", "removed")
                .put("ts", System.currentTimeMillis())
                .put("pkg", sbn.packageName)
                .put("key", sbn.key)
        )
    }

    companion object {
        val TARGETS = mapOf(
            "jp.naver.line.android" to "LINE",
            "com.discord" to "Discord",
            "org.telegram.messenger" to "Telegram",
            "org.telegram.messenger.web" to "Telegram",
            "org.thunderdog.challegram" to "Telegram X",
        )
    }
}
