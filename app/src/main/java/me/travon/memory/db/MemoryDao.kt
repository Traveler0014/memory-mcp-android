package me.travon.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: MemoryEntry)

    @Query("DELETE FROM memory_entries WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM memory_entries ORDER BY timestamp DESC")
    suspend fun getAll(): List<MemoryEntry>

    @Query("SELECT * FROM memory_entries ORDER BY timestamp DESC")
    fun getAllAsFlow(): Flow<List<MemoryEntry>>
}
