package com.dailyapp.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailyapp.ai.AiOrganizer
import com.dailyapp.core.AiPlan
import com.dailyapp.data.AiStore
import com.dailyapp.data.AppDatabase
import kotlinx.coroutines.launch
import java.time.YearMonth

/** 「狀態」頁的 AI 整理區塊：API key、上次執行、待整理數量、本月用量、立即整理。 */
@Composable
fun AiSection(ctx: Context, tick: Int) {
    val store = remember { AiStore(ctx) }
    val db = remember { AppDatabase.get(ctx) }
    val pending by remember { db.messages().observeUnprocessedCount() }.collectAsStateWithLifecycle(0)
    val scope = rememberCoroutineScope()
    // 本區塊自己的重繪計數：存 key、整理完成後遞增
    var version by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var editingKey by remember { mutableStateOf(false) }

    val masked = remember(tick, version) { store.maskedKey() }
    val lastAt = remember(tick, version) { store.lastRunAt }
    val lastResult = remember(tick, version) { store.lastResult }
    val (inT, outT) = remember(tick, version) { store.usage(YearMonth.now()) }

    SectionLabel("AI 整理")
    Rule()
    KeyValue("API key", masked ?: "未設定", emphasize = masked == null)
    KeyValue("模型", AiPlan.MODEL)
    KeyValue("待整理訊息", "$pending 則")
    KeyValue("上次整理", fmtTime(lastAt))
    if (lastResult != null) Text(
        lastResult,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = Space.Page),
    )
    KeyValue("本月用量", "%,d／%,d tokens　約 US$%.3f".format(inT, outT, AiStore.costUsd(inT, outT)))
    Row(Modifier.padding(horizontal = Space.Page), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        PlainAction(if (masked == null) "設定 API key" else "更換 API key") { editingKey = true }
        if (masked != null) PlainAction(if (running) "整理中…" else "立即整理") {
            if (!running) {
                running = true
                scope.launch {
                    try { AiOrganizer.run(ctx) } finally {
                        running = false
                        version++
                    }
                }
            }
        }
    }
    Note("08:00–22:00 每小時自動整理一次，需要網路。夜間的訊息會留到隔天早上。送出前會先遮蔽電話、Email、網址等，其他人的名字換成代稱。")

    if (editingKey) ApiKeyDialog(
        hasKey = masked != null,
        onDismiss = { editingKey = false },
        onSave = { key ->
            store.apiKey = key
            editingKey = false
            version++
        },
        onClear = {
            store.apiKey = null
            editingKey = false
            version++
        },
    )
}

@Composable
private fun ApiKeyDialog(hasKey: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit, onClear: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val valid = text.trim().startsWith("sk-ant-")
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(4.dp),
        containerColor = Ink.White,
        title = { Text("Anthropic API key", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "到 console.anthropic.com 建立 API key 後貼上。key 只存在這支手機，用 Android Keystore 加密。請不要貼到聊天室或其他地方。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("sk-ant-…", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    shape = RoundedCornerShape(2.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Ink.Black, unfocusedBorderColor = Ink.Rule, cursorColor = Ink.Black,
                    ),
                )
                if (text.isNotBlank() && !valid) Text("格式不對：API key 應以 sk-ant- 開頭。", style = MaterialTheme.typography.bodySmall)
                if (hasKey) PlainAction("清除已存的 key", onClear)
            }
        },
        confirmButton = { PlainAction("儲存") { if (valid) onSave(text.trim()) } },
        dismissButton = { PlainAction("取消", onDismiss) },
    )
}
