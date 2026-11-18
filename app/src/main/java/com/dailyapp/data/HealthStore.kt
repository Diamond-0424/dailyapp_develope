package com.dailyapp.data

import android.content.Context
import com.dailyapp.core.ParseMode
import com.dailyapp.core.Platform

/** 健康檢查用的輕量狀態，存在 SharedPreferences（不含訊息內容）。 */
class HealthStore(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("health", Context.MODE_PRIVATE)

    fun onConnected() = p.edit().putLong("connectedAt", now()).apply()
    fun onDisconnected() = p.edit().putLong("disconnectedAt", now()).apply()

    fun onNotification(platform: Platform, mode: ParseMode?) {
        val e = p.edit().putLong("last_${platform.id}", now())
        when (mode) {
            null -> e.putInt("unparsed_${platform.id}", p.getInt("unparsed_${platform.id}", 0) + 1)
            ParseMode.FALLBACK -> e.putInt("fallback_${platform.id}", p.getInt("fallback_${platform.id}", 0) + 1)
            ParseMode.MESSAGING_STYLE -> Unit
        }
        e.apply()
    }

    val connectedAt get() = p.getLong("connectedAt", 0).takeIf { it > 0 }
    val disconnectedAt get() = p.getLong("disconnectedAt", 0).takeIf { it > 0 }
    fun lastNotificationAt(pl: Platform) = p.getLong("last_${pl.id}", 0).takeIf { it > 0 }
    fun fallbackCount(pl: Platform) = p.getInt("fallback_${pl.id}", 0)
    fun unparsedCount(pl: Platform) = p.getInt("unparsed_${pl.id}", 0)

    /** 每天最多清理一次過期訊息。 */
    fun shouldCleanup(): Boolean {
        val last = p.getLong("lastCleanup", 0)
        if (now() - last < DAY) return false
        p.edit().putLong("lastCleanup", now()).apply()
        return true
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
