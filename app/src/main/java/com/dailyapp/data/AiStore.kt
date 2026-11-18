package com.dailyapp.data

import android.content.Context
import android.util.Base64
import java.time.YearMonth
import java.time.ZoneId

/**
 * AI 整理的設定與狀態：
 * - API key：用 Keystore 金鑰加密後存放（spec 6），不寫入 log、不進備份。
 * - 上次執行時間與結果，給「狀態」頁顯示。
 * - 每月 token 用量，M2c 的費用上限會用到。
 */
class AiStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("ai", Context.MODE_PRIVATE)

    var apiKey: String?
        get() = p.getString(KEY_BLOB, null)?.let {
            runCatching { String(KeystoreBox.unwrap(ALIAS, Base64.decode(it, Base64.NO_WRAP)), Charsets.UTF_8) }.getOrNull()
        }
        set(value) {
            val v = value?.trim()?.takeIf { it.isNotEmpty() }
            if (v == null) p.edit().remove(KEY_BLOB).apply()
            else p.edit().putString(
                KEY_BLOB, Base64.encodeToString(KeystoreBox.wrap(ALIAS, v.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            ).apply()
        }

    val hasKey: Boolean get() = p.contains(KEY_BLOB)

    /** 只顯示前後幾碼，讓使用者確認是哪一把。 */
    fun maskedKey(): String? = apiKey?.let { k -> if (k.length <= 12) "••••" else k.take(7) + "…" + k.takeLast(4) }

    fun recordRun(at: Long, result: String) = p.edit().putLong("lastRunAt", at).putString("lastResult", result).apply()
    val lastRunAt: Long? get() = p.getLong("lastRunAt", 0).takeIf { it > 0 }
    val lastResult: String? get() = p.getString("lastResult", null)

    fun addUsage(inputTokens: Long, outputTokens: Long, zone: ZoneId = ZoneId.systemDefault()) {
        if (inputTokens == 0L && outputTokens == 0L) return
        val m = YearMonth.now(zone).toString()
        p.edit()
            .putLong("in_$m", p.getLong("in_$m", 0) + inputTokens)
            .putLong("out_$m", p.getLong("out_$m", 0) + outputTokens)
            .apply()
    }

    fun usage(month: YearMonth): Pair<Long, Long> = p.getLong("in_$month", 0) to p.getLong("out_$month", 0)

    companion object {
        private const val ALIAS = "dailyapp_api_key"
        private const val KEY_BLOB = "key"

        /** claude-haiku-5-5：每百萬 token 輸入 0.10、輸出 0.50 美元。 */
        fun costUsd(inputTokens: Long, outputTokens: Long): Double =
            inputTokens / 1_000_000.0 * 0.10 + outputTokens / 1_000_000.0 * 0.50
    }
}
