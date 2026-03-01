package me.travon.memory

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import me.travon.memory.db.MemoryEntry
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val app = application as MemoryApplication
        
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var isServiceRunning by remember { mutableStateOf(false) } // In real app, poll ActivityManager or use bound service flow
                    var entries by remember { mutableStateOf(emptyList<MemoryEntry>()) }
                    var newContent by remember { mutableStateOf("") }
                    val scope = rememberCoroutineScope()

                    // Collect memory entries
                    LaunchedEffect(Unit) {
                        app.database.memoryDao().getAllAsFlow().collect { list ->
                            entries = list
                        }
                    }

                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "Memory MCP Vault", style = MaterialTheme.typography.headlineMedium)
                        
                        Spacer(modifier = Modifier.height(16.dp))

                        // Control Panel
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("MCP Server Status: ${if (isServiceRunning) "Running" else "Stopped"}")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        val intent = Intent(this@MainActivity, McpForegroundService::class.java)
                                        intent.putExtra("ALLOW_REMOTE", true)
                                        ContextCompat.startForegroundService(this@MainActivity, intent)
                                        isServiceRunning = true
                                    }) {
                                        Text("Start Local & Remote")
                                    }
                                    Button(onClick = {
                                        val intent = Intent(this@MainActivity, McpForegroundService::class.java)
                                        stopService(intent)
                                        isServiceRunning = false
                                    }) {
                                        Text("Stop Server")
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Manual Entry
                        OutlinedTextField(
                            value = newContent,
                            onValueChange = { newContent = it },
                            label = { Text("New Memory") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                if (newContent.isNotBlank()) {
                                    scope.launch {
                                        val content = newContent
                                        newContent = ""
                                        val embedding = app.embeddingEngine.getEmbedding(content)
                                        val entry = MemoryEntry(
                                            id = UUID.randomUUID().toString(),
                                            content = content,
                                            timestamp = System.currentTimeMillis(),
                                            embedding = embedding
                                        )
                                        app.database.memoryDao().insert(entry)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Memorize")
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // List of Memories
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(entries) { entry ->
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(entry.content, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            formatDate(entry.timestamp), 
                                            style = MaterialTheme.typography.bodySmall, 
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Button(onClick = {
                                            scope.launch {
                                                app.database.memoryDao().deleteById(entry.id)
                                            }
                                        }) {
                                            Text("Delete")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun formatDate(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
