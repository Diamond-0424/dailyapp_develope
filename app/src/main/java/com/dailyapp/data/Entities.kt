package com.dailyapp.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
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
    /** 群組專屬的附加關鍵字，逗號分隔。只作為 AI 的提示，不用來過濾訊息。 */
    @ColumnInfo(defaultValue = "") val extraKeywords: String = "",
)

/** 身分設定檔，例如「美宣」。與群組多對多（spec 3.2）。 */
@Entity(tableName = "roles")
data class Role(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 職責描述，給 AI 判斷訊息與自己是否有關。 */
    val description: String,
    /** 關鍵字，逗號分隔。只作為 AI 的提示。 */
    val keywords: String,
)

@Entity(
    tableName = "group_roles",
    primaryKeys = ["groupId", "roleId"],
    foreignKeys = [
        ForeignKey(entity = ChatGroup::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Role::class, parentColumns = ["id"], childColumns = ["roleId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("roleId")],
)
data class GroupRole(val groupId: String, val roleId: Long)

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
    /** 標題或內容由 AI 整理產生，介面上顯示「AI」標籤。 */
    @ColumnInfo(defaultValue = "0") val aiGenerated: Boolean = false,
)

data class TodoRow(
    @Embedded val todo: Todo,
    val groupTitle: String?,
    val platform: String?,
)
