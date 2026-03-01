# Development Guidelines: Local Memory MCP for Android

## 1. Core Philosophy

The objective is the realization of a deterministic, privacy-first, and high-performance memory retrieval system. Reject all unnecessary abstractions. Prioritize execution speed and logical clarity over decorative features.

## 2. Technical Stack Specifications

Language: Kotlin 1.9+ (Strict type safety required).

Network Engine: Ktor Server (CIO engine for non-blocking I/O).

Database: Room (SQLite) with WAL (Write-Ahead Logging) enabled.

Inference: ONNX Runtime (ORT) Mobile.

Serialization: Kotlinx Serialization (JSON).

## 3. Implementation Standards

### 3.1 Embedding & Inference (Local-only)

Model: bge-small-zh-v1.5 in INT8 quantized ONNX format.

Initialization: Load the model during the onCreate phase of the Foreground Service. Ensure it is cached in memory.

Threading: All inference operations must occur on a dedicated CoroutineDispatcher (e.g., Dispatchers.Default) to prevent UI/Network thread starvation.

Logic: Input text must be normalized (trimming, newline removal) before being passed to the Tokenizer.

### 3.2 Storage Strategy (The "Flat" Approach)

Constraint: Since the data scale is $10^1$ to $10^3$, do not implement complex HNSW or IVF indices.

Retrieval:
1.  Fetch all embeddings from Room as a List of FloatArray.
2.  Perform a linear scan using Cosine Similarity.
3.  Math: $similarity = \frac{A \cdot B}{\|A\|\|B\|}$. Since BGE vectors are often normalized, a simple Dot Product may suffice.

### 3.3 MCP Interface Definition (The "Standard Five")

To ensure a closed-loop memory lifecycle, the following five tools must be implemented:

1. memorize(content: String, tags: List<String>?) -> id: Int

    Logic: Generate embedding -> Store content + vector + tags -> Return primary key ID.

2. recall(query: String, limit: Int, tags: List<String>?) -> List<Entry>

    Logic: Semantic search + Keyword boost + Tag filtering.

3. forget(id: Int)

    Logic: Deterministic deletion by ID. Reject "semantic deletion" to avoid accidental data loss.

4. list_memories(limit: Int, offset: Int) -> List<EntryMetadata>

    Logic: Provide visibility into the system state. Essential for debugging and manual cleanup.

5. get_stats() -> Object

    Logic: Return count of entries and last update timestamp. Enables the Host to assess the "weight" of the memory.

### 3.4 Communication: Streamable HTTP (NDJSON)

Framing: Every response message must be a single-line JSON object followed by a newline character (\n).

The Pipe: Use Ktor's respondBytesWriter to maintain a persistent connection for the /stream endpoint.

Heartbeat: Implement a 30-second keep-alive pulse (empty JSON {}) to prevent NAT timeout on mobile networks.

### 3.5 Android Lifecycle Management

Foreground Service: The MCP server must run as a Foreground Service with a visible notification. This is non-negotiable to prevent the "Tombstone" state.

Network Service Discovery (NSD): Automatically register the _mcp-streamable._tcp service upon successful WLAN connection. Unregister immediately on disconnect.

## 4. Coding Conventions

Error Handling: Never swallow exceptions. All I/O errors must be mapped to valid JSON-RPC error codes.

Immutability: Use val by default. State changes in the Memory Vault must be thread-safe (use AtomicReference or Mutex for the memory cache).

Naming: Avoid ambiguous terms like data or info. Use MemoryEntry, VectorBuffer, or QueryContext.

## 5. Testing Requirements

Unit Tests: Verify the mathematical correctness of the Cosine Similarity implementation against known vector pairs.

Protocol Tests: Ensure the NDJSON framing correctly handles multi-line string inputs by escaping them within the JSON object.

Integration: Test the full loop: Memorize -> Embedding -> Storage -> Recall.

## 6. Privacy Guardrails

Scope: No data shall leave the device.

Network: Default listener must be 127.0.0.1. Remote access (WLAN) must be an explicit user opt-in.