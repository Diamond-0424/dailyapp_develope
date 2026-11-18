package com.dailyapp

import android.app.Application
import com.dailyapp.notify.Alerts

class DailyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Alerts.createChannels(this)
    }
}
