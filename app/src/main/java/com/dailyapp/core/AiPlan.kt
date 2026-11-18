package com.dailyapp.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/*
 * 每小時 AI 整理（spec 4.2）的純邏輯部分：組提示詞、輸出格式、解析與驗證。
 * 不依賴 Android 與 SDK，方便單元測試。送出前的個資處理在 Privacy.kt。
 */

data class AiRole(val name: String, val description: String, val keywords: List<String>)

data class AiGroup(
    val key: String,              // 送給 AI 的群組代號，例如 G1
    val title: String,
    val platform: String,
    val myNames: List<String>,
    val roles: List<AiRole>,
    val extraKeywords: List<String>,
)

data class AiTodo(
    val id: Long,
    val kind: String,             // todo / watch
    val status: String,           // draft / active / maybe_done
    val title: String,
    val due: String?,
    val group: String?,           // 群組代號
    val requester: String?,       // 已換成代稱
    val messageIds: List<Long>,
)

data class AiMessage(
    val id: Long,
    val group: String,
    val sender: String,           // 已換成代稱
    val time: String,
    val text: String,             // 已遮蔽、已換代稱
)

enum class AiActionType(val wire: String) {
    NEW_TODO("new_todo"), NEW_WATCH("new_watch"), UPDATE("update"), MAYBE_DONE("maybe_done");

    companion object {
        fun fromWire(s: String?) = entries.firstOrNull { it.wire == s }
    }
}

data class AiAction(
    val type: AiActionType,
    val todoId: Long?,
    val title: String?,
    val requester: String?,
    val due: LocalDateTime?,
    val priority: String?,        // high / medium / low
    val messageIds: List<Long>,
)

object AiPlan {
    const val MODEL = "claude-haiku-5-5"
    const val MAX_MESSAGES_PER_CALL = 200

    private val mapper = ObjectMapper()
    private val dueFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun fmtDue(t: LocalDateTime): String = t.format(dueFormat)

    fun fmtNow(t: LocalDateTime): String =
        fmtDue(t) + "（星期" + t.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.TAIWAN) + "）"

    val SYSTEM = """
你是使用者的工作助理。使用者在多個工作群組中擔任不同身分，你要從群組的新訊息中，整理出跟他有關的事項。

輸入是 JSON：
- now：現在時間，用來推算「明天」「週五」等相對日期。
- groups：群組代號、名稱、使用者在該群組的名字（my_names）、身分（名稱、職責、關鍵字）、附加關鍵字。
- open_todos：使用者目前未完成的事項。status 為 draft 的是「被點名時直接存下的原始訊息」，還沒整理。
- messages：自上次整理以來的新訊息。

隱私處理：其他人的名字已換成「成員A」「成員B」等代稱；[連結：網域]、[Email]、[電話]、[號碼]、[身分證] 是遮蔽標記。代稱請照原樣使用，不要猜真名。

對每則訊息判斷，輸出 actions：
- new_todo：需要使用者本人去做的事。包括：被點名（@ 或叫他的名字）、屬於他身分職責範圍、或在群組中明確交給負責該職責的人。
- new_watch：跟使用者職責有關、他需要知道但不必親自做的事，例如決議、時程變更、別人負責但會影響他的事。
- update：訊息改變了既有事項（截止時間改了、需求變了），或要把 draft 改寫成精簡任務。填 todo_id，並給出更新後的 title、due、priority、requester（沒變的照抄原值）。
- maybe_done：訊息顯示某個既有事項可能已經完成（例如有人說「海報收到了，謝謝」）。填 todo_id，其他欄位填 null。
- 與使用者無關的訊息不要輸出。

規則：
- 同一件事只能有一個事項。已經在 open_todos 的事，用 update 合併，不要再新增。多則訊息講同一件事，合併成一個 action。
- draft 事項一定要用 update 改寫標題，除非它可能已完成。
- title：繁體中文，20 字以內，以動詞開頭，例如「修改迎新海報主視覺」。
- due：格式 yyyy-MM-dd HH:mm。只有日期沒有時間時用 18:00。沒提到期限就填 null，不要自己編。
- requester：交辦人的代稱或名字，不確定就填 null。
- priority：high（今明兩天要完成，或語氣急迫）、medium（一般）、low（有空再做）。
- message_ids：這個判斷依據的訊息 id。
- 寧可漏掉不確定的事，也不要把閒聊當成待辦。
""".trim()

    /** 輸出格式（structured outputs）。所有欄位都必填，可為 null 的欄位用 ["型別", "null"]。 */
    val SCHEMA: Map<String, Any> = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "required" to listOf("actions"),
        "properties" to mapOf(
            "actions" to mapOf(
                "type" to "array",
                "items" to mapOf(
                    "type" to "object",
                    "additionalProperties" to false,
                    "required" to listOf("type", "todo_id", "title", "requester", "due", "priority", "message_ids"),
                    "properties" to mapOf(
                        "type" to mapOf("type" to "string", "enum" to AiActionType.entries.map { it.wire }),
                        "todo_id" to mapOf("type" to listOf("integer", "null")),
                        "title" to mapOf("type" to listOf("string", "null")),
                        "requester" to mapOf("type" to listOf("string", "null")),
                        "due" to mapOf("type" to listOf("string", "null")),
                        "priority" to mapOf("type" to listOf("string", "null")),
                        "message_ids" to mapOf("type" to "array", "items" to mapOf("type" to "integer")),
                    ),
                ),
            ),
        ),
    )

    fun buildInput(now: LocalDateTime, groups: List<AiGroup>, todos: List<AiTodo>, messages: List<AiMessage>): String {
        val root = mapOf(
            "now" to fmtNow(now),
            "groups" to groups.map { g ->
                mapOf(
                    "key" to g.key, "title" to g.title, "platform" to g.platform,
                    "my_names" to g.myNames,
                    "roles" to g.roles.map { mapOf("name" to it.name, "description" to it.description, "keywords" to it.keywords) },
                    "extra_keywords" to g.extraKeywords,
                )
            },
            "open_todos" to todos.map { t ->
                mapOf(
                    "id" to t.id, "kind" to t.kind, "status" to t.status, "title" to t.title, "due" to t.due,
                    "group" to t.group, "requester" to t.requester, "message_ids" to t.messageIds,
                )
            },
            "messages" to messages.map { m ->
                mapOf("id" to m.id, "group" to m.group, "sender" to m.sender, "time" to m.time, "text" to m.text)
            },
        )
        return mapper.writeValueAsString(root)
    }

    /**
     * 解析並驗證 AI 回傳。不合規的 action 直接丟掉，不讓一個錯誤影響整批：
     * - update / maybe_done 的 todo_id 必須在 open_todos 裡
     * - new_* 必須有標題
     * - message_ids 只保留這批真的有的
     * - due 解析失敗視為沒有截止時間
     */
    fun parse(json: String, openTodoIds: Set<Long>, messageIds: Set<Long>): List<AiAction> {
        val root: JsonNode = mapper.readTree(json)
        val arr = root.get("actions") ?: return emptyList()
        val out = mutableListOf<AiAction>()
        val touched = mutableSetOf<Long>()
        for (n in arr) {
            val type = AiActionType.fromWire(n.textOrNull("type")) ?: continue
            val todoId = n.get("todo_id")?.takeIf { it.isIntegralNumber }?.asLong()
            val title = n.textOrNull("title")?.trim()?.takeIf { it.isNotEmpty() }
            val ids = n.get("message_ids")?.filter { it.isIntegralNumber }?.map { it.asLong() }
                ?.filter { it in messageIds }?.distinct().orEmpty()
            when (type) {
                AiActionType.UPDATE, AiActionType.MAYBE_DONE -> {
                    if (todoId == null || todoId !in openTodoIds || !touched.add(todoId)) continue
                }
                AiActionType.NEW_TODO, AiActionType.NEW_WATCH -> if (title == null) continue
            }
            out += AiAction(
                type = type,
                todoId = if (type == AiActionType.NEW_TODO || type == AiActionType.NEW_WATCH) null else todoId,
                title = title,
                requester = n.textOrNull("requester")?.trim()?.takeIf { it.isNotEmpty() },
                due = n.textOrNull("due")?.let { parseDue(it) },
                priority = n.textOrNull("priority")?.lowercase()?.takeIf { it in setOf("high", "medium", "low") },
                messageIds = ids,
            )
        }
        return out
    }

    fun parseDue(s: String): LocalDateTime? =
        runCatching { LocalDateTime.parse(s.trim().replace('T', ' '), dueFormat) }.getOrNull()

    private fun JsonNode.textOrNull(field: String): String? =
        get(field)?.takeIf { it.isTextual }?.asText()
}

/** 分享進來的單則文字：AI 改寫成標題與截止時間。 */
object AiShare {
    val SYSTEM = """
把使用者分享進來的一段聊天訊息，整理成一筆待辦。
- title：繁體中文，20 字以內，以動詞開頭。
- due：格式 yyyy-MM-dd HH:mm，依 now 推算相對日期；只有日期沒有時間用 18:00；沒提到期限填 null。
訊息中的 [連結：網域]、[電話] 等是遮蔽標記。
""".trim()

    val SCHEMA: Map<String, Any> = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "required" to listOf("title", "due"),
        "properties" to mapOf(
            "title" to mapOf("type" to "string"),
            "due" to mapOf("type" to listOf("string", "null")),
        ),
    )

    fun buildInput(now: LocalDateTime, text: String): String =
        ObjectMapper().writeValueAsString(mapOf("now" to AiPlan.fmtNow(now), "message" to text))

    fun parse(json: String): Pair<String, LocalDateTime?>? {
        val n = ObjectMapper().readTree(json)
        val title = n.get("title")?.takeIf { it.isTextual }?.asText()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val due = n.get("due")?.takeIf { it.isTextual }?.asText()?.let { AiPlan.parseDue(it) }
        return title to due
    }
}

/** 新增身分時，AI 建議同義詞與相關關鍵字（spec 3.2）。 */
object AiKeywords {
    val SYSTEM = """
使用者在工作群組中擔任某個身分。根據身分名稱與職責，建議 8 到 12 個群組對話中可能出現、代表「這件事跟這個身分有關」的關鍵字或同義詞。
繁體中文為主，也可包含常見英文或縮寫（例如 KV、IG）。每個關鍵字 1 到 6 個字，不要重複已有的關鍵字。
""".trim()

    val SCHEMA: Map<String, Any> = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "required" to listOf("keywords"),
        "properties" to mapOf("keywords" to mapOf("type" to "array", "items" to mapOf("type" to "string"))),
    )

    fun buildInput(name: String, description: String, existing: List<String>): String =
        ObjectMapper().writeValueAsString(mapOf("name" to name, "description" to description, "existing_keywords" to existing))

    fun parse(json: String, existing: List<String>): List<String> {
        val have = existing.map { it.lowercase() }.toSet()
        return ObjectMapper().readTree(json).get("keywords")?.filter { it.isTextual }?.map { it.asText().trim() }
            ?.filter { it.isNotEmpty() && it.length <= 12 && it.lowercase() !in have }
            ?.distinctBy { it.lowercase() }.orEmpty()
    }
}
