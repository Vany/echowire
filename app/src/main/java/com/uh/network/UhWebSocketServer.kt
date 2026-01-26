package com.uh.network

import android.util.Log
import com.uh.config.RuntimeConfig
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * WebSocket server implementation for broadcasting messages to multiple clients.
 * Runs on specified port, accepts all connections, broadcasts to all without tracking.
 * Handles configuration messages from clients.
 */
class UhWebSocketServer(
    port: Int,
    private val runtimeConfig: RuntimeConfig,
    private val onClientConnect: (String) -> Unit,
    private val onClientDisconnect: (String) -> Unit,
    private val onError: (Exception) -> Unit
) : WebSocketServer(InetSocketAddress(port)) {

    companion object {
        private const val TAG = "UhWebSocketServer"
    }

    private val connectedClients = mutableSetOf<WebSocket>()

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val clientAddress = conn.remoteSocketAddress.toString()
        synchronized(connectedClients) {
            connectedClients.add(conn)
        }
        Log.i(TAG, "Client connected: $clientAddress (total: ${connectedClients.size})")

        // Send handshake with device name for authentication
        sendHandshake(conn)

        onClientConnect(clientAddress)
    }

    /**
     * Send handshake message to newly connected client.
     * Contains device name for client-side authentication/identification.
     */
    private fun sendHandshake(conn: WebSocket) {
        val deviceName = runtimeConfig.get("name") ?: "UH Service"
        val message = JSONObject().apply {
            put("type", "hello")
            put("device_name", deviceName)
            put("protocol_version", 1)
            put("timestamp", System.currentTimeMillis())
        }.toString()

        try {
            conn.send(message)
            Log.d(TAG, "Handshake sent to ${conn.remoteSocketAddress}: device_name=$deviceName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send handshake", e)
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        val clientAddress = conn.remoteSocketAddress.toString()
        synchronized(connectedClients) {
            connectedClients.remove(conn)
        }
        Log.i(TAG, "Client disconnected: $clientAddress (total: ${connectedClients.size})")
        onClientDisconnect(clientAddress)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        Log.d(TAG, "Received message from ${conn.remoteSocketAddress}: $message")

        // Process configure messages
        val response = runtimeConfig.processConfigureMessage(message)
        if (response != null) {
            try {
                conn.send(response)
                Log.d(TAG, "Sent config response: $response")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send config response", e)
            }
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        val clientAddress = conn?.remoteSocketAddress?.toString() ?: "unknown"
        Log.e(TAG, "WebSocket error from $clientAddress", ex)
        onError(ex)
    }

    override fun onStart() {
        Log.i(TAG, "WebSocket server started on port ${address.port}")
    }

    /**
     * Broadcast message to all connected clients.
     * Fire-and-forget: no error handling for individual clients.
     * Clients that fail to receive will be disconnected automatically.
     */
    fun broadcastMessage(message: String) {
        val clientsCopy: List<WebSocket>
        synchronized(connectedClients) {
            clientsCopy = connectedClients.toList()
        }

        clientsCopy.forEach { client ->
            try {
                if (client.isOpen) {
                    client.send(message)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send to client ${client.remoteSocketAddress}", e)
                // Connection will be closed by WebSocket library
            }
        }
    }

    /**
     * Send WebSocket ping to all connected clients.
     * Maintains connection health and detects dead connections.
     */
    fun sendPingToAll() {
        val clientsCopy: List<WebSocket>
        synchronized(connectedClients) {
            clientsCopy = connectedClients.toList()
        }

        clientsCopy.forEach { client ->
            try {
                if (client.isOpen) {
                    client.sendPing()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to ping client ${client.remoteSocketAddress}", e)
            }
        }
    }

    /**
     * Get current number of connected clients.
     */
    fun getClientCount(): Int {
        synchronized(connectedClients) {
            return connectedClients.size
        }
    }

    /**
     * Clean shutdown: close all connections and stop server.
     */
    fun shutdown() {
        try {
            Log.i(TAG, "Shutting down WebSocket server")
            stop(1000) // 1 second timeout
        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown", e)
        }
    }

    // ========== Android STT Message Broadcasting ==========

    /**
     * Broadcast audio level (RMS dB)
     * High frequency: ~20-30 Hz
     */
    fun broadcastAudioLevel(rmsDb: Float, listening: Boolean) {
        val message = JSONObject().apply {
            put("type", "audio_level")
            put("rms_db", rmsDb.toDouble())
            put("listening", listening)
            put("timestamp", System.currentTimeMillis())
        }.toString()

        broadcastMessage(message)
    }

    /**
     * Broadcast partial result (real-time transcription)
     * Frequency: ~0.5-2 Hz during speech
     */
    fun broadcastPartialResult(partialText: String, sessionStart: Long) {
        val message = JSONObject().apply {
            put("type", "partial_result")
            put("text", partialText)
            put("timestamp", System.currentTimeMillis())
            put("session_start", sessionStart)
        }.toString()

        broadcastMessage(message)
        Log.d(TAG, "Partial result broadcasted: \"$partialText\"")
    }

    /**
     * Broadcast final result with all metadata
     * Frequency: Once per utterance
     */
    fun broadcastFinalResult(
        alternatives: List<String>,
        confidenceScores: FloatArray,
        language: String,
        sessionStart: Long,
        sessionDurationMs: Long,
        speechStart: Long,
        speechDurationMs: Long
    ) {
        val message = JSONObject().apply {
            put("type", "final_result")

            // Alternatives array
            val alternativesArray = JSONArray()
            alternatives.forEachIndexed { index, text ->
                val alt = JSONObject().apply {
                    put("text", text)
                    put("confidence", confidenceScores.getOrNull(index)?.toDouble() ?: 0.0)
                }
                alternativesArray.put(alt)
            }
            put("alternatives", alternativesArray)

            // Convenience fields
            put("best_text", alternatives.firstOrNull() ?: "")
            put("best_confidence", confidenceScores.firstOrNull()?.toDouble() ?: 0.0)

            // Metadata
            put("language", language)
            put("timestamp", System.currentTimeMillis())
            put("session_start", sessionStart)
            put("session_duration_ms", sessionDurationMs)
            put("speech_start", speechStart)
            put("speech_duration_ms", speechDurationMs)
        }.toString()

        broadcastMessage(message)

        val bestText = alternatives.firstOrNull() ?: ""
        val bestConf = confidenceScores.firstOrNull() ?: 0f
        Log.i(
            TAG, "Final result broadcasted: \"$bestText\" (confidence: ${"%.3f".format(bestConf)}, " +
                    "alternatives: ${alternatives.size})"
        )
    }

    /**
     * Broadcast recognition event (state changes)
     */
    fun broadcastRecognitionEvent(event: String, listening: Boolean) {
        val message = JSONObject().apply {
            put("type", "recognition_event")
            put("event", event)
            put("timestamp", System.currentTimeMillis())
            put("listening", listening)
        }.toString()

        broadcastMessage(message)
        Log.d(TAG, "Recognition event broadcasted: $event")
    }

    /**
     * Broadcast recognition error
     */
    fun broadcastRecognitionError(errorCode: Int, errorMessage: String, autoRestart: Boolean) {
        val message = JSONObject().apply {
            put("type", "recognition_error")
            put("error_code", errorCode)
            put("error_message", errorMessage)
            put("timestamp", System.currentTimeMillis())
            put("auto_restart", autoRestart)
        }.toString()

        broadcastMessage(message)
        Log.w(TAG, "Recognition error broadcasted: $errorMessage (code: $errorCode)")
    }

    /**
     * Broadcast audio status (backward compatible)
     * Kept for compatibility with old clients
     */
    fun broadcastAudioStatus(listening: Boolean, audioLevel: Float) {
        val message = JSONObject().apply {
            put("type", "audio_status")
            put("listening", listening)
            put("audio_level", audioLevel.toDouble())
            put("timestamp", System.currentTimeMillis())
        }.toString()

        broadcastMessage(message)
    }
}
