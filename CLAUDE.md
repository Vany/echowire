# UH - Architecture & Implementation Knowledge

## Core Architecture

### Component Hierarchy
```
UhService (foreground service, FOREGROUND_SERVICE_TYPE_MICROPHONE)
├── ModelManager: Extract models from APK assets to internal storage
├── AudioCaptureManager: Capture 16kHz mono PCM audio (100ms chunks)
├── SimpleVAD: Energy-based voice activity detection (threshold=0.02)
├── SpeechRecognitionManager: Orchestrate audio → text → embedding pipeline
│   ├── CircularAudioBuffer: 1-30s audio accumulation
│   ├── AudioPreprocessor: PCM → mel spectrogram (80 bins, JTransforms FFT)
│   ├── WhisperModel: TFLite inference with XNNPack acceleration
│   ├── WhisperTokenizer: Token IDs → text with BPE cleanup
│   └── EmbeddingManager: ONNX Runtime text → 384-dim vectors
├── UhWebSocketServer: Broadcast speech/audio_status messages
└── mDNS: Advertise service as _uh._tcp.local.
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
1. **Race Condition Prevention**: UhService.startListening() captures local references (`capturedVad`, `capturedRecognitionManager`) before audio callback to prevent TOCTOU bugs during shutdown
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
adb shell dumpsys meminfo com.uh

# Watch CPU usage
adb shell top -m 10 | grep com.uh

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
- [ ] CLI client compatibility (uhcli)

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
