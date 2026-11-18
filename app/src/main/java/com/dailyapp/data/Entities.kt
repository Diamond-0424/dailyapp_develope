package com.dailyapp.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class GroupState { NEW, ENABLED, IGNORED }
enum class TodoKind { TODO, WATCH }
enum class TodoStatus { DRAFT, ACTIVE, MAYBE_DONE, DONE }
enum class Priority { HIGH, MEDIUM, LOW }

@Entity(tableName = "chat_groups")
data class ChatGroup(
    @PrimaryKey val id: String,          // "<platform>:<conversationKey>"
    val platform: String,
    val title: String,
    val isGroup: Boolean,
    val state: GroupState,
    /** 使用者在此群組的顯示名稱，逗號分隔。 */
    val displayNames: String,
    /** 通知中觀察到的本人名稱，用來建議 displayNames。 */
    val observedSelfName: String?,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
)

@Entity(
    tableName = "raw_messages",
    indices = [Index(value = ["hash"], unique = true), Index("groupId"), Index("postedAt")],
)
data class RawMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: String,
    val sender: String,
    val senderKey: String?,
    val text: String,
    val hasAttachment: Boolean,
    val postedAt: Long,
    val hash: String,
    /** M2 的 AI 整理完成後填入。 */
    val processedAt: Long? = null,
)

@Entity(tableName = "todos", indices = [Index("groupId"), Index("status")])
data class Todo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: TodoKind,
    val title: String,
    /** 來源群組；手動新增或分享進來的待辦為 null。 */
    val groupId: String?,
    val requester: String?,
    val dueAt: Long?,
    val priority: Priority,
    val status: TodoStatus,
    /** 原始訊息摘錄，保存在待辦裡，原始訊息過期刪除後仍可查看。 */
    val excerpt: String,
    val sourceMessageIds: String,
    val hasAttachment: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val snoozedUntil: Long? = null,
)

data class TodoRow(
    @Embedded val todo: Todo,
    val groupTitle: String?,
    val platform: String?,
)
