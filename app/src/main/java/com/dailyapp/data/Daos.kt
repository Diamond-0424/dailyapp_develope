package com.dailyapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Query("SELECT * FROM chat_groups WHERE id = :id")
    suspend fun get(id: String): ChatGroup?

    @Insert
    suspend fun insert(g: ChatGroup)

    @Update
    suspend fun update(g: ChatGroup)

    @Query("UPDATE chat_groups SET extraKeywords = :keywords WHERE id = :id")
    suspend fun setExtraKeywords(id: String, keywords: String)

    @Query(
        "SELECT * FROM chat_groups ORDER BY " +
            "CASE state WHEN 'NEW' THEN 0 WHEN 'ENABLED' THEN 1 ELSE 2 END, lastSeenAt DESC"
    )
    fun observeAll(): Flow<List<ChatGroup>>

    @Query("SELECT COUNT(*) FROM chat_groups WHERE state = 'NEW'")
    fun observeNewCount(): Flow<Int>

    @Query("SELECT * FROM chat_groups WHERE state = 'ENABLED'")
    suspend fun enabled(): List<ChatGroup>
}

@Dao
interface MessageDao {
    /** 重複的訊息（hash 相同）會被忽略，回傳 -1。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(m: RawMessage): Long

    @Query("DELETE FROM raw_messages WHERE postedAt < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("SELECT COUNT(*) FROM raw_messages")
    fun observeCount(): Flow<Int>

    /** 待 AI 整理的訊息：只取目前仍在監聽的群組。 */
    @Query(
        "SELECT m.* FROM raw_messages m JOIN chat_groups g ON g.id = m.groupId " +
            "WHERE m.processedAt IS NULL AND g.state = 'ENABLED' ORDER BY m.postedAt LIMIT :limit"
    )
    suspend fun unprocessed(limit: Int): List<RawMessage>

    @Query(
        "SELECT COUNT(*) FROM raw_messages m JOIN chat_groups g ON g.id = m.groupId " +
            "WHERE m.processedAt IS NULL AND g.state = 'ENABLED'"
    )
    fun observeUnprocessedCount(): Flow<Int>

    @Query("UPDATE raw_messages SET processedAt = :now WHERE id IN (:ids)")
    suspend fun markProcessed(ids: List<Long>, now: Long)
}

@Dao
interface TodoDao {
    @Insert
    suspend fun insert(t: Todo): Long

    @Update
    suspend fun update(t: Todo)

    @Query("SELECT * FROM todos WHERE id = :id")
    suspend fun get(id: Long): Todo?

    @Query("SELECT * FROM todos WHERE status != 'DONE'")
    suspend fun openTodos(): List<Todo>

    @Query("UPDATE todos SET snoozedUntil = :until, updatedAt = :now WHERE id = :id")
    suspend fun snooze(id: Long, until: Long, now: Long)

    /** 每日摘要用：未完成的待辦（不含關注），依截止日排序。 */
    @Query(
        "SELECT * FROM todos WHERE status != 'DONE' AND kind = 'TODO' " +
            "ORDER BY dueAt IS NULL, dueAt, createdAt DESC"
    )
    suspend fun openForDigest(): List<Todo>

    @Query("UPDATE todos SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: Long, status: TodoStatus, now: Long)

    @Query("DELETE FROM todos WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        "SELECT t.*, g.title AS groupTitle, g.platform AS platform FROM todos t " +
            "LEFT JOIN chat_groups g ON g.id = t.groupId " +
            "WHERE t.status != 'DONE' AND t.kind = :kind " +
            "ORDER BY t.dueAt IS NULL, t.dueAt, t.createdAt DESC"
    )
    fun observeOpen(kind: TodoKind): Flow<List<TodoRow>>
}

@Dao
interface RoleDao {
    @Query("SELECT * FROM roles ORDER BY name")
    fun observeAll(): Flow<List<Role>>

    @Query("SELECT * FROM roles ORDER BY name")
    suspend fun all(): List<Role>

    @Insert
    suspend fun insert(r: Role): Long

    @Update
    suspend fun update(r: Role)

    @Delete
    suspend fun delete(r: Role)

    @Query("SELECT * FROM group_roles")
    fun observeLinks(): Flow<List<GroupRole>>

    @Query("SELECT * FROM group_roles")
    suspend fun links(): List<GroupRole>

    @Query("SELECT r.* FROM roles r JOIN group_roles gr ON gr.roleId = r.id WHERE gr.groupId = :groupId ORDER BY r.name")
    suspend fun rolesOf(groupId: String): List<Role>

    @Query("DELETE FROM group_roles WHERE groupId = :groupId")
    suspend fun clearLinks(groupId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLinks(links: List<GroupRole>)

    @Transaction
    suspend fun setGroupRoles(groupId: String, roleIds: Collection<Long>) {
        clearLinks(groupId)
        insertLinks(roleIds.map { GroupRole(groupId, it) })
    }
}
