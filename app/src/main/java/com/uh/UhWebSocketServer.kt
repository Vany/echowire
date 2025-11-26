package com.uh

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
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
        onClientConnect(clientAddress)
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
}
