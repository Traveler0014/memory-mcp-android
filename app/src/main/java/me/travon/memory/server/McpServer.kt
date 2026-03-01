package me.travon.memory.server

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class McpServer(
    private val port: Int = 8080,
    private val onMessage: suspend (String) -> String
) {
    private var server: ApplicationEngine? = null

    fun start(allowRemote: Boolean = false) {
        if (server != null) return
        val host = if (allowRemote) "0.0.0.0" else "127.0.0.1"

        server = embeddedServer(CIO, port = port, host = host) {
            routing {
                post("/stream") {
                    val inputChannel = call.receiveChannel()
                    call.respondBytesWriter {
                        val outputChannel = this
                        val isConnected = AtomicBoolean(true)
                        
                        val heartbeatJob = launch(Dispatchers.IO) {
                            while (isActive && isConnected.get()) {
                                delay(30_000)
                                try {
                                    outputChannel.writeStringUtf8("{}\n")
                                    outputChannel.flush()
                                } catch (e: Exception) {
                                    isConnected.set(false)
                                    break
                                }
                            }
                        }

                        try {
                            var line = inputChannel.readUTF8Line()
                            while (isActive && line != null) {
                                val trimmed = line.trim()
                                if (trimmed.isNotEmpty()) {
                                    try {
                                        val responseJson = onMessage(trimmed)
                                        outputChannel.writeStringUtf8("$responseJson\n")
                                        outputChannel.flush()
                                    } catch (e: Exception) {
                                        val errString = e.message?.replace("\"", "\\\"") ?: "Unknown Error"
                                        val errObject = """{"error": "$errString"}"""
                                        outputChannel.writeStringUtf8("$errObject\n")
                                        outputChannel.flush()
                                    }
                                }
                                line = inputChannel.readUTF8Line()
                            }
                        } catch (e: Exception) {
                            // Link drop or client disconnect
                        } finally {
                            isConnected.set(false)
                            heartbeatJob.cancel()
                        }
                    }
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }

    fun isRunning(): Boolean = server != null
}
