package com.dailyapp.probe

import android.content.Context
import org.json.JSONObject
import java.io.File

/** 記錄存在 App 的私有目錄，其他 App 無法讀取；只有使用者按「匯出」才會分享出去。 */
object LogStore {
    private const val FILE = "probe.jsonl"

    fun file(ctx: Context) = File(ctx.filesDir, FILE)

    @Synchronized
    fun append(ctx: Context, o: JSONObject) {
        file(ctx).appendText(o.toString() + "\n")
    }

    fun event(ctx: Context, name: String) {
        append(ctx, JSONObject().put("type", name).put("ts", System.currentTimeMillis()))
    }

    @Synchronized
    fun clear(ctx: Context) {
        file(ctx).delete()
    }

    data class Stats(
        val perApp: Map<String, Pair<Int, Long>>, // 平台 → (通知數, 最後時間)
        val lastConnected: Long?,
        val lastDisconnected: Long?,
        val dndCount: Int,
        val sizeBytes: Long,
    )

    @Synchronized
    fun stats(ctx: Context): Stats {
        val f = file(ctx)
        val per = mutableMapOf<String, Pair<Int, Long>>()
        var conn: Long? = null
        var disc: Long? = null
        var dnd = 0
        if (f.exists()) {
            f.forEachLine { line ->
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                val ts = o.optLong("ts")
                when (o.optString("type")) {
                    "posted" -> {
                        val pkg = o.optString("pkg")
                        val app = ProbeListenerService.TARGETS[pkg] ?: pkg
                        val count = per[app]?.first ?: 0
                        per[app] = (count + 1) to ts
                        if (o.optInt("interruptionFilter", 1) > 1) dnd++
                    }
                    "listener_connected" -> conn = ts
                    "listener_disconnected" -> disc = ts
                }
            }
        }
        return Stats(per, conn, disc, dnd, if (f.exists()) f.length() else 0)
    }
}
