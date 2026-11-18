package com.dailyapp.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dailyapp.R
import com.dailyapp.core.Platform
import com.dailyapp.data.ChatGroup
import com.dailyapp.ui.MainActivity

/** 本 App 發出的提醒。見 spec.md 5.4 通知管道。 */
object Alerts {
    /** 被 @、今天截止。使用者可在勿擾例外中允許此管道突破勿擾。 */
    const val CH_URGENT = "urgent"
    /** 每日摘要、新群組、系統警告。遵守勿擾。 */
    const val CH_GENERAL = "general"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_URGENT, "緊急：被點名、今天截止", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "可在勿擾模式的例外設定中允許此類別"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_GENERAL, "一般：摘要、新群組、系統提醒", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun mentioned(ctx: Context, group: ChatGroup, todoId: Long, sender: String, text: String) {
        val label = Platform.fromId(group.platform)?.label ?: group.platform
        post(
            ctx, id = (todoId % Int.MAX_VALUE).toInt(), channel = CH_URGENT,
            title = "被點名｜$label・${group.title}",
            text = "$sender：$text",
            tab = MainActivity.TAB_TODO,
        )
    }

    fun newGroup(ctx: Context, group: ChatGroup) {
        val label = Platform.fromId(group.platform)?.label ?: group.platform
        post(
            ctx, id = ("g:" + group.id).hashCode(), channel = CH_GENERAL,
            title = "發現新${if (group.isGroup) "群組" else "對話"}：${group.title}",
            text = "$label・要加入監聽嗎？點此設定",
            tab = MainActivity.TAB_GROUPS,
        )
    }

    private fun post(ctx: Context, id: Int, channel: String, title: String, text: String, tab: Int) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(if (channel == CH_URGENT) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }
}
