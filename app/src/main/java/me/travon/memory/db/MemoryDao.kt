package me.travon.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: MemoryEntry): Long

    @Query("DELETE FROM memory_entries WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("SELECT * FROM memory_entries ORDER BY timestamp DESC")
    suspend fun getAll(): List<MemoryEntry>

    @Query("SELECT * FROM memory_entries ORDER BY timestamp DESC")
    fun getAllAsFlow(): Flow<List<MemoryEntry>>

    @Query("SELECT COUNT(*) FROM memory_entries")
    suspend fun getCount(): Int

    @Query("SELECT MAX(timestamp) FROM memory_entries")
    suspend fun getLastUpdated(): Long?

    @Query("SELECT * FROM memory_entries ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getPaged(limit: Int, offset: Int): List<MemoryEntry>

    @androidx.room.Update
    suspend fun update(entry: MemoryEntry)
}
