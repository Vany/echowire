package com.uh.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.uh.R
import com.uh.config.RuntimeConfig
import com.uh.config.UhConfig
import com.uh.ml.EnhancedAndroidSpeechRecognizer
import com.uh.network.MdnsAdvertiser
import com.uh.network.UhWebSocketServer
import com.uh.ui.MainActivity
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Foreground service: Android STT → WebSocket broadcast.
 * No ML models, no embeddings. Pure platform speech recognition, streamed as fast as possible.
 */
class UhService : Service(), EnhancedAndroidSpeechRecognizer.RecognitionEventListener {

    companion object {
        private const val TAG = "UhService"
        private const val NOTIFICATION_CHANNEL_ID = "echowire_service_channel"
        private const val NOTIFICATION_ID = 1
        private const val AUDIO_LEVEL_THROTTLE_MS = 30L  // ~33 Hz max
    }

    interface ServiceListener {
        fun onServiceStarted(port: Int)
        fun onServiceStopped()
        fun onClientConnected(address: String, totalClients: Int)
        fun onClientDisconnected(address: String, totalClients: Int)
        fun onError(message: String, exception: Exception?)
        fun onConfigChanged(key: String, value: String?)
        fun onAudioLevelChanged(rmsDb: Float)
        fun onListeningStateChanged(listening: Boolean)
        fun onPartialResult(text: String)
        fun onFinalResult(text: String, confidence: Float, language: String)
    }

    // Binding
    private val binder = LocalBinder()
    private var listener: ServiceListener? = null

    // Config
    private val config: UhConfig = UhConfig.DEFAULT
    private val runtimeConfig = RuntimeConfig()

    // Speech recognition
    private var speechRecognizer: EnhancedAndroidSpeechRecognizer? = null
    private var currentLanguage = "en-US"

    // Session timing (passed through to WebSocket messages)
    private var sessionStartTime = 0L
    private var speechStartTime = 0L

    // Track last partial result to send only new words
    private var lastPartialText = ""
    private var sentWords = mutableSetOf<String>()  // Track all sent words in this session

    // Network
    private var webSocketServer: UhWebSocketServer? = null
    private var serverPort: Int = 0
    private var mdnsAdvertiser: MdnsAdvertiser? = null

    // System
    private var wakeLock: PowerManager.WakeLock? = null
    private var scheduler: ScheduledExecutorService? = null
    private var lastAudioLevelBroadcast = 0L
    private var isRunning = false

    inner class LocalBinder : Binder() {
        fun getService(): UhService = this@UhService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        runtimeConfig.addListener(object : RuntimeConfig.ConfigChangeListener {
            override fun onConfigChanged(key: String, value: String?) {
                listener?.onConfigChanged(key, value)
                if (key == "language" && value != null) {
                    changeLanguage(value)
                }
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            startForegroundService()
            startAllComponents()
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

    // Public API

    fun setListener(l: ServiceListener?) {
        listener = l
    }

    fun getClientCount(): Int = webSocketServer?.getClientCount() ?: 0
    fun getServerPort(): Int = serverPort
    fun isServiceRunning(): Boolean = isRunning
    fun getConfigValue(key: String): String? = runtimeConfig.get(key)

    fun setLanguage(languageCode: String) {
        if (currentLanguage != languageCode) {
            runtimeConfig.set("language", languageCode)
        }
    }

    fun getCurrentLanguage(): String = currentLanguage

    // Startup

    private fun startAllComponents() {
        startWebSocketServer()
        startScheduledTasks()
        initializeSpeechRecognition()
    }

    private fun startForegroundService() {
        createNotificationChannel()
        val notification = createNotification(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.i(TAG, "Foreground service started")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "EchoWire Speech Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Continuous speech recognition" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(port: Int): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val text = if (port > 0) "Listening on port $port" else "Starting..."
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("EchoWire")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(port: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, createNotification(port))
    }

    // WebSocket

    private fun startWebSocketServer() {
        try {
            serverPort = findAvailablePort(config.websocketStartPort, config.websocketMaxPortSearch)
            webSocketServer = UhWebSocketServer(
                port = serverPort,
                runtimeConfig = runtimeConfig,
                onClientConnect = { addr ->
                    val count = webSocketServer?.getClientCount() ?: 0
                    listener?.onClientConnected(addr, count)
                },
                onClientDisconnect = { addr ->
                    val count = webSocketServer?.getClientCount() ?: 0
                    listener?.onClientDisconnected(addr, count)
                },
                onError = { ex -> listener?.onError("WebSocket error", ex) }
            )
            webSocketServer?.start()
            updateNotification(serverPort)
            acquireWakeLock()
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
            try {
                ServerSocket(port).use { return port }
            } catch (_: Exception) {
            }
        }
        throw IllegalStateException("No available port in range $startPort-${startPort + maxAttempts}")
    }

    // mDNS

    private fun registerMdnsService() {
        try {
            mdnsAdvertiser = MdnsAdvertiser(
                context = this,
                serviceType = config.mdnsServiceType,
                serviceName = config.mdnsServiceName,
                onError = { msg -> listener?.onError(msg, null) }
            )
            mdnsAdvertiser?.register(serverPort)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create mDNS advertiser", e)
            listener?.onError("mDNS setup error: ${e.message}", e)
        }
    }

    private fun unregisterMdnsService() {
        try {
            mdnsAdvertiser?.unregister(); mdnsAdvertiser = null
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering mDNS", e)
        }
    }

    // Wake lock

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "UhService::WakeLock"
            )
            lock.acquire()
            wakeLock = lock
            Log.i(TAG, "Wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock", e)
        }
    }

    // Scheduled tasks

    private fun startScheduledTasks() {
        scheduler = Executors.newScheduledThreadPool(1)
        scheduler?.scheduleAtFixedRate(
            {
                try {
                    webSocketServer?.sendPingToAll()
                } catch (e: Exception) {
                    Log.e(TAG, "Ping error", e)
                }
            },
            config.pingIntervalMs, config.pingIntervalMs, TimeUnit.MILLISECONDS
        )
    }

    // Speech recognition

    private fun initializeSpeechRecognition() {
        try {
            speechRecognizer = EnhancedAndroidSpeechRecognizer(this, currentLanguage).apply {
                setListener(this@UhService)
                if (!initialize()) {
                    throw IllegalStateException("Speech recognition not available on this device")
                }
                setAutoRestart(true)
            }
            Log.i(TAG, "Speech recognition initialized (language: $currentLanguage)")
            speechRecognizer?.startListening()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize speech recognition", e)
            listener?.onError("Speech recognition init failed: ${e.message}", e)
        }
    }

    private fun changeLanguage(languageCode: String) {
        Log.i(TAG, "Changing language to: $languageCode")
        speechRecognizer?.release()
        currentLanguage = languageCode
        initializeSpeechRecognition()
    }

    // RecognitionEventListener callbacks

    override fun onPartialResult(partialText: String, timestamp: Long) {
        // Skip empty partial results
        if (partialText.isBlank()) return

        // Split into words and filter out already sent ones
        val currentWords = partialText.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
        val newWords = currentWords.filter { word -> !sentWords.contains(word) }

        // Only broadcast if there are new words
        if (newWords.isNotEmpty()) {
            val newText = newWords.joinToString(" ")
            webSocketServer?.broadcastPartialResult(newText, sessionStartTime)
            listener?.onPartialResult(newText)

            // Track sent words
            sentWords.addAll(newWords)
        }

        lastPartialText = partialText
    }

    override fun onFinalResult(
        results: List<String>,
        confidenceScores: FloatArray,
        timestamp: Long,
        sessionDurationMs: Long,
        speechDurationMs: Long
    ) {
        val bestText = results.firstOrNull() ?: ""
        val bestConfidence = confidenceScores.firstOrNull() ?: 0f

        // Send only new words from final result that weren't in partials
        if (bestText.isNotBlank()) {
            val finalWords = bestText.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
            val newWords = finalWords.filter { word -> !sentWords.contains(word) }

            if (newWords.isNotEmpty()) {
                val newText = newWords.joinToString(" ")
                webSocketServer?.broadcastPartialResult(newText, sessionStartTime)
                listener?.onPartialResult(newText)
            }
        }

        // Still broadcast full final result with metadata for clients that need it
        webSocketServer?.broadcastFinalResult(
            alternatives = results,
            confidenceScores = confidenceScores,
            language = currentLanguage,
            sessionStart = sessionStartTime,
            sessionDurationMs = sessionDurationMs,
            speechStart = speechStartTime,
            speechDurationMs = speechDurationMs
        )

        listener?.onFinalResult(bestText, bestConfidence, currentLanguage)
    }

    override fun onRmsChanged(rmsdB: Float, timestamp: Long) {
        if (timestamp - lastAudioLevelBroadcast >= AUDIO_LEVEL_THROTTLE_MS) {
            // Only update UI, don't broadcast to WebSocket clients
            lastAudioLevelBroadcast = timestamp
            listener?.onAudioLevelChanged(rmsdB)
        }
    }

    override fun onReadyForSpeech(timestamp: Long) {
        sessionStartTime = timestamp
        lastPartialText = ""  // Reset partial text tracker
        sentWords.clear()  // Reset sent words tracker for new session
        // Don't broadcast recognition_event
    }

    override fun onBeginningOfSpeech(timestamp: Long) {
        speechStartTime = timestamp
        // Don't broadcast recognition_event
    }

    override fun onEndOfSpeech(timestamp: Long) {
        // Don't broadcast recognition_event
    }

    override fun onError(errorCode: Int, errorMessage: String, timestamp: Long) {
        // Skip "No speech match" errors (code 7) - this is normal when user is silent
        if (errorCode == 7) return

        val autoRestart = speechRecognizer?.isListening() == false
        webSocketServer?.broadcastRecognitionError(errorCode, errorMessage, autoRestart)
        listener?.onError("Recognition error: $errorMessage", null)
    }

    override fun onStateChanged(isListening: Boolean) {
        listener?.onListeningStateChanged(isListening)
        // Don't broadcast recognition_event
    }

    // Shutdown

    private fun stopAllComponents() {
        speechRecognizer?.release()
        speechRecognizer = null

        scheduler?.shutdown()
        try {
            scheduler?.awaitTermination(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        scheduler = null

        unregisterMdnsService()
        releaseWakeLock()

        webSocketServer?.shutdown()
        webSocketServer = null

        listener?.onServiceStopped()
        Log.i(TAG, "All components stopped")
    }
}
