package com.dailyapp.core

import com.dailyapp.core.ReminderPlanner.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class ReminderPlannerTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private val morning = LocalTime.of(9, 0)
    private fun t(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test fun dayBeforeReminderComesFirst() {
        val p = ReminderPlanner.nextTodoReminder(t(2026, 11, 21, 18), null, t(2026, 11, 19, 10), morning, zone)
        assertEquals(ReminderPlanner.Plan(t(2026, 11, 20, 9), Kind.DUE_TOMORROW), p)
    }

    @Test fun dueDayAfterDayBeforePassed() {
        val p = ReminderPlanner.nextTodoReminder(t(2026, 11, 21, 18), null, t(2026, 11, 20, 12), morning, zone)
        assertEquals(ReminderPlanner.Plan(t(2026, 11, 21, 9), Kind.DUE_TODAY), p)
    }

    @Test fun earlyDeadlineRemindsOneHourBefore() {
        val p = ReminderPlanner.nextTodoReminder(t(2026, 11, 21, 8), null, t(2026, 11, 20, 12), morning, zone)
        assertEquals(ReminderPlanner.Plan(t(2026, 11, 21, 7), Kind.DUE_TODAY), p)
    }

    @Test fun allPassedReturnsNull() {
        assertNull(ReminderPlanner.nextTodoReminder(t(2026, 11, 21, 18), null, t(2026, 11, 21, 10), morning, zone))
    }

    @Test fun noDueNoSnoozeReturnsNull() {
        assertNull(ReminderPlanner.nextTodoReminder(null, null, t(2026, 11, 19, 10), morning, zone))
    }

    @Test fun snoozeSuppressesEarlierReminders() {
        // 延後到 11/21 10:00，11/20 09:00 與 11/21 09:00 的提醒都略過
        val p = ReminderPlanner.nextTodoReminder(
            t(2026, 11, 21, 18), t(2026, 11, 21, 10), t(2026, 11, 19, 12), morning, zone,
        )
        assertEquals(ReminderPlanner.Plan(t(2026, 11, 21, 10), Kind.SNOOZED), p)
    }

    @Test fun expiredSnoozeIgnored() {
        val p = ReminderPlanner.nextTodoReminder(
            t(2026, 11, 21, 18), t(2026, 11, 19, 11), t(2026, 11, 19, 12), morning, zone,
        )
        assertEquals(Kind.DUE_TOMORROW, p!!.kind)
    }

    @Test fun nextDigestTodayOrTomorrow() {
        val times = ReminderPlanner.parseTimes("09:00, 14:00,18:00")
        assertEquals(t(2026, 11, 19, 14), ReminderPlanner.nextDigest(t(2026, 11, 19, 10), times, zone))
        assertEquals(t(2026, 11, 20, 9), ReminderPlanner.nextDigest(t(2026, 11, 19, 19), times, zone))
    }

    @Test fun parseTimesSkipsInvalidAndSorts() {
        assertEquals(listOf(LocalTime.of(9, 0), LocalTime.of(18, 0)), ReminderPlanner.parseTimes("18:00,bad,09:00,09:00"))
    }

    @Test fun dueLabels() {
        val now = t(2026, 11, 19, 10)
        assertEquals("今天截止", ReminderPlanner.dueLabel(t(2026, 11, 19, 18), now, zone))
        assertEquals("明天截止", ReminderPlanner.dueLabel(t(2026, 11, 20, 18), now, zone))
        assertEquals("已逾期", ReminderPlanner.dueLabel(t(2026, 11, 19, 9), now, zone))
        assertNull(ReminderPlanner.dueLabel(t(2026, 11, 25, 18), now, zone))
    }
}
