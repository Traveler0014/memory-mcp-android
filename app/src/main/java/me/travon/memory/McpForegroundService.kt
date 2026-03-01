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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.travon.memory.db.MemoryEntry
import me.travon.memory.server.McpServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class McpForegroundService : Service() {

    data class ListenerStatus(
        val isRunning: Boolean = false,
        val address: String? = null,
        val port: Int? = 8080,
        val mDnsName: String? = null
    )

    data class ServerStatus(
        val local: ListenerStatus = ListenerStatus(),
        val remote: ListenerStatus = ListenerStatus()
    )

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var localServer: McpServer? = null
    private var remoteServer: McpServer? = null
    private var localNsd: NSDHelper? = null
    private var remoteNsd: NSDHelper? = null
    
    // Per-session initialization state
    private val sessionInitialized = ConcurrentHashMap<String, Boolean>()

    companion object {
        private const val CHANNEL_ID = "McpServiceChannel"
        private const val NOTIFICATION_ID = 1
        
        const val ACTION_START_LOCAL = "ACTION_START_LOCAL"
        const val ACTION_STOP_LOCAL = "ACTION_STOP_LOCAL"
        const val ACTION_START_REMOTE = "ACTION_START_REMOTE"
        const val ACTION_STOP_REMOTE = "ACTION_STOP_REMOTE"

        const val EXTRA_PORT = "EXTRA_PORT"
        const val EXTRA_MDNS_NAME = "EXTRA_MDNS_NAME"
        
        private val _status = MutableStateFlow(ServerStatus())
        val status = _status.asStateFlow()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val app = application as MemoryApplication
        
        // Initialize Onnx Engine
        serviceScope.launch {
            app.embeddingEngine.initialize()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as MemoryApplication
        val action = intent?.action
        val port = intent?.getIntExtra(EXTRA_PORT, 8080) ?: 8080
        val mDnsName = intent?.getStringExtra(EXTRA_MDNS_NAME) ?: "memory"

        when (action) {
            ACTION_START_LOCAL -> {
                // Master Switch: Start service and local listener
                if (localServer == null || !localServer!!.isRunning()) {
                    localServer = McpServer(port = port) { sid, msg -> handleMcpMessage(sid, msg, app) }
                    localServer?.start(allowRemote = false)
                    _status.value = _status.value.copy(
                        local = ListenerStatus(true, "127.0.0.1", port)
                    )
                }
            }
            ACTION_STOP_LOCAL -> {
                // Master Switch OFF: Stop everything
                stopSelf()
            }
            ACTION_START_REMOTE -> {
                // Sub-switch: Only if local is already running
                if (localServer != null && localServer!!.isRunning()) {
                    if (remoteServer == null || !remoteServer!!.isRunning()) {
                        remoteServer = McpServer(port = port) { sid, msg -> handleMcpMessage(sid, msg, app) }
                        remoteServer?.start(allowRemote = true)
                        remoteNsd = NSDHelper(this, port, mDnsName).apply { registerService() }
                        _status.value = _status.value.copy(
                            remote = ListenerStatus(true, getIpAddress(), port, mDnsName)
                        )
                    }
                }
            }
            ACTION_STOP_REMOTE -> {
                // Sub-switch: Stop only remote
                remoteNsd?.unregisterService()
                remoteNsd = null
                remoteServer?.stop()
                remoteServer = null
                _status.value = _status.value.copy(remote = ListenerStatus(false))
            }
            else -> {
                // Default startup: Treat as START_LOCAL (Master Switch)
                if (localServer == null || !localServer!!.isRunning()) {
                    localServer = McpServer(port = 8080) { sid, msg -> handleMcpMessage(sid, msg, app) }
                    localServer?.start(false)
                    _status.value = _status.value.copy(local = ListenerStatus(true, "127.0.0.1", 8080))
                }
            }
        }

        updateNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        localNsd?.unregisterService()
        remoteNsd?.unregisterService()
        localServer?.stop()
        remoteServer?.stop()
        _status.value = ServerStatus()
        sessionInitialized.clear()
        serviceScope.cancel()
        val app = application as MemoryApplication
        try {
            app.embeddingEngine.close()
        } catch (e: Exception) { }
    }

    private suspend fun handleMcpMessage(sessionId: String, rawMessage: String, app: MemoryApplication): String {
        android.util.Log.d("McpService", "Received for $sessionId: $rawMessage")
        try {
            val json = JSONObject(rawMessage)
            val method = json.optString("method")
            val params = json.optJSONObject("params") ?: JSONObject()
            val id = json.opt("id")

            android.util.Log.d("McpService", "Processing method: $method, ID: $id")

            // MCP Support: Only allow initialize before anything else
            if (method != "initialize" && !sessionInitialized.getOrDefault(sessionId, false)) {
                android.util.Log.w("McpService", "Session $sessionId not initialized")
                return errorResponse(id, -32002, "Session not initialized")
            }

            val result: Any = when (method) {
                "initialize" -> {
                    sessionInitialized[sessionId] = true
                    JSONObject().apply {
                        put("protocolVersion", "2024-11-05")
                        put("capabilities", JSONObject().apply {
                            put("tools", JSONObject())
                        })
                        put("serverInfo", JSONObject().apply {
                            put("name", "Android Memory Vault")
                            put("version", "1.1.0")
                        })
                    }
                }
                "notifications/initialized" -> {
                    return ""
                }
                "tools/list" -> {
                    JSONObject().apply {
                        val tools = JSONArray().apply {
                            put(JSONObject().apply {
                                put("name", "memorize")
                                put("description", "Store a new memory fragment with tags")
                                put("inputSchema", JSONObject().apply {
                                    put("type", "object")
                                    put("properties", JSONObject().apply {
                                        put("content", JSONObject().apply { put("type", "string") })
                                        put("tags", JSONObject().apply { 
                                            put("type", "array")
                                            put("items", JSONObject().apply { put("type", "string") })
                                        })
                                    })
                                    put("required", JSONArray().apply { put("content") })
                                })
                            })
                            put(JSONObject().apply {
                                put("name", "recall")
                                put("description", "Search memories by semantic query and tags")
                                put("inputSchema", JSONObject().apply {
                                    put("type", "object")
                                    put("properties", JSONObject().apply {
                                        put("query", JSONObject().apply { put("type", "string") })
                                        put("limit", JSONObject().apply { put("type", "integer") })
                                        put("tags", JSONObject().apply { 
                                            put("type", "array")
                                            put("items", JSONObject().apply { put("type", "string") })
                                        })
                                    })
                                    put("required", JSONArray().apply { put("query") })
                                })
                            })
                            put(JSONObject().apply {
                                put("name", "forget")
                                put("description", "Delete a memory definitively by its ID")
                                put("inputSchema", JSONObject().apply {
                                    put("type", "object")
                                    put("properties", JSONObject().apply {
                                        put("id", JSONObject().apply { put("type", "integer") })
                                    })
                                    put("required", JSONArray().apply { put("id") })
                                })
                            })
                            put(JSONObject().apply {
                                put("name", "list_memories")
                                put("description", "List metadata of stored memories")
                                put("inputSchema", JSONObject().apply {
                                    put("type", "object")
                                    put("properties", JSONObject().apply {
                                        put("limit", JSONObject().apply { put("type", "integer") })
                                        put("offset", JSONObject().apply { put("type", "integer") })
                                    })
                                })
                            })
                            put(JSONObject().apply {
                                put("name", "get_stats")
                                put("description", "Get stats about the memory vault")
                                put("inputSchema", JSONObject().apply {
                                    put("type", "object")
                                    put("properties", JSONObject())
                                })
                            })
                        }
                        put("tools", tools)
                    }
                }
                "tools/call" -> {
                    val toolName = params.optString("name")
                    val arguments = params.optJSONObject("arguments") ?: JSONObject()
                    
                    when (toolName) {
                        "memorize" -> {
                            val content = arguments.optString("content") ?: throw IllegalArgumentException("Missing content")
                            val tagsArray = arguments.optJSONArray("tags")
                            val tags = if (tagsArray != null) {
                                (0 until tagsArray.length()).joinToString(",") { tagsArray.getString(it) }
                            } else null
                            
                            val embedding = app.embeddingEngine.getEmbedding(content)
                            val entry = MemoryEntry(
                                content = content,
                                timestamp = System.currentTimeMillis(),
                                embedding = embedding,
                                tags = tags
                            )
                            val newId = app.database.memoryDao().insert(entry)
                            JSONObject().apply {
                                put("content", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "text")
                                        put("text", "Stored successfully. ID: $newId")
                                    })
                                })
                            }
                        }
                        "recall" -> {
                            val query = arguments.optString("query") ?: throw IllegalArgumentException("Missing query")
                            val limit = arguments.optInt("limit", 5)
                            val filterTagsArray = arguments.optJSONArray("tags")
                            val filterTags = if (filterTagsArray != null) {
                                (0 until filterTagsArray.length()).map { filterTagsArray.getString(it) }.toSet()
                            } else null
                            
                            val queryEmbedding = app.embeddingEngine.getEmbedding(query)
                            var allEntries = app.database.memoryDao().getAll()
                            
                            // Apply tag filtering if provided
                            if (filterTags != null) {
                                allEntries = allEntries.filter { entry ->
                                    val entryTags = entry.tags?.split(",")?.toSet() ?: emptySet()
                                    filterTags.all { it in entryTags }
                                }
                            }
                            
                            val scoredEntries = allEntries.map { entry ->
                                val score = app.embeddingEngine.cosineSimilarity(queryEmbedding, entry.embedding)
                                Pair(entry, score)
                            }.sortedByDescending { it.second }.take(limit)

                            val responseText = scoredEntries.joinToString("\n\n") { (entry, score) ->
                                "[ID: ${entry.id}] [Score: ${String.format("%.4f", score)}]\nTags: ${entry.tags ?: "none"}\n${entry.content}"
                            }
                            
                            JSONObject().apply {
                                put("content", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "text")
                                        put("text", if (responseText.isEmpty()) "No matching memories found." else responseText)
                                    })
                                })
                            }
                        }
                        "forget" -> {
                            val targetId = arguments.optInt("id", -1)
                            if (targetId == -1) throw IllegalArgumentException("Invalid ID")
                            app.database.memoryDao().deleteById(targetId)
                            JSONObject().apply {
                                put("content", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "text")
                                        put("text", "Memory $targetId removed.")
                                    })
                                })
                            }
                        }
                        "list_memories" -> {
                            val limit = arguments.optInt("limit", 20)
                            val offset = arguments.optInt("offset", 0)
                            val entries = app.database.memoryDao().getPaged(limit, offset)
                            
                            val responseText = entries.joinToString("\n") { entry ->
                                "ID: ${entry.id} | Tags: ${entry.tags ?: "none"} | Content: ${entry.content.take(50)}..."
                            }
                            
                            JSONObject().apply {
                                put("content", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "text")
                                        put("text", if (responseText.isEmpty()) "No memories found." else responseText)
                                    })
                                })
                            }
                        }
                        "get_stats" -> {
                            val count = app.database.memoryDao().getCount()
                            val lastUpdated = app.database.memoryDao().getLastUpdated() ?: 0
                            JSONObject().apply {
                                put("content", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "text")
                                        put("text", "Count: $count\nLast Updated: ${if (lastUpdated > 0) java.util.Date(lastUpdated) else "never"}")
                                    })
                                })
                            }
                        }
                        else -> throw IllegalArgumentException("Unknown tool: $toolName")
                    }
                }
                else -> throw IllegalArgumentException("Unknown method: $method")
            }
            
            return if (id != null && result != "") {
                JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("result", result)
                }.toString()
            } else {
                ""
            }
        } catch (e: Exception) {
            val errString = e.message ?: "Unknown Error"
            return errorResponse(null, -32603, errString)
        }
    }

    private fun errorResponse(id: Any?, code: Int, message: String): String {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            put("error", JSONObject().apply {
                put("code", code)
                put("message", message)
            })
        }.toString()
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

    private fun updateNotification() {
        val s = _status.value
        val localText = if (s.local.isRunning) "Local: ${s.local.port}" else "Local: Off"
        val remoteText = if (s.remote.isRunning) "Remote: ${s.remote.mDnsName}.local:${s.remote.port}" else "Remote: Off"
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Memory MCP Vault")
            .setContentText("$localText | $remoteText")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setOngoing(true)
            .build()
            
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotification(host: String = "127.0.0.1", port: Int = 8080): Notification {
        // This is kept for backward compatibility if needed, but we use updateNotification() now
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Memory MCP Server")
            .setContentText("Running at http://$host:$port/mcp")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .build()
    }

    private fun getIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress
                        if (sAddr != null) {
                            val isIPv4 = sAddr.indexOf(':') < 0
                            if (isIPv4) return sAddr
                        }
                    }
                }
            }
        } catch (ex: Exception) { }
        return null
    }
}
