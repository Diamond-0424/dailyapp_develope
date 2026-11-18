package com.dailyapp.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailyapp.core.Platform
import com.dailyapp.core.ReminderPlanner
import com.dailyapp.data.AppDatabase
import com.dailyapp.data.Priority
import com.dailyapp.data.Todo
import com.dailyapp.data.TodoKind
import com.dailyapp.data.TodoRow
import com.dailyapp.data.TodoStatus
import com.dailyapp.notify.Reminders
import kotlinx.coroutines.launch

/** 編輯對話框的狀態：新增（可能帶分享文字）或編輯既有待辦。 */
private sealed interface Editing {
    data class New(val shared: String?) : Editing
    data class Existing(val todo: Todo) : Editing
}

@Composable
fun TodoScreen(db: AppDatabase, sharedText: String?, onSharedConsumed: () -> Unit) {
    var kindIdx by remember { mutableIntStateOf(0) }
    val kind = if (kindIdx == 0) TodoKind.TODO else TodoKind.WATCH
    val rows by remember(kind) { db.todos().observeOpen(kind) }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<Editing?>(null) }

    // 分享進來的文字：直接開啟新增對話框
    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            editing = Editing.New(sharedText)
            onSharedConsumed()
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            PageHeader("待辦", todayLabel())
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.Page).padding(top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Toggle("我的待辦", kindIdx == 0) { kindIdx = 0 }
                Toggle("關注", kindIdx == 1) { kindIdx = 1 }
                Spacer(Modifier.weight(1f))
                PlainAction("新增") { editing = Editing.New(null) }
            }
            Rule(Modifier.padding(top = 12.dp))
        }
        if (rows.isEmpty()) item {
            Text(
                if (kind == TodoKind.TODO) "目前沒有待辦。\n被 @ 或被叫名字時會自動出現；也可以按「新增」，或在 LINE、Discord 長按訊息分享進來。"
                else "關注事項會在 AI 整理功能上線後出現。",
                style = MaterialTheme.typography.bodyMedium.copy(color = Ink.Secondary),
                modifier = Modifier.padding(Space.Page),
            )
        }
        items(rows, key = { it.todo.id }) { row ->
            TodoItem(
                row,
                onEdit = { editing = Editing.Existing(row.todo) },
                onDone = {
                    scope.launch {
                        db.todos().setStatus(row.todo.id, TodoStatus.DONE, System.currentTimeMillis())
                        Reminders.cancelTodo(ctx, row.todo.id)
                    }
                },
                onDelete = {
                    scope.launch {
                        db.todos().delete(row.todo.id)
                        Reminders.cancelTodo(ctx, row.todo.id)
                    }
                },
                onOpenApp = { row.platform?.let { openPlatformApp(ctx, it) } },
            )
            Rule()
        }
    }

    when (val e = editing) {
        null -> Unit
        is Editing.New -> {
            val firstLine = e.shared?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(60).orEmpty()
            TodoEditDialog(
                heading = if (e.shared != null) "從分享加入待辦" else "新增待辦",
                initialTitle = firstLine,
                initialDueAt = null,
                excerpt = e.shared,
                onDismiss = { editing = null },
                onSave = { title, due ->
                    editing = null
                    scope.launch {
                        val now = System.currentTimeMillis()
                        val t = Todo(
                            kind = TodoKind.TODO, title = title, groupId = null, requester = null,
                            dueAt = due, priority = Priority.MEDIUM, status = TodoStatus.ACTIVE,
                            excerpt = e.shared?.trim().orEmpty(), sourceMessageIds = "", hasAttachment = false,
                            createdAt = now, updatedAt = now,
                        )
                        val id = db.todos().insert(t)
                        Reminders.scheduleTodo(ctx, t.copy(id = id))
                    }
                },
            )
        }
        is Editing.Existing -> {
            val t = e.todo
            TodoEditDialog(
                heading = "編輯待辦",
                initialTitle = t.title,
                initialDueAt = t.dueAt,
                excerpt = t.excerpt,
                onDismiss = { editing = null },
                onSave = { title, due ->
                    editing = null
                    scope.launch {
                        val updated = t.copy(
                            title = title,
                            dueAt = due,
                            // 改了截止時間，舊的延後就不再適用
                            snoozedUntil = if (due != t.dueAt) null else t.snoozedUntil,
                            // 使用者確認過的草稿轉為正式待辦
                            status = if (t.status == TodoStatus.DRAFT) TodoStatus.ACTIVE else t.status,
                            updatedAt = System.currentTimeMillis(),
                        )
                        db.todos().update(updated)
                        Reminders.scheduleTodo(ctx, updated)
                    }
                },
            )
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
private fun TodoItem(row: TodoRow, onEdit: () -> Unit, onDone: () -> Unit, onDelete: () -> Unit, onOpenApp: () -> Unit) {
    val t = row.todo
    val label = row.platform?.let { Platform.fromId(it)?.label ?: it }
    val now = System.currentTimeMillis()
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = Space.Page, vertical = Space.Item),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(t.title, style = MaterialTheme.typography.titleMedium)
        t.dueAt?.let { due ->
            val tag = ReminderPlanner.dueLabel(due, now, Reminders.zone)
            val urgent = ReminderPlanner.isUrgent(due, now, Reminders.zone)
            Text(
                "截止　" + fmtDue(due) + (tag?.let { "　$it" } ?: ""),
                style = if (urgent) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            if (label != null) "$label　${row.groupTitle.orEmpty()}　${t.requester.orEmpty()}　${fmtTime(t.createdAt)}"
            else "手動新增　${fmtTime(t.createdAt)}",
            style = MaterialTheme.typography.bodySmall,
        )
        if (t.excerpt.isNotBlank() && t.excerpt != t.title) {
            Text(t.excerpt, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (t.status == TodoStatus.DRAFT) Tag("草稿")
            if (t.hasAttachment) Tag("含附件")
            t.snoozedUntil?.takeIf { it > now }?.let { Tag("延後至 " + fmtTime(it)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            PlainAction("完成", onDone)
            if (label != null) PlainAction("開啟 $label", onOpenApp)
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
