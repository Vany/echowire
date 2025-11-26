package com.uh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Foreground service that runs WebSocket server and advertises via mDNS.
 * Generates random numbers and pings on scheduled intervals.
 * Broadcasts events to bound UI components.
 */
class UhService : Service() {

    companion object {
        private const val TAG = "UhService"
        private const val NOTIFICATION_CHANNEL_ID = "uh_service_channel"
        private const val NOTIFICATION_ID = 1
    }

    /**
     * Service state listener for UI updates.
     * All callbacks are invoked on background threads - UI must handle thread safety.
     */
    interface ServiceListener {
        fun onServiceStarted(port: Int)
        fun onServiceStopped()
        fun onClientConnected(address: String, totalClients: Int)
        fun onClientDisconnected(address: String, totalClients: Int)
        fun onRandomNumberGenerated(value: Long, timestamp: Long)
        fun onError(message: String, exception: Exception?)
    }

    // Service binding
    private val binder = LocalBinder()
    private var listener: ServiceListener? = null

    // Configuration
    private val config: UhConfig = UhConfig.DEFAULT

    // WebSocket server
    private var webSocketServer: UhWebSocketServer? = null
    private var serverPort: Int = 0

    // mDNS registration
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // Scheduled tasks
    private var scheduler: ScheduledExecutorService? = null

    // Service state
    private var isRunning: Boolean = false

    inner class LocalBinder : Binder() {
        fun getService(): UhService = this@UhService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            startForegroundService()
            startWebSocketServer()
            startScheduledTasks()
            isRunning = true
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        stopAllComponents()
        isRunning = false
    }

    fun setListener(listener: ServiceListener?) {
        this.listener = listener
    }

    fun getClientCount(): Int {
        return webSocketServer?.getClientCount() ?: 0
    }

    fun getServerPort(): Int {
        return serverPort
    }

    fun isServiceRunning(): Boolean {
        return isRunning
    }

    private fun startForegroundService() {
        createNotificationChannel()
        val notification = createNotification(0)
        startForeground(NOTIFICATION_ID, notification)
        Log.i(TAG, "Foreground service started")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "UH Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "WebSocket server status"
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(port: Int): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (port > 0) {
            getString(R.string.service_notification_text, port)
        } else {
            "Starting..."
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(port: Int) {
        val notification = createNotification(port)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun startWebSocketServer() {
        try {
            // Find available port starting from configured port
            serverPort = findAvailablePort(
                config.websocketStartPort,
                config.websocketMaxPortSearch
            )

            webSocketServer = UhWebSocketServer(
                port = serverPort,
                onClientConnect = { address ->
                    val count = webSocketServer?.getClientCount() ?: 0
                    listener?.onClientConnected(address, count)
                },
                onClientDisconnect = { address ->
                    val count = webSocketServer?.getClientCount() ?: 0
                    listener?.onClientDisconnected(address, count)
                },
                onError = { exception ->
                    listener?.onError("WebSocket error", exception)
                }
            )

            webSocketServer?.start()
            updateNotification(serverPort)
            
            // Register mDNS service
            registerMdnsService()
            
            listener?.onServiceStarted(serverPort)
            Log.i(TAG, "WebSocket server started on port $serverPort")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WebSocket server", e)
            listener?.onError("Failed to start server", e)
            stopSelf()
        }
    }

    private fun findAvailablePort(startPort: Int, maxAttempts: Int): Int {
        for (port in startPort until (startPort + maxAttempts)) {
            if (isPortAvailable(port)) {
                return port
            }
        }
        throw IllegalStateException("No available port found in range $startPort-${startPort + maxAttempts}")
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (e: Exception) {
            false
        }
    }

    private fun registerMdnsService() {
        try {
            // Check if WiFi is connected
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wifiManager.isWifiEnabled) {
                Log.w(TAG, "WiFi is not enabled, mDNS registration may fail")
            }
            
            // Acquire multicast lock (required for mDNS on WiFi)
            multicastLock = wifiManager.createMulticastLock("UhService_mDNS").apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.d(TAG, "Multicast lock acquired")
            
            nsdManager = getSystemService(NsdManager::class.java)
            
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = config.mdnsServiceName
                serviceType = config.mdnsServiceType
                port = serverPort
            }
            
            Log.d(TAG, "Attempting mDNS registration: name=${serviceInfo.serviceName}, type=${serviceInfo.serviceType}, port=${serviceInfo.port}")

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    val errorMsg = when (errorCode) {
                        NsdManager.FAILURE_ALREADY_ACTIVE -> "Service already registered"
                        NsdManager.FAILURE_INTERNAL_ERROR -> "Internal error"
                        NsdManager.FAILURE_MAX_LIMIT -> "Max registrations reached"
                        else -> "Unknown error code: $errorCode"
                    }
                    Log.e(TAG, "mDNS registration failed: $errorMsg")
                    listener?.onError("mDNS registration failed: $errorMsg", null)
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "mDNS unregistration failed: $errorCode")
                }

                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "mDNS service registered: ${serviceInfo.serviceName}")
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "mDNS service unregistered: ${serviceInfo.serviceName}")
                }
            }

            nsdManager?.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                registrationListener
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to register mDNS service", e)
            listener?.onError("mDNS registration error: ${e.message}", e)
        }
    }

    private fun unregisterMdnsService() {
        try {
            registrationListener?.let { listener ->
                nsdManager?.unregisterService(listener)
            }
            registrationListener = null
            nsdManager = null
            
            // Release multicast lock
            multicastLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                    Log.d(TAG, "Multicast lock released")
                }
            }
            multicastLock = null
            
            Log.i(TAG, "mDNS service unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering mDNS service", e)
        }
    }

    private fun startScheduledTasks() {
        scheduler = Executors.newScheduledThreadPool(2)

        // Schedule random number generation
        scheduler?.scheduleAtFixedRate(
            { generateAndBroadcastRandomNumber() },
            0,
            config.randomNumberIntervalMs,
            TimeUnit.MILLISECONDS
        )

        // Schedule ping
        scheduler?.scheduleAtFixedRate(
            { sendPingToClients() },
            config.pingIntervalMs,
            config.pingIntervalMs,
            TimeUnit.MILLISECONDS
        )

        Log.i(TAG, "Scheduled tasks started")
    }

    private fun generateAndBroadcastRandomNumber() {
        try {
            val randomValue = Random.nextLong(0, 1_000_000)
            val timestamp = System.currentTimeMillis()

            val message = JSONObject().apply {
                put("type", "random")
                put("value", randomValue)
                put("timestamp", timestamp)
            }.toString()

            webSocketServer?.broadcastMessage(message)
            listener?.onRandomNumberGenerated(randomValue, timestamp)

        } catch (e: Exception) {
            Log.e(TAG, "Error generating random number", e)
            listener?.onError("Random number generation error", e)
        }
    }

    private fun sendPingToClients() {
        try {
            webSocketServer?.sendPingToAll()
            Log.d(TAG, "Ping sent to all clients")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending ping", e)
        }
    }

    private fun stopAllComponents() {
        // Stop scheduled tasks
        scheduler?.shutdown()
        try {
            scheduler?.awaitTermination(1, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Scheduler shutdown interrupted", e)
        }
        scheduler = null

        // Unregister mDNS
        unregisterMdnsService()

        // Stop WebSocket server
        webSocketServer?.shutdown()
        webSocketServer = null

        listener?.onServiceStopped()
        Log.i(TAG, "All components stopped")
    }
}
