package com.dailyapp.ai

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.dailyapp.core.AiAction
import com.dailyapp.core.AiActionType
import com.dailyapp.core.AiGroup
import com.dailyapp.core.AiMessage
import com.dailyapp.core.AiPlan
import com.dailyapp.core.AiRole
import com.dailyapp.core.AiTodo
import com.dailyapp.core.Pseudonymizer
import com.dailyapp.core.Redactor
import com.dailyapp.core.parseNames
import com.dailyapp.data.AiStore
import com.dailyapp.data.AppDatabase
import com.dailyapp.data.Priority
import com.dailyapp.data.RawMessage
import com.dailyapp.data.Todo
import com.dailyapp.data.TodoKind
import com.dailyapp.data.TodoStatus
import com.dailyapp.notify.Alerts
import com.dailyapp.notify.Reminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 每小時 AI 整理（spec 4.2）：
 * 讀佇列 → 遮蔽個資、換代稱 → 呼叫 AI → 換回真名 → 寫入待辦 → 標記訊息已處理。
 * 失敗時訊息留在佇列（processedAt 仍為 null），下一輪重送。
 */
object AiOrganizer {
    private const val TAG = "AiOrganizer"
    /** 一輪最多送幾批，避免積了很久的訊息一次燒太多錢。 */
    private const val MAX_CALLS_PER_RUN = 5
    private val mutex = Mutex()
    private val timeFmt = DateTimeFormatter.ofPattern("MM/dd HH:mm")

    data class Outcome(val created: List<Todo>, val updated: Int, val maybeDone: Int, val processed: Int, val error: String?) {
        fun summary(): String = when {
            error != null && processed == 0 -> error
            processed == 0 -> "沒有新訊息"
            else -> buildString {
                append("整理 $processed 則訊息")
                val parts = listOfNotNull(
                    created.size.takeIf { it > 0 }?.let { "新增 $it" },
                    updated.takeIf { it > 0 }?.let { "更新 $it" },
                    maybeDone.takeIf { it > 0 }?.let { "疑似完成 $it" },
                )
                append(if (parts.isEmpty()) "，沒有跟你有關的事" else "：" + parts.joinToString("、"))
                if (error != null) append("；之後失敗：$error")
            }
        }
    }

    suspend fun run(ctx: Context, now: () -> Long = System::currentTimeMillis): Outcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            val store = AiStore(ctx)
            val key = store.apiKey
            val outcome = if (key == null) Outcome(emptyList(), 0, 0, 0, "尚未設定 API key")
            else AiClient(key).use { client -> runBatches(ctx, client, store, now) }
            store.recordRun(now(), outcome.summary())
            if (outcome.created.isNotEmpty()) Alerts.aiCreated(ctx, outcome.created)
            outcome
        }
    }

    private suspend fun runBatches(ctx: Context, client: AiClient, store: AiStore, now: () -> Long): Outcome {
        val db = AppDatabase.get(ctx)
        val created = mutableListOf<Todo>()
        var updated = 0
        var maybeDone = 0
        var processed = 0
        repeat(MAX_CALLS_PER_RUN) {
            val msgs = db.messages().unprocessed(AiPlan.MAX_MESSAGES_PER_CALL)
            if (msgs.isEmpty()) return Outcome(created, updated, maybeDone, processed, null)
            val batch = prepare(db, msgs)
            when (val r = client.call(AiPlan.SYSTEM, batch.input, AiPlan.SCHEMA)) {
                is AiCall.Ok -> {
                    store.addUsage(r.inputTokens, r.outputTokens)
                    val actions = runCatching {
                        AiPlan.parse(r.json, batch.openTodos.keys, batch.messages.keys)
                    }.getOrElse {
                        Log.w(TAG, "無法解析 AI 回傳", it)
                        emptyList()
                    }
                    val res = apply(ctx, db, batch, actions, now())
                    created += res.created
                    updated += res.updated
                    maybeDone += res.maybeDone
                    processed += msgs.size
                }
                is AiCall.Retry -> return Outcome(created, updated, maybeDone, processed, r.reason)
                is AiCall.Fail -> {
                    store.addUsage(r.inputTokens, r.outputTokens)
                    // AI 拒答或截斷時，同一批重送結果也一樣，標記已處理免得每小時重複花錢。
                    // API key 等設定問題則保留訊息，等使用者修好後再送。
                    if (r.inputTokens > 0) db.messages().markProcessed(msgs.map { it.id }, now())
                    return Outcome(created, updated, maybeDone, processed, r.reason)
                }
            }
        }
        return Outcome(created, updated, maybeDone, processed, null)
    }

    private class Batch(
        val input: String,
        val messages: Map<Long, RawMessage>,
        val openTodos: Map<Long, Todo>,
        val groupKeys: Map<String, String>, // 代號 → groupId
        val pseudonymizer: Pseudonymizer,
    )

    private suspend fun prepare(db: AppDatabase, msgs: List<RawMessage>): Batch {
        val groups = db.groups().enabled()
        val roles = db.roles().all().associateBy { it.id }
        val links = db.roles().links().groupBy({ it.groupId }, { it.roleId })
        val todos = db.todos().openTodos().filter { it.status != TodoStatus.DONE }

        val keyOf = groups.mapIndexed { i, g -> g.id to "G${i + 1}" }.toMap()
        val selfNames = groups.flatMap { parseNames(it.displayNames) + listOfNotNull(it.observedSelfName) }

        // 先登記所有人名，再替換文字，這樣訊息裡提到的交辦人也會被換掉
        val p = Pseudonymizer(selfNames)
        msgs.forEach { p.alias(it.sender) }
        todos.forEach { t -> t.requester?.let { p.alias(it) } }
        fun scrub(s: String) = p.apply(Redactor.redact(s))

        val aiGroups = groups.map { g ->
            AiGroup(
                key = keyOf.getValue(g.id),
                title = g.title,
                platform = g.platform,
                myNames = (parseNames(g.displayNames) + listOfNotNull(g.observedSelfName)).distinct(),
                roles = links[g.id].orEmpty().mapNotNull { roles[it] }
                    .map { AiRole(it.name, it.description, parseNames(it.keywords)) },
                extraKeywords = parseNames(g.extraKeywords),
            )
        }
        val aiTodos = todos.map { t ->
            AiTodo(
                id = t.id,
                kind = if (t.kind == TodoKind.WATCH) "watch" else "todo",
                status = when (t.status) {
                    TodoStatus.DRAFT -> "draft"
                    TodoStatus.MAYBE_DONE -> "maybe_done"
                    else -> "active"
                },
                title = scrub(t.title),
                due = t.dueAt?.let { AiPlan.fmtDue(LocalDateTime.ofInstant(Instant.ofEpochMilli(it), Reminders.zone)) },
                group = t.groupId?.let { keyOf[it] },
                requester = t.requester?.let { p.alias(it) },
                messageIds = t.sourceMessageIds.split(',').mapNotNull { it.trim().toLongOrNull() },
            )
        }
        val aiMsgs = msgs.map { m ->
            AiMessage(
                id = m.id,
                group = keyOf[m.groupId] ?: "?",
                sender = p.alias(m.sender),
                time = LocalDateTime.ofInstant(Instant.ofEpochMilli(m.postedAt), Reminders.zone).format(timeFmt),
                text = scrub(m.text) + if (m.hasAttachment) "（附件）" else "",
            )
        }
        val input = AiPlan.buildInput(LocalDateTime.now(Reminders.zone), aiGroups, aiTodos, aiMsgs)
        return Batch(input, msgs.associateBy { it.id }, todos.associateBy { it.id }, keyOf.entries.associate { (k, v) -> v to k }, p)
    }

    private class Applied(val created: List<Todo>, val updated: Int, val maybeDone: Int)

    private suspend fun apply(ctx: Context, db: AppDatabase, b: Batch, actions: List<AiAction>, now: Long): Applied {
        val p = b.pseudonymizer
        val created = mutableListOf<Todo>()
        val touched = mutableListOf<Todo>()
        var maybeDone = 0
        fun excerptOf(ids: List<Long>) = ids.mapNotNull { b.messages[it] }.joinToString("\n") { "${it.sender}：${it.text}" }
        fun dueOf(a: AiAction) = a.due?.atZone(Reminders.zone)?.toInstant()?.toEpochMilli()
        fun priorityOf(a: AiAction, fallback: Priority) = when (a.priority) {
            "high" -> Priority.HIGH
            "low" -> Priority.LOW
            "medium" -> Priority.MEDIUM
            else -> fallback
        }

        db.withTransaction {
            for (a in actions) when (a.type) {
                AiActionType.NEW_TODO, AiActionType.NEW_WATCH -> {
                    val src = a.messageIds.mapNotNull { b.messages[it] }
                    val t = Todo(
                        kind = if (a.type == AiActionType.NEW_WATCH) TodoKind.WATCH else TodoKind.TODO,
                        title = p.restore(a.title!!),
                        groupId = src.firstOrNull()?.groupId,
                        requester = a.requester?.let { p.restore(it) },
                        dueAt = dueOf(a),
                        priority = priorityOf(a, Priority.MEDIUM),
                        status = TodoStatus.ACTIVE,
                        excerpt = excerptOf(a.messageIds),
                        sourceMessageIds = a.messageIds.joinToString(","),
                        hasAttachment = src.any { it.hasAttachment },
                        createdAt = now,
                        updatedAt = now,
                        aiGenerated = true,
                    )
                    created += t.copy(id = db.todos().insert(t))
                }
                AiActionType.UPDATE -> {
                    val old = b.openTodos.getValue(a.todoId!!)
                    val newIds = a.messageIds.filter { it.toString() !in old.sourceMessageIds.split(',') }
                    val due = dueOf(a) ?: old.dueAt
                    val t = old.copy(
                        title = a.title?.let { p.restore(it) } ?: old.title,
                        requester = a.requester?.let { p.restore(it) } ?: old.requester,
                        dueAt = due,
                        snoozedUntil = if (due != old.dueAt) null else old.snoozedUntil,
                        priority = priorityOf(a, old.priority),
                        status = if (old.status == TodoStatus.DONE) old.status else TodoStatus.ACTIVE,
                        excerpt = listOf(old.excerpt, excerptOf(newIds)).filter { it.isNotBlank() }.joinToString("\n"),
                        sourceMessageIds = (old.sourceMessageIds.split(',').filter { it.isNotBlank() } + newIds.map { it.toString() })
                            .joinToString(","),
                        updatedAt = now,
                        aiGenerated = true,
                    )
                    db.todos().update(t)
                    touched += t
                }
                AiActionType.MAYBE_DONE -> {
                    db.todos().setStatus(a.todoId!!, TodoStatus.MAYBE_DONE, now)
                    maybeDone++
                }
            }
            db.messages().markProcessed(b.messages.keys.toList(), now)
        }
        (created + touched).forEach { Reminders.scheduleTodo(ctx, it) }
        Log.i(TAG, "AI 整理：${b.messages.size} 則訊息，新增 ${created.size}、更新 ${touched.size}、疑似完成 $maybeDone")
        return Applied(created, touched.size, maybeDone)
    }
}
