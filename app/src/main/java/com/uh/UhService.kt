package com.uh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.uh.audio.AudioCaptureManager
import com.uh.audio.SimpleVAD
import com.uh.ml.ModelManager
import com.uh.ml.SpeechRecognitionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineExceptionHandler
import org.json.JSONObject
import java.io.File
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
        fun onConfigChanged(key: String, value: String?)
        
        // Model management callbacks
        fun onModelDownloadProgress(modelName: String, progress: Float, downloaded: Long, total: Long)
        fun onModelLoading(status: String)
        fun onModelLoaded(status: String)
        
        // Audio capture callbacks
        fun onAudioLevelChanged(level: Float)
        fun onListeningStateChanged(listening: Boolean)
        
        // Speech recognition callbacks
        fun onTranscriptionReceived(text: String, language: String?, processingTimeMs: Long)
    }

    // Service binding
    private val binder = LocalBinder()
    private var listener: ServiceListener? = null

    // Configuration
    private val config: UhConfig = UhConfig.DEFAULT
    private val runtimeConfig = RuntimeConfig()

    // Model management
    private lateinit var modelManager: ModelManager
    private var modelDownloadJob: Job? = null
    
    // Coroutine exception handler for debugging
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        Log.e(TAG, "UNCAUGHT COROUTINE EXCEPTION: ${exception.javaClass.simpleName}: ${exception.message}", exception)
        exception.printStackTrace()
        listener?.onError("Uncaught error: ${exception.message}", exception as? Exception)
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob() + exceptionHandler)

    // Audio capture
    private var audioCaptureManager: AudioCaptureManager? = null
    private var vad: SimpleVAD? = null
    @Volatile
    private var isListening: Boolean = false
    
    // Speech recognition
    private var speechRecognitionManager: SpeechRecognitionManager? = null

    // WebSocket server
    private var webSocketServer: UhWebSocketServer? = null
    private var serverPort: Int = 0

    // mDNS registration
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // Wake lock for screen control
    private var wakeLock: PowerManager.WakeLock? = null

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
        
        // Initialize model manager
        modelManager = ModelManager.getInstance(this)
        
        // Register config change listener
        runtimeConfig.addListener(object : RuntimeConfig.ConfigChangeListener {
            override fun onConfigChanged(key: String, value: String?) {
                listener?.onConfigChanged(key, value)
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            startForegroundService()
            initializeModels()
            isRunning = true
        }
        return START_STICKY
    }
    
    /**
     * Initialize models - extract from assets if needed, then load
     */
    private fun initializeModels() {
        if (modelManager.areModelsExtracted()) {
            Log.i(TAG, "Models already extracted, loading...")
            loadModels()
        } else {
            Log.i(TAG, "Models not found, extracting from assets...")
            extractModels()
        }
    }
    
    /**
     * Extract all required models from bundled assets
     */
    private fun extractModels() {
        modelDownloadJob = serviceScope.launch {
            try {
                val listener = object : ModelManager.ExtractionListener {
                    override fun onProgress(modelName: String, progress: Float, extracted: Long, total: Long) {
                        this@UhService.listener?.onModelDownloadProgress(modelName, progress, extracted, total)
                    }
                    
                    override fun onComplete(modelName: String, file: File) {
                        Log.i(TAG, "Model extracted: $modelName -> ${file.name} (${file.length()} bytes)")
                    }
                    
                    override fun onError(modelName: String, error: Exception) {
                        Log.e(TAG, "Model extraction failed: $modelName", error)
                        this@UhService.listener?.onError("Model extraction failed: $modelName - ${error.message}", error)
                    }
                }
                
                modelManager.extractAllModels(listener)
                Log.i(TAG, "All models extracted successfully")
                
                // Now load models
                loadModels()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract models", e)
                this@UhService.listener?.onError("Failed to extract models: ${e.message}", e)
            }
        }
    }
    
    /**
     * Load models into memory and start service components
     */
    private fun loadModels() {
        serviceScope.launch {
            try {
                Log.i(TAG, "Starting model loading...")
                listener?.onModelLoading("Loading models...")
                
                // Get model file paths from ModelManager
                val whisperModelFile = modelManager.whisperModelFile
                val vocabFile = modelManager.whisperVocabFile
                val embeddingModelFile = modelManager.embeddingModelFile
                val embeddingVocabFile = modelManager.tokenizerFile
                
                Log.i(TAG, "Checking model files...")
                Log.i(TAG, "  Whisper model: ${whisperModelFile.absolutePath} (exists: ${whisperModelFile.exists()}, size: ${whisperModelFile.length()} bytes)")
                Log.i(TAG, "  Vocab file: ${vocabFile.absolutePath} (exists: ${vocabFile.exists()}, size: ${vocabFile.length()} bytes)")
                Log.i(TAG, "  Embedding model: ${embeddingModelFile.absolutePath} (exists: ${embeddingModelFile.exists()}, size: ${embeddingModelFile.length()} bytes)")
                Log.i(TAG, "  Tokenizer file: ${embeddingVocabFile.absolutePath} (exists: ${embeddingVocabFile.exists()}, size: ${embeddingVocabFile.length()} bytes)")
                
                if (!whisperModelFile.exists()) {
                    throw IllegalStateException("Whisper model not found: ${whisperModelFile.absolutePath}")
                }
                if (!vocabFile.exists()) {
                    throw IllegalStateException("Whisper vocab not found: ${vocabFile.absolutePath}")
                }
                if (!embeddingModelFile.exists()) {
                    throw IllegalStateException("Embedding model not found: ${embeddingModelFile.absolutePath}")
                }
                if (!embeddingVocabFile.exists()) {
                    throw IllegalStateException("Tokenizer not found: ${embeddingVocabFile.absolutePath}")
                }
                
                Log.i(TAG, "Creating SpeechRecognitionManager...")
                // Initialize SpeechRecognitionManager with all models
                speechRecognitionManager = SpeechRecognitionManager(
                    context = this@UhService,
                    modelFile = whisperModelFile,
                    vocabFile = vocabFile,
                    embeddingModelFile = embeddingModelFile,
                    embeddingVocabFile = embeddingVocabFile
                )
                
                Log.i(TAG, "Initializing models (this may take several seconds)...")
                // Load models (blocks until complete)
                speechRecognitionManager?.initialize()
                
                Log.i(TAG, "Models initialized successfully, setting up listener...")
                // Set recognition listener
                speechRecognitionManager?.setListener(object : SpeechRecognitionManager.RecognitionListener {
                    override fun onTranscription(
                        text: String,
                        embedding: FloatArray,
                        language: String?,
                        startTime: Long,
                        endTime: Long,
                        processingTimeMs: Long
                    ) {
                        handleTranscription(text, embedding, language, startTime, endTime, processingTimeMs)
                    }
                    
                    override fun onProcessingStarted() {
                        Log.d(TAG, "Speech processing started")
                    }
                    
                    override fun onError(error: Exception) {
                        Log.e(TAG, "Speech recognition error", error)
                        listener?.onError("Speech recognition error: ${error.message}", error)
                    }
                })
                
                modelManager.setWhisperLoaded(true)
                
                Log.i(TAG, "Whisper loaded, now loading embedding model...")
                // TODO: Load embedding model (Phase 5)
                // loadEmbeddingModel()
                modelManager.setEmbeddingLoaded(true)
                
                Log.i(TAG, "All models loaded successfully!")
                listener?.onModelLoaded("Models loaded successfully")
                
                // Now start WebSocket server and other components
                Log.i(TAG, "Starting all components...")
                startAllComponents()
                
            } catch (e: Exception) {
                Log.e(TAG, "CRITICAL: Failed to load models - ${e.javaClass.simpleName}: ${e.message}", e)
                e.printStackTrace()
                listener?.onError("Failed to load models: ${e.message}", e)
                // Don't crash the service, but log prominently
                Log.e(TAG, "Service will continue but speech recognition is NOT available")
            } catch (e: Error) {
                Log.e(TAG, "CRITICAL: Fatal error loading models - ${e.javaClass.simpleName}: ${e.message}", e)
                e.printStackTrace()
                listener?.onError("Fatal error loading models: ${e.message}", null)
            }
        }
    }
    
    /**
     * Start all service components after models are loaded
     */
    private fun startAllComponents() {
        startWebSocketServer()
        startScheduledTasks()
        initializeAudioCapture()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        
        // Cancel model download if in progress
        modelDownloadJob?.cancel()
        
        // Cancel coroutine scope
        serviceScope.cancel()
        
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

    fun getConfigValue(key: String): String? {
        return runtimeConfig.get(key)
    }

    private fun startForegroundService() {
        createNotificationChannel()
        val notification = createNotification(0)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        Log.i(TAG, "Foreground service started")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "UH Speech Recognition Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Continuous speech recognition and embedding generation"
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
                runtimeConfig = runtimeConfig,
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
            
            // Acquire wake lock to prevent screen from turning off
            acquireWakeLock()
            
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

    /**
     * Acquire wake lock to prevent screen from turning off while server is running.
     * Uses SCREEN_BRIGHT_WAKE_LOCK to keep screen on - device should be on wire power.
     */
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "UhService::WebSocketServerWakeLock"
            ).apply {
                acquire()
            }
            Log.i(TAG, "Wake lock acquired - screen will stay on")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
            listener?.onError("Wake lock acquisition failed: ${e.message}", e)
        }
    }

    /**
     * Release wake lock to allow screen to turn off normally.
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                    Log.i(TAG, "Wake lock released")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock", e)
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
    
    /**
     * Initialize audio capture system.
     * Creates AudioCaptureManager and VAD, but does not start capture yet.
     */
    private fun initializeAudioCapture() {
        try {
            audioCaptureManager = AudioCaptureManager()
            audioCaptureManager?.initialize()
            
            vad = SimpleVAD(
                threshold = 0.02f,
                minConsecutiveFrames = 3
            )
            
            Log.i(TAG, "Audio capture initialized")
            
            // Auto-start listening after initialization
            startListening()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize audio capture", e)
            listener?.onError("Audio initialization failed: ${e.message}", e)
        }
    }
    
    /**
     * Start continuous audio capture with VAD.
     */
    private fun startListening() {
        if (isListening) {
            Log.w(TAG, "Already listening")
            return
        }
        
        val manager = audioCaptureManager
        if (manager == null) {
            Log.e(TAG, "AudioCaptureManager not initialized")
            return
        }
        
        try {
            manager.startCapture(object : AudioCaptureManager.AudioDataListener {
                override fun onAudioData(audioData: ShortArray, sampleRate: Int, timestamp: Long) {
                    val currentVad = vad
                    val recognitionManager = speechRecognitionManager
                    
                    if (currentVad != null && recognitionManager != null) {
                        val level = manager.getCurrentAudioLevel()
                        val isSpeech = currentVad.processFrame(level)
                        
                        // Feed audio to speech recognition manager
                        recognitionManager.onAudioData(audioData, timestamp, isSpeech)
                        
                        if (isSpeech) {
                            Log.d(TAG, "Speech detected: level=$level, buffer=${recognitionManager.getBufferDuration()}s")
                        }
                    }
                }
                
                override fun onAudioLevel(level: Float) {
                    listener?.onAudioLevelChanged(level)
                }
                
                override fun onError(error: Exception) {
                    Log.e(TAG, "Audio capture error", error)
                    listener?.onError("Audio capture error: ${error.message}", error)
                    stopListening()
                }
            })
            
            isListening = true
            listener?.onListeningStateChanged(true)
            Log.i(TAG, "Listening started")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            listener?.onError("Failed to start listening: ${e.message}", e)
        }
    }
    
    /**
     * Stop audio capture.
     */
    private fun stopListening() {
        if (!isListening) {
            return
        }
        
        try {
            audioCaptureManager?.stopCapture()
            vad?.reset()
            isListening = false
            listener?.onListeningStateChanged(false)
            Log.i(TAG, "Listening stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping listening", e)
        }
    }
    
    /**
     * Handle transcription results from SpeechRecognitionManager.
     * Logs the result and notifies listener for UI updates.
     * 
     * @param text Recognized text
     * @param language Detected language code (e.g., "en", "ru")
     * @param startTime Audio start timestamp
     * @param endTime Audio end timestamp
     * @param processingTimeMs Time taken to process
     */
    private fun handleTranscription(
    private fun handleTranscription(
        text: String,
        embedding: FloatArray,
        language: String?,
        startTime: Long,
        endTime: Long,
        processingTimeMs: Long
    ) {
        val audioDurationMs = endTime - startTime
        val rtf = processingTimeMs.toFloat() / audioDurationMs.toFloat()
        
        Log.i(TAG, "Transcription: \"$text\"")
        Log.i(TAG, "  Language: $language")
        Log.i(TAG, "  Audio duration: ${audioDurationMs}ms")
        Log.i(TAG, "  Processing time: ${processingTimeMs}ms (RTF: ${"%.2f".format(rtf)})")
        Log.i(TAG, "  Embedding: ${embedding.size} dimensions (first 5: [${embedding.take(5).joinToString(", ") { "%.4f".format(it) }}])")
        
        // Notify listener for UI update
        listener?.onTranscriptionReceived(text, language, processingTimeMs)
        
        // TODO Phase 6: Broadcast via WebSocket
        // broadcastSpeechMessage(text, language, embedding, startTime, endTime)
    }

    private fun stopAllComponents() {
        // Stop audio capture
        stopListening()
        audioCaptureManager?.release()
        audioCaptureManager = null
        vad = null
        
        // Release speech recognition
        speechRecognitionManager?.release()
        speechRecognitionManager = null
        
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

        // Release wake lock
        releaseWakeLock()

        // Stop WebSocket server
        webSocketServer?.shutdown()
        webSocketServer = null

        listener?.onServiceStopped()
        Log.i(TAG, "All components stopped")
    }
}
