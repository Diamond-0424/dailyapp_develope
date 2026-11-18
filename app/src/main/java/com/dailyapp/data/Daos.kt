package com.dailyapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Query(
        "SELECT * FROM chat_groups ORDER BY " +
            "CASE state WHEN 'NEW' THEN 0 WHEN 'ENABLED' THEN 1 ELSE 2 END, lastSeenAt DESC"
    )
    fun observeAll(): Flow<List<ChatGroup>>

    @Query("SELECT COUNT(*) FROM chat_groups WHERE state = 'NEW'")
    fun observeNewCount(): Flow<Int>
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
