package com.dailyapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 新增或編輯待辦：標題、截止時間。
 * 分享進來的文字會預填：第一行當標題，全文保留為摘錄。
 */
@Composable
fun TodoEditDialog(
    heading: String,
    initialTitle: String,
    initialDueAt: Long?,
    excerpt: String?,
    onDismiss: () -> Unit,
    onSave: (title: String, dueAt: Long?) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    var title by remember { mutableStateOf(initialTitle) }
    var dueAt by remember { mutableStateOf(initialDueAt) }

    var picking by remember { mutableStateOf(false) }

    if (picking) {
        val today = LocalDate.now(zone)
        val base = dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }
            ?: today.atTime(18, 0)
        DateTimeWheelDialog(
            title = "截止時間",
            initial = base,
            today = today,
            onDismiss = { picking = false },
            onConfirm = {
                dueAt = it.atZone(zone).toInstant().toEpochMilli()
                picking = false
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(4.dp),
        containerColor = Ink.White,
        title = { Text(heading, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("內容", style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    shape = RoundedCornerShape(2.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Ink.Black, unfocusedBorderColor = Ink.Rule, cursorColor = Ink.Black,
                    ),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("截止", style = MaterialTheme.typography.titleSmall)
                    Text(
                        dueAt?.let { fmtDue(it) } ?: "未設定（只會出現在每日摘要）",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        PlainAction(if (dueAt == null) "設定截止時間" else "更改") { picking = true }
                        if (dueAt != null) PlainAction("清除") { dueAt = null }
                    }
                }
                if (!excerpt.isNullOrBlank() && excerpt != title) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("原文", style = MaterialTheme.typography.titleSmall)
                        Text(excerpt, style = MaterialTheme.typography.bodySmall, maxLines = 6)
                    }
                }
            }
        },
        confirmButton = {
            PlainAction("儲存") { if (title.isNotBlank()) onSave(title.trim(), dueAt) }
        },
        dismissButton = { PlainAction("取消", onDismiss) },
    )
}

fun fmtDue(ts: Long): String =
    java.text.SimpleDateFormat("M 月 d 日（EEE）HH:mm", java.util.Locale.TAIWAN).format(java.util.Date(ts))
