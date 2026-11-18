package com.dailyapp

import android.app.Application
import com.dailyapp.ai.AiWorker
import com.dailyapp.notify.Alerts
import com.dailyapp.notify.Reminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DailyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Alerts.createChannels(this)
        AiWorker.schedule(this)
        // 每次啟動都重排一次，避免鬧鐘被系統清掉後就再也不提醒
        CoroutineScope(Dispatchers.IO).launch { runCatching { Reminders.rescheduleAll(this@DailyApp) } }
    }
}
