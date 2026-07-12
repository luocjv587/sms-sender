package com.example.smssender.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingMessageDao {
    @Insert
    suspend fun insert(message: PendingMessage): Long

    @Query("SELECT * FROM pending_messages ORDER BY receivedAt ASC LIMIT 1")
    suspend fun next(): PendingMessage?

    @Query("SELECT * FROM pending_messages ORDER BY receivedAt ASC")
    fun observeAll(): Flow<List<PendingMessage>>

    @Query("SELECT COUNT(*) FROM pending_messages")
    suspend fun count(): Int

    @Query("UPDATE pending_messages SET attempts = attempts + 1, lastError = :error WHERE id = :id")
    suspend fun recordFailure(id: Long, error: String)

    @Delete
    suspend fun delete(message: PendingMessage)
}
