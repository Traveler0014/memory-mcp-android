package me.travon.memory.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memory_entries")
data class MemoryEntry(
    @PrimaryKey
    val id: String,
    val content: String,
    val timestamp: Long,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val embedding: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MemoryEntry

        if (id != other.id) return false
        if (content != other.content) return false
        if (timestamp != other.timestamp) return false
        if (!embedding.contentEquals(other.embedding)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}
