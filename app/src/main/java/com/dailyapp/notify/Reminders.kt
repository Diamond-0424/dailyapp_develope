package com.dailyapp.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.dailyapp.core.ReminderPlanner
import com.dailyapp.data.AppDatabase
import com.dailyapp.data.Todo
import com.dailyapp.data.TodoStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZoneId

/** 每日摘要時間等提醒設定。 */
class ReminderSettings(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    var digestTimes: List<LocalTime>
        get() = ReminderPlanner.parseTimes(p.getString("digestTimes", DEFAULT) ?: DEFAULT)
        set(v) = p.edit().putString("digestTimes", v.distinct().sorted().joinToString(",")).apply()

    /** 截止前一天、當天的提醒時間 = 第一個摘要時間。 */
    val morning: LocalTime get() = digestTimes.firstOrNull() ?: LocalTime.of(9, 0)

    companion object {
        const val DEFAULT = "09:00,14:00,18:00"
    }
}

/**
 * 以 AlarmManager 排程提醒：
 * - 每日摘要：一個鬧鐘，觸發後排下一次
 * - 每筆有截止日或已延後的待辦：各自一個鬧鐘（以待辦 id 區分）
 * 開機、App 更新、時區變更後由 BootReceiver 全部重排。
 */
object Reminders {
    const val ACTION_DIGEST = "com.dailyapp.action.DIGEST"
    const val ACTION_TODO = "com.dailyapp.action.TODO_REMIND"
    const val ACTION_SNOOZE_HOUR = "com.dailyapp.action.SNOOZE_HOUR"
    const val ACTION_SNOOZE_TOMORROW = "com.dailyapp.action.SNOOZE_TOMORROW"
    const val ACTION_DONE = "com.dailyapp.action.DONE"
    const val EXTRA_ID = "id"

    private const val TAG = "Reminders"
    val zone: ZoneId get() = ZoneId.systemDefault()

    fun scheduleDigest(ctx: Context) {
        val at = ReminderPlanner.nextDigest(System.currentTimeMillis(), ReminderSettings(ctx).digestTimes, zone)
        val pi = broadcast(ctx, ACTION_DIGEST, 0, null)
        if (at == null) alarms(ctx).cancel(pi) else setAlarm(ctx, at, pi)
    }

    fun scheduleTodo(ctx: Context, t: Todo) {
        val pi = broadcast(ctx, ACTION_TODO, 0, t.id)
        val plan = if (t.status == TodoStatus.DONE) null else ReminderPlanner.nextTodoReminder(
            t.dueAt, t.snoozedUntil, System.currentTimeMillis() + 1000, ReminderSettings(ctx).morning, zone,
        )
        if (plan == null) alarms(ctx).cancel(pi) else setAlarm(ctx, plan.at, pi)
    }

    fun cancelTodo(ctx: Context, id: Long) {
        alarms(ctx).cancel(broadcast(ctx, ACTION_TODO, 0, id))
        NotificationManagerCompat.from(ctx).cancel(Alerts.todoNotificationId(id))
    }

    suspend fun rescheduleAll(ctx: Context) {
        scheduleDigest(ctx)
        AppDatabase.get(ctx).todos().openTodos().forEach { scheduleTodo(ctx, it) }
    }

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms(ctx).canScheduleExactAlarms()

    private fun setAlarm(ctx: Context, at: Long, pi: PendingIntent) {
        val am = alarms(ctx)
        if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun alarms(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    /** 以 data URI 區分每筆待辦，requestCode 區分動作，避免 PendingIntent 互相覆蓋。 */
    fun broadcast(ctx: Context, action: String, requestCode: Int, id: Long?): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(action)
        if (id != null) i.setData(Uri.parse("dailyapp://todo/$id")).putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            ctx, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    internal fun log(msg: String) = Log.i(TAG, msg)
}

/** 處理鬧鐘與通知上的按鈕。不對外開放（exported=false）。 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handle(ctx, intent)
            } catch (t: Throwable) {
                Log.e("ReminderReceiver", "failed: ${intent.action}", t)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(ctx: Context, intent: Intent) {
        val dao = AppDatabase.get(ctx).todos()
        val id = intent.getLongExtra(Reminders.EXTRA_ID, -1)
        val now = System.currentTimeMillis()
        when (intent.action) {
            Reminders.ACTION_DIGEST -> {
                Alerts.digest(ctx, dao.openForDigest())
                Reminders.scheduleDigest(ctx)
            }
            Reminders.ACTION_TODO -> {
                val t = dao.get(id) ?: return
                if (t.status == TodoStatus.DONE) return
                Alerts.todoReminder(ctx, t)
                Reminders.scheduleTodo(ctx, t)
            }
            Reminders.ACTION_SNOOZE_HOUR, Reminders.ACTION_SNOOZE_TOMORROW -> {
                val until = if (intent.action == Reminders.ACTION_SNOOZE_HOUR) now + 60 * 60 * 1000L
                else ReminderPlanner.tomorrowMorning(now, ReminderSettings(ctx).morning, Reminders.zone)
                dao.snooze(id, until, now)
                NotificationManagerCompat.from(ctx).cancel(Alerts.todoNotificationId(id))
                dao.get(id)?.let { Reminders.scheduleTodo(ctx, it) }
            }
            Reminders.ACTION_DONE -> {
                dao.setStatus(id, TodoStatus.DONE, now)
                Reminders.cancelTodo(ctx, id)
            }
        }
    }
}

/** 開機、App 更新、時間或時區變更後，重新排程所有提醒。只做重排，不接受其他動作。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Reminders.rescheduleAll(ctx)
                Reminders.log("rescheduled after ${intent.action}")
            } finally {
                pending.finish()
            }
        }
    }
}
