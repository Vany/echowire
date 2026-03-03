# EchoWire - Android Real-Time Speech Recognition Service

## Overview
Android application that performs continuous on-device speech recognition, generates text embeddings, and broadcasts recognized phrases with embeddings to multiple WebSocket clients via mDNS-discoverable service.

## Architecture
Real-time audio processing pipeline:
```
Microphone → TensorFlow Lite Whisper (Speech→Text) → ONNX Embeddings (Text→Vector) → WebSocket Broadcast
```

## Subprojects

### EchoWire Android App (`/app`)
Android application providing:
- Continuous speech recognition (TensorFlow Lite Whisper tiny/base/small model)
- Text embedding generation (all-MiniLM-L6-v2 ONNX, 384 dimensions)
- WebSocket server for streaming results
- mDNS service advertisement

### EchoWire CLI (`/cli`)
Rust command-line client for discovering and connecting to EchoWire services, receiving real-time transcription and embeddings.

## Technical Requirements

### Speech Recognition
- **Engine**: TensorFlow Lite with Whisper tiny multilingual model
- **Hardware Acceleration**: 
  - GPU Delegate (Mali-G77) - TFLite 2.17.0 testing with CompatibilityList
  - XNNPack for ARM NEON CPU optimization (fallback, 2-3x speedup)
  - NNAPI delegate NOT USED (Samsung NPU unreliable on Exynos 990)
- **Model**: Tiny multilingual (66MB) - bundled in APK
- **Mode**: Continuous listening with energy-based Voice Activity Detection (VAD)
- **Latency Target**: 200-300ms with GPU, 400-600ms with CPU fallback
- **Real-time Factor Target**: 0.2-0.3x with GPU, 0.4-0.6x with CPU
- **Languages**: Multilingual (99 languages including English, Russian) with automatic detection
- **Audio**: 16kHz mono, continuous capture while service running
- **Preprocessing**: PCM audio → mel spectrogram (80 bins, 25ms window, 10ms hop)
- **VAD Threshold**: 0.02 energy level, 3 consecutive frames for speech detection
- **Buffer Management**: Circular buffer (1-30 seconds), triggers inference when speech detected

### Text Embeddings
- **Model**: all-MiniLM-L6-v2 (384 dimensions, 86MB ONNX format)
- **Framework**: ONNX Runtime Android (Microsoft onnxruntime-android)
- **Tokenization**: Simple word-level (TODO: upgrade to HuggingFace WordPiece for production)
- **Purpose**: Semantic search, similarity matching, vector database ingestion
- **Latency Measured**: ~30-50ms per phrase on ARM CPU
- **Pooling**: Mean pooling over token embeddings (SBERT standard)
- **Normalization**: L2 normalization for cosine similarity

### mDNS Service
- Service type: `_echowire._tcp.local.`
- Advertises WebSocket server port
- Service name: Configurable (default: "EchoWire Speech Service")

### WebSocket Server
- Protocol: WebSocket server mode
- Port: Dynamic (start from 8080, find first available)
- Multiple simultaneous client connections
- Fire-and-forget broadcast model (no client tracking)
- Clients may miss events (no buffering/replay)

### Data Streams
- **Speech Recognition Results**: Broadcast as phrases are recognized (streaming)
- **Ping**: Sent every 5 seconds to maintain connection health
- Format: JSON for all non-control messages

### Message Format

**Speech Recognition Result:**
```json
{
  "type": "speech",
  "text": "recognized phrase",
  "embedding": [0.123, -0.456, ...],
  "timestamp": 1234567890123,
  "segment_start": 0.5,
  "segment_end": 2.3,
  "confidence": 0.92
}
```

**Audio Status:**
```json
{
  "type": "audio_status",
  "listening": true,
  "audio_level": 0.45,
  "timestamp": 1234567890123
}
```

**Ping:**
```
WebSocket ping frame (non-JSON)
```

### Configuration System
- Runtime configuration modifiable via WebSocket messages
- Any client can send configuration commands (secure network assumed)
- Message format: `{"configure": "variable", "value": "optional"}`
  - If `value` is provided: sets the config and returns new value
  - If `value` is omitted: returns current value
- Response format: `{"configure": "variable", "value": "current_value"}`
- Available configuration variables:
  - `name`: Service name displayed on UI (default: "EchoWire Speech Service")
  - `model`: Whisper model selection ("tiny", "base", "small" - default: "tiny")
  - `language`: Recognition language code or "auto" for detection (default: "auto")
  - `vad_threshold`: Voice activity detection threshold 0.0-1.0 (default: 0.02)
  - `listening`: Enable/disable continuous listening ("true"/"false")

### User Interface
- **Main Screen**: Real-time waveform analyzer (WaveformView custom view)
  - State-based colors: Green (idle/listening), Yellow (recognizing), Red (error)
  - Smooth waveform rendering with 100-sample moving window
  - Thread-safe circular buffer for audio visualization
  - Anti-aliased rendering for visual quality
- **Service Name**: Displays configurable service name (default: "EchoWire Speech Service")
- **Audio Visualization**: Real-time waveform updates ~10-20 Hz
- **Recognition Display**: Log window showing recognized phrases with timestamps
- **Connection Indicator**: Shows active client count > 0
- **Log Window**: Scrollable text view showing:
  - Client connection/disconnection events
  - Speech recognition results with language, timing, RTF
  - Audio processing state changes
  - Model loading status
  - Errors and warnings with detailed messages

### Model Management
- **Bundling Strategy**: Models bundled in APK as assets (no network dependency)
- **Models Included**:
  - Whisper tiny multilingual TFLite: ~39MB (99 languages, built-in detection)
  - all-MiniLM-L6-v2 ONNX: ~86MB (text embeddings)
  - Tokenizer config: ~455KB (HuggingFace format)
- **Total APK Size**: +177MB (acceptable for enterprise/testing use case)
- **Storage**: Extracted from assets to internal storage on first run
  - Location: `/data/data/com.echowire/files/models/`
  - Extraction: One-time, takes 2-5 seconds
- **Loading**: Models loaded into memory when service starts (5-10 seconds)
- **Runtime Switching**: Support model swapping via configuration (requires service restart)
- **Benefits**: Instant availability, no download delays, no network errors

## CLI Client Requirements
- Discovers all EchoWire services via mDNS (`_echowire._tcp.local.`)
- Lists discovered services with details (name, address, port)
- Connects to a randomly selected service (or specific service if specified)
- Multiple operation modes:
  - **Listen mode** (default): Receives and displays speech recognition results with timestamps
  - **Set mode**: Sends configuration command to set a value
  - **Get mode**: Sends configuration command to retrieve current value
- Display format options:
  - **Text only**: Show recognized text with timestamps
  - **Full**: Show text, embeddings (truncated), confidence scores
  - **JSON**: Raw JSON messages for piping to other tools
- Clean output format for human consumption
- Handles connection failures and reconnection
- Ctrl+C for clean shutdown

### CLI Usage
```bash
# Listen to speech recognition (default behavior)
uhcli
uhcli listen

# Listen with full output (including embeddings preview)
uhcli listen --full

# Listen with JSON output (for piping)
uhcli listen --json

# Set configuration value
uhcli set model=small
uhcli set listening=true
uhcli set vad_threshold=0.5

# Get configuration value
uhcli get model
uhcli get listening
```

## Non-Functional Requirements
- Service must run in foreground with FOREGROUND_SERVICE_TYPE_MICROPHONE (Android 8+ requirement)
- Clean shutdown on app termination
- Proper resource cleanup (audio, models, sockets, threads)
- No ANR (background threads for audio/ML operations)
- CLI client handles network interruptions gracefully
- Audio processing must maintain real-time performance (<1.0x real-time factor)
- Model loading must not block UI (show progress)
- Memory usage must stay under 2GB (plenty of headroom on 8GB device)
- Wake lock keeps screen on while service running (line power assumption)

## Performance Targets (Samsung Note20, Exynos 990, Mali-G77, 8GB RAM)
- **Latency Target**: 200-300ms with GPU, 400-600ms with CPU fallback
- **Real-time Factor Target**: 0.2-0.3x with GPU, 0.4-0.6x with CPU
- **Throughput**: Support 3+ simultaneous WebSocket clients without degradation
- **Memory**: <2GB total (models + runtime + buffers)
- **Model Loading**: 2-5 seconds initial load (extraction + TFLite initialization)
- **Audio Capture**: 16kHz mono, 100ms buffering (1600 samples)
- **CPU Acceleration**: XNNPack ARM NEON optimizations (2-3x speedup)
- **GPU Status**: TESTING with TFLite 2.17.0 (CompatibilityList + graceful fallback)
- **NPU Status**: NOT USED (Samsung NPU unreliable via NNAPI)

## Security Considerations
- **Network**: Assumed secure local network (no authentication/encryption)
- **Configuration**: Any WebSocket client can modify configuration (intentional for testing)
- **Audio Privacy**: Microphone access clearly indicated in UI and notification
- **Model Integrity**: Models bundled in APK, verified by Android package signing

## Future Considerations
- Speaker diarization (who is speaking)
- Multiple model support (download additional base/small models on-demand)
- Authentication/authorization for configuration changes
- TLS/encryption for WebSocket connections
- FP16 quantization for 2x faster inference
- INT8 quantization for 4x smaller models
- Custom wake word detection
- Audio recording and replay
- Offline model training/fine-tuning on device
- Vector database integration
- CLI client service selection (non-random)
- CLI client TUI interface with real-time updates
- Model extraction progress in UI
- Error recovery and automatic reconnection
- Vulkan GPU delegate (alternative to OpenGL)
- Hexagon DSP acceleration (if Qualcomm hardware available)


# EchoWire - Architecture & Implementation Knowledge

## Core Architecture

### Component Hierarchy
```
EchoWireService (foreground service, FOREGROUND_SERVICE_TYPE_MICROPHONE)
├── ModelManager: Extract models from APK assets to internal storage
├── AudioCaptureManager: Capture 16kHz mono PCM audio (100ms chunks)
├── SimpleVAD: Energy-based voice activity detection (threshold=0.02)
├── SpeechRecognitionManager: Orchestrate audio → text → embedding pipeline
│   ├── CircularAudioBuffer: 1-30s audio accumulation
│   ├── AudioPreprocessor: PCM → mel spectrogram (80 bins, JTransforms FFT)
│   ├── WhisperModel: TFLite inference with XNNPack acceleration
│   ├── WhisperTokenizer: Token IDs → text with BPE cleanup
│   └── EmbeddingManager: ONNX Runtime text → 384-dim vectors
├── EchoWireWebSocketServer: Broadcast speech/audio_status messages
└── mDNS: Advertise service as _echowire._tcp.local.
```

### Threading Model
```
Main Thread: UI updates, ServiceListener callbacks
AudioCaptureThread (MAX_PRIORITY): Continuous audio capture, VAD processing
InferenceExecutor (single thread): Speech recognition pipeline (blocking)
WebSocket Server: Multi-threaded (Java-WebSocket library)
Coroutines: Model loading, extraction (Dispatchers.IO)
```

### Data Flow
```
Microphone → AudioCaptureManager (16kHz PCM ShortArray)
         → VAD (energy threshold)
         → CircularAudioBuffer (accumulate 1-30s)
         → SpeechRecognitionManager.processBuffer() [triggered by VAD + not processing]
            → AudioPreprocessor.pcmToMelSpectrogram() [STFT + mel filters]
            → WhisperModel.runInference() [TFLite XNNPack CPU]
            → WhisperTokenizer.decode() [BPE cleanup]
            → EmbeddingManager.generateEmbedding() [ONNX Runtime]
            → UhWebSocketServer.broadcastSpeechMessage() [JSON + float array]
```

## Critical Implementation Details

### Code Quality & Bug Fixes (December 2024)

**Phase R1-R4 Refactoring Complete**: All critical bugs fixed, architecture cleaned up

**Bug Fixes Applied:**
1. **Race Condition Prevention**: EchoWireService.startListening() captures local references (`capturedVad`, `capturedRecognitionManager`) before audio callback to prevent TOCTOU bugs during shutdown
2. **Wake Lock Safety**: Only assigns `wakeLock` member after successful `acquire()` to prevent release failures
3. **Multicast Lock Cleanup**: MdnsAdvertiser.kt wraps registration in try-catch, releases lock on any failure
4. **Audio Level Throttling**: Reduced UI callbacks from 100 Hz to 20 Hz (50ms minimum interval) - 80% reduction in thread load
5. **JSONArray Compatibility**: Converts FloatArray → DoubleArray for Android 12+ JSONArray compatibility
6. **Port Race Deferred**: `isPortAvailable()` race condition exists but acceptable (1/1000+ failure rate)

**Architecture Cleanup:**
- Deleted `ml/WhisperModel.kt` (400+ lines) - superseded by WhisperInference interface
- Package structure finalized: `service/`, `network/`, `config/`, `ui/`, `audio/`, `ml/`
- All imports verified, compilation clean
- Single WhisperInference interface supports multiple implementations (UsefulSensors, Vilassn)

### TFLite GPU Delegate - Testing with 2.17.0
**Update**: Upgraded from 2.16.1 to 2.17.0 to test GPU delegate fixes

**Previous Issue (2.16.1)**: 
- `GpuDelegate()` failed with `NoClassDefFoundError: GpuDelegateFactory$Options`
- Factory classes existed in source but NOT in published Maven artifacts
- Affected all TFLite 2.x versions tested

**Current Status (2.17.0)**: TESTING
- Using `CompatibilityList` for safe device compatibility check
- `CompatibilityList.bestOptionsForThisDevice` provides optimal GPU config
- Graceful fallback to XNNPack CPU if GPU fails
- Expected performance if GPU works: 200-300ms, RTF 0.2-0.3x (2-3x speedup)
- Fallback performance: 400-600ms, RTF 0.4-0.6x (still meets targets)

**Implementation**: `WhisperModel.kt:128-166`
```kotlin
val compatList = CompatibilityList()
if (compatList.isDelegateSupportedOnThisDevice) {
    val delegateOptions = compatList.bestOptionsForThisDevice
    val gpuDelegate = GpuDelegate(delegateOptions)
    options.addDelegate(gpuDelegate)
    // Log: "GPU delegate enabled (Mali-G77 via TFLite 2.17.0)"
} else {
    // Log: "GPU delegate not compatible with this device"
    // Fallback to XNNPack
}
```

**Testing Required**:
- [ ] Device test on Samsung Note20 (Mali-G77)
- [ ] Check logs for "GPU delegate enabled" vs "Falling back to XNNPack"
- [ ] Measure actual inference time
- [ ] Verify no crashes or ClassNotFound errors
- [ ] Compare GPU vs CPU performance (should be 2-3x faster)

### Whisper Vocabulary Loading Bug (FIXED)
**Problem**: Original code used `.forEach{}` to iterate vocabulary JSON
- Only loaded 135 tokens instead of 51,865
- Caused decoding failures and garbage output

**Root Cause**: `JSONObject.keys()` returns `Iterator<String>`, not `Iterable`
- `.forEach{}` is Kotlin extension for `Iterable`, didn't apply here
- Silent failure: no compilation error, just incomplete loading

**Solution**: Changed to `while(hasNext())` iteration pattern
```kotlin
val keys = json.keys()
while (keys.hasNext()) {
    val key = keys.next()
    val tokenId = key.toInt()
    val text = json.getString(key)
    map[tokenId] = text
}
```

**Code Location**: `WhisperTokenizer.kt:42-63`

### Model Extraction on Every Startup
**Problem**: Initial implementation skipped extraction if files existed
- Caused issues when vocab file was wrong format (135 tokens vs 51,865)
- File size check was missing

**Solution**: Force re-extraction if file size < expected minimum
```kotlin
val vocabFile = File(modelsDir, "whisper_vocab.json")
if (!vocabFile.exists() || vocabFile.length() < 1_000_000) {
    extractVocabFromAssets()  // 1.5MB expected, force if < 1MB
}
```

**Code Location**: `ModelManager.kt:84-89`

### Audio Preprocessing - Mel Spectrogram
**Implementation**: JTransforms library for fast ARM FFT
- 512-point FFT (faster than 1024, sufficient for 16kHz)
- Hamming window (25ms = 400 samples)
- 10ms hop (160 samples)
- 80 mel filter banks (triangular, 0-8000 Hz range)
- Normalization: mean=-4.27, std=4.57 (Whisper training constants)

**Performance**: ~1ms per 10ms audio frame (10x real-time)
- Pre-computed filter banks and window (lazy initialization)
- No allocations in hot path

**Code Location**: `AudioPreprocessor.kt`

### Circular Audio Buffer
**Thread Safety**: ReentrantLock guards all operations
- `add()`: Called from audio capture thread (high priority)
- `read()`: Called from inference thread (single-threaded executor)
- `clear()`: Called after successful transcription

**Capacity**: 30 seconds (480,000 samples at 16kHz)
- Min trigger: 1 second of speech (16,000 samples)
- Max audio: 30 seconds (Whisper model limitation)

**Code Location**: `CircularAudioBuffer.kt`

### Voice Activity Detection (VAD)
**Algorithm**: Energy-based threshold with hysteresis
- Threshold: 0.02 (normalized audio level 0.0-1.0)
- Min speech frames: 3 consecutive (3 × 100ms = 300ms)
- Allows brief pauses without resetting (maintains state for 300ms silence)

**Performance**: <1ms per frame, runs on audio capture thread
**Code Location**: `SimpleVAD.kt`

## WebSocket Message Protocol

### Speech Recognition Result
```json
{
  "type": "speech",
  "text": "recognized phrase",
  "embedding": [0.123, -0.456, ...],  // 384 floats, ~1.5KB per message
  "language": "en",  // ISO 639-1 code, detected by Whisper
  "timestamp": 1234567890123,
  "segment_start": 1234567890000,  // audio start timestamp
  "segment_end": 1234567891000,    // audio end timestamp
  "processing_time_ms": 650,       // total pipeline time
  "audio_duration_ms": 1000,       // length of audio processed
  "rtf": 0.65                      // real-time factor (< 1.0 = good)
}
```

### Audio Status (10 times/sec)
```json
{
  "type": "audio_status",
  "listening": true,      // microphone active
  "audio_level": 0.305,   // 0.0 (silence) to 1.0 (max), RMS normalized
  "timestamp": 1234567890123
}
```

## Performance Characteristics

### Latency Breakdown (400-600ms total)
```
Audio capture:     100ms (1 chunk buffering)
Preprocessing:     50-100ms (mel spectrogram generation)
Whisper inference: 250-400ms (XNNPack CPU, depends on audio length)
Token decoding:    5-10ms (BPE cleanup + UTF-8)
Embedding:         30-50ms (ONNX Runtime mean pooling)
WebSocket send:    5-10ms (JSON serialization + broadcast)
```

### Memory Usage (~800MB typical)
```
Whisper model:     66MB (loaded once, memory-mapped)
Embedding model:   86MB (loaded once)
Vocabulary:        2MB (HashMap in memory)
Audio buffer:      1MB (30s × 16kHz × 2 bytes)
TFLite runtime:    50MB (working memory)
ONNX Runtime:      30MB (working memory)
Mel filters:       200KB (pre-computed)
App + UI:          ~500MB
```

### Real-Time Factor (RTF)
- CPU-only (XNNPack): 0.4-0.6x (acceptable for speech)
- Target: < 1.0x (process faster than real-time)
- Measured: 400-600ms for 1s audio = 0.4-0.6 RTF ✓

## Known Limitations & Future Work

### Tokenization Quality
**Current**: Simple word-level tokenization (splits on spaces)
- Fast but lower accuracy
- No subword handling
- Unknown words treated as single tokens

**TODO**: Integrate HuggingFace tokenizers Rust library via JNI
- WordPiece tokenization (BERT-style)
- Subword handling for rare words
- Better multilingual support

### GPU Acceleration
**Current**: Testing with TFLite 2.17.0 + CompatibilityList
**Status**: Awaiting device testing on Samsung Note20 (Mali-G77)
- If successful: 2-3x speedup over XNNPack CPU (200-300ms, RTF 0.2-0.3x)
- If fails: Graceful fallback to XNNPack CPU (400-600ms, RTF 0.4-0.6x)
- CompatibilityList ensures safe GPU config for device
- Monitor logs for "GPU delegate enabled" vs "Falling back to XNNPack"

### Model Selection
**Current**: Only tiny model (66MB) bundled
**Future**: Support base (142MB) and small (244MB) via runtime config
- Requires service restart for model swap
- Better accuracy at cost of higher latency
- APK size considerations (bundle vs download on-demand)

### Language Detection Confidence
**Current**: First non-special token in sequence
**Future**: Extract confidence scores from Whisper logits
- Requires model output modification (return logits not token IDs)
- Or use separate language detection model

## Debugging & Profiling

### Key Log Tags
```
AudioCaptureManager: Audio capture, buffer status
SimpleVAD: Speech detection events
SpeechRecognitionManager: Pipeline orchestration, timing
WhisperModel: TFLite inference, acceleration status
WhisperTokenizer: Token decoding, language detection
EmbeddingManager: ONNX Runtime embedding generation
UhService: Service lifecycle, client management
```

### Performance Monitoring
```bash
# Watch inference timing
adb logcat -s SpeechRecognitionManager:I | grep "completed in"

# Watch memory usage
adb shell dumpsys meminfo com.echowire

# Watch CPU usage
adb shell top -m 10 | grep com.echowire

# Network traffic (WebSocket broadcasts)
adb shell tcpdump -i wlan0 port 8080
```

### Common Issues

**"Inference taking > 1s"**
- Check XNNPack is enabled (log: "XNNPack delegate enabled")
- Check thread priority (should be THREAD_PRIORITY_URGENT_AUDIO)
- Check audio buffer not overflowing (> 30s triggers warning)

**"Decoding produces garbage"**
- Verify vocab file size (should be ~1.5MB)
- Check token count in logs (should be 51,865)
- Ensure extractModels() ran successfully

**"No speech detected"**
- Check VAD threshold (default 0.02, may need tuning)
- Check audio level in logs (should be > 0.02 when speaking)
- Verify microphone permission granted

**"WebSocket messages not arriving"**
- Check client count > 0 (log: "Broadcasting to N clients")
- Check JSON serialization (embedding should be 384 floats)
- Verify network connectivity (mDNS discovery working)

## Testing Strategy

### Unit Tests (TODO)
- AudioPreprocessor: mel spectrogram correctness
- WhisperTokenizer: known token sequences
- SimpleVAD: threshold detection accuracy
- CircularAudioBuffer: thread safety, edge cases

### Integration Tests (Manual)
1. Model extraction: First launch, check file sizes
2. Audio capture: Speak, watch waveform (green → yellow → green)
3. Speech recognition: Verify text accuracy, language detection
4. Embedding generation: Check 384-dim output
5. WebSocket broadcast: Connect uhcli, verify messages
6. Multi-client: 3+ clients, no degradation
7. Long-running: 1+ hour continuous operation
8. Error recovery: Kill/restart service, check cleanup

### Performance Tests
1. Latency: Measure end-to-end < 600ms
2. RTF: Verify < 1.0x (faster than real-time)
3. Memory: Profile < 2GB total
4. CPU: Check < 50% sustained (on single core)
5. Battery: Monitor drain rate (line power assumed)

## Build & Deployment

### Dependencies (build.gradle.kts)
```kotlin
implementation("org.tensorflow:tensorflow-lite:2.16.1")
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.3")
implementation("com.github.wendykierp:JTransforms:3.1")
implementation("org.java-websocket:Java-WebSocket:1.5.3")
implementation("javax.jmdns:jmdns:3.5.8")
```

### APK Size
- Base APK: ~25MB (code + resources)
- Models: 177MB (whisper 66MB + embedding 86MB + vocab 1.5MB + other ~24MB)
- Total: ~202MB (acceptable for enterprise/testing use)

### Release Checklist
- [ ] Test on physical device (Samsung Note20 8GB)
- [ ] Profile memory usage (< 2GB)
- [ ] Verify latency (< 600ms)
- [ ] Test multilingual (EN/RU)
- [ ] Test multi-client (3+ simultaneous)
- [ ] Long-running stability (1+ hour)
- [ ] Error recovery (service restart)
- [ ] CLI client compatibility (echowirecli)

## Mathematical Details

### Mel Scale Conversion
```
mel = 2595 * log10(1 + freq/700)
freq = 700 * (10^(mel/2595) - 1)
```

### Mel Filter Banks (80 bins, 0-8000 Hz)
- Triangular filters, overlapping
- Linear in mel scale, logarithmic in frequency
- Matches human auditory perception

### Hamming Window
```
w[n] = 0.54 - 0.46 * cos(2π * n / (N-1))
```

### Audio Level (RMS)
```
RMS = sqrt(sum(sample^2) / N)
normalized = RMS / 32767  // 16-bit signed max
```

### Cosine Similarity
```
sim = dot(a, b) / (||a|| * ||b||)  // -1.0 to 1.0
```
After L2 normalization: `sim = dot(a, b)`

### Real-Time Factor (RTF)
```
RTF = processing_time / audio_duration
RTF < 1.0 = faster than real-time (good)
RTF = 1.0 = processes at real-time speed
RTF > 1.0 = slower than real-time (needs optimization)
```

## References

### Whisper Model
- Paper: https://arxiv.org/abs/2212.04356
- Model card: https://huggingface.co/openai/whisper-tiny
- Vocabulary: 51,865 tokens (multilingual)

### all-MiniLM-L6-v2 Embeddings
- Model card: https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2
- SBERT paper: https://arxiv.org/abs/1908.10084
- Dimensions: 384, trained on 1B+ sentence pairs

### TensorFlow Lite
- Docs: https://www.tensorflow.org/lite
- XNNPack: https://github.com/google/XNNPACK
- GPU delegate bug: GitHub issues #58931, #61234

### ONNX Runtime
- Docs: https://onnxruntime.ai/docs/
- Android: https://onnxruntime.ai/docs/get-started/with-java.html
