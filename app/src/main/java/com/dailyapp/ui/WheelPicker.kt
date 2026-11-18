package com.dailyapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/*
 * 上下滾輪選擇器，取代 Android 內建的時鐘轉盤與月曆。
 * 黑白極簡：中間一列粗體黑字，上下以細灰線框出，其餘灰字。
 */

private val RowHeight = 40.dp
private const val VISIBLE = 5          // 一次顯示幾列（奇數）
private const val PAD = VISIBLE / 2    // 上下各補幾列空白，讓第一項與最後一項也能停在中間

const val MINUTE_STEP = 5

/**
 * 單一欄滾輪。`selected` 由外部持有：外部改變時會捲過去；使用者捲動停下後回呼 `onSelected`。
 */
@Composable
fun WheelPicker(
    items: List<String>,
    selected: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selected)
    val scope = rememberCoroutineScope()
    val rowPx = with(LocalDensity.current) { RowHeight.toPx() }
    val lastIndex by rememberUpdatedState(items.lastIndex)
    val centered by remember {
        derivedStateOf {
            val i = state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > rowPx / 2) 1 else 0
            i.coerceIn(0, lastIndex)
        }
    }
    val latestOnSelected by rememberUpdatedState(onSelected)

    // 捲動停下時回報中間那一項
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.filter { !it }.collect { latestOnSelected(centered) }
    }
    // 外部改了選擇（例如按「明天」）就捲過去
    LaunchedEffect(selected) {
        if (!state.isScrollInProgress && centered != selected) state.animateScrollToItem(selected)
    }

    Box(modifier.height(RowHeight * VISIBLE), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.fillMaxWidth().height(1.dp).background(Ink.Black))
            Spacer(Modifier.height(RowHeight - 2.dp))
            Spacer(Modifier.fillMaxWidth().height(1.dp).background(Ink.Black))
        }
        LazyColumn(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state),
            modifier = Modifier.fillMaxWidth().height(RowHeight * VISIBLE),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(PAD) { Spacer(Modifier.height(RowHeight)) }
            items(items.size) { i ->
                val on = i == centered
                Box(
                    Modifier.fillMaxWidth().height(RowHeight)
                        .clickable { scope.launch { state.animateScrollToItem(i) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        items[i],
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = if (on) Ink.Black else Ink.Secondary,
                            fontWeight = if (on) FontWeight(700) else FontWeight(400),
                        ),
                    )
                }
            }
            items(PAD) { Spacer(Modifier.height(RowHeight)) }
        }
    }
}

private val HourLabels = (0..23).map { "%02d".format(it) }
private val MinuteLabels = (0 until 60 step MINUTE_STEP).map { "%02d".format(it) }

/** 時、分兩欄。分鐘以 5 分為單位。 */
@Composable
private fun TimeWheels(
    hour: Int, minuteIdx: Int,
    onHour: (Int) -> Unit, onMinuteIdx: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        WheelPicker(HourLabels, hour, onHour, Modifier.weight(1f))
        Text(":", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 4.dp))
        WheelPicker(MinuteLabels, minuteIdx, onMinuteIdx, Modifier.weight(1f))
    }
}

/** 只選時間（每日摘要用）。 */
@Composable
fun TimeWheelDialog(
    title: String,
    initial: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    var hour by remember { mutableIntStateOf(initial.hour) }
    var minuteIdx by remember { mutableIntStateOf(initial.minute / MINUTE_STEP) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(4.dp),
        containerColor = Ink.White,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            TimeWheels(hour, minuteIdx, { hour = it }, { minuteIdx = it }, Modifier.fillMaxWidth().padding(horizontal = 32.dp))
        },
        confirmButton = { PlainAction("確定") { onConfirm(LocalTime.of(hour, minuteIdx * MINUTE_STEP)) } },
        dismissButton = { PlainAction("取消", onDismiss) },
    )
}

private val Weekday = listOf("一", "二", "三", "四", "五", "六", "日")

private fun dateLabel(d: LocalDate, today: LocalDate): String =
    when (ChronoUnit.DAYS.between(today, d)) {
        0L -> "今天"
        1L -> "明天"
        2L -> "後天"
        else -> "${d.monthValue}/${d.dayOfMonth}（${Weekday[d.dayOfWeek.value - 1]}）"
    }

/** 選日期＋時間（待辦截止用）。日期欄從今天起算一年；若原本的截止日已過，也從那天開始列。 */
@Composable
fun DateTimeWheelDialog(
    title: String,
    initial: LocalDateTime,
    today: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDateTime) -> Unit,
) {
    val dates = remember(initial, today) {
        val start = minOf(today, initial.toLocalDate())
        val end = maxOf(today.plusDays(365), initial.toLocalDate())
        generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
    }
    val labels = remember(dates) { dates.map { dateLabel(it, today) } }
    val todayIdx = dates.indexOf(today)

    var dateIdx by remember { mutableIntStateOf(dates.indexOf(initial.toLocalDate())) }
    var hour by remember { mutableIntStateOf(initial.hour) }
    var minuteIdx by remember { mutableIntStateOf(initial.minute / MINUTE_STEP) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(4.dp),
        containerColor = Ink.White,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    PlainAction("今天") { dateIdx = todayIdx }
                    PlainAction("明天") { dateIdx = todayIdx + 1 }
                    PlainAction("後天") { dateIdx = todayIdx + 2 }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    WheelPicker(labels, dateIdx, { dateIdx = it }, Modifier.weight(1.6f))
                    TimeWheels(hour, minuteIdx, { hour = it }, { minuteIdx = it }, Modifier.weight(1.4f).padding(start = 8.dp))
                }
            }
        },
        confirmButton = {
            PlainAction("確定") { onConfirm(dates[dateIdx].atTime(hour, minuteIdx * MINUTE_STEP)) }
        },
        dismissButton = { PlainAction("取消", onDismiss) },
    )
}
