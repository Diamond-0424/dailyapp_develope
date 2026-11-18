package com.dailyapp.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailyapp.core.Platform
import com.dailyapp.data.AppDatabase
import com.dailyapp.data.TodoKind
import com.dailyapp.data.TodoRow
import com.dailyapp.data.TodoStatus
import kotlinx.coroutines.launch

@Composable
fun TodoScreen(db: AppDatabase) {
    var kindIdx by remember { mutableIntStateOf(0) }
    val kind = if (kindIdx == 0) TodoKind.TODO else TodoKind.WATCH
    val rows by remember(kind) { db.todos().observeOpen(kind) }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            PageHeader("待辦", todayLabel())
            Row(
                Modifier.padding(horizontal = Space.Page).padding(top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Toggle("我的待辦", kindIdx == 0) { kindIdx = 0 }
                Toggle("關注", kindIdx == 1) { kindIdx = 1 }
            }
            Rule(Modifier.padding(top = 12.dp))
        }
        if (rows.isEmpty()) item {
            Text(
                if (kind == TodoKind.TODO) "目前沒有待辦。\n在已監聽的群組中被 @ 或被叫名字時，會自動出現在這裡。"
                else "關注事項會在 AI 整理功能上線後出現。",
                style = MaterialTheme.typography.bodyMedium.copy(color = Ink.Secondary),
                modifier = Modifier.padding(Space.Page),
            )
        }
        items(rows, key = { it.todo.id }) { row ->
            TodoItem(
                row,
                onDone = { scope.launch { db.todos().setStatus(row.todo.id, TodoStatus.DONE, System.currentTimeMillis()) } },
                onDelete = { scope.launch { db.todos().delete(row.todo.id) } },
                onOpenApp = { openPlatformApp(ctx, row.platform) },
            )
            Rule()
        }
    }
}

@Composable
private fun Toggle(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight = if (on) FontWeight(700) else FontWeight(400),
            color = if (on) Ink.Black else Ink.Secondary,
        ),
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 6.dp),
    )
}

@Composable
private fun TodoItem(row: TodoRow, onDone: () -> Unit, onDelete: () -> Unit, onOpenApp: () -> Unit) {
    val t = row.todo
    val label = Platform.fromId(row.platform)?.label ?: row.platform
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.Page, vertical = Space.Item),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(t.title, style = MaterialTheme.typography.titleMedium)
        Text(
            "$label　${row.groupTitle}　${t.requester ?: ""}　${fmtTime(t.createdAt)}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(t.excerpt, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (t.status == TodoStatus.DRAFT) Tag("草稿")
            if (t.hasAttachment) Tag("含附件")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            PlainAction("完成", onDone)
            PlainAction("開啟 $label", onOpenApp)
            PlainAction("刪除", onDelete)
        }
    }
}

fun openPlatformApp(ctx: Context, platformId: String) {
    val pl = Platform.fromId(platformId) ?: return
    val intent = pl.packages.firstNotNullOfOrNull { ctx.packageManager.getLaunchIntentForPackage(it) } ?: return
    ctx.startActivity(intent)
}

fun fmtTime(ts: Long?): String =
    if (ts == null || ts == 0L) "—"
    else java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.TAIWAN).format(java.util.Date(ts))

private fun todayLabel(): String =
    java.text.SimpleDateFormat("yyyy 年 M 月 d 日　EEEE", java.util.Locale.TAIWAN).format(java.util.Date())
