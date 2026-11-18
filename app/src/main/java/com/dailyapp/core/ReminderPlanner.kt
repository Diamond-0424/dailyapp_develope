package com.dailyapp.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 提醒時間的計算（純邏輯，不依賴 Android）。見 spec.md 5.3。
 *
 * - 截止前一天：早上第一個摘要時間提醒
 * - 截止當天：早上第一個摘要時間提醒；若截止時間比這更早，改在截止前 1 小時
 * - 延後（snooze）期間，早於延後時間的提醒都略過
 */
object ReminderPlanner {
    enum class Kind { SNOOZED, DUE_TOMORROW, DUE_TODAY }
    data class Plan(val at: Long, val kind: Kind)

    private const val HOUR = 60 * 60 * 1000L

    fun nextTodoReminder(
        dueAt: Long?,
        snoozedUntil: Long?,
        now: Long,
        morning: LocalTime,
        zone: ZoneId,
    ): Plan? {
        val snoozeActive = snoozedUntil != null && snoozedUntil > now
        val plans = mutableListOf<Plan>()
        if (snoozeActive) plans += Plan(snoozedUntil!!, Kind.SNOOZED)
        if (dueAt != null) {
            val dueDate = Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate()
            plans += Plan(at(dueDate.minusDays(1), morning, zone), Kind.DUE_TOMORROW)
            plans += Plan(minOf(at(dueDate, morning, zone), dueAt - HOUR), Kind.DUE_TODAY)
        }
        return plans
            .filter { it.at > now }
            .filter { !snoozeActive || it.kind == Kind.SNOOZED || it.at >= snoozedUntil!! }
            .minByOrNull { it.at }
    }

    /** 下一個每日摘要時間（今天或明天）。 */
    fun nextDigest(now: Long, times: List<LocalTime>, zone: ZoneId): Long? {
        if (times.isEmpty()) return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (0L..1L).flatMap { d -> times.map { at(today.plusDays(d), it, zone) } }
            .filter { it > now }
            .minOrNull()
    }

    /** 「明天再提醒」：明天早上第一個摘要時間。 */
    fun tomorrowMorning(now: Long, morning: LocalTime, zone: ZoneId): Long =
        at(Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1), morning, zone)

    /** 提醒標題用的截止描述。 */
    fun dueLabel(dueAt: Long?, now: Long, zone: ZoneId): String? {
        if (dueAt == null) return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val due = Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate()
        return when {
            dueAt < now -> "已逾期"
            due == today -> "今天截止"
            due == today.plusDays(1) -> "明天截止"
            else -> null
        }
    }

    /** 今天截止或已逾期：使用緊急通知管道。 */
    fun isUrgent(dueAt: Long?, now: Long, zone: ZoneId): Boolean {
        val l = dueLabel(dueAt, now, zone)
        return l == "今天截止" || l == "已逾期"
    }

    private fun at(date: LocalDate, time: LocalTime, zone: ZoneId) =
        date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    /** 解析 "09:00,14:00" 格式；無效項目略過，結果排序去重。 */
    fun parseTimes(raw: String): List<LocalTime> =
        raw.split(',').mapNotNull { runCatching { LocalTime.parse(it.trim()) }.getOrNull() }.distinct().sorted()
}
