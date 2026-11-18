package com.dailyapp.core

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class AiPlanTest {
    private fun parse(json: String, todos: Set<Long> = setOf(5L), msgs: Set<Long> = setOf(100L, 101L)) =
        AiPlan.parse(json, todos, msgs)

    @Test fun parsesNewTodoWithDue() {
        val a = parse(
            """{"actions":[{"type":"new_todo","todo_id":null,"title":" 修改海報 ","requester":"成員A",
               "due":"2026-11-21 18:00","priority":"HIGH","message_ids":[100,999]}]}"""
        ).single()
        assertEquals(AiActionType.NEW_TODO, a.type)
        assertEquals("修改海報", a.title)
        assertEquals(LocalDateTime.of(2026, 11, 21, 18, 0), a.due)
        assertEquals("high", a.priority)
        assertEquals(listOf(100L), a.messageIds) // 999 不在這批，丟掉
    }

    @Test fun dropsUpdateForUnknownTodo() {
        val out = parse(
            """{"actions":[
              {"type":"update","todo_id":7,"title":"x","requester":null,"due":null,"priority":null,"message_ids":[]},
              {"type":"maybe_done","todo_id":5,"title":null,"requester":null,"due":null,"priority":null,"message_ids":[101]}
            ]}"""
        )
        assertEquals(1, out.size)
        assertEquals(AiActionType.MAYBE_DONE, out[0].type)
        assertEquals(5L, out[0].todoId)
    }

    @Test fun onlyFirstActionPerTodoKept() {
        val out = parse(
            """{"actions":[
              {"type":"update","todo_id":5,"title":"a","requester":null,"due":null,"priority":"low","message_ids":[]},
              {"type":"update","todo_id":5,"title":"b","requester":null,"due":null,"priority":"low","message_ids":[]}
            ]}"""
        )
        assertEquals(listOf("a"), out.map { it.title })
    }

    @Test fun dropsNewWithoutTitleAndUnknownType() {
        val out = parse(
            """{"actions":[
              {"type":"new_watch","todo_id":null,"title":"  ","requester":null,"due":null,"priority":null,"message_ids":[]},
              {"type":"irrelevant","todo_id":null,"title":"x","requester":null,"due":null,"priority":null,"message_ids":[]}
            ]}"""
        )
        assertTrue(out.isEmpty())
    }

    @Test fun badDueBecomesNull() {
        val a = parse(
            """{"actions":[{"type":"new_todo","todo_id":3,"title":"t","requester":null,"due":"下週五","priority":"urgent","message_ids":[]}]}"""
        ).single()
        assertNull(a.due)
        assertNull(a.priority)
        assertNull(a.todoId) // 新增的不帶 todo_id
    }

    @Test fun parseDueAcceptsIsoT() {
        assertEquals(LocalDateTime.of(2026, 1, 2, 9, 30), AiPlan.parseDue("2026-01-02T09:30"))
    }

    @Test fun inputIsValidJsonWithAllSections() {
        val json = AiPlan.buildInput(
            LocalDateTime.of(2026, 11, 19, 10, 0),
            listOf(AiGroup("G1", "社團幹部", "line", listOf("小美"), listOf(AiRole("美宣", "海報", listOf("海報"))), emptyList())),
            listOf(AiTodo(5, "todo", "draft", "@小美 海報", null, "G1", "成員A", listOf(100))),
            listOf(AiMessage(100, "G1", "成員A", "11/19 09:00", "@小美 海報週五前")),
        )
        val n = ObjectMapper().readTree(json)
        assertEquals("2026-11-19 10:00（星期四）", n["now"].asText())
        assertEquals("美宣", n["groups"][0]["roles"][0]["name"].asText())
        assertEquals("draft", n["open_todos"][0]["status"].asText())
        assertEquals(100, n["messages"][0]["id"].asInt())
    }

    @Test fun shareParse() {
        val (title, due) = AiShare.parse("""{"title":"交海報","due":"2026-11-20 18:00"}""")!!
        assertEquals("交海報", title)
        assertEquals(LocalDateTime.of(2026, 11, 20, 18, 0), due)
        assertNull(AiShare.parse("""{"title":"","due":null}"""))
    }

    @Test fun keywordsDedupAgainstExisting() {
        assertEquals(
            listOf("主視覺", "KV"),
            AiKeywords.parse("""{"keywords":["海報","主視覺","KV","kv",""]}""", listOf("海報")),
        )
    }
}
