package com.echowire.service

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
import com.echowire.R
import com.echowire.config.RuntimeConfig
import com.echowire.config.EchoWireConfig
import com.echowire.ml.AndroidSttBackend
import com.echowire.ml.AudioLevelMonitor
import com.echowire.ml.PerceptSttBackend
import com.echowire.ml.SttBackend
import com.echowire.ml.SttListener
import com.echowire.network.MdnsAdvertiser
import com.echowire.network.EchoWireWebSocketServer
import com.echowire.ui.MainActivity
import com.percept.OwnerProfileImpl
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Foreground service: STT backend → WebSocket broadcast.
 * Supports switching between Android platform STT and Percept on-device STT.
 */
class EchoWireService : Service(), SttListener {

    companion object {
        private const val TAG = "EchoWireService"
        private const val NOTIFICATION_CHANNEL_ID = "echowire_service_channel"
        private const val NOTIFICATION_ID = 1
        private const val AUDIO_LEVEL_THROTTLE_MS = 30L
        private const val PROFILE_FILENAME = "owner_profile.bin"
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
        fun onFinalResult(text: String, confidence: Float, language: String, sentenceType: String?)
        fun onBackendChanged(backendName: String)
    }

    // Binding
    private val binder = LocalBinder()
    private var listener: ServiceListener? = null

    // Config
    private val config: EchoWireConfig = EchoWireConfig.DEFAULT
    private val runtimeConfig = RuntimeConfig()

    // STT backends
    private var androidBackend: AndroidSttBackend? = null
    private var perceptBackend: PerceptSttBackend? = null
    private var currentBackend: SttBackend? = null

    // Owner profile (shared, persisted)
    val ownerProfile = OwnerProfileImpl()
    private val profilePath by lazy { File(filesDir, PROFILE_FILENAME).absolutePath }

    // Session timing (passed through to WebSocket messages)
    private var sessionStartTime = 0L

    // Track last partial result to send only new words
    private var lastPartialText = ""
    private var sentWords = mutableSetOf<String>()

    // Audio level metering — own AudioRecord, independent of STT backend
    private var audioLevelMonitor: AudioLevelMonitor? = null

    // Network
    private var webSocketServer: EchoWireWebSocketServer? = null
    private var serverPort: Int = 0
    private var mdnsAdvertiser: MdnsAdvertiser? = null

    // System
    private var wakeLock: PowerManager.WakeLock? = null
    private var scheduler: ScheduledExecutorService? = null
    private var lastAudioLevelBroadcast = 0L
    private var isRunning = false

    inner class LocalBinder : Binder() {
        fun getService(): EchoWireService = this@EchoWireService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        // Load persisted owner profile
        if (File(profilePath).exists()) {
            try {
                ownerProfile.load(profilePath)
                Log.i(TAG, "Owner profile loaded (samples: inferred from mean)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load owner profile", e)
            }
        }

        runtimeConfig.addListener(object : RuntimeConfig.ConfigChangeListener {
            override fun onConfigChanged(key: String, value: String?) {
                listener?.onConfigChanged(key, value)
                if (key == "language" && value != null) {
                    // Language change only applies to Android backend
                    (currentBackend as? AndroidSttBackend)?.let {
                        it.setLanguage(value)
                    }
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

    fun setListener(l: ServiceListener?) { listener = l }
    fun getClientCount(): Int = webSocketServer?.getClientCount() ?: 0
    fun getServerPort(): Int = serverPort
    fun isServiceRunning(): Boolean = isRunning
    fun getConfigValue(key: String): String? = runtimeConfig.get(key)
    fun getActiveBackendName(): String = currentBackend?.displayName ?: "none"

    fun setLanguage(languageCode: String) {
        runtimeConfig.set("language", languageCode)
    }

    fun getCurrentLanguage(): String {
        return (currentBackend as? AndroidSttBackend)?.getLanguage() ?: "auto"
    }

    fun saveOwnerProfile() {
        try {
            ownerProfile.save(profilePath)
            Log.i(TAG, "Owner profile saved")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save owner profile", e)
        }
    }

    // Backend switching

    fun switchToAndroidStt(language: String = "en-US") {
        currentBackend?.stop()
        val backend = androidBackend ?: AndroidSttBackend(this, language).also { androidBackend = it }
        backend.setListener(this)
        backend.start()
        currentBackend = backend
        resetPartialTracking()
        listener?.onBackendChanged(backend.displayName)
        Log.i(TAG, "Switched to Android STT")
    }

    fun switchToPercept() {
        currentBackend?.stop()
        val backend = perceptBackend
            ?: PerceptSttBackend(this, ownerProfile).also { perceptBackend = it }
        backend.setListener(this)
        backend.start()
        currentBackend = backend
        resetPartialTracking()
        listener?.onBackendChanged(backend.displayName)
        Log.i(TAG, "Switched to Percept")
    }

    fun stopCurrentBackend() {
        currentBackend?.stop()
        currentBackend = null
    }

    // Startup

    private fun startAllComponents() {
        startWebSocketServer()
        startScheduledTasks()
        startAudioLevelMonitor()
        // Default to Android STT
        switchToAndroidStt()
    }

    private fun startAudioLevelMonitor() {
        val monitor = AudioLevelMonitor { dBFS ->
            val now = System.currentTimeMillis()
            if (now - lastAudioLevelBroadcast >= AUDIO_LEVEL_THROTTLE_MS) {
                lastAudioLevelBroadcast = now
                listener?.onAudioLevelChanged(dBFS)
            }
        }
        monitor.start()
        audioLevelMonitor = monitor
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
            webSocketServer = EchoWireWebSocketServer(
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
            } catch (_: Exception) {}
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
            // PARTIAL_WAKE_LOCK: keeps CPU running for STT pipeline.
            // Screen-on is handled by FLAG_KEEP_SCREEN_ON in MainActivity.
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EchoWireService::WakeLock")
            lock.acquire()
            wakeLock = lock
            Log.i(TAG, "CPU wake lock acquired")
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
                try { webSocketServer?.sendPingToAll() }
                catch (e: Exception) { Log.e(TAG, "Ping error", e) }
            },
            config.pingIntervalMs, config.pingIntervalMs, TimeUnit.MILLISECONDS
        )
    }

    // SttListener callbacks

    private fun resetPartialTracking() {
        sessionStartTime = System.currentTimeMillis()
        lastPartialText = ""
        sentWords.clear()
    }

    override fun onPartialResult(text: String, language: String, timestampMs: Long) {
        if (text.isBlank()) return

        val currentWords = text.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
        val newWords = currentWords.filter { word -> !sentWords.contains(word) }

        if (newWords.isNotEmpty()) {
            val newText = newWords.joinToString(" ")
            webSocketServer?.broadcastPartialResult(newText, sessionStartTime)
            listener?.onPartialResult(newText)
            sentWords.addAll(newWords)
        }

        lastPartialText = text
    }

    override fun onFinalResult(
        text: String,
        alternatives: List<String>,
        confidences: FloatArray,
        language: String,
        sentenceType: String?,
        timestampMs: Long,
        sessionDurationMs: Long,
        speechDurationMs: Long,
    ) {
        val bestConfidence = confidences.firstOrNull() ?: 0f

        // Send remaining new words from final result
        if (text.isNotBlank()) {
            val finalWords = text.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
            val newWords = finalWords.filter { word -> !sentWords.contains(word) }
            if (newWords.isNotEmpty()) {
                val newText = newWords.joinToString(" ")
                webSocketServer?.broadcastPartialResult(newText, sessionStartTime)
                listener?.onPartialResult(newText)
            }
        }

        webSocketServer?.broadcastFinalResult(
            alternatives = alternatives,
            confidenceScores = confidences,
            language = language,
            sentenceType = sentenceType,
            sessionStart = sessionStartTime,
            sessionDurationMs = sessionDurationMs,
            speechStart = sessionStartTime,
            speechDurationMs = speechDurationMs,
        )

        listener?.onFinalResult(text, bestConfidence, language, sentenceType)

        // Reset tracking for next utterance
        resetPartialTracking()
    }

    override fun onAudioLevel(rmsDb: Float, timestampMs: Long) {
        // Meter is driven by AudioLevelMonitor (real dBFS, backend-independent).
        // STT-library level callbacks are intentionally ignored here.
    }

    override fun onStateChanged(listening: Boolean) {
        listener?.onListeningStateChanged(listening)
    }

    override fun onError(code: Int, message: String, timestampMs: Long) {
        if (code == 7) return  // Skip "No speech match"
        webSocketServer?.broadcastRecognitionError(code, message, true)
        listener?.onError("Recognition error: $message", null)
    }

    // Shutdown

    private fun stopAllComponents() {
        currentBackend?.stop()
        currentBackend = null
        androidBackend = null
        perceptBackend = null

        scheduler?.shutdown()
        try { scheduler?.awaitTermination(1, TimeUnit.SECONDS) }
        catch (_: InterruptedException) {}
        scheduler = null

        audioLevelMonitor?.stop()
        audioLevelMonitor = null

        unregisterMdnsService()
        releaseWakeLock()

        webSocketServer?.shutdown()
        webSocketServer = null

        listener?.onServiceStopped()
        Log.i(TAG, "All components stopped")
    }
}
