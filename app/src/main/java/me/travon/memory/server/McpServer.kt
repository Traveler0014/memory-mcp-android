package me.travon.memory.server

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles both MCP Streamable HTTP (2025-11-25) and Legacy SSE transports.
 */
class McpServer(
    private val port: Int = 8080,
    private val onMessage: suspend (String, String) -> String
) {
    private var server: ApplicationEngine? = null
    var boundHost: String? = null
        private set
    var boundPort: Int = port
        private set

    private val sessions = ConcurrentHashMap<String, SseSession>()

    private class SseSession(
        val channel: ByteWriteChannel,
        val id: String
    ) {
        suspend fun sendMessage(message: String) {
            channel.writeStringUtf8("event: message\ndata: $message\n\n")
            channel.flush()
        }

        suspend fun sendEndpoint(endpoint: String) {
            channel.writeStringUtf8("event: endpoint\ndata: $endpoint\n\n")
            channel.flush()
        }
        
        suspend fun sendHeartbeat() {
            channel.writeStringUtf8(":\n\n")
            channel.flush()
        }
    }

    fun start(allowRemote: Boolean = false) {
        if (server != null) return
        val host = if (allowRemote) "0.0.0.0" else "127.0.0.1"
        boundHost = host
        boundPort = port

        server = embeddedServer(CIO, port = port, host = host) {
            install(CORS) {
                anyHost()
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Delete)
                allowHeader("Content-Type")
                allowHeader("MCP-Session-Id")
                allowHeader("x-mcp-session-id")
                exposeHeader("MCP-Session-Id")
                exposeHeader("x-mcp-session-id")
            }
            
            routing {
                // --- Streamable HTTP (2025-11-25) ---
                
                get("/mcp") {
                    handleSseRequest(call, isLegacy = false)
                }

                post("/mcp") {
                    handlePostMessage(call)
                }
                
                delete("/mcp") {
                    handleDeleteSession(call)
                }

                // --- Legacy SSE ---

                get("/sse") {
                    handleSseRequest(call, isLegacy = true)
                }

                post("/messages") {
                    handlePostMessage(call)
                }
                
                delete("/messages") {
                    handleDeleteSession(call)
                }
            }
        }.start(wait = false)
    }

    private suspend fun handleSseRequest(call: ApplicationCall, isLegacy: Boolean) {
        val sessionId = call.request.header("MCP-Session-Id")
            ?: call.request.header("x-mcp-session-id")
            ?: call.parameters["sessionId"]
            ?: UUID.randomUUID().toString()
            
        android.util.Log.d("McpServer", "SSE Request Start: sessionId=$sessionId, isLegacy=$isLegacy")
        call.request.headers.entries().forEach { (k, v) -> 
            android.util.Log.d("McpServer", "  Header: $k = ${v.joinToString(", ")}") 
        }
        
        call.response.header("MCP-Session-Id", sessionId)
        call.response.header("x-mcp-session-id", sessionId)
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        call.response.header(HttpHeaders.AccessControlExposeHeaders, "MCP-Session-Id, x-mcp-session-id")
        call.response.cacheControl(CacheControl.NoCache(null))
        
        call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
            val session = SseSession(this, sessionId)
            sessions[sessionId] = session
            
            try {
                session.sendHeartbeat()
                if (isLegacy) {
                    // Legacy SSE
                    session.sendEndpoint("/messages?sessionId=$sessionId")
                } else {
                    // Standard Streamable HTTP
                    session.sendEndpoint("/mcp?sessionId=$sessionId")
                }
                
                // Keep alive loop
                while (coroutineContext.isActive) {
                    delay(30_000)
                    session.sendHeartbeat()
                }
            } catch (e: Exception) {
                // Disconnect
            } finally {
                sessions.remove(sessionId)
            }
        }
    }

    private suspend fun handlePostMessage(call: ApplicationCall) {
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        
        var sessionId = call.request.header("MCP-Session-Id") 
            ?: call.request.header("x-mcp-session-id")
            ?: call.parameters["sessionId"]
            
        val text = call.receiveText()
        android.util.Log.i("McpServer", "POST Request: sessionId=$sessionId, bodyLen=${text.length}")
        
        // Peek if it's an initialization request
        val isInitialize = text.contains("\"method\":\"initialize\"")
        
        if (sessionId == null) {
            if (isInitialize) {
                sessionId = UUID.randomUUID().toString()
                android.util.Log.i("McpServer", "New session generated: $sessionId")
            } else {
                android.util.Log.w("McpServer", "POST Request failed: Missing sessionId and not an initialize request.")
                return call.respond(HttpStatusCode.BadRequest, "Missing MCP-Session-Id")
            }
        }
            
        val response = onMessage(sessionId, text)
        val session = sessions[sessionId]
        
        // Return session ID in headers for every response
        call.response.header("MCP-Session-Id", sessionId)
        call.response.header("x-mcp-session-id", sessionId)
        call.response.header(HttpHeaders.AccessControlExposeHeaders, "MCP-Session-Id, x-mcp-session-id")

        if (session != null && !isInitialize) {
            // Established session: send via SSE and return 202
            session.sendMessage(response)
            call.respond(HttpStatusCode.Accepted)
        } else {
            // No SSE stream yet OR it's initialize: return in body
            android.util.Log.d("McpServer", "Direct response for $sessionId: $response")
            call.respondText(response, ContentType.Application.Json, HttpStatusCode.OK)
        }
    }
    
    private suspend fun handleDeleteSession(call: ApplicationCall) {
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        val sessionId = call.request.header("MCP-Session-Id")
            ?: call.request.header("x-mcp-session-id")
            ?: call.parameters["sessionId"]
            ?: return call.respond(HttpStatusCode.BadRequest, "Missing MCP-Session-Id")
            
        sessions.remove(sessionId)
        call.respond(HttpStatusCode.OK)
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        sessions.clear()
    }

    fun isRunning(): Boolean = server != null
}
