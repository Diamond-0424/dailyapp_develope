package com.dailyapp.ai

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dailyapp.core.Redactor
import com.dailyapp.core.AiKeywords
import com.dailyapp.core.AiShare
import com.dailyapp.data.AiStore
import com.dailyapp.notify.Reminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * 每小時執行一次 AI 整理，只在 08:00–22:59 之間真正送出（spec 4.2）。
 * 夜間的訊息照樣存下，留到隔天早上第一輪處理。
 */
class AiWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!AiStore(applicationContext).hasKey) return Result.success()
        if (!inWindow(LocalTime.now(Reminders.zone))) return Result.success()
        AiOrganizer.run(applicationContext)
        // 失敗也回報成功：訊息留在佇列，下一輪自然重送，不需要 WorkManager 另外重試
        return Result.success()
    }

    companion object {
        private const val NAME = "ai_hourly"

        fun inWindow(t: LocalTime) = t.hour in 8..22

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<AiWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

/** 介面上即時用到的小型 AI 呼叫。沒有 API key 或失敗時回傳 null，由呼叫端維持原狀。 */
object AiAssist {
    /** 分享進來的文字 → 標題與截止時間。只做遮蔽，分享的文字沒有發言者資訊可換代稱。 */
    suspend fun suggestTodo(ctx: Context, text: String): Pair<String, LocalDateTime?>? = withContext(Dispatchers.IO) {
        val store = AiStore(ctx)
        val key = store.apiKey ?: return@withContext null
        val input = AiShare.buildInput(LocalDateTime.now(Reminders.zone), Redactor.redact(text))
        AiClient(key).use { c ->
            when (val r = c.call(AiShare.SYSTEM, input, AiShare.SCHEMA, maxTokens = 4000L)) {
                is AiCall.Ok -> {
                    store.addUsage(r.inputTokens, r.outputTokens)
                    runCatching { AiShare.parse(r.json) }.getOrNull()
                }
                is AiCall.Fail -> { store.addUsage(r.inputTokens, r.outputTokens); null }
                is AiCall.Retry -> null
            }
        }
    }

    suspend fun suggestKeywords(ctx: Context, name: String, description: String, existing: List<String>): Result<List<String>> =
        withContext(Dispatchers.IO) {
            val store = AiStore(ctx)
            val key = store.apiKey ?: return@withContext Result.failure(IllegalStateException("尚未設定 API key"))
            AiClient(key).use { c ->
                when (val r = c.call(AiKeywords.SYSTEM, AiKeywords.buildInput(name, description, existing), AiKeywords.SCHEMA, 4000L)) {
                    is AiCall.Ok -> {
                        store.addUsage(r.inputTokens, r.outputTokens)
                        runCatching { AiKeywords.parse(r.json, existing) }
                    }
                    is AiCall.Fail -> { store.addUsage(r.inputTokens, r.outputTokens); Result.failure(IllegalStateException(r.reason)) }
                    is AiCall.Retry -> Result.failure(IllegalStateException(r.reason))
                }
            }
        }
}
