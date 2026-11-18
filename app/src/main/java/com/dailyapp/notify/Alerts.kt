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
import com.dailyapp.core.ReminderPlanner
import com.dailyapp.data.ChatGroup
import com.dailyapp.data.Todo
import com.dailyapp.data.TodoKind
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

    fun todoNotificationId(todoId: Long) = ("todo:$todoId").hashCode()

    /** 每日摘要：列出未完成待辦。沒有待辦時不打擾。 */
    fun digest(ctx: Context, todos: List<Todo>) {
        if (todos.isEmpty()) return
        val now = System.currentTimeMillis()
        val style = NotificationCompat.InboxStyle()
        todos.take(6).forEach { t ->
            val due = ReminderPlanner.dueLabel(t.dueAt, now, Reminders.zone)
                ?: t.dueAt?.let { "截止 " + fmt(it) }
            style.addLine(if (due != null) "${t.title}（$due）" else t.title)
        }
        if (todos.size > 6) style.setSummaryText("另有 ${todos.size - 6} 項")
        post(
            ctx, id = DIGEST_ID, channel = CH_GENERAL,
            title = "未完成待辦 ${todos.size} 項",
            text = todos.first().title,
            tab = MainActivity.TAB_TODO,
            style = style,
        )
    }

    /** 單筆待辦的截止提醒，附「延後 1 小時／明天再提醒／完成」。今天截止或逾期走緊急管道。 */
    fun todoReminder(ctx: Context, t: Todo) {
        val now = System.currentTimeMillis()
        val label = ReminderPlanner.dueLabel(t.dueAt, now, Reminders.zone) ?: "提醒"
        val urgent = ReminderPlanner.isUrgent(t.dueAt, now, Reminders.zone)
        val detail = buildString {
            t.dueAt?.let { append("截止 ").append(fmt(it)) }
            if (t.excerpt.isNotBlank() && t.excerpt != t.title) {
                if (isNotEmpty()) append("\n")
                append(t.excerpt)
            }
        }
        post(
            ctx, id = todoNotificationId(t.id), channel = if (urgent) CH_URGENT else CH_GENERAL,
            title = "$label｜${t.title}",
            text = detail.ifBlank { t.title },
            tab = MainActivity.TAB_TODO,
            actions = listOf(
                "延後 1 小時" to Reminders.broadcast(ctx, Reminders.ACTION_SNOOZE_HOUR, 1, t.id),
                "明天再提醒" to Reminders.broadcast(ctx, Reminders.ACTION_SNOOZE_TOMORROW, 2, t.id),
                "完成" to Reminders.broadcast(ctx, Reminders.ACTION_DONE, 3, t.id),
            ),
        )
    }

    /** AI 整理出新事項時通知一次（一般管道，勿擾模式下不響）。 */
    fun aiCreated(ctx: Context, todos: List<Todo>) {
        if (todos.isEmpty()) return
        val style = NotificationCompat.InboxStyle()
        todos.take(6).forEach { style.addLine((if (it.kind == TodoKind.WATCH) "關注｜" else "") + it.title) }
        if (todos.size > 6) style.setSummaryText("另有 ${todos.size - 6} 項")
        post(
            ctx, id = AI_ID, channel = CH_GENERAL,
            title = "AI 整理出 ${todos.size} 項新事項",
            text = todos.first().title,
            tab = MainActivity.TAB_TODO,
            style = style,
        )
    }

    private const val DIGEST_ID = 0x0D16E57
    private const val AI_ID = 0x0A1A1

    private fun fmt(ts: Long) =
        java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.TAIWAN).format(java.util.Date(ts))

    private fun post(
        ctx: Context,
        id: Int,
        channel: String,
        title: String,
        text: String,
        tab: Int,
        style: NotificationCompat.Style? = null,
        actions: List<Pair<String, PendingIntent>> = emptyList(),
    ) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style ?: NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(if (channel == CH_URGENT) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
        actions.forEach { (label, action) -> b.addAction(0, label, action) }
        NotificationManagerCompat.from(ctx).notify(id, b.build())
    }
}
