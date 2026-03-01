package me.travon.memory

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.travon.memory.db.MemoryEntry
import me.travon.memory.server.McpServer
import org.json.JSONObject
import java.util.UUID

class McpForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var mcpServer: McpServer? = null
    private var nsdManager: NSDHelper? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val app = application as MemoryApplication
        
        // Initialize Onnx Engine
        app.embeddingEngine.initialize()

        mcpServer = McpServer(port = 8080) { rawMessage ->
            handleMcpMessage(rawMessage, app)
        }
        
        nsdManager = NSDHelper(this, 8080)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val allowRemote = intent?.getBooleanExtra("ALLOW_REMOTE", false) ?: false
        
        startForeground(NOTIFICATION_ID, createNotification())
        
        if (mcpServer?.isRunning() == false) {
            mcpServer?.start(allowRemote)
            if (allowRemote) {
                nsdManager?.registerService()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        nsdManager?.unregisterService()
        mcpServer?.stop()
        serviceScope.cancel()
        val app = application as MemoryApplication
        app.embeddingEngine.close()
    }

    private suspend fun handleMcpMessage(rawMessage: String, app: MemoryApplication): String {
        try {
            val json = JSONObject(rawMessage)
            val method = json.optString("method")
            val params = json.optJSONObject("params")
            val id = json.opt("id")

            val result = when (method) {
                "memorize" -> {
                    val content = params?.optString("content") ?: throw IllegalArgumentException("Missing content")
                    val embedding = app.embeddingEngine.getEmbedding(content)
                    val entry = MemoryEntry(
                        id = UUID.randomUUID().toString(),
                        content = content,
                        timestamp = System.currentTimeMillis(),
                        embedding = embedding
                    )
                    app.database.memoryDao().insert(entry)
                    """{"status": "success", "id": "${entry.id}"}"""
                }
                "recall" -> {
                    val query = params?.optString("query") ?: throw IllegalArgumentException("Missing query")
                    val limit = params?.optInt("limit", 5) ?: 5
                    
                    val queryEmbedding = app.embeddingEngine.getEmbedding(query)
                    val allEntries = app.database.memoryDao().getAll()
                    
                    val scoredEntries = allEntries.map { entry ->
                        val score = app.embeddingEngine.cosineSimilarity(queryEmbedding, entry.embedding)
                        Pair(entry, score)
                    }.sortedByDescending { it.second }.take(limit)

                    val resultsJson = scoredEntries.joinToString(",") { (entry, score) ->
                        """{"id": "${entry.id}", "content": "${entry.content.replace("\"", "\\\"")}", "score": $score}"""
                    }
                    """{"results": [$resultsJson]}"""
                }
                else -> throw IllegalArgumentException("Unknown method: $method")
            }
            
            return if (id != null) {
                """{"jsonrpc": "2.0", "id": $id, "result": $result}"""
            } else {
                result
            }
        } catch (e: Exception) {
            val errString = e.message?.replace("\"", "\\\"") ?: "Unknown Error"
            return """{"jsonrpc": "2.0", "error": {"code": -32603, "message": "$errString"}}"""
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MCP Service Channel",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Memory MCP Server")
            .setContentText("Memory MCP Server is running locally.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "McpServiceChannel"
        private const val NOTIFICATION_ID = 1
    }
}
