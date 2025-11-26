# CLAUDE Memory - UH Project

## Target Device Constraints

### Hardware: Samsung Galaxy Note20 (SM-N980F)
- **SoC**: Exynos 990 (NOT Qualcomm Snapdragon)
- **Architecture**: ARM64-v8a
- **RAM**: ~7.45 GB
- **Android**: 12
- **GPU**: Mali-G77 MP11
- **NPU**: Available via Android NNAPI

### Critical Limitation: No Qualcomm QNN Support
The target device uses Samsung Exynos 990, which means:
- **Cannot use** Qualcomm Neural Network (QNN) SDK
- **Cannot use** WhisperKit Android with QNN acceleration
- **Must use** TensorFlow Lite or ONNX Runtime
- **Can leverage** Samsung NPU via Android NNAPI delegation

### ML Framework Choice: TensorFlow Lite
**Why TensorFlow Lite:**
- Works on all Android devices (CPU, GPU, NPU)
- NNAPI delegation for Samsung NPU acceleration
- Mature ecosystem with proven Whisper models
- GPU acceleration via OpenGL/Vulkan on Mali
- Well-documented model conversion process

**Acceleration Options:**
1. CPU (baseline, always works)
2. GPU via TFLite GPU delegate (Mali-G77 support)
3. NPU via NNAPI delegate (Samsung Neural Processing)

### Dependencies for Exynos
```kotlin
// TensorFlow Lite for Whisper inference
implementation("org.tensorflow:tensorflow-lite:2.14.0")
implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")

// ONNX Runtime for embeddings (cross-platform)
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.3")
```

### Model Bundling Strategy
**Decision: Bundle models in APK (no network dependency)**
- Models placed in `app/src/main/assets/models/`
- Extracted to internal storage on first run
- Whisper tiny multilingual: 66MB (supports 99 languages including Russian/English)
- Embedding model: 86MB
- Tokenizer: 455KB
- Total APK size increase: ~177MB (acceptable for target use case)
- Benefits: No download delays, no network errors, instant availability

### Language Detection
**Requirement:** Support Russian and English with automatic language detection
**Implementation:** Whisper tiny multilingual model has built-in language detection:
- Detects 99 languages automatically during transcription
- No separate language detection model needed
- Language code returned with each transcription segment
- Can filter or route based on detected language (Russian/English)

## Project Context
- Target: Android 12 (API 31+)
- Development: macOS 26
- Language: Kotlin (preferred for Android)
- Architecture: Service-based with Activity UI

## Target Device Hardware
- **Device**: Samsung Galaxy Note20 (SM-N980F)
- **SoC**: Exynos 990 (universal990)
  - Octa-core: 2x Exynos M5 @ 2.73GHz + 2x Cortex-A76 @ 2.50GHz + 4x Cortex-A55 @ 2.0GHz
  - GPU: Mali-G77 MP11
- **Architecture**: ARM64-v8a
- **RAM**: ~7.45 GB
- **Display**: 1080x2400 @ 450 DPI (20:9 aspect ratio)
- **Notes**: 
  - Non-Qualcomm device (Exynos) → WhisperKit QNN acceleration NOT available
  - Should use TFLite or Google SpeechRecognizer for speech recognition
  - Sufficient RAM for small/medium ML models (~250MB)
  - High-resolution display suitable for detailed UI

## Key Technical Decisions

### WebSocket Implementation
- Library: Java-WebSocket (org.java-websocket:Java-WebSocket) - mature, simple API
- Alternative considered: OkHttp WebSocket (more complex, requires separate server setup)
- Server runs on background thread, broadcasts to all clients without tracking

### mDNS Implementation  
- Android NsdManager (native Android API)
- No external dependencies needed
- Service type: `_uh._tcp.local.`

### Threading Model
- Main thread: UI updates only
- Service thread: WebSocket server
- Scheduled executor: Random number generation (1s), ping (5s)
- No synchronization needed - broadcast is fire-and-forget

### Message Format
```
Random: {"type":"random","value":123456,"timestamp":1234567890123}
Ping: WebSocket frame-level ping (not JSON)
```

## Android Specifics
- **Foreground Service**: Required for Android 8+ to prevent killing
- **Permissions**: INTERNET, FOREGROUND_SERVICE
- **Service Type**: FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE (Android 14+)
- **Notification**: Required for foreground service visibility

## Build System
- Gradle 8.x
- Kotlin 1.9.x
- Android Gradle Plugin 8.x
- Target SDK: 34 (Android 14)
- Min SDK: 31 (Android 12)

## Code Style
- Explicit types where ambiguity exists
- Null safety enforced
- Coroutines avoided initially (simpler threading model)
- Direct callback patterns for service-to-UI communication

## Build System
- **Makefile targets**: Simple interface for common operations
  - `make install`: Build debug APK and install to connected device
  - `make build`: Build debug APK only
  - `make clean`: Clean build artifacts
  - `make uninstall`: Remove app from device
  - `make logs`: View filtered logcat output
  - `make start-service`/`stop-service`: Control service via adb
- **Gradle wrapper**: Version 8.2, included in repository
- **Build output**: `app/build/outputs/apk/debug/app-debug.apk`

## CLI Client (uhcli) - Rust Implementation

### Project Structure
- Location: `/cli` subdirectory
- Binary name: `uhcli`
- Rust Edition: 2021

### Key Dependencies
- **tokio**: Async runtime with full features
- **tokio-tungstenite**: WebSocket client (async, works with tokio)
- **mdns-sd**: Pure Rust mDNS/DNS-SD implementation (cross-platform)
- **serde/serde_json**: JSON serialization/deserialization
- **rand**: Random service selection
- **anyhow**: Error handling with context
- **chrono**: Timestamp formatting
- **clap**: Command-line argument parsing with derive macros (v4)

### CLI Architecture

**Command Structure:**
```
uhcli [SUBCOMMAND]
├── listen  - Listen to broadcast messages (default)
├── set     - Set configuration value (key=value)
└── get     - Get configuration value (key)
```

**Subcommands:**
- `listen`: Default behavior, connects and displays random number broadcasts
- `set key=value`: Sends configure message with value, waits for response, displays result
- `get key`: Sends configure message without value, waits for response, displays result

**Key Value Parsing:**
- Accepts `key=value` format
- Strips quotes from values: `name="My Device"` → `name=My Device`
- Validates non-empty keys
- Custom parser using `clap::value_parser`

### Architecture Decisions

**mDNS Discovery:**
- `mdns-sd` crate chosen for pure Rust, cross-platform support
- Discovery timeout: 5 seconds (configurable)
- Browses for `_uh._tcp.local.` service type
- Collects all resolved services with addresses
- Handles service removal events during discovery

**Service Selection:**
- Uses `rand::seq::SliceRandom` for cryptographically secure random selection
- Requires at least one discovered service
- Uses first available IP address from service info

**WebSocket Client:**
- `tokio-tungstenite` for async WebSocket operations
- Split stream pattern: separate read/write halves
- Text messages only (JSON)
- Automatic pong responses to ping frames

**Message Handling:**
- Deserializes JSON to `RandomMessage` struct for random broadcasts
- Deserializes JSON to `ConfigureResponse` struct for config responses
- Validates message type == "random" for broadcasts
- Formats timestamps as HH:MM:SS.mmm using chrono
- Falls back to raw message display for parsing errors
- Displays configure responses in listen mode

**Configure Message Flow:**
1. **Set Operation**: `uhcli set name=MyDevice`
   - Discovers services, selects random
   - Connects to WebSocket
   - Sends: `{"configure":"name","value":"MyDevice"}`
   - Waits for response (3s timeout)
   - Displays: `Configuration updated: name = MyDevice`
   - Closes connection

2. **Get Operation**: `uhcli get name`
   - Discovers services, selects random
   - Connects to WebSocket
   - Sends: `{"configure":"name"}`
   - Waits for response (3s timeout)
   - Displays: `Current configuration: name = MyDevice`
   - Closes connection

3. **Listen Operation**: `uhcli` or `uhcli listen`
   - Discovers services, selects random
   - Connects to WebSocket
   - Displays all incoming messages (random numbers, config responses)
   - Runs until Ctrl+C

**Shutdown Handling:**
- `tokio::signal::ctrl_c()` for graceful Ctrl+C handling
- `tokio::select!` for concurrent message receiving and signal handling
- Clean connection closure on shutdown

### Error Handling Strategy
- `anyhow::Result` for all fallible operations
- Context added at each error point for debugging
- Early return on critical failures (no services, connection failure)
- Continue on non-critical errors (message parse errors)

### IPv6 URL Formatting
- IPv6 addresses in URLs require square brackets per RFC 3986
- `format_address_for_url()` helper function handles both IPv4 and IPv6:
  - IPv4: `192.168.1.100` → `ws://192.168.1.100:8080/`
  - IPv6: `2a00:...` → `ws://[2a00:...]:8080/`
- Applied to all WebSocket URL construction points (listen, set, get)

### Output Format
```
UH CLI - WebSocket Random Number Client
========================================

Discovering services (5s timeout)...

  Found: UH_Service._uh._tcp.local. at hostname:8080

Discovered 1 service(s):

  [1] UH_Service._uh._tcp.local.
      Host: hostname
      Port: 8080
      Addresses: [192.168.1.100]

Randomly selected: UH_Service._uh._tcp.local.

Connecting to ws://192.168.1.100:8080/...
Connected!

Receiving messages (Ctrl+C to stop):

[12:34:56.789] Random: 123456
[12:34:57.790] Random: 789012
...
```

## Known Limitations
- No client tracking (by design)
- No message replay/history
- No authentication
- Configuration system exists but not exposed in UI

## Implementation Notes

### Service Architecture
- **UhService**: Foreground service managing lifecycle
  - Creates notification channel and foreground notification
  - Manages WebSocket server, mDNS registration, scheduled tasks
  - ServiceListener interface for UI callbacks (background thread!)
  
### Threading Strategy
- **Main thread**: UI updates only, runOnUiThread() for callbacks
- **WebSocket thread**: Java-WebSocket library handles internally
- **ScheduledExecutorService**: 2 threads for random number and ping tasks
- **No shared mutable state**: Broadcast is fire-and-forget, connection count is synchronized

### Port Selection Logic
- Iterates from startPort (8080) up to maxPortSearch (100 attempts)
- Uses ServerSocket() probe to test availability
- Port announced via mDNS after selection

### Error Handling
- WebSocket errors: logged, reported to listener, connection auto-closed
- mDNS registration failures: logged but service continues
- Port unavailability: service stops with error
- All exceptions caught at task boundaries

### UI Update Pattern
```kotlin
// Service callbacks arrive on background threads
override fun onClientConnected(address: String, totalClients: Int) {
    runOnUiThread {
        updateConnectionIndicator(totalClients)
        addLog("Client connected: $address")
    }
}
```

### Resource Cleanup
Order matters for clean shutdown:
1. Stop scheduler (no new messages)
2. Unregister mDNS
3. Shutdown WebSocket server (closes connections)
4. Notify listener

## Configuration System

### RuntimeConfig Class
Thread-safe runtime configuration management with change notification.

**Storage:**
- `MutableMap<String, String>` for key-value storage
- Synchronized access for thread safety
- Default values initialized in constructor

**Operations:**
- `set(key, value)`: Sets value and notifies listeners, returns new value
- `get(key)`: Returns current value or null if not exists
- `addListener()/removeListener()`: Observer pattern for change notifications

**WebSocket Integration:**
- `processConfigureMessage(json)`: Parses configure messages, processes set/get, returns response JSON
- Message format: `{"configure": "key", "value": "optional"}`
- Response format: `{"configure": "key", "value": "current_value"}`
- If value provided: sets then returns new value
- If value omitted: returns current value only
- Returns null for invalid/unknown keys

**Configuration Variables:**
- `name`: Service name displayed on UI (default: "UH Service")

**Flow:**
1. Client sends configure message via WebSocket
2. UhWebSocketServer.onMessage() receives message on WebSocket thread
3. RuntimeConfig.processConfigureMessage() parses and processes
4. If set operation: notifies listeners via ConfigChangeListener
5. Response sent back to requesting client only
6. UhService forwards config changes to ServiceListener on WebSocket thread
7. MainActivity.onConfigChanged() receives callback on WebSocket thread
8. UI updates via runOnUiThread() in MainActivity

**Initial Config Reading:**
- MainActivity reads current config when binding to service
- UhService.getConfigValue(key) exposes RuntimeConfig.get(key)
- Ensures UI shows current config even if MainActivity binds after config changes
- Called in onServiceConnected() to sync UI with service state

**Thread Safety:**
- All config access synchronized
- Listeners notified on calling thread (WebSocket thread)
- UI updates handled via runOnUiThread() in MainActivity

**Future Extensions:**
- Persistence: Save to SharedPreferences
- Validation: Type checking, value constraints
- More config variables: intervals, thresholds, etc.

## Power Management

### Wake Lock Implementation
Screen lock prevention to ensure WebSocket server availability while device is on wire power.

**Strategy:**
- Acquire wake lock when WebSocket server starts (can accept connections)
- Release wake lock when service stops
- Uses SCREEN_BRIGHT_WAKE_LOCK with ACQUIRE_CAUSES_WAKEUP flag

**Implementation:**
- PowerManager.WakeLock field in UhService
- acquireWakeLock() called after WebSocket server.start()
- releaseWakeLock() called in stopAllComponents() before server shutdown
- Wake lock tag: "UhService::WebSocketServerWakeLock"

**Permission:**
- WAKE_LOCK permission added to AndroidManifest.xml

**Error Handling:**
- Exceptions logged with TAG
- Errors reported via ServiceListener.onError()
- Service continues if wake lock acquisition fails (non-critical)

**Thread Safety:**
- Wake lock operations on service thread (same as server lifecycle)
- isHeld check before release prevents double-release

**Assumptions:**
- Device on wire power (battery drain not a concern)
- Screen stays on for monitoring/debugging
- No user configuration for wake lock behavior (always on when server running)

**Lifecycle:**
```
Service Start
  → startWebSocketServer()
    → server.start()
    → acquireWakeLock() ✓ screen stays on
  → (server running, clients can connect)

Service Stop
  → stopAllComponents()
    → scheduler.shutdown()
    → unregisterMdnsService()
    → releaseWakeLock() ✓ screen can turn off
    → server.shutdown()
```

## Android Speech Recognition Research (2024-2025)

### On-Device Speech Recognition Approaches

#### 1. Google SpeechRecognizer API (Android Native)
**Pros:**
- Built into Android, no extra dependencies
- Simple RecognitionListener interface
- RECORD_AUDIO permission only

**Cons:**
- Not truly continuous: requires manual restart after onEndOfSpeech()
- ~500ms gap between recognition sessions (misses words)
- Server-dependent for best accuracy (may work offline with degraded quality)
- Rate limiting when called frequently
- No direct access to embeddings

**Continuous Listening Pattern:**
- Call `startListening()` in `onResults()` or `onError()` callbacks
- Still has gaps, misses words between sessions
- Libraries like DroidSpeech attempt to smooth over gaps
- Not suitable for mission-critical continuous dictation

#### 2. CMU Sphinx / PocketSphinx (On-Device, Keyword Spotting)
**Pros:**
- True offline operation
- No gaps in listening (continuous VAD)
- Excellent for keyword spotting and limited vocabulary (~10-100 words)
- Low resource usage, works behind lock screen

**Cons:**
- Poor accuracy for natural speech dictation and large vocabularies
- Must train custom models
- Not maintained actively (last update ~2018)
- No modern embedding support

**Use Case:**
- Wake word detection ("Hey Assistant")
- Command recognition with fixed grammar
- Not suitable for transcribing arbitrary speech

#### 3. Whisper (OpenAI) - On-Device Implementations

##### WhisperKit Android (Argmax Inc.) - RECOMMENDED
**Status:** Experimental API, active development (2024-2025)
**GitHub:** https://github.com/argmaxinc/WhisperKitAndroid

**Pros:**
- State-of-the-art accuracy (matches GPT-4o-transcribe)
- Multiple model sizes: tiny (~40MB), base, small, medium, large-v3-turbo (1B params)
- Qualcomm QNN acceleration (NPU/DSP support)
- Streaming inference support (progressive transcription)
- Active development, professional backing

**Cons:**
- Experimental API (@OptIn required)
- Requires QNN dependencies (Qualcomm-specific optimization)
- Model download/storage overhead (40MB-1.5GB)
- May not work well on non-Qualcomm devices

**Dependencies:**
```kotlin
implementation("com.argmaxinc:whisperkit:0.3.3")
implementation("com.qualcomm.qnn:qnn-runtime:2.34.0")
implementation("com.qualcomm.qnn:qnn-litert-delegate:2.34.0")
```

**Architecture:**
```kotlin
@OptIn(ExperimentalWhisperKit::class)
val whisperKit = WhisperKit.Builder()
    .setModel(WhisperKit.OPENAI_TINY_EN)
    .setApplicationContext(applicationContext)
    .setCallback { what, result ->
        when (what) {
            WhisperKit.TextOutputCallback.MSG_INIT -> {
                // Model initialized successfully
            }
            WhisperKit.TextOutputCallback.MSG_TEXT_OUT -> {
                val fullText = result.text
                val segments = result.segments  // Progressive transcription
                segments.forEach { segment ->
                    val segmentText = segment.text
                    val timestamp = segment.start  // timing information
                }
            }
        }
    }
    .build()
```

##### TensorFlow Lite Whisper (vilassn/whisper_android)
**GitHub:** https://github.com/vilassn/whisper_android

**Pros:**
- Mature TFLite ecosystem
- Java and Native API options
- Works on all Android devices (CPU/GPU/NNAPI)
- Model conversion tools provided

**Cons:**
- Must convert models yourself (Python scripts)
- More boilerplate than WhisperKit
- No official OpenAI support
- Slower than QNN on Qualcomm devices

**Model Sizes:**
- tiny: ~40MB, fastest, decent accuracy
- base: ~75MB, balanced
- small: ~250MB, good accuracy
- medium: ~750MB, very good
- large: ~1.5GB, best accuracy (may be too slow on mobile)

##### whisper.cpp Android (ggerganov port)
**GitHub:** https://github.com/ggerganov/whisper.cpp

**Pros:**
- Mature C++ implementation
- OpenCL support (GPU acceleration via CLBlast)
- Quantized models (int8) for smaller size
- Active community

**Cons:**
- Requires NDK/JNI integration
- Complex build setup
- Manual model management

### Text Embeddings on Android

#### Sentence Transformers via ONNX Runtime - RECOMMENDED

**Best Library:** shubham0204/Sentence-Embeddings-Android
**GitHub:** https://github.com/shubham0204/Sentence-Embeddings-Android
**Article:** https://www.droidcon.com/2024/07/08/from-python-to-android-hf-sentence-transformers-embeddings/

**Supported Models:**
- all-MiniLM-L6-v2 (384 dims, 23MB, fastest) - RECOMMENDED
- bge-small-en (384 dims, good quality)
- snowflake-arctic-embed-s (384 dims, retrieval optimized)
- Model2Vec (static embeddings, ultra-fast)

**Maven:**
```kotlin
implementation("com.ml.shubham0204:sentence-embeddings:1.x.x")
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.0")
```

**Architecture:**
```kotlin
val sentenceEmbedding = SentenceEmbedding()

// Initialize (once, on background thread)
CoroutineScope(Dispatchers.IO).launch {
    sentenceEmbedding.init(
        modelFilepath = modelFile.absolutePath,
        tokenizerBytes = tokenizerFile.readBytes(),
        useTokenTypeIds = false,
        outputTensorName = "sentence_embedding",
        useFP16 = false,  // Use FP16 for faster inference on compatible devices
        useXNNPack = false  // XNNPACK for CPU acceleration
    )
}

// Encode text to embedding (on background thread)
CoroutineScope(Dispatchers.IO).launch {
    val embedding: FloatArray = sentenceEmbedding.encode("Delhi has a population 32 million")
    // embedding.size = 384 (for all-MiniLM-L6-v2)
}
```

**Implementation Details:**
- ONNX Runtime for inference (Microsoft's onnxruntime)
- Rust + JNI for tokenizer (HuggingFace tokenizers crate)
- Models from HuggingFace Hub in ONNX format
- Mean pooling strategy (SBERT standard)

**Performance:**
- all-MiniLM-L6-v2: ~50ms per sentence on modern Android devices
- FP16 can double speed on compatible hardware
- XNNPACK provides 2-3x speedup on ARM CPUs

**Dimensions:**
- 384 dims (all-MiniLM-L6-v2, bge-small-en, snowflake-arctic-embed-s)
- 768 dims (bert-base-uncased, larger models)

### Recommended Architecture for Continuous Speech → Embeddings Pipeline

#### Option A: WhisperKit + Sentence-Embeddings (RECOMMENDED for Qualcomm devices)
**Stack:**
1. WhisperKit Android (speech → text, streaming)
2. Sentence-Embeddings-Android (text → embeddings)
3. WebSocket (stream embeddings to server)

**Flow:**
```
Microphone → WhisperKit (progressive segments) 
          → SentenceEmbedding.encode(segment.text)
          → WebSocket.send({"text": text, "embedding": FloatArray(384), "timestamp": ms})
```

**Message Format:**
```json
{
    "type": "speech_embedding",
    "text": "recognized phrase",
    "embedding": [0.123, -0.456, ...],  // 384 floats for all-MiniLM-L6-v2
    "timestamp": 1234567890123,
    "segment_start": 0.5,  // seconds from recording start
    "segment_end": 2.3
}
```

**Pros:**
- Best accuracy (Whisper quality)
- Hardware accelerated (QNN)
- Progressive streaming (get partial results)
- State-of-the-art embeddings

**Cons:**
- Requires Qualcomm device for full speed
- Larger model downloads (~65MB minimum)
- More complex integration

#### Option B: Google SpeechRecognizer + Sentence-Embeddings (Fastest to implement)
**Stack:**
1. SpeechRecognizer (speech → text)
2. Sentence-Embeddings-Android (text → embeddings)
3. WebSocket (stream embeddings to server)

**Flow:**
```
Microphone → SpeechRecognizer (restarts in onResults)
          → SentenceEmbedding.encode(bestMatch)
          → WebSocket.send({"text": text, "embedding": FloatArray(384), "timestamp": ms})
```

**Pros:**
- Simple integration (native Android API)
- No model downloads
- Works on all devices

**Cons:**
- 500ms gaps (loses words between sessions)
- Requires internet for best accuracy
- Not truly continuous

#### Option C: TFLite Whisper + Sentence-Embeddings (Most portable)
**Stack:**
1. TFLite Whisper (speech → text, on-device)
2. Sentence-Embeddings-Android (text → embeddings)
3. WebSocket (stream embeddings to server)

**Pros:**
- Works on all Android devices (CPU/GPU/NNAPI)
- Fully offline
- Mature ecosystem

**Cons:**
- Slower than QNN on Qualcomm
- Must convert/download models
- More setup work

### Personal Embeddings Consideration

**Challenge:** Generating "personal embeddings" requires:
1. Speaker identification/diarization (who is speaking)
2. Custom embedding model fine-tuned on user's speech patterns

**Practical Approach:**
- Use speaker metadata + timestamp as "personal context"
- Fine-tune embeddings offline on user's historical transcripts
- Simple approach: append user_id to text before embedding
- Or: Add user_id as separate field in JSON message

**Speaker Diarization:**
- Not available in lightweight Android frameworks
- WhisperKit Pro (paid, cloud) has diarization
- For on-device: would need pyannote.audio (Python, heavy)
- Practical: Use device_id or user_id as "speaker" identifier

### Recommended Implementation Path

#### Phase 1: Proof of Concept (1-2 days)
- Use Google SpeechRecognizer (simplest, works immediately)
- Add Sentence-Embeddings-Android for embeddings
- Extend existing WebSocket server with new message type
- Test basic flow: speech → text → embedding → WebSocket

**Implementation:**
```kotlin
// In UhService
private lateinit var speechRecognizer: SpeechRecognizer
private lateinit var sentenceEmbedding: SentenceEmbedding

private val recognitionListener = object : RecognitionListener {
    override fun onResults(results: Bundle) {
        val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val bestMatch = matches?.firstOrNull() ?: return
        
        // Generate embedding
        CoroutineScope(Dispatchers.IO).launch {
            val embedding = sentenceEmbedding.encode(bestMatch)
            val message = JSONObject().apply {
                put("type", "speech_embedding")
                put("text", bestMatch)
                put("embedding", JSONArray(embedding.toList()))
                put("timestamp", System.currentTimeMillis())
            }
            broadcast(message.toString())
            
            // Restart listening for continuous
            speechRecognizer.startListening(recognizerIntent)
        }
    }
}
```

#### Phase 2: Production Quality (3-5 days)
- Migrate to WhisperKit Android (better continuous recognition)
- Optimize model size (start with tiny/base)
- Add VAD (Voice Activity Detection) for start/stop
- Buffer management for streaming
- Error handling and reconnection

#### Phase 3: Enhancement (1-2 weeks)
- Fine-tune embedding model on user data
- Add speaker identification metadata
- Optimize battery usage
- Add offline model management
- Implement model downloading UI

### Key Dependencies for Full Stack

```gradle
// build.gradle.kts (app module)

dependencies {
    // Speech Recognition (Option A - WhisperKit, recommended)
    implementation("com.argmaxinc:whisperkit:0.3.3")
    implementation("com.qualcomm.qnn:qnn-runtime:2.34.0")
    implementation("com.qualcomm.qnn:qnn-litert-delegate:2.34.0")
    
    // OR (Option B - Google SpeechRecognizer, simplest)
    // No extra dependencies needed, use android.speech.SpeechRecognizer
    
    // OR (Option C - TFLite Whisper)
    // implementation("org.tensorflow:tensorflow-lite:2.14.0")
    
    // Embeddings (required for all options)
    implementation("com.ml.shubham0204:sentence-embeddings:1.x.x")  // Check Maven Central
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.0")
    
    // Existing WebSocket (already have)
    implementation("org.java-websocket:Java-WebSocket:1.5.3")
    
    // Coroutines (for async operations)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```

### Performance Estimates (Pixel 7 Pro, Snapdragon 8 Gen 2)

**WhisperKit tiny:**
- Real-time factor: ~0.3x (processes 1s audio in 0.3s)
- Latency: ~300ms for 3s audio chunk
- Accuracy: ~85% WER on conversational speech

**Sentence Embeddings (all-MiniLM-L6-v2):**
- Inference: ~50ms per sentence
- Throughput: ~20 sentences/second

**Combined Pipeline:**
- Speech → Text: ~300ms (for 3s audio)
- Text → Embedding: ~50ms
- Total: ~350ms latency
- Can stream partial results progressively

### Battery Considerations
- Continuous listening: ~200-300mAh/hour (microphone + ML)
- WhisperKit: ~100-150mAh/hour (sporadic inference)
- Embeddings: negligible (~5mAh/hour, only when speech detected)
- Total: ~300-450mAh/hour continuous operation
- **Recommendation:** Require device charging or warn user

### Storage Requirements
- Whisper tiny: ~40MB
- Whisper base: ~75MB
- all-MiniLM-L6-v2 ONNX: ~23MB
- Tokenizer: ~500KB
- Total minimum: ~65MB for WhisperKit tiny + embeddings

### Permissions Required

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

### References
- WhisperKit Android: https://github.com/argmaxinc/WhisperKitAndroid
- Sentence Embeddings Android: https://github.com/shubham0204/Sentence-Embeddings-Android
- TFLite Whisper: https://github.com/vilassn/whisper_android
- Whisper.cpp: https://github.com/ggerganov/whisper.cpp
- DroidSpeech (continuous SpeechRecognizer): https://github.com/vikramezhil/DroidSpeech
- Sentence Transformers: https://www.sbert.net/

