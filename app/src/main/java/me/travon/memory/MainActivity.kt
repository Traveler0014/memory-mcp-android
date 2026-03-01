package me.travon.memory

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
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
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MemoryApplication
        
        setContent {
            MaterialTheme {
                var selectedTab by remember { mutableIntStateOf(0) }
                val serverStatus by McpForegroundService.status.collectAsState()
                val scope = rememberCoroutineScope()
                var entries by remember { mutableStateOf(emptyList<MemoryEntry>()) }

                LaunchedEffect(Unit) {
                    app.database.memoryDao().getAllAsFlow().collect { entries = it }
                }

                Scaffold(
                    topBar = {
                        Column {
                            TopAppBar(title = { Text("Memory Vault") })
                            TabRow(selectedTabIndex = selectedTab) {
                                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Service") })
                                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Manage") })
                                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Recall Test") })
                            }
                        }
                    }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                        when (selectedTab) {
                            0 -> ServiceScreen(serverStatus, this@MainActivity)
                            1 -> ManagementScreen(app, entries, scope)
                            2 -> RecallScreen(app, entries)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ServiceScreen(serverStatus: McpForegroundService.ServerStatus, activity: MainActivity) {
    var localPort by remember { mutableStateOf("8080") }
    var remotePort by remember { mutableStateOf("8081") }
    var mDnsName by remember { mutableStateOf("memory") }

    Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        // Master Service Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (serverStatus.local.isRunning)
                    MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Vault Service", style = MaterialTheme.typography.titleLarge)
                        Text(if (serverStatus.local.isRunning) "Local Access: Active" else "Stopped")
                    }
                    Switch(
                        checked = serverStatus.local.isRunning,
                        onCheckedChange = { checked ->
                            val intent = Intent(activity, McpForegroundService::class.java).apply {
                                action = if (checked) McpForegroundService.ACTION_START_LOCAL
                                         else McpForegroundService.ACTION_STOP_LOCAL
                                putExtra(McpForegroundService.EXTRA_PORT, localPort.toIntOrNull() ?: 8080)
                            }
                            if (checked) ContextCompat.startForegroundService(activity, intent)
                            else activity.startService(intent)
                        }
                    )
                }
                if (serverStatus.local.isRunning) {
                    Text("Endpoint: http://localhost:${serverStatus.local.port}/mcp", style = MaterialTheme.typography.labelSmall)
                } else {
                    OutlinedTextField(
                        value = localPort,
                        onValueChange = { if (it.all { c -> c.isDigit() }) localPort = it },
                        label = { Text("Local Port") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Remote Access Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (serverStatus.remote.isRunning) MaterialTheme.colorScheme.secondaryContainer 
                                else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Remote LAN Access", style = MaterialTheme.typography.titleMedium)
                        Text(if (serverStatus.remote.isRunning) "Remote Access: Active" else "Restricted")
                    }
                    Switch(
                        enabled = serverStatus.local.isRunning,
                        checked = serverStatus.remote.isRunning,
                        onCheckedChange = { checked ->
                            val intent = Intent(activity, McpForegroundService::class.java).apply {
                                action = if (checked) McpForegroundService.ACTION_START_REMOTE
                                         else McpForegroundService.ACTION_STOP_REMOTE
                                putExtra(McpForegroundService.EXTRA_PORT, remotePort.toIntOrNull() ?: 8081)
                                putExtra(McpForegroundService.EXTRA_MDNS_NAME, mDnsName)
                            }
                            activity.startService(intent)
                        }
                    )
                }
                if (serverStatus.remote.isRunning) {
                    Text("mDNS: http://${serverStatus.remote.mDnsName}.local:${serverStatus.remote.port}/mcp", style = MaterialTheme.typography.labelSmall)
                    Text("IP: http://${serverStatus.remote.address}:${serverStatus.remote.port}/mcp", style = MaterialTheme.typography.labelSmall)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = remotePort,
                            onValueChange = { if (it.all { c -> c.isDigit() }) remotePort = it },
                            label = { Text("Port") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = mDnsName,
                            onValueChange = { mDnsName = it },
                            label = { Text("mDNS Name") },
                            modifier = Modifier.weight(2f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ManagementScreen(app: MemoryApplication, entries: List<MemoryEntry>, scope: kotlinx.coroutines.CoroutineScope) {
    var newContent by remember { mutableStateOf("") }
    var newTags by remember { mutableStateOf("") }
    var editingEntry by remember { mutableStateOf<MemoryEntry?>(null) }

    Column(modifier = Modifier.padding(16.dp)) {
        OutlinedTextField(
            value = newContent,
            onValueChange = { newContent = it },
            label = { Text("Quick Memorize") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = newTags,
            onValueChange = { newTags = it },
            label = { Text("Tags (comma separated)") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                if (newContent.isNotBlank()) {
                    scope.launch {
                        val content = newContent
                        val tags = newTags.takeIf { it.isNotBlank() }
                        newContent = ""
                        newTags = ""
                        val embedding = app.embeddingEngine.getEmbedding(content)
                        app.database.memoryDao().insert(MemoryEntry(content = content, timestamp = System.currentTimeMillis(), embedding = embedding, tags = tags))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Store") }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(entries) { entry ->
                MemoryItemCard(entry, onEdit = { editingEntry = it }, onDelete = {
                    scope.launch { app.database.memoryDao().deleteById(entry.id) }
                })
            }
        }
    }

    if (editingEntry != null) {
        EditMemoryDialog(
            entry = editingEntry!!,
            onDismiss = { editingEntry = null },
            onConfirm = { updatedEntry ->
                scope.launch {
                    val embedding = if (updatedEntry.content != editingEntry!!.content) {
                        app.embeddingEngine.getEmbedding(updatedEntry.content)
                    } else updatedEntry.embedding
                    app.database.memoryDao().update(updatedEntry.copy(embedding = embedding))
                }
                editingEntry = null
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryItemCard(entry: MemoryEntry, onEdit: (MemoryEntry) -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onEdit(entry) }) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(entry.content, style = MaterialTheme.typography.bodyLarge)
            if (!entry.tags.isNullOrBlank()) {
                FlowRow(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    entry.tags.split(",").forEach { tag ->
                        SuggestionChip(onClick = {}, label = { Text(tag.trim()) })
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                Text(
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(entry.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }
        }
    }
}

@Composable
fun EditMemoryDialog(entry: MemoryEntry, onDismiss: () -> Unit, onConfirm: (MemoryEntry) -> Unit) {
    var content by remember { mutableStateOf(entry.content) }
    var tags by remember { mutableStateOf(entry.tags ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Memory") },
        text = {
            Column {
                OutlinedTextField(value = content, onValueChange = { content = it }, label = { Text("Content") })
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = tags, onValueChange = { tags = it }, label = { Text("Tags") })
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(entry.copy(content = content, tags = tags.takeIf { it.isNotBlank() })) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun RecallScreen(app: MemoryApplication, entries: List<MemoryEntry>) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<Pair<MemoryEntry, Float>>()) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Semantic Query") },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = {
                    if (query.isNotBlank()) {
                        scope.launch {
                            val qEmbedding = app.embeddingEngine.getEmbedding(query)
                            results = entries.map { it to app.embeddingEngine.cosineSimilarity(qEmbedding, it.embedding) }
                                .sortedByDescending { it.second }
                                .take(10)
                        }
                    }
                }) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results) { (entry, score) ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(entry.content, modifier = Modifier.weight(1f))
                            Text(String.format("%.2f", score), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        if (!entry.tags.isNullOrBlank()) {
                            Text("Tags: ${entry.tags}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
