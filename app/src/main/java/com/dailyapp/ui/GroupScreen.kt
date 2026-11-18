package com.dailyapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailyapp.core.Platform
import com.dailyapp.core.parseNames
import com.dailyapp.data.AppDatabase
import com.dailyapp.data.ChatGroup
import com.dailyapp.data.GroupState
import kotlinx.coroutines.launch

@Composable
fun GroupScreen(db: AppDatabase) {
    val groups by remember { db.groups().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ChatGroup?>(null) }

    fun save(g: ChatGroup) = scope.launch { db.groups().update(g) }

    val sections = listOf(
        "新發現" to groups.filter { it.state == GroupState.NEW },
        "監聽中" to groups.filter { it.state == GroupState.ENABLED },
        "不監聽" to groups.filter { it.state == GroupState.IGNORED },
    )

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            PageHeader("群組", "收到通知的對話會列在這裡，預設不監聽。")
        }
        if (groups.isEmpty()) item {
            Text(
                "尚未發現任何對話。請先到「狀態」確認權限都已開啟。",
                style = MaterialTheme.typography.bodyMedium.copy(color = Ink.Secondary),
                modifier = Modifier.padding(Space.Page),
            )
        }
        for ((label, list) in sections) {
            if (list.isEmpty()) continue
            item(key = "h:$label") {
                SectionLabel("$label　${list.size}")
                Rule()
            }
            items(list, key = { it.id }) { g ->
                GroupItem(
                    g,
                    onEnable = {
                        val names = g.displayNames.ifBlank { g.observedSelfName.orEmpty() }
                        save(g.copy(state = GroupState.ENABLED, displayNames = names))
                    },
                    onIgnore = { save(g.copy(state = GroupState.IGNORED)) },
                    onEditNames = { editing = g },
                )
                Rule()
            }
        }
    }

    editing?.let { g ->
        var text by remember(g.id) { mutableStateOf(g.displayNames.ifBlank { g.observedSelfName.orEmpty() }) }
        AlertDialog(
            onDismissRequest = { editing = null },
            shape = RoundedCornerShape(4.dp),
            containerColor = Ink.White,
            title = { Text("我在此群組的名稱", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(g.title, style = MaterialTheme.typography.bodyMedium)
                    Text("多個名稱以逗號分隔，例如：小美, Mei, 美宣", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Discord 被 @ 時顯示的是「顯示名稱」或伺服器暱稱，不是帳號名稱，請一併填入。花體或上標字母直接輸入一般英文即可。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        textStyle = MaterialTheme.typography.bodyLarge,
                        shape = RoundedCornerShape(2.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Ink.Black, unfocusedBorderColor = Ink.Rule, cursorColor = Ink.Black,
                        ),
                    )
                }
            },
            confirmButton = {
                PlainAction("儲存") {
                    save(g.copy(displayNames = parseNames(text).joinToString(", ")))
                    editing = null
                }
            },
            dismissButton = { PlainAction("取消") { editing = null } },
        )
    }
}

@Composable
private fun GroupItem(g: ChatGroup, onEnable: () -> Unit, onIgnore: () -> Unit, onEditNames: () -> Unit) {
    val label = Platform.fromId(g.platform)?.label ?: g.platform
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.Page, vertical = Space.Item),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(g.title, style = MaterialTheme.typography.titleMedium)
        Text("$label　${if (g.isGroup) "群組" else "私訊"}　最後訊息 ${fmtTime(g.lastSeenAt)}", style = MaterialTheme.typography.bodySmall)
        if (g.state == GroupState.ENABLED) {
            Text(
                "我的名稱：" + g.displayNames.ifBlank { "未設定（無法偵測點名）" },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (g.state != GroupState.ENABLED) PlainAction("開始監聽", onEnable)
            if (g.state == GroupState.ENABLED) PlainAction("設定名稱", onEditNames)
            if (g.state != GroupState.IGNORED) PlainAction("不監聽", onIgnore)
        }
    }
}
