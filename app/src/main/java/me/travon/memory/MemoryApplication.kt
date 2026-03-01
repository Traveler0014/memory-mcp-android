package me.travon.memory

import android.app.Application
import me.travon.memory.db.MemoryDatabase
import me.travon.memory.inference.EmbeddingEngine

class MemoryApplication : Application() {
    
    val database by lazy { MemoryDatabase.getDatabase(this) }
    val embeddingEngine by lazy { EmbeddingEngine(this) }

    override fun onCreate() {
        super.onCreate()
        // Initialize components if needed
    }
}
