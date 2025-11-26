# TODO - UH Speech Recognition App - Detailed Implementation Guide

## Phase 1: Project Setup & Dependencies

### 1.1 Update AndroidManifest.xml Permissions

**Location:** `app/src/main/AndroidManifest.xml`

**Add permissions:**
```xml
<!-- Already have: -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<!-- ADD THESE: -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
```

**Update service declaration:**
```xml
<service
    android:name=".UhService"
    android:enabled="true"
    android:exported="false"
    android:foregroundServiceType="microphone"  <!-- CHANGE FROM: connectedDevice -->
    android:permission="android.permission.BIND_JOB_SERVICE" />
```

**Runtime Permission Handling:**
- RECORD_AUDIO is dangerous permission, requires runtime request
- Add to MainActivity.onCreate() before starting service:
```kotlin
private fun checkAudioPermission() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
        != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(this, 
            arrayOf(Manifest.permission.RECORD_AUDIO), 
            RECORD_AUDIO_REQUEST_CODE)
    } else {
        startService()
    }
}

override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, 
                                       grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == RECORD_AUDIO_REQUEST_CODE && 
        grantResults.isNotEmpty() && 
        grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        startService()
    } else {
        // Show error, cannot function without microphone
        addLog("ERROR: Microphone permission denied")
    }
}

companion object {
    private const val RECORD_AUDIO_REQUEST_CODE = 1001
}
```

### 1.2 Add Dependencies to build.gradle.kts

**Location:** `app/build.gradle.kts`

**Add to dependencies block:**
```kotlin
dependencies {
    // Existing dependencies (keep these)
    implementation("org.java-websocket:Java-WebSocket:1.5.3")
    implementation("org.json:json:20230227")
    
    // Speech Recognition - WhisperKit Android
    implementation("com.argmaxinc:whisperkit:0.3.3")
    implementation("com.qualcomm.qnn:qnn-runtime:2.34.0")
    implementation("com.qualcomm.qnn:qnn-litert-delegate:2.34.0")
    
    // Text Embeddings - Check Maven Central for latest version
    // implementation("com.ml.shubham0204:sentence-embeddings:1.0.0")  // TODO: Find actual version
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.3")
    
    // Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    
    // Lifecycle components (if not already present)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-service:2.6.2")
}
```

**Add JNI library packaging (for native libs):**
```kotlin
android {
    // ... existing config
    
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}
```

**Note on Sentence-Embeddings-Android:**
- Library may not be on Maven Central yet
- Option A: Use JitPack or copy AAR manually
- Option B: Use ONNX Runtime directly with manual tokenization
- We'll implement Option B initially for more control

### 1.3 Update Foreground Service Type

**Location:** `app/src/main/kotlin/com/yourpackage/UhService.kt`

**Update notification creation:**
```kotlin
private fun createNotification(): Notification {
    val channelId = "uh_service_channel"
    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            channelId,
            "UH Speech Recognition Service",  // UPDATE: More descriptive
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Continuous speech recognition and embedding generation"  // UPDATE
        }
        notificationManager.createNotificationChannel(channel)
    }
    
    val notificationIntent = Intent(this, MainActivity::class.java)
    val pendingIntent = PendingIntent.getActivity(
        this, 0, notificationIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    
    return NotificationCompat.Builder(this, channelId)
        .setContentTitle("UH Speech Recognition")  // UPDATE
        .setContentText("Listening and processing speech...")  // UPDATE
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentIntent(pendingIntent)
        .setOngoing(true)
        .build()
}

override fun onCreate() {
    super.onCreate()
    val notification = createNotification()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(NOTIFICATION_ID, notification, 
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)  // UPDATE: Explicit type
    } else {
        startForeground(NOTIFICATION_ID, notification)
    }
}
```

### 1.4 Verify Compatibility

**Check gradle.properties:**
```properties
android.useAndroidX=true
android.enableJetifier=true
kotlin.code.style=official
```

**Check build.gradle.kts (app module):**
```kotlin
android {
    namespace = "com.yourpackage"
    compileSdk = 34
    
    defaultConfig {
        applicationId = "com.yourpackage.uh"
        minSdk = 31  // Android 12
        targetSdk = 34  // Android 14
        versionCode = 1
        versionName = "1.0"
        
        // Support for native libraries
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }
    
    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }
}
```

**Checklist:**
- [ ] AndroidManifest.xml updated with RECORD_AUDIO and FOREGROUND_SERVICE_MICROPHONE
- [ ] Service type changed to "microphone"
- [ ] Runtime permission request added to MainActivity
- [ ] Dependencies added to build.gradle.kts
- [ ] JNI packaging configured
- [ ] Gradle sync successful
- [ ] Build successful (may have missing classes, that's ok)

---

## Phase 2: Model Management

### 2.1 Create ModelManager Class

**Purpose:** Centralized model download, storage, loading, and lifecycle management

**Location:** Create `app/src/main/kotlin/com/yourpackage/ml/ModelManager.kt`

**Architecture:**
```kotlin
package com.yourpackage.ml

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest

/**
 * Manages ML model downloads, verification, and loading
 * Thread-safe singleton for app-wide model access
 */
class ModelManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "ModelManager"
        private const val MODELS_DIR = "models"
        
        // Model download URLs (HuggingFace or GitHub releases)
        private const val WHISPER_BASE_URL = "https://huggingface.co/argmaxinc/whisperkit-coreml/resolve/main/openai_whisper-base/model.onnx"
        private const val EMBEDDING_MODEL_URL = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx"
        private const val TOKENIZER_URL = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/tokenizer.json"
        
        // Expected checksums (SHA-256)
        private const val WHISPER_BASE_SHA256 = "YOUR_CHECKSUM_HERE"  // TODO: Get actual checksum
        private const val EMBEDDING_SHA256 = "YOUR_CHECKSUM_HERE"
        private const val TOKENIZER_SHA256 = "YOUR_CHECKSUM_HERE"
        
        @Volatile
        private var instance: ModelManager? = null
        
        fun getInstance(context: Context): ModelManager {
            return instance ?: synchronized(this) {
                instance ?: ModelManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    // Model file references
    private val modelsDir: File = File(context.filesDir, MODELS_DIR)
    val whisperModelFile: File = File(modelsDir, "whisper_base.onnx")
    val embeddingModelFile: File = File(modelsDir, "embedding.onnx")
    val tokenizerFile: File = File(modelsDir, "tokenizer.json")
    
    // Loading state
    @Volatile
    var isWhisperLoaded = false
        private set
    @Volatile
    var isEmbeddingLoaded = false
        private set
    
    // Progress listener
    interface DownloadListener {
        fun onProgress(model: String, progress: Float, downloaded: Long, total: Long)
        fun onComplete(model: String, file: File)
        fun onError(model: String, error: Exception)
    }
    
    init {
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
            Log.d(TAG, "Created models directory: ${modelsDir.absolutePath}")
        }
    }
    
    /**
     * Check if all required models are downloaded
     */
    fun areModelsDownloaded(): Boolean {
        return whisperModelFile.exists() && 
               embeddingModelFile.exists() && 
               tokenizerFile.exists()
    }
    
    /**
     * Download all required models
     * Suspending function for coroutine usage
     */
    suspend fun downloadAllModels(listener: DownloadListener?) = withContext(Dispatchers.IO) {
        try {
            if (!whisperModelFile.exists()) {
                downloadModel("Whisper Base", WHISPER_BASE_URL, whisperModelFile, 
                             WHISPER_BASE_SHA256, listener)
            } else {
                Log.d(TAG, "Whisper model already exists")
            }
            
            if (!embeddingModelFile.exists()) {
                downloadModel("Embedding Model", EMBEDDING_MODEL_URL, embeddingModelFile, 
                             EMBEDDING_SHA256, listener)
            } else {
                Log.d(TAG, "Embedding model already exists")
            }
            
            if (!tokenizerFile.exists()) {
                downloadModel("Tokenizer", TOKENIZER_URL, tokenizerFile, 
                             TOKENIZER_SHA256, listener)
            } else {
                Log.d(TAG, "Tokenizer already exists")
            }
            
            Log.i(TAG, "All models ready")
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed", e)
            throw e
        }
    }
    
    /**
     * Download single model with progress tracking
     */
    private suspend fun downloadModel(
        name: String,
        url: String,
        destination: File,
        expectedSha256: String?,
        listener: DownloadListener?
    ) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Downloading $name from $url")
        
        try {
            val connection = URL(url).openConnection()
            connection.connect()
            
            val fileLength = connection.contentLength
            val input = connection.getInputStream()
            val output = FileOutputStream(destination)
            
            val buffer = ByteArray(8192)
            var downloaded = 0L
            var read: Int
            
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                downloaded += read
                
                // Report progress on main thread
                val progress = downloaded.toFloat() / fileLength
                withContext(Dispatchers.Main) {
                    listener?.onProgress(name, progress, downloaded, fileLength.toLong())
                }
            }
            
            output.flush()
            output.close()
            input.close()
            
            Log.i(TAG, "Downloaded $name: ${destination.length()} bytes")
            
            // Verify checksum if provided
            if (expectedSha256 != null && !verifySha256(destination, expectedSha256)) {
                destination.delete()
                throw SecurityException("Checksum verification failed for $name")
            }
            
            withContext(Dispatchers.Main) {
                listener?.onComplete(name, destination)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download $name", e)
            destination.delete()  // Clean up partial download
            withContext(Dispatchers.Main) {
                listener?.onError(name, e)
            }
            throw e
        }
    }
    
    /**
     * Verify file checksum
     */
    private fun verifySha256(file: File, expectedSha256: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        
        val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        val matches = actualSha256.equals(expectedSha256, ignoreCase = true)
        
        if (!matches) {
            Log.e(TAG, "Checksum mismatch for ${file.name}")
            Log.e(TAG, "Expected: $expectedSha256")
            Log.e(TAG, "Actual:   $actualSha256")
        }
        
        return matches
    }
    
    /**
     * Delete all models (for cleanup or reset)
     */
    fun deleteAllModels() {
        whisperModelFile.delete()
        embeddingModelFile.delete()
        tokenizerFile.delete()
        isWhisperLoaded = false
        isEmbeddingLoaded = false
        Log.i(TAG, "All models deleted")
    }
    
    /**
     * Get total size of downloaded models
     */
    fun getTotalModelSize(): Long {
        var size = 0L
        if (whisperModelFile.exists()) size += whisperModelFile.length()
        if (embeddingModelFile.exists()) size += embeddingModelFile.length()
        if (tokenizerFile.exists()) size += tokenizerFile.length()
        return size
    }
}
```

### 2.2 Add Model Download UI

**Location:** Update `app/src/main/res/layout/activity_main.xml`

**Add progress UI elements:**
```xml
<!-- Add inside your existing LinearLayout, before log window -->

<LinearLayout
    android:id="@+id/modelDownloadContainer"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="16dp"
    android:visibility="gone">  <!-- Initially hidden -->
    
    <TextView
        android:id="@+id/modelDownloadStatus"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Downloading models..."
        android:textSize="16sp"
        android:textStyle="bold" />
    
    <ProgressBar
        android:id="@+id/modelDownloadProgress"
        style="?android:attr/progressBarStyleHorizontal"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:max="100" />
    
    <TextView
        android:id="@+id/modelDownloadDetails"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="4dp"
        android:text="0 MB / 0 MB"
        android:textSize="12sp" />
</LinearLayout>
```

### 2.3 Integrate ModelManager in UhService

**Location:** `app/src/main/kotlin/com/yourpackage/UhService.kt`

**Add model management:**
```kotlin
class UhService : Service() {
    // ... existing fields
    
    private lateinit var modelManager: ModelManager
    private var modelDownloadJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize model manager
        modelManager = ModelManager.getInstance(this)
        
        // Start foreground notification
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        // Check models and download if needed
        initializeModels()
    }
    
    private fun initializeModels() {
        if (modelManager.areModelsDownloaded()) {
            Log.i(TAG, "Models already downloaded, loading...")
            loadModels()
        } else {
            Log.i(TAG, "Models not found, starting download...")
            downloadModels()
        }
    }
    
    private fun downloadModels() {
        modelDownloadJob = serviceScope.launch {
            try {
                val listener = object : ModelManager.DownloadListener {
                    override fun onProgress(model: String, progress: Float, downloaded: Long, total: Long) {
                        listener?.onModelDownloadProgress(model, progress, downloaded, total)
                    }
                    
                    override fun onComplete(model: String, file: File) {
                        Log.i(TAG, "Model downloaded: $model -> ${file.name}")
                    }
                    
                    override fun onError(model: String, error: Exception) {
                        Log.e(TAG, "Model download failed: $model", error)
                        listener?.onError("Model download failed: ${error.message}")
                    }
                }
                
                modelManager.downloadAllModels(listener)
                Log.i(TAG, "All models downloaded successfully")
                
                // Now load models
                loadModels()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download models", e)
                listener?.onError("Failed to download models: ${e.message}")
            }
        }
    }
    
    private fun loadModels() {
        serviceScope.launch {
            try {
                listener?.onModelLoading("Loading models...")
                
                // Load WhisperKit model (will implement in Phase 4)
                // loadWhisperModel()
                
                // Load embedding model (will implement in Phase 5)
                // loadEmbeddingModel()
                
                listener?.onModelLoaded("Models loaded successfully")
                
                // Now we can start the actual service
                startAllComponents()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load models", e)
                listener?.onError("Failed to load models: ${e.message}")
            }
        }
    }
    
    override fun onDestroy() {
        modelDownloadJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
```

### 2.4 Update ServiceListener Interface

**Location:** `app/src/main/kotlin/com/yourpackage/UhService.kt`

**Add model-related callbacks:**
```kotlin
interface ServiceListener {
    // Existing callbacks
    fun onClientConnected(address: String, totalClients: Int)
    fun onClientDisconnected(address: String, totalClients: Int)
    fun onConfigChanged(key: String, value: String)
    fun onError(message: String)
    
    // NEW: Model management callbacks
    fun onModelDownloadProgress(model: String, progress: Float, downloaded: Long, total: Long)
    fun onModelLoading(status: String)
    fun onModelLoaded(status: String)
}
```

### 2.5 Update MainActivity to Show Progress

**Location:** `app/src/main/kotlin/com/yourpackage/MainActivity.kt`

**Implement new callbacks:**
```kotlin
class MainActivity : AppCompatActivity(), UhService.ServiceListener {
    // ... existing fields
    
    private lateinit var modelDownloadContainer: LinearLayout
    private lateinit var modelDownloadStatus: TextView
    private lateinit var modelDownloadProgress: ProgressBar
    private lateinit var modelDownloadDetails: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // ... existing initialization
        
        // Initialize model download UI
        modelDownloadContainer = findViewById(R.id.modelDownloadContainer)
        modelDownloadStatus = findViewById(R.id.modelDownloadStatus)
        modelDownloadProgress = findViewById(R.id.modelDownloadProgress)
        modelDownloadDetails = findViewById(R.id.modelDownloadDetails)
        
        // ... rest of onCreate
    }
    
    // NEW: Model download progress
    override fun onModelDownloadProgress(model: String, progress: Float, downloaded: Long, total: Long) {
        runOnUiThread {
            modelDownloadContainer.visibility = View.VISIBLE
            modelDownloadStatus.text = "Downloading $model..."
            modelDownloadProgress.progress = (progress * 100).toInt()
            
            val downloadedMB = downloaded / (1024 * 1024)
            val totalMB = total / (1024 * 1024)
            modelDownloadDetails.text = "$downloadedMB MB / $totalMB MB"
        }
    }
    
    // NEW: Model loading status
    override fun onModelLoading(status: String) {
        runOnUiThread {
            modelDownloadContainer.visibility = View.VISIBLE
            modelDownloadStatus.text = status
            modelDownloadProgress.isIndeterminate = true
            modelDownloadDetails.text = "Please wait..."
            addLog(status)
        }
    }
    
    // NEW: Model loaded
    override fun onModelLoaded(status: String) {
        runOnUiThread {
            modelDownloadContainer.visibility = View.GONE
            addLog(status)
        }
    }
    
    // ... existing callbacks
}
```

**Checklist:**
- [ ] ModelManager class created with download/verify/load methods
- [ ] Model download UI added to activity_main.xml
- [ ] ModelManager integrated in UhService
- [ ] ServiceListener extended with model callbacks
- [ ] MainActivity updated to show download progress
- [ ] Test model download (will download ~100MB total)
- [ ] Verify models stored in /data/data/com.yourpackage/files/models/

**Testing:**
```bash
# Clear models for testing
adb shell rm -rf /data/data/com.yourpackage/files/models/

# Reinstall and watch logs
make install
make logs

# Check model files
adb shell ls -lh /data/data/com.yourpackage/files/models/
```

---

## Phase 3: Audio Capture

### 3.1 Create AudioCaptureManager Class

**Purpose:** Continuous audio capture from microphone with real-time processing

**Location:** Create `app/src/main/kotlin/com/yourpackage/audio/AudioCaptureManager.kt`

**Key Requirements:**
- 16kHz sample rate (Whisper requirement)
- Mono channel (single microphone)
- 16-bit PCM encoding
- Continuous capture without gaps
- Audio level monitoring for UI
- Thread-safe buffer management

**Architecture:**
```kotlin
package com.yourpackage.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Manages continuous audio capture from microphone
 * Thread-safe, handles buffer management and audio level monitoring
 */
class AudioCaptureManager {
    
    companion object {
        private const val TAG = "AudioCaptureManager"
        
        // Audio configuration (Whisper requirements)
        private const val SAMPLE_RATE = 16000  // 16kHz
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        
        // Buffer size: 100ms worth of audio = 1600 samples = 3200 bytes
        private const val BUFFER_SIZE_MS = 100
        private const val BUFFER_SIZE_SAMPLES = SAMPLE_RATE * BUFFER_SIZE_MS / 1000
        private const val BUFFER_SIZE_BYTES = BUFFER_SIZE_SAMPLES * 2  // 16-bit = 2 bytes per sample
    }
    
    // Audio recording
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    
    // Audio level monitoring
    @Volatile
    private var currentAudioLevel: Float = 0f
    
    // Audio data listener
    interface AudioDataListener {
        /**
         * Called when new audio data is available
         * @param audioData PCM 16-bit samples (short array)
         * @param sampleRate Sample rate (always 16000 Hz)
         * @param timestamp Capture timestamp in milliseconds
         */
        fun onAudioData(audioData: ShortArray, sampleRate: Int, timestamp: Long)
        
        /**
         * Called periodically with audio level for visualization
         * @param level Audio level 0.0 (silence) to 1.0 (max)
         */
        fun onAudioLevel(level: Float)
        
        /**
         * Called when audio capture encounters an error
         */
        fun onError(error: Exception)
    }
    
    private var listener: AudioDataListener? = null
    
    /**
     * Initialize AudioRecord (must call before start)
     * Returns minimum buffer size or throws exception
     */
    fun initialize(): Int {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            throw IllegalStateException("Audio recording not supported on this device")
        }
        
        // Use larger buffer size for stability (at least 2x minimum)
        val bufferSize = maxOf(minBufferSize, BUFFER_SIZE_BYTES * 4)
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,  // Optimized for speech
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )
        
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("Failed to initialize AudioRecord")
        }
        
        Log.i(TAG, "AudioRecord initialized: buffer=$bufferSize bytes, min=$minBufferSize bytes")
        return bufferSize
    }
    
    /**
     * Start continuous audio capture
     * @param listener Callback for audio data
     */
    fun startCapture(listener: AudioDataListener) {
        if (isRecording.get()) {
            Log.w(TAG, "Already recording")
            return
        }
        
        this.listener = listener
        
        if (audioRecord == null) {
            try {
                initialize()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize AudioRecord", e)
                listener.onError(e)
                return
            }
        }
        
        isRecording.set(true)
        
        recordingThread = Thread({
            captureLoop()
        }, "AudioCaptureThread").apply {
            priority = Thread.MAX_PRIORITY  // High priority for real-time audio
            start()
        }
        
        Log.i(TAG, "Audio capture started")
    }
    
    /**
     * Main capture loop - runs on background thread
     */
    private fun captureLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        
        val buffer = ShortArray(BUFFER_SIZE_SAMPLES)
        
        try {
            audioRecord?.startRecording()
            Log.d(TAG, "AudioRecord.startRecording() called")
            
            while (isRecording.get()) {
                val samplesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                
                if (samplesRead > 0) {
                    val timestamp = System.currentTimeMillis()
                    
                    // Calculate audio level (RMS)
                    val level = calculateAudioLevel(buffer, samplesRead)
                    currentAudioLevel = level
                    
                    // Notify listener with audio data
                    listener?.onAudioData(
                        buffer.copyOf(samplesRead),  // Copy only valid samples
                        SAMPLE_RATE,
                        timestamp
                    )
                    
                    // Periodically report audio level (every 10 buffers = ~1 second)
                    if (timestamp % 1000 < BUFFER_SIZE_MS) {
                        listener?.onAudioLevel(level)
                    }
                    
                } else if (samplesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                    Log.e(TAG, "AudioRecord read error: INVALID_OPERATION")
                    break
                } else if (samplesRead == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "AudioRecord read error: BAD_VALUE")
                    break
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in capture loop", e)
            listener?.onError(e)
        } finally {
            audioRecord?.stop()
            Log.d(TAG, "AudioRecord stopped")
        }
    }
    
    /**
     * Calculate audio level (RMS) for visualization
     * @return level 0.0 (silence) to 1.0 (max)
     */
    private fun calculateAudioLevel(buffer: ShortArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        val rms = sqrt(sum / length)
        // Normalize to 0.0-1.0 range (32767 is max value for 16-bit signed)
        return (rms / 32767.0).toFloat().coerceIn(0f, 1f)
    }
    
    /**
     * Stop audio capture
     */
    fun stopCapture() {
        if (!isRecording.get()) {
            Log.w(TAG, "Not recording")
            return
        }
        
        isRecording.set(false)
        
        // Wait for thread to finish
        recordingThread?.join(1000)
        recordingThread = null
        
        Log.i(TAG, "Audio capture stopped")
    }
    
    /**
     * Release resources (call when done with manager)
     */
    fun release() {
        stopCapture()
        
        audioRecord?.release()
        audioRecord = null
        
        listener = null
        
        Log.i(TAG, "AudioCaptureManager released")
    }
    
    /**
     * Check if currently recording
     */
    fun isRecording(): Boolean = isRecording.get()
    
    /**
     * Get current audio level (thread-safe)
     */
    fun getCurrentAudioLevel(): Float = currentAudioLevel
}
```

### 3.2 Add Simple VAD (Voice Activity Detection)

**Purpose:** Detect when speech is present to avoid processing silence

**Location:** Create `app/src/main/kotlin/com/yourpackage/audio/SimpleVAD.kt`

**Architecture:**
```kotlin
package com.yourpackage.audio

import kotlin.math.abs

/**
 * Simple energy-based Voice Activity Detection
 * Not sophisticated but sufficient for reducing unnecessary processing
 */
class SimpleVAD(
    private val threshold: Float = 0.02f,  // Energy threshold (0.0-1.0)
    private val minSpeechFrames: Int = 3    // Minimum consecutive frames to consider speech
) {
    
    private var speechFrameCount = 0
    private var silenceFrameCount = 0
    
    /**
     * Process audio frame and determine if speech is present
     * @param audioLevel Audio level from AudioCaptureManager (0.0-1.0)
     * @return true if speech detected, false if silence
     */
    fun processFrame(audioLevel: Float): Boolean {
        return if (audioLevel > threshold) {
            speechFrameCount++
            silenceFrameCount = 0
            speechFrameCount >= minSpeechFrames
        } else {
            silenceFrameCount++
            if (silenceFrameCount > minSpeechFrames) {
                speechFrameCount = 0
                false
            } else {
                // Maintain speech state for brief pauses
                speechFrameCount >= minSpeechFrames
            }
        }
    }
    
    /**
     * Reset VAD state
     */
    fun reset() {
        speechFrameCount = 0
        silenceFrameCount = 0
    }
}
```

### 3.3 Test Audio Capture (Without Recognition)

**Location:** Add test method in `UhService.kt`

**Purpose:** Verify audio capture works before integrating speech recognition

```kotlin
// Temporary test in UhService
private fun testAudioCapture() {
    val audioCaptureManager = AudioCaptureManager()
    
    try {
        audioCaptureManager.initialize()
        
        audioCaptureManager.startCapture(object : AudioCaptureManager.AudioDataListener {
            override fun onAudioData(audioData: ShortArray, sampleRate: Int, timestamp: Long) {
                // Just log summary for testing
                val avgSample = audioData.map { abs(it.toInt()) }.average()
                Log.d(TAG, "Audio: ${audioData.size} samples, avg=$avgSample, rate=$sampleRate")
            }
            
            override fun onAudioLevel(level: Float) {
                Log.d(TAG, "Audio level: $level")
                listener?.onAudioLevelChanged(level)
            }
            
            override fun onError(error: Exception) {
                Log.e(TAG, "Audio capture error", error)
                listener?.onError("Audio error: ${error.message}")
            }
        })
        
        // Let it run for 10 seconds
        Thread.sleep(10000)
        
        audioCaptureManager.stopCapture()
        audioCaptureManager.release()
        
        Log.i(TAG, "Audio capture test completed")
        
    } catch (e: Exception) {
        Log.e(TAG, "Audio capture test failed", e)
    }
}
```

### 3.4 Add Audio Level Visualization to UI

**Location:** Update `app/src/main/res/layout/activity_main.xml`

**Add audio level meter:**
```xml
<!-- Add after modelDownloadContainer -->

<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:padding="16dp">
    
    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Audio Level:"
        android:textSize="14sp" />
    
    <ProgressBar
        android:id="@+id/audioLevelMeter"
        style="?android:attr/progressBarStyleHorizontal"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:layout_marginStart="8dp"
        android:max="100"
        android:progress="0" />
    
    <TextView
        android:id="@+id/audioLevelText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="8dp"
        android:text="0%"
        android:textSize="12sp" />
</LinearLayout>

<View
    android:layout_width="match_parent"
    android:layout_height="1dp"
    android:background="#CCCCCC" />
```

### 3.5 Update ServiceListener for Audio

**Location:** `app/src/main/kotlin/com/yourpackage/UhService.kt`

**Add audio callback:**
```kotlin
interface ServiceListener {
    // ... existing callbacks
    
    // NEW: Audio level monitoring
    fun onAudioLevelChanged(level: Float)
}
```

### 3.6 Update MainActivity for Audio Level Display

**Location:** `app/src/main/kotlin/com/yourpackage/MainActivity.kt`

```kotlin
class MainActivity : AppCompatActivity(), UhService.ServiceListener {
    // ... existing fields
    
    private lateinit var audioLevelMeter: ProgressBar
    private lateinit var audioLevelText: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // ... existing initialization
        
        // Initialize audio level UI
        audioLevelMeter = findViewById(R.id.audioLevelMeter)
        audioLevelText = findViewById(R.id.audioLevelText)
    }
    
    // NEW: Audio level callback
    override fun onAudioLevelChanged(level: Float) {
        runOnUiThread {
            val percentage = (level * 100).toInt()
            audioLevelMeter.progress = percentage
            audioLevelText.text = "$percentage%"
        }
    }
    
    // ... existing callbacks
}
```

**Checklist:**
- [ ] AudioCaptureManager class created with 16kHz/mono/16-bit configuration
- [ ] SimpleVAD class created for basic voice detection
- [ ] Audio level monitoring implemented
- [ ] Test method added to verify audio capture
- [ ] Audio level meter added to UI
- [ ] ServiceListener extended with audio level callback
- [ ] MainActivity updated to display audio level
- [ ] Test audio capture (speak into phone, watch level meter)
- [ ] Verify continuous capture without gaps
- [ ] Check CPU usage (should be <5% for audio capture alone)

**Testing:**
```bash
# Install and watch logs
make install
make logs | grep -E "(AudioCapture|Audio:)"

# Watch for:
# - "AudioRecord initialized" message
# - Regular "Audio:" log entries with samples
# - "Audio level:" entries showing 0.0-1.0 range
# - Higher levels when speaking, lower when silent

# Check audio meter in UI
# - Should show 0% when silent
# - Should show 20-80% when speaking normally
# - Should update smoothly (10-20 times per second)
```

---
  - [ ] Audio level monitoring (for UI visualization)
  - [ ] Buffer management (circular buffer or streaming)
  - [ ] Voice Activity Detection (VAD) integration
  - [ ] Clean resource cleanup
- [ ] Test audio capture without recognition (verify levels)

## Phase 4: Speech Recognition Integration
- [ ] Create SpeechRecognitionManager class
  - [ ] WhisperKit initialization with base model
  - [ ] Audio data feeding (from AudioCaptureManager)
  - [ ] Streaming transcription (progressive segments)
  - [ ] Confidence score extraction
  - [ ] Timestamp tracking (segment start/end)
  - [ ] Error handling and recovery
- [ ] Test recognition pipeline (audio → text)
- [ ] Optimize for real-time performance (<500ms latency)
- [ ] Profile memory usage (target <2GB)

## Phase 5: Text Embedding Generation
- [ ] Create EmbeddingManager class
  - [ ] Sentence-Embeddings-Android initialization
  - [ ] Model and tokenizer loading
  - [ ] Text → embedding conversion (384 dims)
  - [ ] Batch processing optimization
  - [ ] Error handling
- [ ] Integrate with SpeechRecognitionManager (text → embedding)
- [ ] Test embedding generation (<50ms per phrase)

## Phase 6: WebSocket Integration
- [ ] Extend UhWebSocketServer for speech messages
  - [ ] Add "speech" message type handling
  - [ ] Add "audio_status" message type
  - [ ] Serialize embedding arrays to JSON
  - [ ] Broadcast speech recognition results
  - [ ] Maintain backward compatibility with configure messages
- [ ] Update RuntimeConfig with new variables:
  - [x] name (already have)
  - [ ] model (tiny/base/small)
  - [ ] language (en/multilingual)
  - [ ] vad_threshold (0.0-1.0)
  - [ ] listening (true/false)
- [ ] Test WebSocket broadcasting of speech messages

## Phase 7: Service Architecture
- [ ] Refactor UhService for speech recognition:
  - [x] WebSocket server (already have)
  - [x] mDNS registration (already have)
  - [ ] Remove random number generation
  - [ ] Remove ping task (keep ping frames)
  - [ ] Add ModelManager lifecycle
  - [ ] Add AudioCaptureManager lifecycle
  - [ ] Add SpeechRecognitionManager lifecycle
  - [ ] Add EmbeddingManager lifecycle
  - [ ] Coordinate pipeline: Audio → Speech → Embedding → Broadcast
  - [ ] Handle start/stop/pause listening
- [ ] Implement ServiceListener callbacks:
  - [x] onClientConnected/onClientDisconnected (already have)
  - [ ] onSpeechRecognized(text, embedding, timestamp)
  - [ ] onAudioLevelChanged(level)
  - [ ] onModelLoaded(modelName)
  - [ ] onListeningStateChanged(listening)
  - [x] onConfigChanged (already have)
  - [x] onError (already have)

## Phase 8: UI Implementation
- [ ] Update MainActivity layout:
  - [x] Service name display (already have)
  - [x] Connection indicator (already have)
  - [ ] Listening indicator (microphone active)
  - [ ] Audio level meter (visual bar or waveform)
  - [ ] Recognition display (scrollable text view for phrases)
  - [x] Log window (already have, extend for new events)
  - [ ] Model status display (loaded/loading/error)
- [ ] Implement UI callbacks from ServiceListener:
  - [ ] Update listening indicator
  - [ ] Update audio level meter (smooth animation)
  - [ ] Display recognized phrases in real-time
  - [ ] Show model loading progress
  - [ ] Handle errors gracefully
- [ ] Add UI controls:
  - [ ] Start/Stop listening button (optional, or always-on)
  - [ ] Model selection dropdown (optional)
  - [ ] Clear recognition display button

## Phase 9: Build & Testing
- [x] Makefile targets (already have, verify still work)
- [ ] Test on physical device with 8GB RAM
  - [ ] Verify model loading (<10s)
  - [ ] Verify real-time performance (<500ms latency)
  - [ ] Verify memory usage (<2GB)
  - [ ] Test continuous operation (1+ hour)
  - [ ] Test multiple WebSocket clients
- [ ] Test mDNS discovery from CLI client
- [ ] Test configuration changes via WebSocket
- [ ] Test error recovery (disconnect/reconnect)

## Phase 10: CLI Client Updates
- [ ] Update uhcli for speech messages:
  - [x] mDNS discovery (already have)
  - [x] WebSocket connection (already have)
  - [ ] Parse "speech" message type
  - [ ] Parse "audio_status" message type
  - [ ] Display recognized text with timestamps
  - [ ] Display embeddings (truncated/optional)
  - [ ] Display confidence scores
  - [ ] Add --full flag for verbose output
  - [ ] Add --json flag for raw JSON output
- [ ] Update configuration commands:
  - [x] set/get (already have)
  - [ ] Add new config variables (model, language, vad_threshold, listening)
- [ ] Test CLI with Android app

## Phase 11: Optimization & Polish
- [ ] Profile and optimize hot paths:
  - [ ] Audio capture buffering
  - [ ] WhisperKit inference
  - [ ] Embedding generation
  - [ ] WebSocket serialization
- [ ] Memory leak detection and fixes
- [ ] Battery usage optimization (line power assumed, but still)
- [ ] Audio quality tuning (sample rate, bit depth)
- [ ] VAD threshold tuning for best accuracy/latency
- [ ] Error messages and user feedback
- [ ] Log verbosity levels

## Phase 12: Documentation & Deployment
- [x] Update SPEC.md (done)
- [ ] Update CLAUDE.md with implementation notes
- [ ] Update README.md with usage instructions
- [ ] Document model selection guide (tiny/base/small trade-offs)
- [ ] Document CLI usage examples
- [ ] Create APK release build
- [ ] Test deployment on clean device

## Known Challenges & Questions
- WhisperKit QNN acceleration requires Qualcomm device - what if device incompatible?
- Model download on first launch - need progress UI and error handling
- Real-time performance tuning - may need to adjust buffer sizes, model size
- WebSocket message size - embedding arrays are 384 floats (~1.5KB per message)
- Memory pressure - need to test on actual 8GB device under load
- Audio permission handling - runtime permission request flow
- Service lifecycle - ensure clean shutdown and restart
