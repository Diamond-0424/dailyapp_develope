package com.dailyapp.data

import androidx.room.withTransaction
import com.dailyapp.core.MentionDetector
import com.dailyapp.core.ParsedConversation
import com.dailyapp.core.messageHash
import com.dailyapp.core.parseNames
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 一則通知處理後，需要由服務層發出的提醒。 */
sealed interface IngestEvent {
    data class NewGroup(val group: ChatGroup) : IngestEvent
    data class Mentioned(val group: ChatGroup, val todoId: Long, val sender: String, val text: String) : IngestEvent
}

class Repository(private val db: AppDatabase) {
    private val mutex = Mutex()

    suspend fun ingest(conv: ParsedConversation, now: Long = System.currentTimeMillis()): List<IngestEvent> =
        mutex.withLock {
            db.withTransaction {
                val events = mutableListOf<IngestEvent>()
                val group = upsertGroup(conv, now, events)
                if (group.state != GroupState.ENABLED) return@withTransaction events

                // 使用者未設定顯示名稱時，先用通知中觀察到的本人名稱
                val names = parseNames(group.displayNames).ifEmpty { listOfNotNull(group.observedSelfName) }

                for (m in conv.messages) {
                    if (m.isSelf || m.text.isBlank()) continue
                    val id = db.messages().insert(
                        RawMessage(
                            groupId = group.id,
                            sender = m.sender!!,
                            senderKey = m.senderKey,
                            text = m.text,
                            hasAttachment = m.hasAttachment,
                            postedAt = m.time,
                            hash = messageHash(group.id, m),
                        )
                    )
                    if (id == -1L) continue // 已經處理過的舊訊息
                    if (!MentionDetector.isMentioned(m.text, names)) continue

                    val todoId = db.todos().insert(
                        Todo(
                            kind = TodoKind.TODO,
                            title = m.text.lineSequence().first().take(60),
                            groupId = group.id,
                            requester = m.sender,
                            dueAt = null,
                            priority = Priority.MEDIUM,
                            status = TodoStatus.DRAFT,
                            excerpt = "${m.sender}：${m.text}",
                            sourceMessageIds = id.toString(),
                            hasAttachment = m.hasAttachment,
                            createdAt = now,
                            updatedAt = now,
                        )
                    )
                    events += IngestEvent.Mentioned(group, todoId, m.sender, m.text)
                }
                events
            }
        }

    private suspend fun upsertGroup(conv: ParsedConversation, now: Long, events: MutableList<IngestEvent>): ChatGroup {
        val existing = db.groups().get(conv.groupId)
        if (existing == null) {
            val g = ChatGroup(
                id = conv.groupId,
                platform = conv.platform.id,
                title = conv.title,
                isGroup = conv.isGroup,
                state = GroupState.NEW, // 新群組預設不監聽
                displayNames = "",
                observedSelfName = conv.selfName,
                firstSeenAt = now,
                lastSeenAt = now,
            )
            db.groups().insert(g)
            events += IngestEvent.NewGroup(g)
            return g
        }
        val updated = existing.copy(
            title = conv.title,
            lastSeenAt = now,
            observedSelfName = conv.selfName ?: existing.observedSelfName,
        )
        db.groups().update(updated)
        return updated
    }

    suspend fun cleanupOldMessages(now: Long = System.currentTimeMillis()) =
        db.messages().deleteOlderThan(now - RETENTION_MS)

    companion object {
        const val RETENTION_MS = 30 * HealthStore.DAY
    }
}
